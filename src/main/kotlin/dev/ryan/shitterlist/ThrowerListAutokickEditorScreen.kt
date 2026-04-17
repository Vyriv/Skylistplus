package dev.ryan.throwerlist

import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text

class ThrowerListAutokickEditorScreen(
    private val parent: ThrowerListSettingsScreen,
) : Screen(Text.literal("Skylist Autokick Editor")) {
    private var localToggleButton: ButtonWidget? = null
    private var remoteToggleButton: ButtonWidget? = null
    private var localTemplateField: TextFieldWidget? = null
    private var remoteTemplateField: TextFieldWidget? = null
    private var saveButton: ButtonWidget? = null
    private var cancelButton: ButtonWidget? = null
    private var statusMessage: Text? = null
    private var statusColor = 0xFFFF7777.toInt()
    private var leftMouseDown = false

    override fun init() {
        super.init()
        val panel = panelRect()
        val fieldWidth = panel.width() - 32
        val toggleWidth = 74
        val toggleX = panel.right - 16 - toggleWidth

        localToggleButton = ThemedButtonWidget.builder(toggleMessage("Local", ConfigManager.isAutokickEnabled(false))) {
            val enabled = ConfigManager.setAutokickEnabled(!ConfigManager.isAutokickEnabled(false), false)
            localToggleButton?.message = toggleMessage("Local", enabled)
            showStatus("Local autokick ${if (enabled) "enabled" else "disabled"}.", 0xFF88FF88.toInt())
        }.dimensions(toggleX, panel.top + 34, toggleWidth, 20).build().also { addDrawableChild(it) }

        localTemplateField = TextFieldWidget(
            textRenderer,
            panel.left + 16,
            panel.top + 58,
            fieldWidth,
            20,
            Text.literal("Local autokick message"),
        ).also {
            it.setMaxLength(256)
            it.setDrawsBackground(false)
            ThemeRenderer.applyTextFieldInset(it)
            it.text = ConfigManager.getAutokickMessageTemplate(false)
            addDrawableChild(it)
            setInitialFocus(it)
        }

        remoteToggleButton = ThemedButtonWidget.builder(toggleMessage("Remote", ConfigManager.isAutokickEnabled(true))) {
            val enabled = ConfigManager.setAutokickEnabled(!ConfigManager.isAutokickEnabled(true), true)
            remoteToggleButton?.message = toggleMessage("Remote", enabled)
            showStatus("Remote autokick ${if (enabled) "enabled" else "disabled"}.", 0xFF88FF88.toInt())
        }.dimensions(toggleX, panel.top + 108, toggleWidth, 20).build().also { addDrawableChild(it) }

        remoteTemplateField = TextFieldWidget(
            textRenderer,
            panel.left + 16,
            panel.top + 132,
            fieldWidth,
            20,
            Text.literal("Remote autokick message"),
        ).also {
            it.setMaxLength(256)
            it.setDrawsBackground(false)
            ThemeRenderer.applyTextFieldInset(it)
            it.text = ConfigManager.getAutokickMessageTemplate(true)
            addDrawableChild(it)
        }

        saveButton = ThemedButtonWidget.builder(Text.literal("Save")) {
            saveTemplates()
        }.dimensions(panel.left + 16, panel.bottom - 34, 100, 20).build().also { addDrawableChild(it) }

        cancelButton = ThemedButtonWidget.builder(Text.literal("Back")) {
            close()
        }.dimensions(panel.right - 116, panel.bottom - 34, 100, 20).build().also { addDrawableChild(it) }
    }

    override fun shouldPause(): Boolean = false

    override fun close() {
        parent.onAutokickEditorClosed(statusMessage?.string, statusColor)
        client?.setScreen(parent)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        val theme = ThemeManager.current()
        context.fill(0, 0, width, height, theme.overlayBackground)
        val panel = panelRect()
        ThemeRenderer.drawPanel(context, panel.left, panel.top, panel.right, panel.bottom, 22, theme)

        drawCentered(context, title.string, width / 2, panel.top + 8, 0xFFFFFFFF.toInt())
        drawText(context, "Local", panel.left + 16, panel.top + 40, 0xFFDCE5EF.toInt())
        drawText(context, "Message placeholders: <IGN> and <REASON>", panel.left + 16, panel.top + 26, theme.sectionHeader)
        drawText(context, "Reason preview", panel.left + 16, panel.top + 82, theme.mutedText)
        drawText(context, "Remote", panel.left + 16, panel.top + 114, theme.sectionHeader)
        drawText(context, "Reason preview", panel.left + 16, panel.top + 156, theme.mutedText)
        ThemeRenderer.drawTextField(context, localTemplateField, theme)
        ThemeRenderer.drawTextField(context, remoteTemplateField, theme)

        super.render(context, mouseX, mouseY, deltaTicks)
        ThemeRenderer.drawButton(context, localToggleButton, mouseX.toDouble(), mouseY.toDouble(), leftMouseDown, theme)
        ThemeRenderer.drawButton(context, remoteToggleButton, mouseX.toDouble(), mouseY.toDouble(), leftMouseDown, theme)
        ThemeRenderer.drawButton(context, saveButton, mouseX.toDouble(), mouseY.toDouble(), leftMouseDown, theme)
        ThemeRenderer.drawButton(context, cancelButton, mouseX.toDouble(), mouseY.toDouble(), leftMouseDown, theme)

        statusMessage?.let {
            drawCentered(context, it.string, panel.centerX(), panel.bottom - 12, statusColor)
        }
    }

    override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
        if (click.button() == 0) {
            leftMouseDown = true
        }
        return super.mouseClicked(click, doubled)
    }

    override fun mouseReleased(click: Click): Boolean {
        if (click.button() == 0) {
            leftMouseDown = false
        }
        return super.mouseReleased(click)
    }

    private fun saveTemplates() {
        val localTemplate = localTemplateField?.text?.trim().orEmpty()
        val remoteTemplate = remoteTemplateField?.text?.trim().orEmpty()

        when {
            localTemplate.isEmpty() -> showStatus("Local autokick message cannot be empty.", 0xFFFF7777.toInt())
            remoteTemplate.isEmpty() -> showStatus("Remote autokick message cannot be empty.", 0xFFFF7777.toInt())
            !AutokickMessageFormatter.isValidTemplate(localTemplate) ->
                showStatus("Local message must include <IGN> and <REASON>.", 0xFFFF7777.toInt())
            !AutokickMessageFormatter.isValidTemplate(remoteTemplate) ->
                showStatus("Remote message must include <IGN> and <REASON>.", 0xFFFF7777.toInt())
            else -> {
                ConfigManager.setAutokickMessageTemplate(localTemplate, false)
                ConfigManager.setAutokickMessageTemplate(remoteTemplate, true)
                showStatus("Autokick messages saved.", 0xFF88FF88.toInt())
            }
        }
    }

    private fun showStatus(message: String, color: Int) {
        statusMessage = Text.literal(message)
        statusColor = color
    }

    private fun toggleMessage(label: String, enabled: Boolean): Text = Text.literal("$label: ${if (enabled) "ON" else "OFF"}")

    private fun panelRect(): PanelRect {
        val layout = UiLayoutManager.autokickEditorPanel()
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
        fun width(): Int = right - left
        fun centerX(): Int = (left + right) / 2
    }
}
