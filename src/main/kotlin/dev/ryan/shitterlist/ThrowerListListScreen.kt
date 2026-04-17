package dev.ryan.throwerlist

import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.MutableText
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Util
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import kotlin.math.roundToInt

class ThrowerListListScreen(
    private var currentTab: Tab = Tab.ALL,
    private val openFromCenter: Boolean = true,
    private val startFromDungeonWidth: Boolean = false,
    initialSearch: String = "",
) : Screen(Text.literal("Skylist")) {
    private val versionLabel = "v${SkylistPlusRuntimeVersion.featureVersion()}"
    private val titleVersionScale = 0.75f
    private val titleVersionGap = 4

    enum class Tab(val label: String, val emptyMessage: String) {
        ALL("All", "No Skylist entries to display."),
        LOCAL("Local", "No local Skylist entries to display."),
        REMOTE("Remote", "No remote Skylist entries to display."),
        SCAMMERS("Scammers", "No scammer entries to display."),
    }

    enum class EntrySource(val label: String, val badgeColor: Int, val selectedBadgeColor: Int, val accentColor: Int) {
        LOCAL("Local", 0xFF1D4D29.toInt(), 0xFF285B36.toInt(), 0xFF55FF55.toInt()),
        REMOTE("Remote", 0x00000000, 0x00000000, 0x00000000),
        SCAMMER("Scammer", 0xFF4F241B.toInt(), 0xFF673024.toInt(), 0xFFFF9A7A.toInt()),
    }

    data class GuiEntry(
        val username: String,
        val uuid: String,
        val reason: String,
        val ts: Long?,
        val tags: List<String> = emptyList(),
        val ignored: Boolean = false,
        val autoRemoveAfter: String? = null,
        val expiresAt: Long? = null,
        val source: EntrySource,
        val isRemoteDisabled: Boolean = false,
        val severity: ScammerListManager.Severity? = null,
        val altUsernames: List<String> = emptyList(),
        val altUuids: List<String> = emptyList(),
        val discordUsers: List<ScammerListManager.DiscordUser> = emptyList(),
        val discordIds: List<String> = emptyList(),
        val evidence: String? = null,
    ) {
        val isLocal: Boolean
            get() = source == EntrySource.LOCAL
        val isScammer: Boolean
            get() = source == EntrySource.SCAMMER
        val discordLabels: List<String>
            get() = discordUsers.map { it.label }
    }

    private enum class RowAction(val tooltip: String) {
        VIEW("View entry"),
        EDIT("Edit entry"),
        TOGGLE_REMOTE("Click to toggle actions on or off"),
        REMOVE("Remove entry"),
        REMOVE_SCAMMER("Remove cached scammer entry"),
    }

    private data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun contains(mouseX: Double, mouseY: Double): Boolean =
            mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom

        fun centerX(): Int = (left + right) / 2
        fun width(): Int = right - left
        fun height(): Int = bottom - top
    }

    private val usernameRegex = Regex("^[A-Za-z0-9_]{1,16}$")
    private val addedTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yy")
    private val shortAddedFormatter = DateTimeFormatter.ofPattern("dd/MM/yy")
    private val layout: UiLayoutManager.MainListLayout
        get() = UiLayoutManager.mainList()
    private val statusSuccessColor = 0xFF88FF88.toInt()
    private val statusInfoColor = 0xFF7FD6FF.toInt()
    private val statusWarningColor = 0xFFFFDD77.toInt()
    private val statusErrorColor = 0xFFFF7777.toInt()

    private var searchField: TextFieldWidget? = null
    private var addButton: ButtonWidget? = null
    private var settingsButton: ButtonWidget? = null
    private var refreshButton: ButtonWidget? = null
    private var doneButton: ButtonWidget? = null
    private var trollButton: ButtonWidget? = null
    private var editButton: ButtonWidget? = null
    private var removeButton: ButtonWidget? = null
    private var copyUsernameButton: ButtonWidget? = null
    private var copyUuidButton: ButtonWidget? = null
    private var addScammerActionButton: ButtonWidget? = null
    private var scammerSettingsActionButton: ButtonWidget? = null

    private var allEntries = emptyList<GuiEntry>()
    private var filteredEntries = emptyList<GuiEntry>()
    private var scrollOffset = 0
    private var searchQuery = initialSearch
    private var selectedEntryKey: String? = null
    private var pendingDeleteKey: String? = null
    private var statusMessage: Text? = null
    private var statusColor = 0xFFFFFFFF.toInt()
    private var refreshingRemote = false
    private var leftMouseDown = false
    private var trollClickCount = 0
    private var sidebarExpanded = startFromDungeonWidth
    private var sidebarAnimationProgress = if (startFromDungeonWidth) 1f else 0f
    private var frameWidthProgress = 0f

    override fun init() {
        super.init()
        val frame = frameRect()
        val bottomButtons = bottomButtonRowRect()
        val actionRow = selectionActionRowRect()
        val search = searchRect()

        searchField = TextFieldWidget(
            textRenderer,
            search.left,
            search.top,
            search.width(),
            search.height(),
            Text.literal("Search by username or reason..."),
        ).also {
            it.setMaxLength(64)
            it.setDrawsBackground(false)
            ThemeRenderer.applyTextFieldInset(it)
            it.text = searchQuery
            it.setChangedListener { value ->
                searchQuery = value
                refreshEntries()
            }
            addDrawableChild(it)
            setInitialFocus(it)
        }

        val troll = trollButtonRect()
        trollButton = ThemedButtonWidget.builder(trollButtonMessage()) {
            handleTrollButtonClick()
        }.dimensions(troll.left, troll.top, troll.width(), troll.height()).build().also { addDrawableChild(it) }

        val titleButtons = titleBarButtonsRect()
        val footerButtonCount = 4
        val bottomButtonWidth = (bottomButtons.width() - (layout.footer.buttonGap * (footerButtonCount - 1))) / footerButtonCount
        addButton = ThemedButtonWidget.builder(Text.literal("+ Add Entry")) {
            client?.setScreen(ThrowerListEntryEditorScreen(this, null))
        }.dimensions(bottomButtons.left, bottomButtons.top, bottomButtonWidth, layout.footer.height).build().also { addDrawableChild(it) }

        settingsButton = ThemedButtonWidget.builder(Text.literal("Settings")) {
            client?.setScreen(ThrowerListSettingsScreen(this))
        }.dimensions(
            bottomButtons.left + (bottomButtonWidth + layout.footer.buttonGap),
            bottomButtons.top,
            bottomButtonWidth,
            layout.footer.height,
        ).build().also { addDrawableChild(it) }

        refreshButton = ThemedButtonWidget.builder(Text.literal("Refresh")) {
            refreshRemoteEntries()
        }.dimensions(
            bottomButtons.left + (bottomButtonWidth + layout.footer.buttonGap) * 2,
            bottomButtons.top,
            bottomButtonWidth,
            layout.footer.height,
        ).build().also { addDrawableChild(it) }

        doneButton = ThemedButtonWidget.builder(Text.literal("Done")) {
            close()
        }.dimensions(
            bottomButtons.left + (bottomButtonWidth + layout.footer.buttonGap) * 3,
            bottomButtons.top,
            bottomButtonWidth,
            layout.footer.height,
        ).build().also { addDrawableChild(it) }

        val detailButtons = layout.actionStrip.detailButtons
        val detailButtonTop = actionRow.top + detailButtons.topOffset
        val detailButtonsRight = actionRow.right
        val copyUuidLeft = detailButtonsRight - detailButtons.width
        val copyUsernameLeft = copyUuidLeft - detailButtons.gap - detailButtons.width
        val editLeft = copyUsernameLeft - detailButtons.gap - detailButtons.width
        val removeLeft = editLeft - detailButtons.gap - detailButtons.width
        removeButton = ThemedButtonWidget.builder(Text.literal("Remove Entry")) {
            if (currentTab == Tab.LOCAL) {
                client?.setScreen(ThrowerListShareCodeScreen(this, ThrowerListShareCodeScreen.Mode.EXPORT))
            } else {
                selectedEntry()?.let(::queueDeleteFromAction)
            }
        }.dimensions(removeLeft, detailButtonTop, detailButtons.width, detailButtons.height).build().also { addDrawableChild(it) }

        editButton = ThemedButtonWidget.builder(Text.literal("Edit Entry")) {
            if (currentTab == Tab.LOCAL) {
                client?.setScreen(ThrowerListShareCodeScreen(this, ThrowerListShareCodeScreen.Mode.IMPORT))
            } else {
                selectedEntry()?.let { client?.setScreen(ThrowerListEntryEditorScreen(this, it)) }
            }
        }.dimensions(editLeft, detailButtonTop, detailButtons.width, detailButtons.height).build().also { addDrawableChild(it) }

        copyUsernameButton = ThemedButtonWidget.builder(Text.literal("Copy IGN")) {
            selectedEntry()?.let { copyToClipboard(it.username, "Copied IGN ${it.username}.") }
        }.dimensions(copyUsernameLeft, detailButtonTop, detailButtons.width, detailButtons.height).build().also { addDrawableChild(it) }

        copyUuidButton = ThemedButtonWidget.builder(Text.literal("Check")) {
            client?.setScreen(ScammerCheckLookupScreen(this, selectedEntry()?.username.orEmpty()))
        }.dimensions(copyUuidLeft, detailButtonTop, detailButtons.width, detailButtons.height).build().also { addDrawableChild(it) }
        val scamSettingsLeft = removeLeft
        val addScammerLeft = editLeft
        addScammerActionButton = ThemedButtonWidget.builder(Text.literal("Report a Scam")) {
            Util.getOperatingSystem().open(URI.create("https://forms.gle/sVR8ii2EV1f1yWwDA"))
        }.dimensions(addScammerLeft, detailButtonTop, detailButtons.width, detailButtons.height).build().also { addDrawableChild(it) }
        scammerSettingsActionButton = ThemedButtonWidget.builder(Text.literal("Scammer Settings")) {
            client?.setScreen(ScammerSettingsScreen(this))
        }.dimensions(scamSettingsLeft, detailButtonTop, detailButtons.width, detailButtons.height).build().also { addDrawableChild(it) }

        refreshEntries()
        updateActionButtons()
    }

    override fun shouldPause(): Boolean = false

    override fun close() {
        client?.setScreen(null)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        val theme = ThemeManager.current()
        val frame = frameRect()
        val search = searchRect()
        val count = countRect()
        val listArea = listAreaRect()
        val actionRow = selectionActionRowRect()
        val bottomButtons = bottomButtonRowRect()
        sidebarAnimationProgress = animateSidebarProgress(sidebarAnimationProgress, if (sidebarExpanded) 1f else 0f)
        frameWidthProgress = animateSidebarProgress(frameWidthProgress, 1f)
        relayoutWidgets()

        ThemeRenderer.drawPanel(context, frame.left, frame.top, frame.right, frame.bottom, layout.titleBar.height, theme)
        drawSidebar(context, mouseX.toDouble(), mouseY.toDouble(), theme)
        context.fill(listArea.left, listArea.top, listArea.right, listArea.bottom, theme.listBackground)
        context.fill(count.left, count.top, count.right, count.bottom, theme.withAlpha(theme.secondaryPanel, 0xA8))
        context.fill(actionRow.left, actionRow.top - 2, actionRow.right, actionRow.top - 1, theme.idleBorder)
        ThemeRenderer.drawTextField(context, searchField, theme)

        val countLabel = countLabel()
        drawInlineTitle(context, frame, theme)
        drawCenteredText(context, countLabel, count.centerX(), count.top + 6, theme.hoverAccent)
        drawTabs(context, mouseX.toDouble(), mouseY.toDouble(), theme)
        drawSearchPlaceholder(context, theme)
        drawEntries(context, mouseX.toDouble(), mouseY.toDouble(), listArea, theme)
        drawSelectionActions(context, actionRow, theme)
        drawStatus(context, frame, bottomButtons)
        drawPoweredBySbz(context, listArea, theme)

        super.render(context, mouseX, mouseY, deltaTicks)
        
        val donationRect = donationButtonRect()
        val discordRect = discordButtonRect()
        val creditsRect = creditsButtonRect()
        val settingsRect = settingsButtonRect()

        drawDonationButton(context, mouseX.toDouble(), mouseY.toDouble(), theme)
        drawDiscordButton(context, mouseX.toDouble(), mouseY.toDouble(), theme)
        drawCreditsButton(context, mouseX.toDouble(), mouseY.toDouble(), theme)
        drawSettingsIconButton(context, mouseX.toDouble(), mouseY.toDouble(), theme)
        drawButtons(context, mouseX.toDouble(), mouseY.toDouble(), theme)

        if (donationRect.contains(mouseX.toDouble(), mouseY.toDouble())) {
            context.drawTooltip(textRenderer, Text.literal("Donate"), mouseX, mouseY)
        }
        if (discordRect.contains(mouseX.toDouble(), mouseY.toDouble())) {
            context.drawTooltip(textRenderer, Text.literal("Discord"), mouseX, mouseY)
        }
        if (creditsRect.contains(mouseX.toDouble(), mouseY.toDouble())) {
            context.drawTooltip(textRenderer, Text.literal("Credits"), mouseX, mouseY)
        }
        if (settingsRect.contains(mouseX.toDouble(), mouseY.toDouble())) {
            context.drawTooltip(textRenderer, Text.literal("Settings"), mouseX, mouseY)
        }
        val hoveredAction = hoveredRowAction(mouseX.toDouble(), mouseY.toDouble())
        if (hoveredAction != null) {
            context.drawTooltip(textRenderer, Text.literal(hoveredAction.second.tooltip), mouseX, mouseY)
            return
        }

        hoveredEntry(mouseX.toDouble(), mouseY.toDouble())?.first?.let {
            context.drawTooltip(textRenderer, buildTooltip(it), mouseX, mouseY)
        }
    }

    override fun mouseReleased(click: Click): Boolean {
        if (click.button() == 0) {
            leftMouseDown = false
        }
        return super.mouseReleased(click)
    }

    override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
        if (click.button() == 0) {
            leftMouseDown = true
        }
        val mouseX = click.x()
        val mouseY = click.y()
        if (click.button() != 0) {
            pendingDeleteKey = null
            return super.mouseClicked(click, doubled)
        }

        if (toggleRect().contains(mouseX, mouseY)) {
            sidebarExpanded = !sidebarExpanded
            return true
        }

        if (donationButtonRect().contains(mouseX, mouseY)) {
            Util.getOperatingSystem().open(URI.create("https://buymeacoffee.com/skylist"))
            return true
        }

        if (discordButtonRect().contains(mouseX, mouseY)) {
            Util.getOperatingSystem().open(URI.create("https://discord.gg/9DX2dgyUkD"))
            return true
        }

        if (creditsButtonRect().contains(mouseX, mouseY)) {
            client?.setScreen(ThrowerListCreditsScreen(this))
            return true
        }

        if (settingsButtonRect().contains(mouseX, mouseY)) {
            client?.setScreen(ThrowerListSettingsScreen(this))
            return true
        }

        if (sidebarVisibleRect().contains(mouseX, mouseY)) {
            if (sidebarAnimationProgress >= 0.65f) {
                when {
                    listsButtonRect().contains(mouseX, mouseY) -> return true
                    dungeonButtonRect().contains(mouseX, mouseY) -> client?.setScreen(ThrowerListDungeonScreen(this))
                    miscButtonRect().contains(mouseX, mouseY) -> client?.setScreen(ThrowerListMiscModulesScreen(currentTab, openedFromDungeon = false))
                }
            }
            return true
        }

        if (currentTab == Tab.SCAMMERS && sbzLinkRect().contains(mouseX, mouseY)) {
            Util.getOperatingSystem().open(URI.create("https://discord.gg/skyblock"))
            return true
        }

        Tab.entries.firstOrNull { tabRect(it).contains(mouseX, mouseY) }?.let {
            currentTab = it
            pendingDeleteKey = null
            refreshEntries()
            return true
        }

        val hoveredEntry = hoveredEntry(mouseX, mouseY)
        if (hoveredEntry != null) {
            val (entry, rowRect) = hoveredEntry
            when (hoveredActionForRow(entry, rowRect, mouseX, mouseY)) {
                RowAction.EDIT -> {
                    pendingDeleteKey = null
                    selectEntry(entry)
                    client?.setScreen(ThrowerListEntryEditorScreen(this, entry))
                    return true
                }

                RowAction.VIEW -> {
                    pendingDeleteKey = null
                    selectEntry(entry)
                    client?.setScreen(ThrowerListEntryProfileScreen(this, entry))
                    return true
                }

                RowAction.TOGGLE_REMOTE -> {
                    selectEntry(entry)
                    toggleRemoteEntry(entry)
                    return true
                }

                RowAction.REMOVE -> {
                    selectEntry(entry)
                    handleDeleteClick(entry)
                    return true
                }

                RowAction.REMOVE_SCAMMER -> {
                    selectEntry(entry)
                    handleDeleteClick(entry)
                    return true
                }

                null -> {
                    pendingDeleteKey = null
                    selectEntry(entry)
                    if (doubled) {
                        client?.setScreen(ThrowerListEntryProfileScreen(this, entry))
                    }
                    return true
                }
            }
        }

        if (isFooterActionClick(mouseX, mouseY)) {
            return super.mouseClicked(click, doubled)
        }

        pendingDeleteKey = null
        return super.mouseClicked(click, doubled)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (!listAreaRect().contains(mouseX, mouseY)) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
        }

        val maxOffset = (filteredEntries.size - maxVisibleRows()).coerceAtLeast(0)
        if (maxOffset <= 0) {
            scrollOffset = 0
            return true
        }

        val delta = when {
            verticalAmount > 0 -> -1
            verticalAmount < 0 -> 1
            else -> 0
        }
        scrollOffset = (scrollOffset + delta).coerceIn(0, maxOffset)
        pendingDeleteKey = null
        return true
    }

    fun submitEditor(
        editor: ThrowerListEntryEditorScreen,
        targetEntry: GuiEntry?,
        usernameInput: String,
        reasonInput: String,
        tagsInput: List<String>,
        ignored: Boolean,
        autoRemoveInput: String,
    ) {
        val username = usernameInput.trim()
        val reason = reasonInput.trim()
        val tags = ThrowerTags.normalize(tagsInput)
        val parsedExpiry = parseAutoRemove(autoRemoveInput, editor) ?: return
        if (reason.isBlank()) {
            editor.showError("Reason is required.")
            return
        }

        if (targetEntry != null && !targetEntry.isLocal) {
            editor.showError("Remote entries can't be edited.")
            return
        }

        if (targetEntry != null) {
            applyReasonChange(targetEntry, reason, tags, ignored, parsedExpiry.first, parsedExpiry.second)
            client?.setScreen(this)
            return
        }

        if (!usernameRegex.matches(username)) {
            editor.showError("Enter a valid Minecraft username.")
            return
        }

        if (ConfigManager.findByUsername(username) != null) {
            editor.showError("$username is already on your local list.")
            return
        }

        editor.setBusy("Resolving username...")
        UsernameResolver.resolve(username).thenAccept { resolved ->
            ThrowerListMod.client.execute {
                if (resolved == null) {
                    editor.showError("Could not resolve username: $username")
                    return@execute
                }

                val duplicate = ConfigManager.findByUuid(resolved.uuid) ?: ConfigManager.findByUsername(resolved.username)
                if (duplicate != null) {
                    editor.showError("${resolved.username} is already on your local list.")
                    return@execute
                }

                if (ContentManager.isProtectedCreditUsername(resolved.username)) {
                    editor.showError("My beta testers are not throwers :(")
                    return@execute
                }

                val createdAt = System.currentTimeMillis()
                ConfigManager.addPlayer(
                    PlayerEntry(
                        username = resolved.username,
                        uuid = resolved.uuid,
                        reason = reason,
                        ts = createdAt,
                        tags = tags,
                        ignored = ignored,
                        autoRemoveAfter = parsedExpiry.first,
                        expiresAt = parsedExpiry.second?.applyTo(createdAt),
                    ),
                )
                currentTab = Tab.LOCAL
                selectedEntryKey = entryKey(resolved.uuid, EntrySource.LOCAL)
                pendingDeleteKey = null
                showStatus("Added ${resolved.username} to your local list.", statusSuccessColor)
                refreshEntries()
                client?.setScreen(this)
            }
        }
    }

    fun onSettingsChanged(status: String? = null, color: Int = statusInfoColor) {
        refreshEntries()
        if (status != null) {
            showStatus(status, color)
        }
    }

    private fun refreshEntries() {
        allEntries = entriesForCurrentTab()
        val query = searchQuery.trim().lowercase()
        filteredEntries = if (query.isBlank()) {
            allEntries
        } else {
            allEntries.filter {
                it.username.lowercase().contains(query) ||
                    it.uuid.lowercase().contains(query) ||
                    it.reason.lowercase().contains(query) ||
                    it.tags.any { tag -> tag.contains(query) } ||
                    it.altUsernames.any { alt -> alt.lowercase().contains(query) } ||
                    it.discordLabels.any { user -> user.lowercase().contains(query) } ||
                    it.discordIds.any { id -> id.contains(query) }
            }
        }

        val maxOffset = (filteredEntries.size - maxVisibleRows()).coerceAtLeast(0)
        scrollOffset = scrollOffset.coerceIn(0, maxOffset)
        if (selectedEntryKey != null && filteredEntries.none { entryKey(it) == selectedEntryKey }) {
            selectedEntryKey = null
        }
        updateActionButtons()
    }

    private fun refreshRemoteEntries() {
        if (refreshingRemote) {
            showStatus("Remote refresh is already running.", statusWarningColor)
            return
        }

        refreshingRemote = true
        refreshButton?.active = false
        showStatus("Refreshing remote data...", statusInfoColor)
        CompletableFuture.allOf(
            RemoteListManager.refreshAsync(),
            ScammerListManager.refreshAsync(),
        ).whenComplete { _, throwable ->
            ThrowerListMod.client.execute {
                refreshingRemote = false
                refreshButton?.active = true
                if (throwable != null) {
                    val scammerFailure = ScammerListManager.lastFailureReason()
                    showStatus(scammerFailure ?: "Remote refresh failed. Check your connection and try again.", statusErrorColor)
                } else {
                    refreshEntries()
                    showStatus("Remote data refreshed.", statusSuccessColor)
                }
            }
        }
    }

    private fun applyReasonChange(
        entry: GuiEntry,
        newReason: String,
        newTags: List<String>,
        ignored: Boolean,
        autoRemoveAfter: String?,
        expiryDefinition: EntryExpiry.Definition?,
    ) {
        val expiresAt = resolveExpiresAt(entry, autoRemoveAfter, expiryDefinition)
        if (entry.isLocal) {
            ConfigManager.updateLocalEntry(entry.username, newReason, newTags, ignored, autoRemoveAfter, expiresAt)
            selectedEntryKey = entryKey(entry.uuid, EntrySource.LOCAL)
            showStatus("Updated ${entry.username}'s entry.", statusWarningColor)
            refreshEntries()
            return
        }

        val existingLocal = ConfigManager.findByUuid(entry.uuid) ?: ConfigManager.findByUsername(entry.username)
        if (existingLocal != null) {
            ConfigManager.updateLocalEntry(existingLocal.username, newReason, newTags, ignored, autoRemoveAfter, expiresAt)
        } else {
            if (ContentManager.isProtectedCreditUsername(entry.username)) {
                showStatus("My beta testers are not throwers :(", statusErrorColor)
                return
            }

            ConfigManager.addPlayer(
                PlayerEntry(
                    username = entry.username,
                    uuid = entry.uuid,
                    reason = newReason,
                    ts = System.currentTimeMillis(),
                    tags = newTags.toMutableList(),
                    ignored = ignored,
                    autoRemoveAfter = autoRemoveAfter,
                    expiresAt = expiresAt,
                ),
            )
        }

        currentTab = Tab.LOCAL
        selectedEntryKey = entryKey(entry.uuid, EntrySource.LOCAL)
        showStatus("Saved a local override for ${entry.username}.", statusWarningColor)
        refreshEntries()
    }

    private fun handleDeleteClick(entry: GuiEntry) {
        val deleteKey = deleteKey(entry)
        if (pendingDeleteKey == deleteKey) {
            pendingDeleteKey = null
            deleteEntry(entry)
        } else {
            pendingDeleteKey = deleteKey
            showStatus(deleteConfirmationMessage(entry), statusErrorColor)
        }
    }

    private fun queueDeleteFromAction(entry: GuiEntry) {
        selectEntry(entry)
        if (entry.isLocal) {
            handleDeleteClick(entry)
        } else {
            toggleRemoteEntry(entry)
        }
    }

    private fun deleteEntry(entry: GuiEntry) {
        if (entry.isLocal) {
            ConfigManager.removePlayer(entry.username)
            if (selectedEntryKey == entryKey(entry)) {
                selectedEntryKey = null
            }
            showStatus("Removed ${entry.username} from your local list.", statusErrorColor)
            refreshEntries()
            return
        }

        if (entry.isScammer) {
            if (selectedEntryKey == entryKey(entry)) {
                selectedEntryKey = null
            }
            if (ScammerListManager.removeCachedEntry(entry.uuid)) {
                showStatus("Removed cached scammer entry for ${entry.username}.", statusWarningColor)
            }
            refreshEntries()
            return
        }

        if (!entry.isScammer) {
            toggleRemoteEntry(entry)
        }
    }

    private fun toggleRemoteEntry(entry: GuiEntry) {
        if (entry.isScammer) {
            return
        }
        val disabled = ConfigManager.toggleRemoteEntryDisabled(entry.uuid)
        showStatus(
            if (disabled) "Actions disabled for ${entry.username}." else "Actions enabled for ${entry.username}.",
            if (disabled) statusWarningColor else statusSuccessColor,
        )
        refreshEntries()
    }

    private fun drawTabs(context: DrawContext, mouseX: Double, mouseY: Double, theme: ThemePalette) {
        Tab.entries.forEach { tab ->
            val rect = tabRect(tab)
            val active = tab == currentTab
            val hovered = rect.contains(mouseX, mouseY)
            val fillColor = when {
                active -> theme.mix(theme.secondaryPanel, theme.primaryAccent, 0.18f)
                hovered -> theme.mix(theme.secondaryPanel, theme.hoverAccent, 0.18f)
                else -> theme.mainBackground
            }
            context.fill(rect.left, rect.top, rect.right, rect.bottom, fillColor)
            ThemeRenderer.drawOutline(context, rect.left, rect.top, rect.width(), rect.height(), if (hovered || active) theme.idleBorder else theme.withAlpha(theme.idleBorder, 0x80))
            if (active) {
                context.fill(rect.left, rect.bottom - 2, rect.right, rect.bottom, theme.primaryAccent)
            }
            val textColor = if (active) 0xFFFFFFFF.toInt() else theme.lightTextAccent
            drawCenteredText(context, tab.label, rect.centerX(), rect.top + 6, textColor)
        }
    }

    private fun drawSearchPlaceholder(context: DrawContext, theme: ThemePalette) {
        val field = searchField ?: return
        if (searchQuery.isBlank() && !field.isFocused) {
            drawText(
                context,
                "Search by username or reason...",
                ThemeRenderer.textFieldPlaceholderX(field),
                ThemeRenderer.textFieldPlaceholderY(field),
                theme.mutedText,
            )
        }
    }

    private fun drawEntries(context: DrawContext, mouseX: Double, mouseY: Double, listArea: Rect, theme: ThemePalette) {
        val visibleEntries = filteredEntries.drop(scrollOffset).take(maxVisibleRows())
        if (visibleEntries.isEmpty()) {
            val message = if (allEntries.isEmpty()) currentTab.emptyMessage else "No entries match your search."
            drawCenteredText(context, message, listArea.centerX(), listArea.top + 34, theme.sectionHeader)
            if (allEntries.isNotEmpty()) {
                drawCenteredText(context, "Try a shorter search or switch tabs.", listArea.centerX(), listArea.top + 48, theme.mutedText)
            }
            return
        }

        visibleEntries.forEachIndexed { index, entry ->
            val row = rowRect(index)
            val hovered = row.contains(mouseX, mouseY)
            val selected = selectedEntryKey == entryKey(entry)
            val source = entrySource(entry)
            val badgeRect = sourceBadgeRect(row)
            val tagBadgeRect = listTagBadgeRect(row, entry)
            val textLeft = tagBadgeRect?.right?.plus(8) ?: (row.left + 12)
            val textRight = badgeRect.left - 14
            val textWidth = (textRight - textLeft).coerceAtLeast(40)
            val reasonPreview = truncateToWidth(singleLine(entry.reason), textWidth)
            val dateColor = scamRecencyColor(entry.ts)

            val background = when {
                selected -> theme.selectedRow
                hovered -> theme.hoveredRow
                else -> theme.idleRow
            }
            context.fill(row.left, row.top, row.right, row.bottom, background)
            val accent = if (entry.ignored) 0xFF8E99A3.toInt() else source.accentColor
            context.fill(row.left, row.top, row.left + 4, row.bottom, if (selected) accent else theme.idleBorder)
            if (hovered) {
                ThemeRenderer.drawOutline(context, row.left, row.top, row.width(), row.height(), theme.primaryAccent)
            }

            if (selected) {
                context.fill(row.left + 4, row.top, row.right, row.top + 1, theme.hoverAccent)
                context.fill(row.left + 4, row.bottom - 1, row.right, row.bottom, theme.hoverAccent)
            }

            tagBadgeRect?.let {
                val (letter, color) = listTagBadge(entry, theme) ?: return@let
                context.fill(it.left, it.top, it.right, it.bottom, color)
                drawCenteredText(context, letter, it.centerX(), it.top + 3, 0xFFFFFFFF.toInt())
            }
            drawText(context, entry.username, textLeft, row.top + 7, if (selected) 0xFFFFFFFF.toInt() else theme.lightTextAccent)
            drawText(context, reasonPreview, textLeft, row.top + 22, theme.lightTextAccent)
            drawText(context, shortAddedLabel(entry), row.left + 12, row.top + 36, if (entry.isScammer) dateColor else theme.mutedText)

            val badgeColor = when (source) {
                EntrySource.LOCAL -> when {
                    entry.ignored && selected -> 0xFF404850.toInt()
                    entry.ignored -> 0xFF353C42.toInt()
                    selected -> source.selectedBadgeColor
                    else -> source.badgeColor
                }

                EntrySource.REMOTE -> if (selected) theme.mix(theme.darkAccent, theme.hoverAccent, 0.18f) else theme.darkAccent
                EntrySource.SCAMMER -> if (selected) source.selectedBadgeColor else source.badgeColor
            }
            val badgeTextColor = when (source) {
                EntrySource.LOCAL -> if (entry.ignored) 0xFFC5CDD6.toInt() else source.accentColor
                EntrySource.REMOTE -> theme.lightTextAccent
                EntrySource.SCAMMER -> source.accentColor
            }
            context.fill(badgeRect.left, badgeRect.top, badgeRect.right, badgeRect.bottom, badgeColor)
            drawCenteredText(context, if (entry.ignored) "Ignored" else source.label, badgeRect.centerX(), badgeRect.top + 2, badgeTextColor)
            val stateRect = remoteStateRect(row, badgeRect)
            if (entry.isScammer) {
                val severity = entry.severity
                context.fill(stateRect.left, stateRect.top, stateRect.right, stateRect.bottom, severity?.color ?: theme.panelBackground)
                drawCenteredText(context, severity?.label ?: "Info", stateRect.centerX(), stateRect.top + 2, 0xFFFFFFFF.toInt())
            } else if (entry.isLocal) {
                val removePending = pendingDeleteKey == deleteKey(entry)
                val removeHovered = stateRect.contains(mouseX, mouseY)
                context.fill(stateRect.left, stateRect.top, stateRect.right, stateRect.bottom, if (removeHovered) 0xFF7A2E2E.toInt() else 0xFF5A2323.toInt())
                drawCenteredText(context, if (removePending) "Sure?" else "Remove", stateRect.centerX(), stateRect.top + 2, 0xFFFFCACA.toInt())
            } else {
                val stateFill = if (entry.isRemoteDisabled) 0xFF4A1D1D.toInt() else 0xFF1E4424.toInt()
                val stateText = if (entry.isRemoteDisabled) 0xFFFF9D9D.toInt() else 0xFF9DFF9D.toInt()
                context.fill(stateRect.left, stateRect.top, stateRect.right, stateRect.bottom, stateFill)
                drawCenteredText(context, if (entry.isRemoteDisabled) "Disabled" else "Enabled", stateRect.centerX(), stateRect.top + 2, stateText)
            }

            if (hovered || selected) {
                val viewRect = viewIconRect(row)
                val editRect = editIconRect(row)
                val toggleRect = stateRect
                val viewHovered = viewRect.contains(mouseX, mouseY)
                val editHovered = editRect.contains(mouseX, mouseY)
                val deleteHovered = !entry.isScammer && toggleRect.contains(mouseX, mouseY)
                context.fill(viewRect.left, viewRect.top, viewRect.right, viewRect.bottom, if (viewHovered) theme.hoverAccent else theme.panelBackground)
                context.fill(editRect.left, editRect.top, editRect.right, editRect.bottom, if (editHovered && !entry.isScammer) theme.hoverAccent else theme.panelBackground)
                if (viewHovered) {
                    ThemeRenderer.drawOutline(context, viewRect.left, viewRect.top, viewRect.width(), viewRect.height(), theme.primaryAccent)
                }
                if (editHovered && !entry.isScammer) {
                    ThemeRenderer.drawOutline(context, editRect.left, editRect.top, editRect.width(), editRect.height(), theme.primaryAccent)
                }
                if (deleteHovered) {
                    ThemeRenderer.drawOutline(context, toggleRect.left, toggleRect.top, toggleRect.width(), toggleRect.height(), if (entry.isLocal) 0xC0FFAAAA.toInt() else theme.primaryAccent)
                }
                drawCenteredText(context, "\u25CC", viewRect.centerX(), viewRect.top + 3, if (viewHovered) theme.mainBackground else theme.hoverAccent)
                drawCenteredText(context, if (entry.isScammer) "-" else "\u270E", editRect.centerX(), editRect.top + 3, if (editHovered && !entry.isScammer) theme.mainBackground else theme.hoverAccent)
            }
        }
    }

    private fun drawSelectionActions(context: DrawContext, actionRow: Rect, theme: ThemePalette) {
        val textMaxWidth = actionTextMaxWidth(actionRow)
        val selected = selectedEntry()
        if (selected == null) {
            val lines = mutableListOf<String>()
            lines += when (currentTab) {
                Tab.LOCAL -> "Select an entry to use view, edit, copy, import, and export actions."
                Tab.SCAMMERS -> "Select a scammer entry to view details"
                else -> "Select an entry to use view, edit, toggle, remove, and copy actions."
            }
            lines.flatMap { wrapText(it, textMaxWidth) }
                .forEachIndexed { index, line ->
                    drawText(context, line, actionRow.left, actionRow.top + 4 + index * 10, theme.mutedText)
                }
            return
        }

        if (currentTab == Tab.SCAMMERS) {
            val details = mutableListOf(selected.severity?.label ?: "Unknown severity")
            if (selected.discordLabels.isNotEmpty()) {
                details += "Discord: ${selected.discordLabels.joinToString(", ")}"
            }
            drawText(context, truncateToWidth(selected.username, textMaxWidth), actionRow.left, actionRow.top + 6, 0xFFFFFFFF.toInt())
            details.forEachIndexed { index, line ->
                drawText(context, truncateToWidth(line, textMaxWidth), actionRow.left, actionRow.top + 18 + index * 10, theme.subtleText)
            }
            return
        }

        val status = when {
            selected.isLocal -> "Local entry selected"
            selected.isScammer -> "${selected.severity?.label ?: "Scammer"} severity scammer entry"
            selected.isRemoteDisabled -> "Actions disabled for ${selected.username}"
            else -> "Actions enabled for ${selected.username}"
        }
        drawText(context, truncateToWidth(selected.username, textMaxWidth), actionRow.left, actionRow.top + 6, 0xFFFFFFFF.toInt())
        wrapText(status, textMaxWidth)
            .forEachIndexed { index, line ->
                drawText(context, line, actionRow.left, actionRow.top + 18 + index * 10, theme.subtleText)
            }
    }

    private fun drawStatus(context: DrawContext, frame: Rect, bottomButtons: Rect) {
        statusMessage?.let { message ->
            drawText(
                context,
                truncateToWidth(message.string, frame.width() - layout.status.horizontalPadding * 2),
                frame.left + layout.status.horizontalPadding,
                bottomButtons.top - layout.status.height - layout.status.bottomGap,
                statusColor,
            )
        }
    }

    private fun drawPoweredBySbz(context: DrawContext, listArea: Rect, theme: ThemePalette) {
        if (currentTab != Tab.SCAMMERS) {
            return
        }

        val text = "Powered by sbz (Discord.gg/skyblock)"
        val rect = sbzLinkRect()
        drawCenteredText(context, text, rect.centerX(), rect.top, theme.hoverAccent)
    }

    private fun updateActionButtons() {
        val selected = selectedEntry()

        editButton?.visible = currentTab != Tab.SCAMMERS
        removeButton?.visible = currentTab != Tab.SCAMMERS
        addScammerActionButton?.visible = currentTab == Tab.SCAMMERS
        scammerSettingsActionButton?.visible = false
        editButton?.active = currentTab == Tab.LOCAL || (selected != null && !selected.isScammer)
        removeButton?.active = currentTab == Tab.LOCAL || (selected != null && !selected.isScammer)
        copyUsernameButton?.active = selected != null
        copyUuidButton?.active = true
        addScammerActionButton?.active = true
        scammerSettingsActionButton?.active = false

        if (currentTab == Tab.LOCAL) {
            editButton?.setMessage(Text.literal("Import List"))
            removeButton?.setMessage(Text.literal("Export List"))
        } else {
            editButton?.setMessage(Text.literal("Edit Entry"))
            removeButton?.setMessage(
                Text.literal(
                    when {
                        selected == null -> "Toggle"
                        selected.isLocal -> "Remove Entry"
                        else -> "Toggle"
                    },
                ),
            )
        }
        copyUsernameButton?.setMessage(Text.literal("Copy IGN"))
        addScammerActionButton?.setMessage(Text.literal("Report a Scam"))
        scammerSettingsActionButton?.setMessage(Text.literal("Scammer Settings"))
        copyUuidButton?.setMessage(Text.literal("Check"))
    }

    private fun handleTrollButtonClick() {
        val messages = ContentManager.trollButtonMessages()
        if (trollClickCount >= messages.lastIndex) {
            CommandHandler.triggerSillyDisconnectSequence()
            return
        }

        trollClickCount++
        trollButton?.setMessage(trollButtonMessage())
    }

    private fun entriesForCurrentTab(): List<GuiEntry> {
        val localEntries = ConfigManager.listPlayers()
            .sortedBy { it.username.lowercase() }
            .map {
                GuiEntry(
                    username = it.username,
                    uuid = it.uuid,
                    reason = it.reason,
                    ts = it.ts,
                    tags = it.tags,
                    ignored = it.ignored,
                    autoRemoveAfter = it.autoRemoveAfter,
                    expiresAt = it.expiresAt,
                    source = EntrySource.LOCAL,
                )
            }
        val remoteEntries = RemoteListManager.listEntries()
            .sortedBy { it.username.lowercase() }
            .map {
                GuiEntry(
                    username = it.username,
                    uuid = it.uuid,
                    reason = it.reason,
                    ts = it.ts,
                    tags = it.tags,
                    autoRemoveAfter = null,
                    expiresAt = null,
                    source = EntrySource.REMOTE,
                    isRemoteDisabled = it.isDisabled,
                )
            }
        val scammerEntries = ScammerListManager.listEntries().map {
            GuiEntry(
                username = it.username,
                uuid = it.uuid,
                reason = it.reason,
                ts = it.creationTimeMillis,
                tags = listOf("scammer"),
                source = EntrySource.SCAMMER,
                severity = it.severity,
                altUsernames = it.altUsernames,
                altUuids = it.altUuids,
                discordUsers = it.discordUsers,
                discordIds = it.discordIds,
                evidence = it.evidence,
            )
        }

        return when (currentTab) {
            Tab.LOCAL -> localEntries
            Tab.REMOTE -> remoteEntries
            Tab.SCAMMERS -> scammerEntries
            Tab.ALL -> {
                val localUuids = localEntries.mapTo(hashSetOf()) { it.uuid.lowercase() }
                buildList {
                    addAll(localEntries)
                    addAll(remoteEntries.filterNot { it.uuid.lowercase() in localUuids })
                }
            }
        }
    }

    private fun hoveredEntry(mouseX: Double, mouseY: Double): Pair<GuiEntry, Rect>? {
        filteredEntries.drop(scrollOffset).take(maxVisibleRows()).forEachIndexed { index, entry ->
            val row = rowRect(index)
            if (row.contains(mouseX, mouseY)) {
                return entry to row
            }
        }
        return null
    }

    private fun hoveredRowAction(mouseX: Double, mouseY: Double): Pair<GuiEntry, RowAction>? {
        val hovered = hoveredEntry(mouseX, mouseY) ?: return null
        val action = hoveredActionForRow(hovered.first, hovered.second, mouseX, mouseY) ?: return null
        return hovered.first to action
    }

    private fun hoveredActionForRow(entry: GuiEntry, row: Rect, mouseX: Double, mouseY: Double): RowAction? =
        when {
            viewIconRect(row).contains(mouseX, mouseY) -> RowAction.VIEW
            entry.isScammer && editIconRect(row).contains(mouseX, mouseY) -> RowAction.REMOVE_SCAMMER
            !entry.isScammer && editIconRect(row).contains(mouseX, mouseY) -> RowAction.EDIT
            entry.isLocal && remoteStateRect(row, sourceBadgeRect(row)).contains(mouseX, mouseY) -> RowAction.REMOVE
            !entry.isLocal && !entry.isScammer && remoteStateRect(row, sourceBadgeRect(row)).contains(mouseX, mouseY) -> RowAction.TOGGLE_REMOTE
            else -> null
        }

    private fun isFooterActionClick(mouseX: Double, mouseY: Double): Boolean =
        listOfNotNull(editButton, removeButton, copyUsernameButton, copyUuidButton, addScammerActionButton, scammerSettingsActionButton).filter { it.visible }.any {
            mouseX >= it.x && mouseX <= it.x + it.width && mouseY >= it.y && mouseY <= it.y + it.height
        }

    private fun selectedEntry(): GuiEntry? =
        filteredEntries.firstOrNull { entryKey(it) == selectedEntryKey }
            ?: allEntries.firstOrNull { entryKey(it) == selectedEntryKey }

    private fun selectEntry(entry: GuiEntry) {
        selectedEntryKey = entryKey(entry)
        updateActionButtons()
    }

    private fun copyToClipboard(value: String, message: String) {
        client?.keyboard?.setClipboard(value)
        showStatus(message, statusInfoColor)
    }

    private fun buildTooltip(entry: GuiEntry): MutableList<Text> {
        val theme = ThemeManager.current()
        val sourceText = when {
            entry.isLocal && entry.ignored -> Text.literal("Local (ignored)").formatted(Formatting.GRAY)
            entry.isLocal -> Text.literal("Local").formatted(Formatting.GREEN)
            entry.isScammer -> Text.literal("Scammer").formatted(Formatting.RED)
            entry.isRemoteDisabled -> Text.literal("Remote (disabled)").styled { it.withColor(theme.primaryAccent and 0xFFFFFF) }
            else -> Text.literal("Remote").styled { it.withColor(theme.primaryAccent and 0xFFFFFF) }
        }
        val lines = mutableListOf<Text>()
        lines += tooltipLine("Name", Text.literal(entry.username).formatted(if (entry.ignored) Formatting.GRAY else Formatting.RED))
        appendWrappedTooltip(lines, "Reason", entry.reason, Formatting.DARK_GRAY)
        if (entry.tags.isNotEmpty()) {
            lines += tooltipLine("Tags", Text.literal(entry.tags.joinToString(", ")).formatted(Formatting.GRAY))
        }
        if (entry.ignored) {
            lines += tooltipLine("Ignore", Text.literal("Yes").formatted(Formatting.GRAY))
        }
        entry.autoRemoveAfter?.let { autoRemoveAfter ->
            lines += tooltipLine("Auto remove", Text.literal(autoRemoveAfter).formatted(Formatting.GRAY))
        }
        entry.expiresAt?.let { expiresAt ->
            lines += tooltipLine("Expires", Text.literal(formatAdded(expiresAt)).formatted(Formatting.GRAY), Formatting.WHITE)
        }
        lines += tooltipLine("Source", sourceText, Formatting.WHITE)
        if (entry.isScammer) {
            lines += tooltipLine("Severity", Text.literal(entry.severity?.label ?: "Unknown").formatted(Formatting.GOLD), Formatting.WHITE)
            lines += tooltipLine("Date", Text.literal(formatAdded(entry.ts)).formatted(Formatting.GRAY), Formatting.WHITE)
            if (entry.discordLabels.isNotEmpty()) {
                lines += tooltipLine("Discord", Text.literal(entry.discordLabels.joinToString(", ")).formatted(Formatting.GRAY), Formatting.WHITE)
            }
        } else {
            lines += tooltipLine("Added", Text.literal(formatAdded(entry.ts)).formatted(Formatting.GRAY), Formatting.WHITE)
        }
        return lines
    }

    private fun appendWrappedTooltip(lines: MutableList<Text>, label: String, value: String, valueColor: Formatting) {
        val wrapped = wrapText(value, (layout.tooltip.maxWidth - textRenderer.getWidth("$label: ")).coerceAtLeast(40))
        wrapped.forEachIndexed { index, line ->
            lines += if (index == 0) {
                tooltipLine(label, Text.literal(line).formatted(valueColor))
            } else {
                Text.empty()
                    .append(Text.literal("  ").formatted(Formatting.DARK_GRAY))
                    .append(Text.literal(line).formatted(valueColor))
            }
        }
    }

    private fun tooltipLine(label: String, value: MutableText, labelColor: Formatting = Formatting.WHITE): Text {
        return Text.empty()
            .append(Text.literal("$label: ").formatted(labelColor, Formatting.BOLD))
            .append(value)
    }

    private fun wrapText(text: String, maxWidth: Int): List<String> {
        val words = text.split(' ')
        if (words.isEmpty()) {
            return listOf(text)
        }

        val lines = mutableListOf<String>()
        var current = StringBuilder()
        words.forEach { word ->
            val candidate = if (current.isEmpty()) word else current.toString() + " " + word
            if (textRenderer.getWidth(candidate) <= maxWidth) {
                current = StringBuilder(candidate)
            } else {
                if (current.isNotEmpty()) {
                    lines += current.toString()
                }
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) {
            lines += current.toString()
        }
        return lines.ifEmpty { listOf(text) }
    }

    private fun wrapReason(reason: String, maxWidth: Int): List<String> {
        val normalized = reason.replace(Regex("\\s*\\R\\s*"), " ").trim()
        val wrapped = wrapText(normalized, maxWidth)
        if (wrapped.size <= 2) {
            return wrapped
        }
        return listOf(wrapped[0], truncateReason(wrapped[1], 32))
    }

    private fun deleteConfirmationMessage(entry: GuiEntry): String =
        if (entry.isLocal) {
            "Click Remove again to delete ${entry.username} from your local list."
        } else if (entry.isScammer) {
            "Click again to remove ${entry.username}."
        } else if (entry.isRemoteDisabled) {
            "Click again to enable actions for ${entry.username}."
        } else {
            "Click again to disable actions for ${entry.username}."
        }

    private fun countLabel(): String = "Showing ${filteredEntries.size} of ${allEntries.size}"

    private fun entrySource(entry: GuiEntry): EntrySource = entry.source

    private fun entryKey(entry: GuiEntry): String = entryKey(entry.uuid, entry.source)

    private fun entryKey(uuid: String, source: EntrySource): String = "${source.name.lowercase()}:${uuid.lowercase()}"

    private fun deleteKey(entry: GuiEntry): String = entryKey(entry)

    private fun shortAddedLabel(entry: GuiEntry): String =
        if (entry.ts == null || entry.ts <= 0L) {
            if (entry.isScammer) "Date unknown" else "Added unknown"
        } else if (entry.isScammer) {
            "Date ${formatShortAdded(entry.ts)}"
        } else {
            "Added ${formatShortAdded(entry.ts)}"
        }

    private fun formatAdded(ts: Long?): String {
        if (ts == null || ts <= 0L) {
            return "unknown"
        }

        return Instant.ofEpochMilli(ts)
            .atZone(ZoneId.systemDefault())
            .format(addedTimeFormatter)
    }

    private fun formatShortAdded(ts: Long): String {
        if (ts <= 0L) {
            return "unknown"
        }
        return Instant.ofEpochMilli(ts)
            .atZone(ZoneId.systemDefault())
            .format(shortAddedFormatter)
    }

    fun scamRecencyColor(ts: Long?): Int {
        if (ts == null) {
            return 0xFFD0D0D0.toInt()
        }

        val ageMillis = (System.currentTimeMillis() - ts).coerceAtLeast(0)
        val ageDays = ageMillis / 86_400_000L
        return when {
            ageDays <= 7 -> 0xFFFF6B6B.toInt()
            ageDays <= 30 -> 0xFFFFA347.toInt()
            ageDays <= 90 -> 0xFFFFE066.toInt()
            ageDays <= 180 -> 0xFF78E08F.toInt()
            else -> 0xFFD0D0D0.toInt()
        }
    }

    private fun truncateReason(reason: String, maxLength: Int): String {
        val singleLine = reason.replace(Regex("\\s*\\R\\s*"), " ").trim()
        return if (singleLine.length <= maxLength) singleLine else singleLine.take(maxLength - 1).trimEnd().trimEnd('.', ',', ';', ':') + "..."
    }

    private fun truncateToWidth(text: String, maxWidth: Int): String {
        if (textRenderer.getWidth(text) <= maxWidth) {
            return text
        }

        var end = text.length
        while (end > 0 && textRenderer.getWidth(text.take(end) + "...") > maxWidth) {
            end--
        }
        return text.take(end).trimEnd().trimEnd('.', ',', ';', ':') + "..."
    }

    private fun singleLine(text: String): String = text.replace(Regex("\\s*\\R\\s*"), " ").trim()

    private fun showStatus(message: String, color: Int) {
        statusMessage = Text.literal(message)
        statusColor = color
    }

    private fun frameRect(): Rect {
        val frame = layout.frame
        val startWidth = when {
            startFromDungeonWidth -> frame.dungeonWidth
            openFromCenter -> 72
            else -> frame.maxWidth
        }
        val targetWidth = lerpInt(startWidth, frame.maxWidth, frameWidthProgress)
        val frameWidth = minOf(targetWidth, width - frame.horizontalMargin * 2)
        return Rect((width - frameWidth) / 2, frame.top, (width + frameWidth) / 2, height - frame.bottomMargin)
    }

    private fun relayoutWidgets() {
        val search = searchRect()
        searchField?.let {
            it.x = search.left
            it.y = search.top
            it.setWidth(search.width())
            ThemeRenderer.applyTextFieldInset(it)
        }

        trollButton?.let { moveWidget(it, trollButtonRect()) }

        val titleButtons = titleBarButtonsRect()
        val bottomButtons = bottomButtonRowRect()
        val footerButtonCount = 4
        val bottomButtonWidth = (bottomButtons.width() - (layout.footer.buttonGap * (footerButtonCount - 1))) / footerButtonCount
        addButton?.let { moveWidget(it, Rect(bottomButtons.left, bottomButtons.top, bottomButtons.left + bottomButtonWidth, bottomButtons.bottom)) }
        settingsButton?.let {
            moveWidget(
                it,
                Rect(
                    bottomButtons.left + (bottomButtonWidth + layout.footer.buttonGap),
                    bottomButtons.top,
                    bottomButtons.left + (bottomButtonWidth + layout.footer.buttonGap) + bottomButtonWidth,
                    bottomButtons.bottom,
                ),
            )
        }
        refreshButton?.let {
            moveWidget(
                it,
                Rect(
                    bottomButtons.left + (bottomButtonWidth + layout.footer.buttonGap) * 2,
                    bottomButtons.top,
                    bottomButtons.left + (bottomButtonWidth + layout.footer.buttonGap) * 2 + bottomButtonWidth,
                    bottomButtons.bottom,
                ),
            )
        }
        doneButton?.let {
            moveWidget(
                it,
                Rect(
                    bottomButtons.left + (bottomButtonWidth + layout.footer.buttonGap) * 3,
                    bottomButtons.top,
                    bottomButtons.left + (bottomButtonWidth + layout.footer.buttonGap) * 3 + bottomButtonWidth,
                    bottomButtons.bottom,
                ),
            )
        }

        val actionRow = selectionActionRowRect()
        val detailButtons = layout.actionStrip.detailButtons
        val detailButtonTop = actionRow.top + detailButtons.topOffset
        val detailButtonsRight = actionRow.right
        val copyUuidLeft = detailButtonsRight - detailButtons.width
        val copyUsernameLeft = copyUuidLeft - detailButtons.gap - detailButtons.width
        val editLeft = copyUsernameLeft - detailButtons.gap - detailButtons.width
        val removeLeft = editLeft - detailButtons.gap - detailButtons.width
        removeButton?.let { moveWidget(it, Rect(removeLeft, detailButtonTop, removeLeft + detailButtons.width, detailButtonTop + detailButtons.height)) }
        editButton?.let { moveWidget(it, Rect(editLeft, detailButtonTop, editLeft + detailButtons.width, detailButtonTop + detailButtons.height)) }
        copyUsernameButton?.let { moveWidget(it, Rect(copyUsernameLeft, detailButtonTop, copyUsernameLeft + detailButtons.width, detailButtonTop + detailButtons.height)) }
        copyUuidButton?.let { moveWidget(it, Rect(copyUuidLeft, detailButtonTop, copyUuidLeft + detailButtons.width, detailButtonTop + detailButtons.height)) }
        addScammerActionButton?.let { moveWidget(it, Rect(editLeft, detailButtonTop, editLeft + detailButtons.width, detailButtonTop + detailButtons.height)) }
        scammerSettingsActionButton?.let { moveWidget(it, Rect(removeLeft, detailButtonTop, removeLeft + detailButtons.width, detailButtonTop + detailButtons.height)) }
    }

    private fun moveWidget(widget: ButtonWidget, rect: Rect) {
        widget.x = rect.left
        widget.y = rect.top
        widget.width = rect.width()
        widget.height = rect.height()
    }

    private fun searchRect(): Rect {
        val tabs = tabsRowRect()
        val content = contentRect()
        val search = layout.search
        return Rect(
            content.left,
            tabs.bottom + search.topSpacing,
            content.right - search.countWidth - search.countGap,
            tabs.bottom + search.topSpacing + search.height,
        )
    }

    private fun countRect(): Rect {
        val search = searchRect()
        val content = contentRect()
        return Rect(search.right + layout.search.countGap, search.top, content.right, search.bottom)
    }

    private fun listAreaRect(): Rect {
        val search = searchRect()
        val actions = selectionActionRowRect()
        val content = contentRect()
        return Rect(content.left, search.bottom + layout.list.topSpacing, content.right, actions.top - layout.actionStrip.bottomGap)
    }

    private fun selectionActionRowRect(): Rect {
        val footer = bottomButtonRowRect()
        val statusTop = footer.top - layout.status.bottomGap - layout.status.height
        val content = contentRect()
        return Rect(
            content.left,
            statusTop - layout.actionStrip.bottomGap - layout.actionStrip.height,
            content.right,
            statusTop - layout.actionStrip.bottomGap,
        )
    }

    private fun bottomButtonRowRect(): Rect {
        val content = contentRect()
        val footer = layout.footer
        return Rect(
            content.left,
            frameRect().bottom - footer.bottomGap - footer.height,
            content.right,
            frameRect().bottom - footer.bottomGap,
        )
    }

    private fun titleBarButtonsRect(): Rect {
        val content = contentRect()
        val titleBar = layout.titleBar
        val top = frameRect().top + titleBar.controlTopOffset
        val width = titleBar.donationButtonWidth + titleBar.buttonGap + titleBar.discordButtonWidth + titleBar.buttonGap + titleBar.creditsButtonWidth + titleBar.buttonGap + titleBar.settingsButtonWidth
        return Rect(content.right - width, top, content.right, top + titleBar.buttonHeight)
    }

    private fun donationButtonRect(): Rect {
        val titleButtons = titleBarButtonsRect()
        return Rect(
            titleButtons.left,
            titleButtons.top,
            titleButtons.left + layout.titleBar.donationButtonWidth,
            titleButtons.bottom,
        )
    }

    private fun discordButtonRect(): Rect {
        val titleButtons = titleBarButtonsRect()
        val left = titleButtons.left + layout.titleBar.donationButtonWidth + layout.titleBar.buttonGap
        return Rect(
            left,
            titleButtons.top,
            left + layout.titleBar.discordButtonWidth,
            titleButtons.bottom,
        )
    }

    private fun creditsButtonRect(): Rect {
        val titleButtons = titleBarButtonsRect()
        val left = titleButtons.left + layout.titleBar.donationButtonWidth + layout.titleBar.buttonGap + layout.titleBar.discordButtonWidth + layout.titleBar.buttonGap
        return Rect(
            left,
            titleButtons.top,
            left + layout.titleBar.creditsButtonWidth,
            titleButtons.bottom,
        )
    }

    private fun settingsButtonRect(): Rect {
        val titleButtons = titleBarButtonsRect()
        val left = titleButtons.left + layout.titleBar.donationButtonWidth + layout.titleBar.buttonGap + layout.titleBar.discordButtonWidth + layout.titleBar.buttonGap + layout.titleBar.creditsButtonWidth + layout.titleBar.buttonGap
        return Rect(
            left,
            titleButtons.top,
            left + layout.titleBar.settingsButtonWidth,
            titleButtons.bottom,
        )
    }

    private fun trollButtonRect(): Rect {
        val content = contentRect()
        val titleBar = layout.titleBar
        val top = frameRect().top + titleBar.controlTopOffset
        val left = content.left + titleBar.trollButtonLeftOffset
        val width = minOf(titleBar.trollButtonMaxWidth, content.width() / 2 - titleBar.trollButtonRightPadding - titleBar.trollButtonLeftOffset)
            .coerceAtLeast(titleBar.trollButtonMinWidth)
        return Rect(left, top, left + width, top + titleBar.buttonHeight)
    }

    private fun actionTextMaxWidth(actionRow: Rect): Int {
        val firstButtonX = listOfNotNull(editButton, removeButton, copyUsernameButton, copyUuidButton, addScammerActionButton, scammerSettingsActionButton)
            .minOfOrNull { it.x } ?: actionRow.right
        return (firstButtonX - actionRow.left - layout.actionStrip.textGap).coerceAtLeast(layout.actionStrip.textMinWidth)
    }

    private fun tabsRowRect(): Rect {
        val content = contentRect()
        val top = frameRect().top + layout.titleBar.height + layout.tabs.topSpacing
        return Rect(content.left, top, content.right, top + layout.tabs.height)
    }

    private fun sbzLinkRect(): Rect {
        val listArea = listAreaRect()
        val text = "Powered by sbz (Discord.gg/skyblock)"
        val width = textRenderer.getWidth(text)
        val left = listArea.right - width - 6
        return Rect(left, listArea.bottom - 12, listArea.right - 6, listArea.bottom)
    }

    private fun contentRect(): Rect {
        val frame = frameRect()
        val inset = layout.frame.contentInset
        return Rect(frame.left + inset, frame.top, frame.right - inset, frame.bottom)
    }

    private fun sidebarRect(): Rect {
        val frame = frameRect()
        val sidebar = layout.sidebar
        return Rect(
            frame.left + sidebar.frameInset,
            frame.top + sidebar.verticalInset,
            frame.left + sidebar.frameInset + sidebar.handleWidth,
            frame.bottom - sidebar.verticalInset,
        )
    }

    private fun sidebarVisibleRect(): Rect {
        val handle = sidebarRect()
        val flyoutWidth = layout.sidebar.flyoutWidth
        val extraWidth = (flyoutWidth * sidebarAnimationProgress).roundToInt()
        return Rect(handle.left - extraWidth, handle.top, handle.right, handle.bottom)
    }

    private fun sidebarFlyoutRect(): Rect {
        val sidebar = sidebarVisibleRect()
        val handle = sidebarRect()
        return Rect(sidebar.left, sidebar.top, handle.left, sidebar.bottom)
    }

    private fun toggleRect(): Rect {
        val sidebar = sidebarRect()
        val toggle = layout.sidebar
        val top = frameRect().top + toggle.toggleTopOffset
        return Rect(
            sidebar.left + toggle.toggleLeftOffset,
            top,
            sidebar.left + toggle.toggleLeftOffset + toggle.toggleSize,
            top + toggle.toggleSize,
        )
    }

    private fun dungeonButtonRect(): Rect {
        val config = layout.sidebar
        val lists = listsButtonRect()
        return Rect(lists.left, lists.bottom + 6, lists.right, lists.bottom + 6 + config.buttonHeight)
    }

    private fun miscButtonRect(): Rect {
        val config = layout.sidebar
        val dungeon = dungeonButtonRect()
        return Rect(dungeon.left, dungeon.bottom + 6, dungeon.right, dungeon.bottom + 6 + config.buttonHeight)
    }

    private fun listsButtonRect(): Rect {
        val sidebar = sidebarFlyoutRect()
        val config = layout.sidebar
        val left = sidebar.left + config.innerPadding
        val right = (sidebar.right - config.innerPadding).coerceAtLeast(left + config.buttonMinWidth)
        val top = sidebar.top + config.headerTopOffset + textRenderer.fontHeight + config.headerButtonGap
        return Rect(left, top, right, top + config.buttonHeight)
    }

    private fun tabRect(tab: Tab): Rect {
        val tabs = tabsRowRect()
        val tabWidth = (tabs.width() - layout.tabs.gapTotal) / Tab.entries.size
        val gap = layout.tabs.gapTotal / (Tab.entries.size - 1).coerceAtLeast(1)
        val index = Tab.entries.indexOf(tab)
        val left = tabs.left + index * (tabWidth + gap)
        return Rect(left, tabs.top, left + tabWidth, tabs.bottom)
    }

    private fun rowRect(index: Int): Rect {
        val area = listAreaRect()
        val row = layout.row
        val top = area.top + index * row.height
        return Rect(
            area.left + row.inset,
            top + row.inset,
            area.right - row.inset,
            top + row.height - row.inset,
        )
    }

    private fun sourceBadgeRect(row: Rect): Rect {
        val sourceBadge = layout.row.sourceBadge
        val width = maxOf(
            textRenderer.getWidth("Scammer"),
            textRenderer.getWidth("Remote"),
            textRenderer.getWidth("Disabled"),
            textRenderer.getWidth("Enabled"),
            textRenderer.getWidth("Remove"),
            textRenderer.getWidth("Sure?"),
            textRenderer.getWidth("Critical"),
        ) + sourceBadge.padding
        return Rect(
            row.right - width - sourceBadge.rightInset,
            row.top + sourceBadge.topOffset,
            row.right - sourceBadge.rightInset,
            row.top + sourceBadge.topOffset + sourceBadge.height,
        )
    }

    private fun listTagBadgeRect(row: Rect, entry: GuiEntry): Rect? {
        if (entry.tags.isEmpty() && !entry.ignored) return null
        val tagBadge = layout.row.tagBadge
        return Rect(
            row.left + tagBadge.leftOffset,
            row.top + tagBadge.topOffset,
            row.left + tagBadge.leftOffset + tagBadge.size,
            row.top + tagBadge.topOffset + tagBadge.size,
        )
    }

    private fun listTagBadge(entry: GuiEntry, theme: ThemePalette): Pair<String, Int>? =
        when {
            entry.ignored -> "I" to 0xFF606A73.toInt()
            entry.tags.firstOrNull() == "toxic" -> "T" to 0xFF9A4C63.toInt()
            entry.tags.firstOrNull() == "griefer" -> "G" to 0xFFB97544.toInt()
            entry.tags.firstOrNull() == "ratter" -> "R" to 0xFF7E5AB6.toInt()
            entry.tags.firstOrNull() == "cheater" -> "C" to 0xFFAA4F78.toInt()
            entry.tags.firstOrNull() == "scammer" -> "S" to 0xFFC08A42.toInt()
            else -> null
        }

    private fun remoteStateRect(row: Rect, badgeRect: Rect): Rect {
        val remoteState = layout.row.remoteState
        return Rect(badgeRect.left, row.top + remoteState.topOffset, badgeRect.right, row.top + remoteState.topOffset + remoteState.height)
    }

    private fun viewIconRect(row: Rect): Rect =
        layout.row.viewIcon.let { icon ->
            Rect(row.right - icon.rightInset - icon.width, row.top + icon.topOffset, row.right - icon.rightInset, row.top + icon.topOffset + icon.height)
        }

    private fun editIconRect(row: Rect): Rect =
        layout.row.editIcon.let { icon ->
            Rect(row.right - icon.rightInset - icon.width, row.top + icon.topOffset, row.right - icon.rightInset, row.top + icon.topOffset + icon.height)
        }

    private fun maxVisibleRows(): Int = (listAreaRect().height() / layout.row.height).coerceAtLeast(1)

    private fun drawButtons(context: DrawContext, mouseX: Double, mouseY: Double, theme: ThemePalette) {
        listOfNotNull(trollButton, addButton, settingsButton, refreshButton, doneButton, editButton, removeButton, copyUsernameButton, copyUuidButton, addScammerActionButton, scammerSettingsActionButton)
            .filter { it.visible }
            .forEach { widget -> ThemeRenderer.drawButton(context, widget, mouseX, mouseY, leftMouseDown, theme) }
    }

    private fun drawDiscordButton(context: DrawContext, mouseX: Double, mouseY: Double, theme: ThemePalette) {
        val rect = discordButtonRect()
        val hovered = rect.contains(mouseX, mouseY)
        ThemeRenderer.drawDiscordButton(context, rect.left, rect.top, rect.width(), hovered, hovered && leftMouseDown, theme)
    }

    private fun drawCreditsButton(context: DrawContext, mouseX: Double, mouseY: Double, theme: ThemePalette) {
        val rect = creditsButtonRect()
        val hovered = rect.contains(mouseX, mouseY)
        ThemeRenderer.drawCreditsButton(context, rect.left, rect.top, rect.width(), hovered, hovered && leftMouseDown, theme)
    }

    private fun drawSettingsIconButton(context: DrawContext, mouseX: Double, mouseY: Double, theme: ThemePalette) {
        val rect = settingsButtonRect()
        val hovered = rect.contains(mouseX, mouseY)
        ThemeRenderer.drawSettingsButton(context, rect.left, rect.top, rect.width(), hovered, hovered && leftMouseDown, theme)
    }

    private fun drawDonationButton(context: DrawContext, mouseX: Double, mouseY: Double, theme: ThemePalette) {
        val rect = donationButtonRect()
        val hovered = rect.contains(mouseX, mouseY)
        ThemeRenderer.drawDonationButton(context, rect.left, rect.top, rect.width(), hovered, hovered && leftMouseDown, theme)
    }

    private fun drawInlineTitle(context: DrawContext, frame: Rect, theme: ThemePalette) {
        val content = contentRect()
        val titleText = title.string
        val titleX = content.centerX() - (textRenderer.getWidth(titleText) + titleVersionGap + (textRenderer.getWidth(versionLabel) * titleVersionScale).roundToInt()) / 2
        val titleY = frame.top + 10
        val versionX = titleX + textRenderer.getWidth(titleText) + titleVersionGap
        val versionY = titleY + ((textRenderer.fontHeight - textRenderer.fontHeight * titleVersionScale) / 2f).roundToInt()
        drawText(context, titleText, titleX, titleY, 0xFFFFFFFF.toInt())
        drawScaledText(context, versionLabel, versionX, versionY, theme.subtleText, titleVersionScale)
    }

    private fun drawSidebar(context: DrawContext, mouseX: Double, mouseY: Double, theme: ThemePalette) {
        val sidebarBounds = sidebarVisibleRect()
        val flyoutBounds = sidebarFlyoutRect()
        val toggle = toggleRect()
        val listsButton = listsButtonRect()
        val button = dungeonButtonRect()
        val miscButton = miscButtonRect()
        val hoveredSidebar = sidebarBounds.contains(mouseX, mouseY)
        val hoveredToggle = toggle.contains(mouseX, mouseY)
        val hoveredLists = sidebarAnimationProgress >= 0.65f && listsButton.contains(mouseX, mouseY)
        val hoveredDungeon = sidebarAnimationProgress >= 0.65f && button.contains(mouseX, mouseY)
        val hoveredMisc = sidebarAnimationProgress >= 0.65f && miscButton.contains(mouseX, mouseY)

        drawSidebarToggle(context, toggle, hoveredToggle, theme)

        if (sidebarAnimationProgress <= 0.01f) {
            return
        }

        if (flyoutBounds.width() > 0) {
            context.fill(
                flyoutBounds.left,
                flyoutBounds.top,
                flyoutBounds.right,
                flyoutBounds.bottom,
                theme.withAlpha(theme.secondaryPanel, (120 + (sidebarAnimationProgress * 72f).roundToInt()).coerceIn(0, 255)),
            )
            ThemeRenderer.drawOutline(
                context,
                flyoutBounds.left,
                flyoutBounds.top,
                flyoutBounds.width(),
                flyoutBounds.height(),
                if (hoveredSidebar) theme.primaryAccent else theme.idleBorder,
            )
        }

        if (sidebarAnimationProgress < 0.65f || flyoutBounds.width() <= 0 || button.width() <= 12) {
            return
        }

        val headerX = flyoutBounds.left + layout.sidebar.innerPadding
        val headerY = flyoutBounds.top + layout.sidebar.headerTopOffset
        drawText(context, "Modules", headerX, headerY, theme.hoverAccent)
        drawSidebarButton(context, listsButton, "Lists", hoveredLists, true, theme)
        drawSidebarButton(context, button, "Dungeon", hoveredDungeon, false, theme)
        drawSidebarButton(context, miscButton, "Misc", hoveredMisc, false, theme)
    }

    private fun drawSidebarButton(
        context: DrawContext,
        rect: Rect,
        label: String,
        hovered: Boolean,
        selected: Boolean,
        theme: ThemePalette,
    ) {
        val fill = when {
            selected -> theme.hoverAccent
            hovered && leftMouseDown -> theme.darkAccent
            hovered -> theme.hoverAccent
            else -> theme.panelBackground
        }
        val border = if (selected || hovered) theme.primaryAccent else theme.idleBorder
        val textColor = if (selected || hovered) theme.mainBackground else theme.lightTextAccent
        context.fill(rect.left, rect.top, rect.right, rect.bottom, fill)
        ThemeRenderer.drawOutline(context, rect.left, rect.top, rect.width(), rect.height(), border)
        ThemeRenderer.drawCenteredTextWithShadow(context, textRenderer, label, rect.centerX(), rect.top + 6, textColor)
    }

    private fun drawSidebarToggle(context: DrawContext, toggle: Rect, hovered: Boolean, theme: ThemePalette) {
        val color = if (hovered) theme.primaryAccent else theme.lightTextAccent
        val centerX = toggle.centerX()
        val centerY = (toggle.top + toggle.bottom) / 2
        val topOffset = 4
        val bottomOffset = 4
        val lineHeight = 2
        val lineWidth = 10

        drawSidebarLine(context, centerX, centerY - topOffset, lineWidth, lineHeight, color)
        drawSidebarLine(context, centerX, centerY, lineWidth, lineHeight, color)
        drawSidebarLine(context, centerX, centerY + bottomOffset, lineWidth, lineHeight, color)
    }

    private fun drawSidebarLine(context: DrawContext, centerX: Int, centerY: Int, width: Int, height: Int, color: Int) {
        val left = centerX - width / 2
        val top = centerY - height / 2
        context.fill(left, top, left + width, top + height, color)
    }

    private fun animateSidebarProgress(current: Float, target: Float): Float {
        val difference = target - current
        if (kotlin.math.abs(difference) < 0.01f) {
            return target
        }
        return (current + difference * 0.18f).coerceIn(0f, 1f)
    }

    private fun lerpInt(start: Int, end: Int, progress: Float): Int =
        (start + ((end - start) * progress.coerceIn(0f, 1f))).roundToInt()

    private fun drawCenteredText(context: DrawContext, text: String, centerX: Int, y: Int, color: Int) {
        ThemeRenderer.drawCenteredText(context, textRenderer, text, centerX, y, color)
    }

    private fun drawText(context: DrawContext, text: String, x: Int, y: Int, color: Int) {
        ThemeRenderer.drawText(context, textRenderer, text, x, y, color)
    }

    private fun drawScaledText(context: DrawContext, text: String, x: Int, y: Int, color: Int, scale: Float) {
        val matrices = context.matrices
        matrices.pushMatrix()
        matrices.scale(scale, scale)
        ThemeRenderer.drawText(context, textRenderer, text, (x / scale).roundToInt(), (y / scale).roundToInt(), color)
        matrices.popMatrix()
    }

    fun openEditorFor(entry: GuiEntry) {
        selectEntry(entry)
        if (entry.isScammer) {
            client?.setScreen(ThrowerListEntryProfileScreen(this, entry))
        } else {
            client?.setScreen(ThrowerListEntryEditorScreen(this, entry))
        }
    }

    fun performPrimaryAction(entry: GuiEntry) {
        selectEntry(entry)
        when {
            entry.isLocal -> handleDeleteClick(entry)
            entry.isScammer -> client?.setScreen(ThrowerListEntryProfileScreen(this, entry))
            else -> toggleRemoteEntry(entry)
        }
    }

    fun copyIgn(entry: GuiEntry) {
        copyToClipboard(entry.username, "Copied IGN ${entry.username}.")
    }

    fun copyUuid(entry: GuiEntry) {
        copyToClipboard(entry.uuid, "Copied UUID for ${entry.username}.")
    }

    fun findEntry(uuid: String, source: EntrySource): GuiEntry? =
        filteredEntries.firstOrNull { it.uuid.equals(uuid, ignoreCase = true) && it.source == source }
            ?: allEntries.firstOrNull { it.uuid.equals(uuid, ignoreCase = true) && it.source == source }
            ?: entriesForCurrentTab().firstOrNull { it.uuid.equals(uuid, ignoreCase = true) && it.source == source }

    fun formatEntryAdded(ts: Long?): String = formatAdded(ts)

    fun currentTabForReturn(): Tab = currentTab

    private fun parseAutoRemove(autoRemoveInput: String, editor: ThrowerListEntryEditorScreen): Pair<String?, EntryExpiry.Definition?>? {
        val trimmed = autoRemoveInput.trim()
        if (trimmed.isEmpty()) {
            return null to null
        }

        val definition = EntryExpiry.parse(trimmed)
        if (definition == null) {
            editor.showError("Use auto remove values like 1mt 2d or 1 month 2 days.")
            return null
        }

        return definition.canonical to definition
    }

    private fun resolveExpiresAt(entry: GuiEntry, autoRemoveAfter: String?, expiryDefinition: EntryExpiry.Definition?): Long? {
        if (autoRemoveAfter == null || expiryDefinition == null) {
            return null
        }

        if (entry.autoRemoveAfter == autoRemoveAfter && entry.expiresAt != null && !EntryExpiry.hasExpired(entry.expiresAt)) {
            return entry.expiresAt
        }

        return expiryDefinition.applyTo(System.currentTimeMillis())
    }

    private fun trollButtonMessage(): Text {
        val messages = ContentManager.trollButtonMessages()
        val index = trollClickCount.coerceIn(0, messages.lastIndex)
        return Text.literal(messages[index])
    }
}
