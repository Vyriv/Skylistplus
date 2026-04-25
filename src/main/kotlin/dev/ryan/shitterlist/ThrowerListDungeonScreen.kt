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

class ThrowerListDungeonScreen(private val parent: ThrowerListListScreen) : Screen(Text.literal("Skylist")) {
    private val versionLabel = "v${SkylistPlusRuntimeVersion.featureVersion()}"
    private val titleVersionScale = 0.75f
    private val titleVersionGap = 4
    private val floors = listOf("F7", "M1", "M2", "M3", "M4", "M5", "M6", "M7")
    private val layout: UiLayoutManager.MainListLayout
        get() = UiLayoutManager.mainList()
    private val checkItems = listOf(
        CheckItem(
            label = "No spirit pet",
            isEnabled = { ConfigManager.isDungeonNoSpiritPetEnabled() },
            setEnabled = { ConfigManager.setDungeonNoSpiritPetEnabled(it) },
            statusLabel = "No spirit pet",
        ),
        CheckItem(
            label = "No prince shard",
            isEnabled = { ConfigManager.isDungeonNoPrinceAttributeShardEnabled() },
            setEnabled = { ConfigManager.setDungeonNoPrinceAttributeShardEnabled(it) },
            statusLabel = "No prince shard",
        ),
        CheckItem(
            label = "Thorns on armour",
            isEnabled = { ConfigManager.isDungeonThornsOnEquippedArmourEnabled() },
            setEnabled = { ConfigManager.setDungeonThornsOnEquippedArmourEnabled(it) },
            statusLabel = "Thorns on armour",
        ),
    )

    private val floorFields = linkedMapOf<String, TextFieldWidget>()
    private val floorPlaceholder = "X:XX or XmXXs"

    private var saveButton: ButtonWidget? = null
    private var clearButton: ButtonWidget? = null

    private var leftMouseDown = false
    private var sidebarExpanded = true
    private var sidebarAnimationProgress = 1f
    private var frameWidthProgress = 0f
    private var moduleExpanded = false
    private var moduleExpansionProgress = 0f
    private var failPbModuleExpanded = false
    private var failPbModuleExpansionProgress = 0f
    private var statusMessage: String? = null
    private var statusColor = 0xFF7FD6FF.toInt()

    private data class ModuleLayout(
        val content: Rect,
        val innerLeft: Int,
        val innerRight: Int,
        val infoY: Int,
        val floorGridTop: Int,
        val leftColumnX: Int,
        val rightColumnX: Int,
        val floorLabelOffset: Int,
        val rowGap: Int,
        val checksHeaderY: Int,
        val kickIfY: Int,
        val checkboxStartY: Int,
        val checkboxRowHeight: Int,
        val checkboxRowGap: Int,
    )

    override fun init() {
        super.init()
        floorFields.clear()
        floors.forEach { floor ->
            val field = TextFieldWidget(textRenderer, 0, 0, 80, 20, Text.literal(floor)).also {
                it.setDrawsBackground(false)
                it.setMaxLength(8)
                it.text = ConfigManager.getDungeonPbThreshold(floor).orEmpty()
                addDrawableChild(it)
            }
            floorFields[floor] = field
        }

        saveButton = ThemedButtonWidget.builder(Text.literal("Save")) {
            saveThresholds()
        }.dimensions(0, 0, 64, 20).build().also { addDrawableChild(it) }

        clearButton = ThemedButtonWidget.builder(Text.literal("Clear")) {
            floorFields.values.forEach { it.text = "" }
            statusMessage = "Cleared dungeon PB thresholds. Press Save to apply."
            statusColor = ThemeManager.current().subtleText
        }.dimensions(0, 0, 64, 20).build().also { addDrawableChild(it) }

        layoutWidgets()
    }

    override fun shouldPause(): Boolean = false

