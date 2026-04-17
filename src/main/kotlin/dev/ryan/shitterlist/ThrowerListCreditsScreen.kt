package dev.ryan.throwerlist

import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.OrderedText
import net.minecraft.text.Text

class ThrowerListCreditsScreen(private val parent: ThrowerListListScreen) : Screen(Text.literal("Skylist Credits")) {
    private var doneButton: ButtonWidget? = null
    private var wrappedLines: List<OrderedText> = listOf(Text.literal("Loading credits...").asOrderedText())
    private var leftMouseDown = false

    override fun init() {
        super.init()
        val panel = panelRect()
        doneButton = ThemedButtonWidget.builder(Text.literal("Done")) {
            client?.setScreen(parent)
        }.dimensions(panel.centerX() - 50, panel.bottom - 28, 100, 20).build().also { addDrawableChild(it) }

        refreshCredits()
    }

    override fun shouldPause(): Boolean = false

    override fun close() {
        client?.setScreen(parent)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        val theme = ThemeManager.current()
        val panel = panelRect()
        context.fill(0, 0, width, height, theme.overlayBackground)
        ThemeRenderer.drawPanel(context, panel.left, panel.top, panel.right, panel.bottom, 24, theme)
        drawCenteredText(context, title.string, panel.centerX(), panel.top + 8, 0xFFFFFFFF.toInt())

        var y = panel.top + 32
        wrappedLines.forEach { line ->
            ThemeRenderer.drawTextWithShadow(context, textRenderer, line, panel.left + 16, y, theme.lightTextAccent)
            y += 10
        }

        super.render(context, mouseX, mouseY, deltaTicks)
        ThemeRenderer.drawButton(context, doneButton, mouseX.toDouble(), mouseY.toDouble(), leftMouseDown, theme)
    }

    private fun refreshCredits() {
        wrappedLines = listOf(Text.literal("Loading credits...").asOrderedText())
        CommandHandler.requestCreditsLines(
            onReady = { lines ->
                val formattedLines = mutableListOf<Text>()
                lines.filterNot { line -> line.string.all { it == '-' } }.forEach { line ->
                    val content = line.string.trim()
                    when {
                        content.isBlank() -> formattedLines += Text.empty()
                        content == "Developers:" || content == "Beta Testers:" -> {
                            if (formattedLines.isNotEmpty() && formattedLines.last().string.isNotBlank()) {
                                formattedLines += Text.empty()
                            }
                            formattedLines += line.copy()
                            formattedLines += Text.empty()
                        }
                        else -> formattedLines += line.copy()
                    }
                }
                wrappedLines = formattedLines.flatMap { line ->
                    if (line.string.isBlank()) {
                        listOf(Text.empty().asOrderedText())
                    } else {
                        textRenderer.wrapLines(line, panelRect().width() - 32)
                    }
                }
            },
            onError = { error ->
                wrappedLines = textRenderer.wrapLines(Text.literal(error), panelRect().width() - 32)
            },
        )
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

    private fun panelRect(): Rect {
        val layout = UiLayoutManager.creditsPanel()
        val halfWidth = layout.width / 2
        val halfHeight = layout.height / 2
        return Rect(width / 2 - halfWidth, height / 2 - halfHeight, width / 2 + halfWidth, height / 2 + halfHeight)
    }

    private fun drawCenteredText(context: DrawContext, text: String, centerX: Int, y: Int, color: Int) {
        ThemeRenderer.drawCenteredTextWithShadow(context, textRenderer, text, centerX, y, color)
    }

    private data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun width(): Int = right - left
        fun centerX(): Int = (left + right) / 2
    }
}
