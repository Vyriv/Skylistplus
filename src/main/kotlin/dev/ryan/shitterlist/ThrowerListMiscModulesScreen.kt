package dev.ryan.throwerlist

import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import net.minecraft.util.Util
import java.net.URI
import kotlin.math.roundToInt

class ThrowerListMiscModulesScreen(
    private val returnTab: ThrowerListListScreen.Tab,
    private val openedFromDungeon: Boolean,
) : Screen(Text.literal("Skylist")) {
    private val versionLabel = "v${SkylistPlusRuntimeVersion.featureVersion()}"
    private val titleVersionScale = 0.75f
    private val titleVersionGap = 4
    private val usernameRegex = Regex("^[A-Za-z0-9_]{1,16}$")
    private val layout: UiLayoutManager.MainListLayout
        get() = UiLayoutManager.mainList()

    private var ignoreInputField: TextFieldWidget? = null
    private var addButton: ButtonWidget? = null
    private var backButton: ButtonWidget? = null
    private var leftMouseDown = false
    private var sidebarExpanded = true
    private var sidebarAnimationProgress = 1f
    private var frameWidthProgress = 0f
    private var ignoreListExpanded = false
    private var swingSpeedExpanded = false
    private var statusMessage: String? = null
    private var statusColor = 0xFF7FD6FF.toInt()
    private var listOffset = 0

    override fun init() {
        super.init()
        ignoreInputField = TextFieldWidget(textRenderer, 0, 0, 120, 20, Text.literal("Username")).also {
            it.setDrawsBackground(false)
            it.setMaxLength(16)
            ThemeRenderer.applyTextFieldInset(it)
            addDrawableChild(it)
            setInitialFocus(it)
        }
        addButton = ThemedButtonWidget.builder(Text.literal("Add")) { addIgnoredUsername() }
            .dimensions(0, 0, 92, 20).build().also { addDrawableChild(it) }
        backButton = ThemedButtonWidget.builder(Text.literal("Back")) { close() }
            .dimensions(0, 0, 92, 20).build().also { addDrawableChild(it) }
        layoutWidgets()
        syncVisibility()
    }

    override fun shouldPause(): Boolean = false

    override fun close() {
        client?.setScreen(if (openedFromDungeon) ThrowerListDungeonScreen(ThrowerListListScreen(returnTab, openFromCenter = false, startFromDungeonWidth = true)) else ThrowerListListScreen(returnTab, openFromCenter = false, startFromDungeonWidth = true))
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        sidebarAnimationProgress = animateProgress(sidebarAnimationProgress, if (sidebarExpanded) 1f else 0f)
        frameWidthProgress = animateProgress(frameWidthProgress, 1f)
        layoutWidgets()

        val theme = ThemeManager.current()
        val frame = frameRect()
        val body = bodyRect()
        ThemeRenderer.drawPanel(context, frame.left, frame.top, frame.right, frame.bottom, layout.titleBar.height, theme)
        drawSidebar(context, mouseX.toDouble(), mouseY.toDouble(), theme)
        context.fill(body.left, body.top, body.right, body.bottom, theme.frameBackground)
        drawInlineTitle(context, frame, theme)
        drawDonationButton(context, mouseX.toDouble(), mouseY.toDouble(), theme)
        drawDiscordButton(context, mouseX.toDouble(), mouseY.toDouble(), theme)
        drawCreditsButton(context, mouseX.toDouble(), mouseY.toDouble(), theme)
        drawSettingsIconButton(context, mouseX.toDouble(), mouseY.toDouble(), theme)

        val donationRect = donationButtonRect()
        val discordRect = discordButtonRect()
        val creditsRect = creditsButtonRect()
        val settingsRect = settingsButtonRect()

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

        drawText(context, "Misc Modules", body.centerX() - textRenderer.getWidth("Misc Modules") / 2, body.top + 10, theme.lightTextAccent)

        val ignoreHeader = moduleHeaderRect()
        drawDropdown(context, ignoreHeader, "Ignorelist", ConfigManager.isMiscIgnoreListEnabled(), ignoreListExpanded, mouseX.toDouble(), mouseY.toDouble(), theme)
        drawIgnoreListContent(context, mouseX.toDouble(), mouseY.toDouble(), theme)

        val swingHeader = swingSpeedHeaderRect()
        drawDropdown(context, swingHeader, "Swing Speed", ConfigManager.isSwingSpeedEnabled(), swingSpeedExpanded, mouseX.toDouble(), mouseY.toDouble(), theme)
        drawSwingSpeedContent(context, mouseX.toDouble(), mouseY.toDouble(), theme)

        super.render(context, mouseX, mouseY, deltaTicks)
        ThemeRenderer.drawButton(context, addButton, mouseX.toDouble(), mouseY.toDouble(), leftMouseDown, theme)
        ThemeRenderer.drawButton(context, backButton, mouseX.toDouble(), mouseY.toDouble(), leftMouseDown, theme)

        statusMessage?.let {
            ThemeRenderer.drawCenteredTextWithShadow(context, textRenderer, it, body.centerX(), body.bottom - 14, statusColor)
        }
    }

    override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
        if (click.button() == 0) {
            leftMouseDown = true
        }

        val mouseX = click.x()
        val mouseY = click.y()
        val button = click.button()

        if (moduleHeaderRect().contains(mouseX, mouseY)) {
            when (button) {
                0 -> {
                    val enabled = ConfigManager.setMiscIgnoreListEnabled(!ConfigManager.isMiscIgnoreListEnabled())
                    showStatus("Misc ignore list ${if (enabled) "enabled" else "disabled"}.", 0xFF88FF88.toInt())
                    return true
                }

                1 -> {
                    ignoreListExpanded = !ignoreListExpanded
                    if (ignoreListExpanded) swingSpeedExpanded = false
                    syncVisibility()
                    showStatus(if (ignoreListExpanded) "Expanded misc ignore list settings." else "Collapsed misc ignore list settings.", ThemeManager.current().subtleText)
                    return true
                }
            }
        }

        if (swingSpeedHeaderRect().contains(mouseX, mouseY)) {
            when (button) {
                0 -> {
                    val enabled = ConfigManager.setSwingSpeedEnabled(!ConfigManager.isSwingSpeedEnabled())
                    showStatus("Swing speed ${if (enabled) "enabled" else "disabled"}.", 0xFF88FF88.toInt())
                    return true
                }
                1 -> {
                    swingSpeedExpanded = !swingSpeedExpanded
                    if (swingSpeedExpanded) ignoreListExpanded = false
                    syncVisibility()
                    showStatus(if (swingSpeedExpanded) "Expanded swing speed settings." else "Collapsed swing speed settings.", ThemeManager.current().subtleText)
                    return true
                }
            }
        }

        if (swingSpeedExpanded && swingSpeedSliderRect().contains(mouseX, mouseY) && button == 0) {
            updateSwingSpeedFromMouse(mouseX)
            return true
        }

        if (button != 0) {
            return super.mouseClicked(click, doubled)
        }

        if (toggleRect().contains(mouseX, mouseY)) {
            sidebarExpanded = !sidebarExpanded
            return true
        }
        if (donationButtonRect().contains(mouseX, mouseY)) {
            Util.getOperatingSystem().open(URI.create("https://ko-fi.com/vyriv"))
            return true
        }

        if (discordButtonRect().contains(mouseX, mouseY)) {
            Util.getOperatingSystem().open(URI.create("https://discord.gg/9DX2dgyUkD"))
            return true
        }

        if (creditsButtonRect().contains(mouseX, mouseY)) {
            client?.setScreen(ThrowerListCreditsScreen(ThrowerListListScreen(returnTab, openFromCenter = false, startFromDungeonWidth = true)))
            return true
        }

        if (settingsButtonRect().contains(mouseX, mouseY)) {
            client?.setScreen(ThrowerListSettingsScreen(ThrowerListListScreen(returnTab, openFromCenter = false, startFromDungeonWidth = true)))
            return true
        }
        if (sidebarVisibleRect().contains(mouseX, mouseY) && sidebarAnimationProgress >= 0.65f) {
            when {
                listsButtonRect().contains(mouseX, mouseY) -> {
                    client?.setScreen(ThrowerListListScreen(returnTab, openFromCenter = false, startFromDungeonWidth = true))
                    return true
                }
                dungeonButtonRect().contains(mouseX, mouseY) -> {
                    client?.setScreen(ThrowerListDungeonScreen(ThrowerListListScreen(returnTab, openFromCenter = false, startFromDungeonWidth = true)))
                    return true
                }
                miscButtonRect().contains(mouseX, mouseY) -> return true
            }
            return true
        }
        ignoredUsernames().drop(listOffset).take(maxVisibleRows()).indices.firstOrNull { removeButtonRect(it).contains(mouseX, mouseY) }?.let { index ->
            val username = ignoredUsernames().drop(listOffset)[index]
            ConfigManager.removeMiscIgnoredUsername(username)
            val maxOffset = (ignoredUsernames().size - maxVisibleRows()).coerceAtLeast(0)
            listOffset = listOffset.coerceAtMost(maxOffset)
            syncVisibility()
            showStatus("Removed $username from the ignore list.", 0xFFFFDD77.toInt())
            return true
        }
        return super.mouseClicked(click, doubled)
    }

    override fun mouseReleased(click: Click): Boolean {
        if (click.button() == 0) {
            leftMouseDown = false
        }
        return super.mouseReleased(click)
    }

    private fun addIgnoredUsername() {
        val username = ignoreInputField?.text?.trim().orEmpty()
        when {
            username.isEmpty() -> showStatus("Enter a username to ignore.", 0xFFFF7777.toInt())
            !usernameRegex.matches(username) -> showStatus("Enter a valid Minecraft username.", 0xFFFF7777.toInt())
            !ConfigManager.addMiscIgnoredUsername(username) -> showStatus("$username is already in the misc ignore list.", 0xFFFFDD77.toInt())
            else -> {
                ignoreInputField?.text = ""
                syncVisibility()
                showStatus("Added $username to the misc ignore list.", 0xFF88FF88.toInt())
            }
        }
    }

    private fun ignoredUsernames(): List<String> = ConfigManager.miscIgnoredUsernames()
    private fun maxVisibleRows(): Int = 8

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (!ignoreListExpanded || !moduleContentRect().contains(mouseX, mouseY)) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
        }

        val names = ignoredUsernames()
        val maxOffset = (names.size - maxVisibleRows()).coerceAtLeast(0)
        if (maxOffset <= 0) {
            listOffset = 0
            return true
        }

        val delta = when {
            verticalAmount > 0 -> -1
            verticalAmount < 0 -> 1
            else -> 0
        }
        listOffset = (listOffset + delta).coerceIn(0, maxOffset)
        return true
    }

    override fun mouseDragged(click: Click, offsetX: Double, offsetY: Double): Boolean {
        if (swingSpeedExpanded && swingSpeedSliderRect().contains(click.x(), click.y()) && click.button() == 0) {
            updateSwingSpeedFromMouse(click.x())
            return true
        }
        return super.mouseDragged(click, offsetX, offsetY)
    }

    private fun updateSwingSpeedFromMouse(mouseX: Double) {
        val slider = swingSpeedSliderRect()
        val percent = ((mouseX - slider.left) / slider.width()).coerceIn(0.0, 1.0)
        // Map 0-100% to 0.1-1.0 swing speed
        val value = 0.1f + (percent.toFloat() * 0.9f)
        ConfigManager.setSwingSpeedValue(value)
    }

    private fun drawIgnoreListContent(context: DrawContext, mouseX: Double, mouseY: Double, theme: ThemePalette) {
        if (!ignoreListExpanded) return
        val panel = moduleContentRect()
        context.fill(panel.left, panel.top, panel.right, panel.bottom, theme.withAlpha(theme.secondaryPanel, 138))
        ThemeRenderer.drawOutline(context, panel.left, panel.top, panel.width(), panel.height(), theme.idleBorder)
        drawText(context, "Add a user to ignore them", panel.left + 16, panel.top + 12, theme.mutedText)
        ThemeRenderer.drawTextField(context, ignoreInputField, theme)
        drawInputPlaceholder(context, theme)
        drawIgnoreRows(context, mouseX, mouseY, theme)
    }

    private fun drawSwingSpeedContent(context: DrawContext, mouseX: Double, mouseY: Double, theme: ThemePalette) {
        if (!swingSpeedExpanded) return
        val header = swingSpeedHeaderRect()
        val panelTop = header.bottom + 10
        val panelBottom = panelTop + 74
        val panel = Rect(header.left, panelTop, header.right, panelBottom)
        context.fill(panel.left, panel.top, panel.right, panel.bottom, theme.withAlpha(theme.secondaryPanel, 138))
        ThemeRenderer.drawOutline(context, panel.left, panel.top, panel.width(), panel.height(), theme.idleBorder)

        drawText(context, "Configure how slow your swing animation is.", panel.left + 16, panel.top + 12, theme.mutedText)

        val slider = swingSpeedSliderRect()
        val value = ConfigManager.getSwingSpeedValue()
        val percent = (value - 0.1f) / 0.9f

        // Slider background
        context.fill(slider.left, slider.top + 8, slider.right, slider.bottom - 8, theme.panelBackground)
        ThemeRenderer.drawOutline(context, slider.left, slider.top + 8, slider.width(), slider.height() - 16, theme.idleBorder)

        // Slider fill
        val fillRight = slider.left + (slider.width() * percent).toInt()
        context.fill(slider.left, slider.top + 8, fillRight, slider.bottom - 8, theme.primaryAccent)

        // Slider handle
        val handleX = fillRight - 2
        context.fill(handleX, slider.top + 4, handleX + 4, slider.bottom - 4, theme.lightTextAccent)
        ThemeRenderer.drawOutline(context, handleX, slider.top + 4, 4, slider.height() - 8, theme.idleBorder)

        val valueText = "${(value * 100).roundToInt()}%"
        drawText(context, "Speed: $valueText", slider.right + 10, slider.top + 6, theme.lightTextAccent)
    }

    private fun drawDropdown(context: DrawContext, rect: Rect, label: String, enabled: Boolean, expanded: Boolean, mouseX: Double, mouseY: Double, theme: ThemePalette) {
        val hovered = rect.contains(mouseX, mouseY)
        val fill = when {
            hovered && leftMouseDown -> theme.darkAccent
            hovered -> theme.secondaryPanel
            enabled -> theme.withAlpha(theme.hoverAccent, 0x70)
            else -> theme.panelBackground
        }
        context.fill(rect.left, rect.top, rect.right, rect.bottom, fill)
        ThemeRenderer.drawOutline(context, rect.left, rect.top, rect.width(), rect.height(), if (hovered || enabled) theme.primaryAccent else theme.idleBorder)
        drawText(context, label, rect.left + 10, rect.top + 8, if (enabled) theme.hoverAccent else theme.lightTextAccent)
        val stateText = if (enabled) "ON" else "OFF"
        val stateWidth = textRenderer.getWidth(stateText)
        drawText(context, stateText, rect.right - 24 - stateWidth, rect.top + 8, if (enabled) theme.hoverAccent else theme.subtleText)
        drawText(context, if (expanded) "v" else ">", rect.right - 14, rect.top + 8, theme.lightTextAccent)
    }

    private fun drawInputPlaceholder(context: DrawContext, theme: ThemePalette) {
        val field = ignoreInputField ?: return
        if (field.text.isBlank() && !field.isFocused) {
            drawText(context, "IGN", ThemeRenderer.textFieldPlaceholderX(field), ThemeRenderer.textFieldPlaceholderY(field), theme.mutedText)
        }
    }

    private fun drawIgnoreRows(context: DrawContext, mouseX: Double, mouseY: Double, theme: ThemePalette) {
        val names = ignoredUsernames()
        val visibleNames = names.drop(listOffset).take(maxVisibleRows())
        if (visibleNames.isEmpty()) {
            drawText(context, "No misc ignored users yet.", moduleContentRect().left + 16, moduleContentRect().top + 74, theme.mutedText)
            return
        }
        visibleNames.forEachIndexed { index, username ->
            val row = rowRect(index)
            val remove = removeButtonRect(index)
            val hoveredRemove = remove.contains(mouseX, mouseY)
            context.fill(row.left, row.top, row.right, row.bottom, theme.secondaryPanel)
            ThemeRenderer.drawOutline(context, row.left, row.top, row.width(), row.height(), theme.idleBorder)
            drawText(context, username, row.left + 10, row.top + 7, theme.lightTextAccent)
            context.fill(remove.left, remove.top, remove.right, remove.bottom, if (hoveredRemove) 0xFF7A2E2E.toInt() else 0xFF5A2323.toInt())
            ThemeRenderer.drawOutline(context, remove.left, remove.top, remove.width(), remove.height(), if (hoveredRemove) theme.primaryAccent else theme.idleBorder)
            ThemeRenderer.drawCenteredTextWithShadow(context, textRenderer, "Remove", remove.centerX(), remove.top + 5, 0xFFFFCACA.toInt())
        }
        if (names.size > visibleNames.size) {
            drawText(context, "Showing ${listOffset + 1}-${listOffset + visibleNames.size} of ${names.size}", moduleContentRect().left + 16, rowRect(visibleNames.lastIndex).bottom + 8, theme.mutedText)
        }
    }

    private fun syncVisibility() {
        val visible = ignoreListExpanded
        ignoreInputField?.visible = visible
        addButton?.visible = visible
        addButton?.active = visible
        val names = ignoredUsernames()
        val maxOffset = (names.size - maxVisibleRows()).coerceAtLeast(0)
        listOffset = listOffset.coerceAtMost(maxOffset)
    }

    private fun layoutWidgets() {
        val panel = moduleContentRect()
        val horizontalInset = 16
        val controlGap = 8
        val primaryButtonWidth = 108
        val controlHeight = 20
        val controlRight = panel.right - horizontalInset
        val addButtonLeft = controlRight - primaryButtonWidth
        val fieldOuterLeft = panel.left + horizontalInset
        val fieldOuterWidth = (addButtonLeft - controlGap - fieldOuterLeft).coerceAtLeast(120)

        ignoreInputField?.apply {
            x = fieldOuterLeft
            y = panel.top + 34
            setWidth(fieldOuterWidth)
            ThemeRenderer.applyTextFieldInset(this)
        }
        addButton?.apply {
            x = addButtonLeft
            y = panel.top + 34
            setDimensions(primaryButtonWidth, controlHeight)
        }
        backButton?.apply {
            x = panel.right - primaryButtonWidth
            y = panel.bottom + 14
            setDimensions(primaryButtonWidth, controlHeight)
        }
    }

    private fun frameRect(): Rect {
        val frame = layout.frame
        val targetWidth = lerpInt(frame.maxWidth, frame.dungeonWidth, frameWidthProgress)
        val frameWidth = minOf(targetWidth, width - frame.horizontalMargin * 2)
        return Rect((width - frameWidth) / 2, frame.top, (width + frameWidth) / 2, height - frame.bottomMargin)
    }

    private fun contentRect(): Rect {
        val frame = frameRect()
        val inset = layout.frame.contentInset
        val sidebar = layout.sidebar
        return Rect(frame.left + inset + sidebar.handleWidth + sidebar.contentGap, frame.top, frame.right - inset, frame.bottom)
    }

    private fun bodyRect(): Rect {
        val content = contentRect()
        return Rect(content.left, frameRect().top + layout.titleBar.height + 10, content.right, frameRect().bottom - 10)
    }

    private fun moduleHeaderRect(): Rect {
        val body = bodyRect()
        return Rect(body.left + 12, body.top + 36, body.right - 12, body.top + 60)
    }

    private fun swingSpeedHeaderRect(): Rect {
        val ignoreHeader = moduleHeaderRect()
        val offset = if (ignoreListExpanded) moduleContentRect().height() + 8 else 32
        return Rect(ignoreHeader.left, ignoreHeader.top + offset, ignoreHeader.right, ignoreHeader.bottom + offset)
    }

    private fun swingSpeedSliderRect(): Rect {
        return if (swingSpeedExpanded) {
            val header = swingSpeedHeaderRect()
            val panelTop = header.bottom + 10
            Rect(header.left + 16, panelTop + 34, header.right - 80, panelTop + 54)
        } else {
            val panel = moduleContentRect()
            Rect(panel.left + 16, panel.top + 34, panel.right - 80, panel.top + 54)
        }
    }

    private fun moduleContentRect(): Rect {
        val header = moduleHeaderRect()
        val body = bodyRect()
        return Rect(header.left, header.bottom + 10, header.right, body.bottom - 52)
    }

    private fun rowRect(index: Int): Rect {
        val panel = moduleContentRect()
        val top = panel.top + 94 + index * 26
        return Rect(panel.left + 16, top, panel.right - 16, top + 22)
    }

    private fun removeButtonRect(index: Int): Rect {
        val row = rowRect(index)
        return Rect(row.right - 86, row.top + 2, row.right - 6, row.bottom - 2)
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

    private fun sidebarRect(): Rect {
        val frame = frameRect()
        val sidebar = layout.sidebar
        return Rect(frame.left + sidebar.frameInset, frame.top + sidebar.verticalInset, frame.left + sidebar.frameInset + sidebar.handleWidth, frame.bottom - sidebar.verticalInset)
    }

    private fun sidebarVisibleRect(): Rect {
        val handle = sidebarRect()
        val extraWidth = (layout.sidebar.flyoutWidth * sidebarAnimationProgress).roundToInt()
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
        return Rect(sidebar.left + toggle.toggleLeftOffset, top, sidebar.left + toggle.toggleLeftOffset + toggle.toggleSize, top + toggle.toggleSize)
    }

    private fun listsButtonRect(): Rect {
        val sidebar = sidebarFlyoutRect()
        val config = layout.sidebar
        val left = sidebar.left + config.innerPadding
        val right = (sidebar.right - config.innerPadding).coerceAtLeast(left + config.buttonMinWidth)
        val top = sidebar.top + config.headerTopOffset + textRenderer.fontHeight + config.headerButtonGap
        return Rect(left, top, right, top + config.buttonHeight)
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

    private fun drawSidebar(context: DrawContext, mouseX: Double, mouseY: Double, theme: ThemePalette) {
        val sidebarBounds = sidebarVisibleRect()
        val flyoutBounds = sidebarFlyoutRect()
        val toggle = toggleRect()
        val listsButton = listsButtonRect()
        val dungeonButton = dungeonButtonRect()
        val miscButton = miscButtonRect()
        val hoveredSidebar = sidebarBounds.contains(mouseX, mouseY)
        val hoveredToggle = toggle.contains(mouseX, mouseY)
        val hoveredLists = sidebarAnimationProgress >= 0.65f && listsButton.contains(mouseX, mouseY)
        val hoveredDungeon = sidebarAnimationProgress >= 0.65f && dungeonButton.contains(mouseX, mouseY)
        val hoveredMisc = sidebarAnimationProgress >= 0.65f && miscButton.contains(mouseX, mouseY)

        drawSidebarToggle(context, toggle, hoveredToggle, theme)
        if (sidebarAnimationProgress <= 0.01f) return
        if (flyoutBounds.width() > 0) {
            context.fill(flyoutBounds.left, flyoutBounds.top, flyoutBounds.right, flyoutBounds.bottom, theme.withAlpha(theme.secondaryPanel, (120 + (sidebarAnimationProgress * 72f).roundToInt()).coerceIn(0, 255)))
            ThemeRenderer.drawOutline(context, flyoutBounds.left, flyoutBounds.top, flyoutBounds.width(), flyoutBounds.height(), if (hoveredSidebar) theme.primaryAccent else theme.idleBorder)
        }
        if (sidebarAnimationProgress < 0.65f || flyoutBounds.width() <= 0) return
        val headerX = flyoutBounds.left + layout.sidebar.innerPadding
        val headerY = flyoutBounds.top + layout.sidebar.headerTopOffset
        drawText(context, "Modules", headerX, headerY, theme.hoverAccent)
        drawSidebarButton(context, listsButton, "Lists", hoveredLists, false, theme)
        drawSidebarButton(context, dungeonButton, "Dungeon", hoveredDungeon, false, theme)
        drawSidebarButton(context, miscButton, "Misc", hoveredMisc, true, theme)
    }

    private fun drawSidebarButton(context: DrawContext, rect: Rect, label: String, hovered: Boolean, selected: Boolean, theme: ThemePalette) {
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
        drawSidebarLine(context, centerX, centerY - 4, 10, 2, color)
        drawSidebarLine(context, centerX, centerY, 10, 2, color)
        drawSidebarLine(context, centerX, centerY + 4, 10, 2, color)
    }

    private fun drawSidebarLine(context: DrawContext, centerX: Int, centerY: Int, width: Int, height: Int, color: Int) {
        val left = centerX - width / 2
        val top = centerY - height / 2
        context.fill(left, top, left + width, top + height, color)
    }

    private fun animateProgress(current: Float, target: Float): Float {
        val difference = target - current
        if (kotlin.math.abs(difference) < 0.01f) return target
        return (current + difference * 0.18f).coerceIn(0f, 1f)
    }

    private fun lerpInt(start: Int, end: Int, progress: Float): Int =
        (start + ((end - start) * progress.coerceIn(0f, 1f))).roundToInt()

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

    private fun drawDonationButton(context: DrawContext, mouseX: Double, mouseY: Double, theme: ThemePalette) {
        val rect = donationButtonRect()
        val hovered = rect.contains(mouseX, mouseY)
        ThemeRenderer.drawDonationButton(context, rect.left, rect.top, rect.width(), hovered, hovered && leftMouseDown, theme)
    }

    private fun drawSettingsIconButton(context: DrawContext, mouseX: Double, mouseY: Double, theme: ThemePalette) {
        val rect = settingsButtonRect()
        val hovered = rect.contains(mouseX, mouseY)
        ThemeRenderer.drawSettingsButton(context, rect.left, rect.top, rect.width(), hovered, hovered && leftMouseDown, theme)
    }

    private fun drawSettingsButton(context: DrawContext, mouseX: Double, mouseY: Double, theme: ThemePalette) {
        val rect = settingsButtonRect()
        val hovered = rect.contains(mouseX, mouseY)
        ThemeRenderer.drawSettingsButton(context, rect.left, rect.top, rect.width(), hovered, hovered && leftMouseDown, theme)
    }

    private fun drawCreditsButton(context: DrawContext, mouseX: Double, mouseY: Double, theme: ThemePalette) {
        val rect = creditsButtonRect()
        val hovered = rect.contains(mouseX, mouseY)
        ThemeRenderer.drawCreditsButton(context, rect.left, rect.top, rect.width(), hovered, hovered && leftMouseDown, theme)
    }

    private fun drawDiscordButton(context: DrawContext, mouseX: Double, mouseY: Double, theme: ThemePalette) {
        val rect = discordButtonRect()
        val hovered = rect.contains(mouseX, mouseY)
        ThemeRenderer.drawDiscordButton(context, rect.left, rect.top, rect.width(), hovered, hovered && leftMouseDown, theme)
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

    private fun showStatus(message: String, color: Int) {
        statusMessage = message
        statusColor = color
    }

    private data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun contains(mouseX: Double, mouseY: Double): Boolean = mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom
        fun centerX(): Int = (left + right) / 2
        fun width(): Int = right - left
        fun height(): Int = bottom - top
    }
}
