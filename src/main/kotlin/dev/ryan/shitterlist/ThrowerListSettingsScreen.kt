package dev.ryan.throwerlist

import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text
import net.minecraft.util.Util
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ThrowerListSettingsScreen(private val parent: ThrowerListListScreen) : Screen(Text.literal("Skylist Settings")) {
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yy")
    private val versionLabel = "v${SkylistPlusRuntimeVersion.featureVersion()}"

    private var autokickButton: ButtonWidget? = null
    private var editAutokickButton: ButtonWidget? = null
    private var notificationsButton: ButtonWidget? = null
    private var assumeLeaderButton: ButtonWidget? = null
    private var themeButton: ButtonWidget? = null
    private var themesFolderButton: ButtonWidget? = null
    private var reloadThemesButton: ButtonWidget? = null
    private var scammerSettingsButton: ButtonWidget? = null
    private var backButton: ButtonWidget? = null
    private var statusMessage: Text? = null
    private var statusColor = 0xFF7FD6FF.toInt()
    private var leftMouseDown = false

    override fun init() {
        super.init()
        val panel = panelRect()
        val rowWidth = 210
        val toggleWidth = (rowWidth * 3) / 4
        val editWidth = rowWidth - toggleWidth
        val rowLeft = panel.centerX() - rowWidth / 2
        autokickButton = toggleButton(rowLeft, panel.top + 38, toggleWidth, "Autokick", ConfigManager.isAutokickEnabled()) {
            val enabled = ConfigManager.setAutokickEnabled(!ConfigManager.isAutokickEnabled())
            updateButtonLabels()
            showStatus("Local and remote autokick ${if (enabled) "enabled" else "disabled"}.", 0xFF88FF88.toInt())
        }
        editAutokickButton = ThemedButtonWidget.builder(Text.literal("Edit")) {
            client?.setScreen(ThrowerListAutokickEditorScreen(this))
        }.dimensions(rowLeft + toggleWidth, panel.top + 38, editWidth, 20).build().also { addDrawableChild(it) }

        notificationsButton = toggleButton(rowLeft, panel.top + 64, rowWidth, "Lobby Notifications", ConfigManager.isLobbyNotificationsEnabled()) {
            val enabled = ConfigManager.setLobbyNotificationsEnabled(!ConfigManager.isLobbyNotificationsEnabled())
            updateButtonLabels()
            showStatus("Lobby notifications ${if (enabled) "enabled" else "disabled"}.", 0xFF88FF88.toInt())
        }

        assumeLeaderButton = toggleButton(rowLeft, panel.top + 90, rowWidth, "Assume Party Leader", ConfigManager.isAssumePartyLeader()) {
            val enabled = ConfigManager.setAssumePartyLeader(!ConfigManager.isAssumePartyLeader())
            updateButtonLabels()
            showStatus("Assume party leader ${if (enabled) "enabled" else "disabled"}.", 0xFF88FF88.toInt())
        }

        val themeRowWidth = rowWidth
        val themeButtonWidth = (themeRowWidth * 4) / 5
        val themesFolderWidth = themeRowWidth - themeButtonWidth
        val themeButtonGap = 6
        val reloadButtonWidth = 20
        themeButton = ThemedButtonWidget.builder(Text.literal("Theme: ${ThemeManager.activeThemeLabel()}")) {
            val label = ThemeManager.cycleTheme()
            updateButtonLabels()
            showStatus("Theme changed to $label.", ThemeManager.current().hoverAccent)
        }.dimensions(rowLeft, panel.top + 116, themeButtonWidth, 20).build().also { addDrawableChild(it) }
        themesFolderButton = ThemedButtonWidget.builder(Text.literal("Open")) {
            Util.getOperatingSystem().open(ThemeManager.themesDirectoryPath().toFile())
        }.dimensions(rowLeft + themeButtonWidth, panel.top + 116, themesFolderWidth, 20).build().also { addDrawableChild(it) }
        reloadThemesButton = ThemedButtonWidget.builder(Text.literal("R")) {
            val label = ThemeManager.reloadThemes()
            updateButtonLabels()
            showStatus("Reloaded themes. Active theme: $label.", ThemeManager.current().hoverAccent)
        }.dimensions(rowLeft + themeButtonWidth + themesFolderWidth + themeButtonGap, panel.top + 116, reloadButtonWidth, 20).build()
            .also { addDrawableChild(it) }

        val bottomGap = 12
        val scammerSettingsWidth = 144
        val backWidth = 84
        val combinedWidth = scammerSettingsWidth + backWidth + bottomGap
        val bottomLeft = panel.centerX() - combinedWidth / 2
        scammerSettingsButton = ThemedButtonWidget.builder(Text.literal("Scammer Settings")) {
            client?.setScreen(ScammerSettingsScreen(this))
        }.dimensions(bottomLeft, panel.bottom - 42, scammerSettingsWidth, 20).build().also { addDrawableChild(it) }

        backButton = ThemedButtonWidget.builder(Text.literal("Back")) {
            close()
        }.dimensions(bottomLeft + scammerSettingsWidth + bottomGap, panel.bottom - 42, backWidth, 20).build().also { addDrawableChild(it) }

        updateButtonLabels()
    }

    override fun shouldPause(): Boolean = false

    override fun close() {
        parent.onSettingsChanged(statusMessage?.string, statusColor)
        client?.setScreen(parent)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        val theme = ThemeManager.current()
        context.fill(0, 0, width, height, theme.overlayBackground)
        val panel = panelRect()
        ThemeRenderer.drawPanel(context, panel.left, panel.top, panel.right, panel.bottom, 22, theme)

        drawCentered(context, title.string, width / 2, panel.top + 8, 0xFFFFFFFF.toInt())
        drawText(
            context,
            versionLabel,
            panel.right - 12 - textRenderer.getWidth(versionLabel),
            panel.top + 8,
            theme.subtleText,
        )
        drawCentered(context, "Remote entries loaded: ${RemoteListManager.listEntries().size}", panel.centerX(), panel.top + 154, theme.lightTextAccent)
        drawCentered(context, "Scammers loaded: ${ScammerListManager.listEntries().size}", panel.centerX(), panel.top + 168, theme.lightTextAccent)
        drawCentered(context, "Last refresh: ${formatLastRefresh()}", panel.centerX(), panel.top + 182, theme.subtleText)

        statusMessage?.let {
            drawCentered(context, it.string, panel.centerX(), panel.bottom - 16, statusColor)
        }

        super.render(context, mouseX, mouseY, deltaTicks)
        ThemeRenderer.drawButton(context, autokickButton, mouseX.toDouble(), mouseY.toDouble(), leftMouseDown, theme)
        ThemeRenderer.drawButton(context, editAutokickButton, mouseX.toDouble(), mouseY.toDouble(), leftMouseDown, theme)
        ThemeRenderer.drawButton(context, notificationsButton, mouseX.toDouble(), mouseY.toDouble(), leftMouseDown, theme)
        ThemeRenderer.drawButton(context, assumeLeaderButton, mouseX.toDouble(), mouseY.toDouble(), leftMouseDown, theme)
        ThemeRenderer.drawButton(context, themeButton, mouseX.toDouble(), mouseY.toDouble(), leftMouseDown, theme)
        ThemeRenderer.drawButton(context, themesFolderButton, mouseX.toDouble(), mouseY.toDouble(), leftMouseDown, theme)
        ThemeRenderer.drawButton(context, reloadThemesButton, mouseX.toDouble(), mouseY.toDouble(), leftMouseDown, theme)
        ThemeRenderer.drawButton(context, scammerSettingsButton, mouseX.toDouble(), mouseY.toDouble(), leftMouseDown, theme)
        ThemeRenderer.drawButton(context, backButton, mouseX.toDouble(), mouseY.toDouble(), leftMouseDown, theme)
    }

    override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
        if (click.button() == 0) {
            leftMouseDown = true
        }
        if (click.button() == 1 && themeButton?.isMouseOver(click.x(), click.y()) == true) {
            val label = ThemeManager.cycleThemeBack()
            updateButtonLabels()
            showStatus("Theme changed to $label.", ThemeManager.current().hoverAccent)
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

    private fun updateButtonLabels() {
        autokickButton?.setMessage(toggleMessage("Autokick", ConfigManager.isAutokickEnabled()))
        notificationsButton?.setMessage(toggleMessage("Lobby Notifications", ConfigManager.isLobbyNotificationsEnabled()))
        assumeLeaderButton?.setMessage(toggleMessage("Assume Party Leader", ConfigManager.isAssumePartyLeader()))
        themeButton?.setMessage(Text.literal("Theme: ${ThemeManager.activeThemeLabel()}"))
    }

    fun onAutokickEditorClosed(message: String?, color: Int) {
        if (!message.isNullOrBlank()) {
            showStatus(message, color)
        }
    }

    private fun toggleButton(x: Int, y: Int, width: Int, label: String, enabled: Boolean, action: () -> Unit): ButtonWidget =
        ThemedButtonWidget.builder(toggleMessage(label, enabled)) { action() }
            .dimensions(x, y, width, 20)
            .build()
            .also { addDrawableChild(it) }

    private fun toggleMessage(label: String, enabled: Boolean): Text = Text.literal("$label: ${if (enabled) "ON" else "OFF"}")

    private fun formatLastRefresh(): String {
        val lastRefresh = listOfNotNull(
            RemoteListManager.lastRefreshCompletedAt(),
            ScammerListManager.lastRefreshCompletedAt(),
        ).maxOrNull() ?: return "Not yet recorded"
        return Instant.ofEpochMilli(lastRefresh)
            .atZone(ZoneId.systemDefault())
            .format(timeFormatter)
    }

    private fun showStatus(message: String, color: Int) {
        statusMessage = Text.literal(message)
        statusColor = color
    }

    private fun panelRect(): PanelRect {
        val layout = UiLayoutManager.settingsPanel()
        val halfWidth = layout.width / 2
        val halfHeight = layout.height / 2
        return PanelRect(width / 2 - halfWidth, height / 2 - halfHeight, width / 2 + halfWidth, height / 2 + halfHeight)
    }

    private fun drawCentered(context: DrawContext, text: String, centerX: Int, y: Int, color: Int) {
        ThemeRenderer.drawCenteredTextWithShadow(context, textRenderer, text, centerX, y, color)
    }

    private fun drawText(context: DrawContext, text: String, x: Int, y: Int, color: Int) {
        ThemeRenderer.drawTextWithShadow(context, textRenderer, text, x, y, color)
    }

    private data class PanelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun centerX(): Int = (left + right) / 2
    }
}
