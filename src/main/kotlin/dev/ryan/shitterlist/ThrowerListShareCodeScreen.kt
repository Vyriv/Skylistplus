package dev.ryan.throwerlist

import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text

class ThrowerListShareCodeScreen(
    private val parent: ThrowerListListScreen,
    private val mode: Mode,
) : Screen(Text.literal(if (mode == Mode.EXPORT) "Export Local List" else "Import Local List")) {
    enum class Mode { EXPORT, IMPORT }

    private var codeField: TextFieldWidget? = null
    private var primaryButton: ButtonWidget? = null
    private var closeButton: ButtonWidget? = null
    private var leftMouseDown = false
    private var statusMessage: Text? = null
    private var statusColor = 0xFFFFFFFF.toInt()

    override fun init() {
        super.init()
        val panel = panelRect()
        val initialCode = if (mode == Mode.EXPORT) ListShareCodec.export(ConfigManager.listPlayers()) else ""
        codeField = TextFieldWidget(textRenderer, panel.left + 20, panel.top + 62, panel.width() - 40, 20, Text.literal("Share code")).also {
            it.setMaxLength(8192)
            it.setDrawsBackground(false)
            ThemeRenderer.applyTextFieldInset(it)
            it.text = initialCode
            it.setEditable(mode == Mode.IMPORT)
            addDrawableChild(it)
            setInitialFocus(it)
        }

        primaryButton = ThemedButtonWidget.builder(Text.literal(if (mode == Mode.EXPORT) "Copy Code" else "Import List")) {
            if (mode == Mode.EXPORT) {
                val code = codeField?.text.orEmpty()
                client?.keyboard?.setClipboard(code)
                showStatus("Copied export code.", 0xFF88FF88.toInt())
            } else {
                importCode()
            }
        }.dimensions(panel.left + 20, panel.bottom - 36, 120, 20).build().also { addDrawableChild(it) }

        closeButton = ThemedButtonWidget.builder(Text.literal("Back")) {
            close()
        }.dimensions(panel.right - 120, panel.bottom - 36, 100, 20).build().also { addDrawableChild(it) }
    }

    override fun shouldPause(): Boolean = false

    override fun close() {
        client?.setScreen(parent)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        val theme = ThemeManager.current()
        context.fill(0, 0, width, height, theme.overlayBackground)
        val panel = panelRect()
        ThemeRenderer.drawPanel(context, panel.left, panel.top, panel.right, panel.bottom, 22, theme)
        ThemeRenderer.drawTextField(context, codeField, theme)
        drawCentered(context, title.string, panel.centerX(), panel.top + 8, 0xFFFFFFFF.toInt())
        drawText(
            context,
            if (mode == Mode.EXPORT) "Export code" else "Paste code here",
            panel.left + 20,
            panel.top + 46,
            theme.sectionHeader,
        )
        if (mode == Mode.IMPORT && codeField?.text.isNullOrBlank() && codeField?.isFocused == false) {
            drawText(
                context,
                "paste code here",
                ThemeRenderer.textFieldPlaceholderX(codeField!!),
                ThemeRenderer.textFieldPlaceholderY(codeField!!),
                theme.mutedText,
            )
        }
        statusMessage?.let {
            drawCenteredInRange(
                context,
                it.string,
                (primaryButton?.x ?: panel.left) + (primaryButton?.width ?: 0) + 16,
                (closeButton?.x ?: panel.right) - 16,
                panel.bottom - 30,
                statusColor,
            )
        }
        super.render(context, mouseX, mouseY, deltaTicks)
        ThemeRenderer.drawButton(context, primaryButton, mouseX.toDouble(), mouseY.toDouble(), leftMouseDown, theme)
        ThemeRenderer.drawButton(context, closeButton, mouseX.toDouble(), mouseY.toDouble(), leftMouseDown, theme)
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

    private fun importCode() {
        val decoded = ListShareCodec.decode(codeField?.text.orEmpty())
        if (decoded.error != null) {
            showStatus(decoded.error, 0xFFFF7777.toInt())
            return
        }
        val result = ConfigManager.importPlayers(decoded.entries.map { it.copy() })
        val message = if (result.importedCount <= 0) {
            "No new local entries were imported. Skipped ${result.skippedCount}."
        } else if (result.skippedCount > 0) {
            "Imported ${result.importedCount} local entries and skipped ${result.skippedCount}."
        } else {
            "Imported ${result.importedCount} local entries."
        }
        parent.onSettingsChanged(message, if (result.importedCount > 0) 0xFF88FF88.toInt() else 0xFFFFDD77.toInt())
        client?.setScreen(parent)
    }

    private fun showStatus(message: String, color: Int) {
        statusMessage = Text.literal(message)
        statusColor = color
    }

    private fun drawCentered(context: DrawContext, text: String, centerX: Int, y: Int, color: Int) {
        ThemeRenderer.drawCenteredTextWithShadow(context, textRenderer, text, centerX, y, color)
    }

    private fun drawText(context: DrawContext, text: String, x: Int, y: Int, color: Int) {
        ThemeRenderer.drawTextWithShadow(context, textRenderer, text, x, y, color)
    }

    private fun drawCenteredInRange(context: DrawContext, text: String, left: Int, right: Int, y: Int, color: Int) {
        val clamped = truncateToWidth(text, (right - left).coerceAtLeast(40))
        val x = left + ((right - left) - textRenderer.getWidth(clamped)) / 2
        ThemeRenderer.drawTextWithShadow(context, textRenderer, clamped, x, y, color)
    }

    private fun truncateToWidth(text: String, maxWidth: Int): String {
        if (textRenderer.getWidth(text) <= maxWidth) {
            return text
        }
        var end = text.length
        while (end > 0 && textRenderer.getWidth(text.take(end) + "...") > maxWidth) {
            end--
        }
        return text.take(end).trimEnd() + "..."
    }

    private fun panelRect(): PanelRect {
        val layout = UiLayoutManager.shareCodePanel()
        val halfWidth = layout.width / 2
        val halfHeight = layout.height / 2
        return PanelRect(width / 2 - halfWidth, height / 2 - halfHeight, width / 2 + halfWidth, height / 2 + halfHeight)
    }

    private data class PanelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun centerX(): Int = (left + right) / 2
        fun width(): Int = right - left
    }
}