    override fun close() {
        client?.setScreen(parent)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        sidebarAnimationProgress = animateSidebarProgress(sidebarAnimationProgress, if (sidebarExpanded) 1f else 0f)
        frameWidthProgress = animateSidebarProgress(frameWidthProgress, 1f)
        moduleExpansionProgress = animateSidebarProgress(moduleExpansionProgress, if (moduleExpanded) 1f else 0f)
        failPbModuleExpansionProgress = animateSidebarProgress(failPbModuleExpansionProgress, if (failPbModuleExpanded) 1f else 0f)
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

        val headerY = body.top + 10
        drawText(context, "Dungeon Modules", body.centerX() - textRenderer.getWidth("Dungeon Modules") / 2, headerY, theme.lightTextAccent)
        drawFailPbModuleHeader(context, mouseX.toDouble(), mouseY.toDouble(), theme)
        drawFailPbExpandedModule(context, mouseX.toDouble(), mouseY.toDouble(), theme)
        drawModuleHeader(context, mouseX.toDouble(), mouseY.toDouble(), theme)
        drawExpandedModule(context, mouseX.toDouble(), mouseY.toDouble(), theme)

        if (moduleExpansionProgress >= 0.9f) {
            val moduleLayout = moduleLayout()
            floorFields.values.forEach { ThemeRenderer.drawTextField(context, it, theme) }
            super.render(context, mouseX, mouseY, deltaTicks)

            floorFields.forEach { (floor, field) ->
                drawText(context, floor, field.x - moduleLayout.floorLabelOffset + 2, field.y + 2, theme.lightTextAccent)
            }
            drawFieldPlaceholders(context, theme)
            drawText(context, "Checks", moduleLayout.innerLeft, moduleLayout.checksHeaderY, theme.hoverAccent)
            drawText(context, "Kick if:", moduleLayout.innerLeft, moduleLayout.kickIfY, theme.lightTextAccent)
            drawCheckItems(context, mouseX.toDouble(), mouseY.toDouble(), theme)

            listOfNotNull(saveButton, clearButton).forEach {
                ThemeRenderer.drawButton(context, it, mouseX.toDouble(), mouseY.toDouble(), leftMouseDown, theme)
            }
        }

        statusMessage?.let {
            ThemeRenderer.drawCenteredText(context, textRenderer, it, body.centerX(), body.bottom - 14, statusColor)
        }
    }

    override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
        if (click.button() == 0) {
            leftMouseDown = true
        }

        val button = click.button()
        val mouseX = click.x()
        val mouseY = click.y()
        if (moduleHeaderRect().contains(mouseX, mouseY)) {
            when (button) {
                0 -> {
                    val enabled = ConfigManager.setDungeonAutokickEnabled(!ConfigManager.isDungeonAutokickEnabled())
                    statusMessage = "Dungeon autokick ${if (enabled) "enabled" else "disabled"}."
                    statusColor = 0xFF88FF88.toInt()
                    return true
                }

                1 -> {
                    moduleExpanded = !moduleExpanded
                    statusMessage = if (moduleExpanded) {
                        "Expanded dungeon autokick settings."
                    } else {
                        "Collapsed dungeon autokick settings."
                    }
                    statusColor = ThemeManager.current().subtleText
                    return true
                }
            }
        }

        if (failPbHeaderRect().contains(mouseX, mouseY)) {
            when (button) {
                0 -> {
                    val enabled = DungeonPuzzleFailPbStore.setEnabled(!DungeonPuzzleFailPbStore.isEnabled())
                    statusMessage = "Dungeon puzzle fail PBs ${if (enabled) "enabled" else "disabled"}."
                    statusColor = 0xFF88FF88.toInt()
                    return true
                }

                1 -> {
                    failPbModuleExpanded = !failPbModuleExpanded
                    statusMessage = if (failPbModuleExpanded) {
                        "Expanded dungeon puzzle fail PB settings."
                    } else {
                        "Collapsed dungeon puzzle fail PB settings."
                    }
                    statusColor = ThemeManager.current().subtleText
                    return true
                }
            }
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
            client?.setScreen(ThrowerListCreditsScreen(parent))
            return true
        }

