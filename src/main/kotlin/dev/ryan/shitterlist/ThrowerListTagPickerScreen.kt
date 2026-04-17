package dev.ryan.throwerlist

import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text

class ThrowerListTagPickerScreen(
    private val parent: ThrowerListEntryEditorScreen,
    selectedTags: Collection<String>,
) : Screen(Text.literal("Select Tags")) {
    private val activeTags = linkedSetOf<String>().apply { addAll(ThrowerTags.normalize(selectedTags)) }
    private val tagButtons = mutableListOf<TagButton>()
    private var saveButton: ButtonWidget? = null
    private var backButton: ButtonWidget? = null
    private var leftMouseDown = false

    override fun init() {
        super.init()
        val panel = panelRect()
        tagButtons.clear()

        layoutRows().forEach { row ->
            row.forEach { layout ->
                val button = ThemedButtonWidget.builder(Text.literal(layout.label)) {
                    if (layout.tag in activeTags) activeTags.remove(layout.tag) else activeTags.add(layout.tag)
                }.dimensions(layout.x, layout.y, layout.width, layout.height).build()
                tagButtons += TagButton(layout.tag, button)
                addDrawableChild(button)
            }
        }

        val buttonY = panel.bottom - 36
        saveButton = ThemedButtonWidget.builder(Text.literal("Save")) {
            parent.onTagsSelected(activeTags.toList())
            client?.setScreen(parent)
        }.dimensions(panel.left + 20, buttonY, 120, 20).build().also { addDrawableChild(it) }
        backButton = ThemedButtonWidget.builder(Text.literal("Back")) {
            client?.setScreen(parent)
        }.dimensions(panel.right - 140, buttonY, 120, 20).build().also { addDrawableChild(it) }
    }

    override fun shouldPause(): Boolean = false

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        val theme = ThemeManager.current()
        context.fill(0, 0, width, height, theme.overlayBackground)
        val panel = panelRect()
        ThemeRenderer.drawPanel(context, panel.left, panel.top, panel.right, panel.bottom, 24, theme)

        drawCentered(context, title.string, panel.centerX(), panel.top + 10, 0xFFFFFFFF.toInt())
        drawCentered(context, "Optional. Local entries only.", panel.centerX(), panel.top + 30, theme.mutedText)
        context.fill(panel.left + 20, panel.top + 46, panel.right - 20, panel.top + 47, theme.idleBorder)

        super.render(context, mouseX, mouseY, deltaTicks)

        tagButtons.forEach { drawTagButton(context, it, mouseX.toDouble(), mouseY.toDouble(), theme) }
        ThemeRenderer.drawButton(context, saveButton, mouseX.toDouble(), mouseY.toDouble(), leftMouseDown, theme)
        ThemeRenderer.drawButton(context, backButton, mouseX.toDouble(), mouseY.toDouble(), leftMouseDown, theme)
    }

    override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
        if (click.button() == 0) leftMouseDown = true
        return super.mouseClicked(click, doubled)
    }

    override fun mouseReleased(click: Click): Boolean {
        if (click.button() == 0) leftMouseDown = false
        return super.mouseReleased(click)
    }

    private fun drawTagButton(context: DrawContext, tagButton: TagButton, mouseX: Double, mouseY: Double, theme: ThemePalette) {
        val widget = tagButton.widget
        val hovered = ThemeRenderer.isWidgetHovered(widget, mouseX, mouseY)
        val selected = tagButton.tag in activeTags
        val tint = tagTint(tagButton.tag)
        val fill = when {
            selected -> theme.mix(theme.primaryAccent, tint, 0.35f)
            hovered -> theme.mix(theme.panelBackground, tint, 0.2f)
            else -> theme.mix(theme.secondaryPanel, tint, 0.12f)
        }
        val border = when {
            selected -> theme.mix(theme.hoverAccent, tint, 0.25f)
            hovered -> theme.mix(theme.primaryAccent, tint, 0.2f)
            else -> theme.mix(theme.idleBorder, tint, 0.18f)
        }
        val textColor = when {
            selected -> theme.lightTextAccent
            hovered -> theme.hoverAccent
            else -> theme.mix(theme.lightTextAccent, tint, 0.18f)
        }

        context.fill(widget.x, widget.y, widget.x + widget.width, widget.y + widget.height, fill)
        ThemeRenderer.drawOutline(context, widget.x, widget.y, widget.width, widget.height, border)
        if (selected) {
            context.fill(widget.x + 6, widget.y + 6, widget.x + 10, widget.y + widget.height - 6, theme.lightTextAccent)
        }

        val label = tagButton.label
        val textOffset = if (selected) 8 else 0
        val textX = widget.x + (widget.width - textRenderer.getWidth(label)) / 2 + textOffset
        val textY = widget.y + (widget.height - 8) / 2
        ThemeRenderer.drawTextWithShadow(context, textRenderer, label, textX, textY, textColor)
    }

    private fun layoutRows(): List<List<TagLayout>> {
        val panel = panelRect()
        val buttonWidth = 144
        val buttonHeight = 28
        val columnGap = 12
        val rowGap = 12
        val startY = panel.top + 64

        return ThrowerTags.supported
            .map { it to it.replaceFirstChar { ch -> ch.uppercase() } }
            .chunked(2)
            .mapIndexed { rowIndex, row ->
                val rowWidth = row.size * buttonWidth + (row.size - 1) * columnGap
                val startX = panel.centerX() - rowWidth / 2
                row.mapIndexed { columnIndex, (tag, label) ->
                    TagLayout(
                        tag = tag,
                        label = label,
                        x = startX + columnIndex * (buttonWidth + columnGap),
                        y = startY + rowIndex * (buttonHeight + rowGap),
                        width = buttonWidth,
                        height = buttonHeight,
                    )
                }
            }
    }

    private fun tagTint(tag: String): Int =
        when (tag) {
            "toxic" -> 0xFF9A4C63.toInt()
            "griefer" -> 0xFFB97544.toInt()
            "ratter" -> 0xFF7E5AB6.toInt()
            "cheater" -> 0xFFAA4F78.toInt()
            "scammer" -> 0xFFC08A42.toInt()
            else -> 0xFF777777.toInt()
        }

    private fun drawCentered(context: DrawContext, text: String, centerX: Int, y: Int, color: Int) {
        ThemeRenderer.drawCenteredTextWithShadow(context, textRenderer, text, centerX, y, color)
    }

    private fun panelRect(): PanelRect {
        val layout = UiLayoutManager.tagPickerPanel()
        val halfWidth = layout.width / 2
        val halfHeight = layout.height / 2
        return PanelRect(width / 2 - halfWidth, height / 2 - halfHeight, width / 2 + halfWidth, height / 2 + halfHeight)
    }

    private data class TagButton(val tag: String, val widget: ButtonWidget) {
        val label: String
            get() = tag.replaceFirstChar { it.uppercase() }
    }

    private data class TagLayout(val tag: String, val label: String, val x: Int, val y: Int, val width: Int, val height: Int)

    private data class PanelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun centerX(): Int = (left + right) / 2
    }
}