        if (settingsButtonRect().contains(mouseX, mouseY)) {
            client?.setScreen(ThrowerListSettingsScreen(parent))
            return true
        }

        if (sidebarVisibleRect().contains(mouseX, mouseY) && sidebarAnimationProgress >= 0.65f) {
            when {
                listsButtonRect().contains(mouseX, mouseY) -> {
                    client?.setScreen(ThrowerListListScreen(parent.currentTabForReturn(), openFromCenter = false, startFromDungeonWidth = true))
                    return true
                }

                dungeonButtonRect().contains(mouseX, mouseY) -> return true
                miscButtonRect().contains(mouseX, mouseY) -> {
                    client?.setScreen(ThrowerListMiscModulesScreen(parent.currentTabForReturn(), openedFromDungeon = true))
                    return true
                }
            }
            return true
        }

        if (moduleExpansionProgress >= 0.9f) {
            checkItems.indices.firstOrNull { checkRowRect(it).contains(mouseX, mouseY) }?.let { index ->
                toggleCheck(index)
                return true
            }
        }

        if (failPbModuleExpansionProgress >= 0.9f && failPbAnnounceRowRect().contains(mouseX, mouseY)) {
            val enabled = DungeonPuzzleFailPbStore.setAnnounceInChatEnabled(!DungeonPuzzleFailPbStore.isAnnounceInChatEnabled())
            statusMessage = "Dungeon puzzle fail PB announce in chat ${if (enabled) "enabled" else "disabled"}."
            statusColor = 0xFF88FF88.toInt()
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

    private fun saveThresholds() {
        for ((floor, field) in floorFields) {
            val raw = field.text.trim()
            if (raw.isBlank()) {
                continue
            }

            val normalized = DungeonAutokickService.normalizeConfiguredTime(raw)
            if (normalized == null) {
                statusMessage = "$floor time must be m:ss or xmxxs."
                statusColor = 0xFFFF7777.toInt()
                return
            }
        }

        floorFields.forEach { (floor, field) ->
            ConfigManager.setDungeonPbThreshold(floor, DungeonAutokickService.normalizeConfiguredTime(field.text))
            field.text = ConfigManager.getDungeonPbThreshold(floor).orEmpty()
        }
        statusMessage = "Dungeon autokick settings saved."
        statusColor = 0xFF88FF88.toInt()
    }

    private fun toggleCheck(index: Int) {
        val item = checkItems[index]
        val enabled = item.setEnabled(!item.isEnabled())
        statusMessage = "${item.statusLabel} check ${if (enabled) "enabled" else "disabled"}."
        statusColor = 0xFF88FF88.toInt()
    }

    private fun drawModuleHeader(context: DrawContext, mouseX: Double, mouseY: Double, theme: ThemePalette) {
        val rect = moduleHeaderRect()
        val hovered = rect.contains(mouseX, mouseY)
        val enabled = ConfigManager.isDungeonAutokickEnabled()
        val fill = when {
            hovered && leftMouseDown -> theme.darkAccent
            hovered -> theme.secondaryPanel
            enabled -> theme.withAlpha(theme.hoverAccent, 0x70)
            else -> theme.panelBackground
        }
        val border = if (hovered || enabled) theme.primaryAccent else theme.idleBorder
        val textColor = if (enabled) theme.hoverAccent else theme.lightTextAccent
        context.fill(rect.left, rect.top, rect.right, rect.bottom, fill)
        ThemeRenderer.drawOutline(context, rect.left, rect.top, rect.width(), rect.height(), border)
        drawText(context, "Dungeon Autokick", rect.left + 10, rect.top + 8, textColor)
        val stateText = if (enabled) "ON" else "OFF"
        val stateWidth = textRenderer.getWidth(stateText)
        drawText(context, stateText, rect.right - 24 - stateWidth, rect.top + 8, if (enabled) theme.hoverAccent else theme.subtleText)
        drawText(context, if (moduleExpanded) "v" else ">", rect.right - 14, rect.top + 8, theme.lightTextAccent)
    }

    private fun drawFailPbModuleHeader(context: DrawContext, mouseX: Double, mouseY: Double, theme: ThemePalette) {
        val rect = failPbHeaderRect()
        val hovered = rect.contains(mouseX, mouseY)
        val enabled = DungeonPuzzleFailPbStore.isEnabled()
        val fill = when {
            hovered && leftMouseDown -> theme.darkAccent
            hovered -> theme.secondaryPanel
            enabled -> theme.withAlpha(theme.hoverAccent, 0x70)
            else -> theme.panelBackground
        }
        val border = if (hovered || enabled) theme.primaryAccent else theme.idleBorder
        val textColor = if (enabled) theme.hoverAccent else theme.lightTextAccent
        context.fill(rect.left, rect.top, rect.right, rect.bottom, fill)
        ThemeRenderer.drawOutline(context, rect.left, rect.top, rect.width(), rect.height(), border)
        drawText(context, "Dungeon Puzzle Fail PBs", rect.left + 10, rect.top + 8, textColor)
        val stateText = if (enabled) "ON" else "OFF"
        val stateWidth = textRenderer.getWidth(stateText)
        drawText(context, stateText, rect.right - 24 - stateWidth, rect.top + 8, if (enabled) theme.hoverAccent else theme.subtleText)
        drawText(context, if (failPbModuleExpanded) "v" else ">", rect.right - 14, rect.top + 8, theme.lightTextAccent)
    }

    private fun drawExpandedModule(context: DrawContext, mouseX: Double, mouseY: Double, theme: ThemePalette) {
        if (moduleExpansionProgress <= 0.01f) {
            updateWidgetVisibility()
            return
        }

        val panel = moduleContentRect()
        val fullTop = panel.top
        val fullBottom = panel.bottom
        val animatedBottom = lerpInt(fullTop, fullBottom, moduleExpansionProgress)
        val animatedTop = fullTop - ((1f - moduleExpansionProgress) * 12f).roundToInt()
        context.fill(
            panel.left,
            animatedTop,
            panel.right,
            animatedBottom,
            theme.withAlpha(theme.secondaryPanel, (96 + moduleExpansionProgress * 72f).roundToInt().coerceIn(0, 255)),
        )
        ThemeRenderer.drawOutline(context, panel.left, animatedTop, panel.width(), (animatedBottom - animatedTop).coerceAtLeast(1), theme.idleBorder)

        if (moduleExpansionProgress < 0.35f) {
            updateWidgetVisibility()
            return
        }

        val moduleLayout = moduleLayout()
        val contentTop = moduleLayout.infoY - ((1f - moduleExpansionProgress) * 10f).roundToInt()
        val detectedFloor = DungeonFloorDetector.detectCurrentFloor(client ?: return).let { it ?: "None detected" }
        val disabledHint = "Blank = disabled"
        drawText(context, disabledHint, panel.centerX() - textRenderer.getWidth(disabledHint) / 2, contentTop, theme.subtleText)
        drawText(context, "Current floor: $detectedFloor", moduleLayout.innerLeft, contentTop + 16, theme.hoverAccent)
        updateWidgetVisibility()
    }

    private fun drawFailPbExpandedModule(context: DrawContext, mouseX: Double, mouseY: Double, theme: ThemePalette) {
        if (failPbModuleExpansionProgress <= 0.01f) {
            return
        }

        val panel = failPbContentRect()
        val fullTop = panel.top
        val fullBottom = panel.bottom
        val animatedBottom = lerpInt(fullTop, fullBottom, failPbModuleExpansionProgress)
        val animatedTop = fullTop - ((1f - failPbModuleExpansionProgress) * 12f).roundToInt()
        context.fill(
            panel.left,
            animatedTop,
            panel.right,
            animatedBottom,
            theme.withAlpha(theme.secondaryPanel, (96 + failPbModuleExpansionProgress * 72f).roundToInt().coerceIn(0, 255)),
        )
        ThemeRenderer.drawOutline(context, panel.left, animatedTop, panel.width(), (animatedBottom - animatedTop).coerceAtLeast(1), theme.idleBorder)

        if (failPbModuleExpansionProgress < 0.35f) {
            return
        }

        drawText(context, "Tracks your fastest fail time for supported dungeon puzzles.", panel.left + 14, panel.top + 12, theme.subtleText)
        drawFailPbToggleRow(context, mouseX, mouseY, theme)
    }

    private fun updateWidgetVisibility() {
        val visible = moduleExpansionProgress >= 0.9f
        floorFields.values.forEach {
            it.visible = visible
            it.setFocusUnlocked(visible)
        }
        listOfNotNull(saveButton, clearButton).forEach {
            it.visible = visible
            it.active = visible
        }
    }

    private fun moduleHeaderRect(): Rect {
        val failHeader = failPbHeaderRect()
        val gap = 14
        val failContentHeight = failPbExpandedHeight()
        val body = bodyRect()
        val top = failHeader.bottom + gap + lerpInt(0, failContentHeight, failPbModuleExpansionProgress)
        return Rect(body.left + 12, top, body.right - 12, top + 24)
    }

    private fun expandedContentTop(): Int = moduleHeaderRect().bottom + 20

    private fun moduleContentRect(): Rect {
        val header = moduleHeaderRect()
        val body = bodyRect()
        return Rect(header.left, header.bottom + 10, header.right, body.bottom - 52)
    }

    private fun failPbHeaderRect(): Rect {
        val body = bodyRect()
        return Rect(body.left + 12, body.top + 36, body.right - 12, body.top + 60)
    }

    private fun failPbContentRect(): Rect {
        val header = failPbHeaderRect()
        return Rect(header.left, header.bottom + 10, header.right, header.bottom + 10 + failPbExpandedHeight())
    }

    private fun failPbExpandedHeight(): Int = 58

    private fun layoutWidgets() {
        val moduleLayout = moduleLayout()
        val renderLayout = UiLayoutManager.rendering()
        val fieldOuterWidth = ((moduleLayout.innerRight - moduleLayout.innerLeft - 56) / 2).coerceAtLeast(112)

        floors.forEachIndexed { index, floor ->
            val row = index % 4
            val column = index / 4
            val outerLeft = if (column == 0) moduleLayout.leftColumnX else moduleLayout.rightColumnX
            val outerTop = moduleLayout.floorGridTop + row * moduleLayout.rowGap
            floorFields[floor]?.let { field ->
                field.x = outerLeft + renderLayout.textFieldInsetX + moduleLayout.floorLabelOffset
                field.y = outerTop + renderLayout.textFieldInsetY
                field.setWidth((fieldOuterWidth - renderLayout.textFieldInsetX * 2).coerceAtLeast(renderLayout.textFieldMinWidth))
            }
        }

        saveButton?.apply {
            x = moduleLayout.content.right - 134
            y = moduleLayout.content.bottom + 14
            setDimensions(64, 20)
        }
        clearButton?.apply {
            x = moduleLayout.content.right - 64
            y = moduleLayout.content.bottom + 14
            setDimensions(64, 20)
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

        if (sidebarAnimationProgress < 0.65f || flyoutBounds.width() <= 0) {
            return
        }

        val headerX = flyoutBounds.left + layout.sidebar.innerPadding
        val headerY = flyoutBounds.top + layout.sidebar.headerTopOffset
        drawText(context, "Modules", headerX, headerY, theme.hoverAccent)
        drawSidebarButton(context, listsButton, "Lists", hoveredLists, false, theme)
        drawSidebarButton(context, dungeonButton, "Dungeon", hoveredDungeon, true, theme)
        drawSidebarButton(context, miscButton, "Misc", hoveredMisc, false, theme)
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

    private fun drawCheckItems(context: DrawContext, mouseX: Double, mouseY: Double, theme: ThemePalette) {
        val checkboxSize = 12
        checkItems.forEachIndexed { index, item ->
            val row = checkRowRect(index)
            val hovered = row.contains(mouseX, mouseY)
            val checkbox = checkBoxRect(index)
            val fill = when {
                hovered && leftMouseDown -> theme.darkAccent
                hovered -> theme.secondaryPanel
                else -> theme.panelBackground
            }
            context.fill(row.left, row.top, row.right, row.bottom, fill)
            ThemeRenderer.drawOutline(context, row.left, row.top, row.width(), row.height(), if (hovered) theme.primaryAccent else theme.idleBorder)

            context.fill(checkbox.left, checkbox.top, checkbox.right, checkbox.bottom, theme.fieldBackground)
            ThemeRenderer.drawOutline(context, checkbox.left, checkbox.top, checkbox.width(), checkbox.height(), if (hovered) theme.primaryAccent else theme.idleBorder)

            if (item.isEnabled()) {
                context.fill(checkbox.left + 3, checkbox.top + 3, checkbox.right - 3, checkbox.bottom - 3, theme.hoverAccent)
            }

            val textX = checkbox.right + 8
            val lines = wrapText(item.label, checkTextMaxWidth())
            val totalHeight = lines.size * 10
            val startY = row.top + ((row.height() - totalHeight) / 2)
            lines.forEachIndexed { lineIndex, line ->
                drawText(context, line, textX, startY + lineIndex * 10, theme.lightTextAccent)
            }
        }
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

    private fun animateSidebarProgress(current: Float, target: Float): Float {
        val difference = target - current
        if (kotlin.math.abs(difference) < 0.01f) {
            return target
        }
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

    private fun drawText(context: DrawContext, text: String, x: Int, y: Int, color: Int) {
        ThemeRenderer.drawText(context, textRenderer, text, x, y, color)
    }

    private fun wrapText(text: String, maxWidth: Int): List<String> {
        val words = text.split(' ')
        if (words.isEmpty()) {
            return listOf(text)
        }

        val lines = mutableListOf<String>()
        var current = StringBuilder()
        words.forEach { word ->
            val candidate = if (current.isEmpty()) word else "${current} $word"
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

    private fun checkTextMaxWidth(): Int {
        val quarterScreen = width / 4
        val halfScreen = width / 2
        val bodyMax = moduleContentRect().width() - 52
        return when {
            quarterScreen >= 160 -> minOf(quarterScreen, bodyMax)
            else -> minOf(halfScreen, bodyMax)
        }.coerceAtLeast(120)
    }

    private fun checkRowRect(index: Int): Rect {
        val moduleLayout = moduleLayout()
        val left = moduleLayout.innerLeft
        val top = moduleLayout.checkboxStartY + index * moduleLayout.checkboxRowGap
        val textLines = wrapText(checkItems[index].label, checkTextMaxWidth()).size
        val height = maxOf(moduleLayout.checkboxRowHeight, textLines * 10 + 8)
        val right = (left + 28 + checkTextMaxWidth()).coerceAtMost(moduleLayout.innerRight)
        return Rect(left, top, right, top + height)
    }

    private fun drawFieldPlaceholders(context: DrawContext, theme: ThemePalette) {
        floorFields.values.forEach { field ->
            if (field.text.isBlank() && !field.isFocused) {
                drawText(
                    context,
                    floorPlaceholder,
                    ThemeRenderer.textFieldPlaceholderX(field),
                    ThemeRenderer.textFieldPlaceholderY(field),
                    theme.mutedText,
                )
            }
        }
    }

    private fun moduleLayout(): ModuleLayout {
        val content = moduleContentRect()
        val innerLeft = content.left + 16
        val innerRight = content.right - 16
        val infoY = content.top + 12
        val floorGridTop = infoY + 36
        val availableWidth = (innerRight - innerLeft).coerceAtLeast(220)
        val columnWidth = (availableWidth - 28) / 2
        val leftColumnX = innerLeft + 8
        val rightColumnX = innerLeft + columnWidth + 20
        return ModuleLayout(
            content = content,
            innerLeft = innerLeft,
            innerRight = innerRight,
            infoY = infoY,
            floorGridTop = floorGridTop,
            leftColumnX = leftColumnX,
            rightColumnX = rightColumnX,
            floorLabelOffset = 28,
            rowGap = 24,
            checksHeaderY = floorGridTop + 4 * 24 + 18,
            kickIfY = floorGridTop + 4 * 24 + 32,
            checkboxStartY = floorGridTop + 4 * 24 + 46,
            checkboxRowHeight = 18,
            checkboxRowGap = 22,
        )
    }

    private fun drawFailPbToggleRow(context: DrawContext, mouseX: Double, mouseY: Double, theme: ThemePalette) {
        val row = failPbAnnounceRowRect()
        val hovered = row.contains(mouseX, mouseY)
        val checkbox = failPbAnnounceBoxRect()
        val fill = when {
            hovered && leftMouseDown -> theme.darkAccent
            hovered -> theme.secondaryPanel
            else -> theme.panelBackground
        }
        context.fill(row.left, row.top, row.right, row.bottom, fill)
        ThemeRenderer.drawOutline(context, row.left, row.top, row.width(), row.height(), if (hovered) theme.primaryAccent else theme.idleBorder)
        context.fill(checkbox.left, checkbox.top, checkbox.right, checkbox.bottom, theme.fieldBackground)
        ThemeRenderer.drawOutline(context, checkbox.left, checkbox.top, checkbox.width(), checkbox.height(), if (hovered) theme.primaryAccent else theme.idleBorder)
        if (DungeonPuzzleFailPbStore.isAnnounceInChatEnabled()) {
            context.fill(checkbox.left + 3, checkbox.top + 3, checkbox.right - 3, checkbox.bottom - 3, theme.hoverAccent)
        }
        drawText(context, "Announce in chat (/pc)", checkbox.right + 8, row.top + 5, theme.lightTextAccent)
    }

    private fun failPbAnnounceRowRect(): Rect {
        val panel = failPbContentRect()
        return Rect(panel.left + 14, panel.top + 28, panel.right - 14, panel.top + 28 + 22)
    }

    private fun failPbAnnounceBoxRect(): Rect {
        val row = failPbAnnounceRowRect()
        val size = 12
        val top = row.top + (row.height() - size) / 2
        return Rect(row.left + 8, top, row.left + 8 + size, top + size)
    }

    private fun checkBoxRect(index: Int): Rect {
        val row = checkRowRect(index)
        val size = 12
        val top = row.top + (row.height() - size) / 2
        return Rect(row.left + 8, top, row.left + 8 + size, top + size)
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

    private fun drawSettingsButton(context: DrawContext, mouseX: Double, mouseY: Double, theme: ThemePalette) {
        val rect = settingsButtonRect()
        val hovered = rect.contains(mouseX, mouseY)
        ThemeRenderer.drawSettingsButton(context, rect.left, rect.top, rect.width(), hovered, hovered && leftMouseDown, theme)
    }

    private fun drawScaledText(context: DrawContext, text: String, x: Int, y: Int, color: Int, scale: Float) {
        val matrices = context.matrices
        matrices.pushMatrix()
        matrices.scale(scale, scale)
        ThemeRenderer.drawText(context, textRenderer, text, (x / scale).roundToInt(), (y / scale).roundToInt(), color)
        matrices.popMatrix()
    }

    private data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun contains(mouseX: Double, mouseY: Double): Boolean =
            mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom

        fun centerX(): Int = (left + right) / 2
        fun width(): Int = right - left
        fun height(): Int = bottom - top
    }

    private data class CheckItem(
        val label: String,
        val isEnabled: () -> Boolean,
        val setEnabled: (Boolean) -> Boolean,
        val statusLabel: String,
    )
}
