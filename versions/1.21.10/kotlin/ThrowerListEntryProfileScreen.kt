package dev.ryan.throwerlist

import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class ThrowerListEntryProfileScreen(
    private val parent: ThrowerListListScreen,
    private val entry: ThrowerListListScreen.GuiEntry,
) : Screen(Text.literal("Skylist Entry Profile")) {
    private val profileDateFormatter = DateTimeFormatter.ofPattern("HH:mm:ss d-M-yyyy", Locale.ENGLISH)
    private var editButton: ButtonWidget? = null
    private var actionButton: ButtonWidget? = null
    private var copyIgnButton: ButtonWidget? = null
    private var copyUuidButton: ButtonWidget? = null
    private var closeButton: ButtonWidget? = null
    private var leftMouseDown = false

    override fun init() {
        super.init()
        val panel = panelRect()
        val padding = 20
        val buttonGap = 12
        val headerHeight = 34
        val buttonWidth = ((panel.width() - padding * 2 - buttonGap) / 2).coerceAtLeast(112)
        val rowOneY = panel.bottom - 60
        val rowTwoY = panel.bottom - 32
        val leftX = panel.left + padding
        val rightX = leftX + buttonWidth + buttonGap

        editButton = ButtonWidget.builder(Text.literal("Edit")) {
            currentEntry()?.let(parent::openEditorFor)
        }.dimensions(leftX, rowOneY, buttonWidth, 20).build().also {
            it.active = entry.isLocal
            addDrawableChild(it)
        }
        actionButton = ButtonWidget.builder(Text.literal(if (entry.isLocal) "Remove" else if (entry.isRemoteDisabled) "Enable" else "Disable")) {
            currentEntry()?.let(parent::performPrimaryAction)
        }.dimensions(rightX, rowOneY, buttonWidth, 20).build().also { addDrawableChild(it) }
        copyIgnButton = ButtonWidget.builder(Text.literal("Copy IGN")) {
            currentEntry()?.let(parent::copyIgn)
        }.dimensions(leftX, rowTwoY, buttonWidth, 20).build().also { addDrawableChild(it) }
        copyUuidButton = ButtonWidget.builder(Text.literal("Copy UUID")) {
            currentEntry()?.let(parent::copyUuid)
        }.dimensions(rightX, rowTwoY, buttonWidth, 20).build().also { addDrawableChild(it) }
        closeButton = ButtonWidget.builder(Text.literal("X")) {
            close()
        }.dimensions(panel.right - padding - 18, panel.top + 8, 18, 18).build().also { addDrawableChild(it) }
    }

    override fun shouldPause(): Boolean = false

    override fun close() {
        client?.setScreen(parent)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        val theme = ThemeManager.current()
        val currentEntry = currentEntry() ?: entry
        editButton?.active = currentEntry.isLocal
        actionButton?.message = Text.literal(if (currentEntry.isLocal) "Remove" else if (currentEntry.isRemoteDisabled) "Enable" else "Disable")
        context.fill(0, 0, width, height, theme.overlayBackground)

        val panel = panelRect()
        ThemeRenderer.drawPanel(context, panel.left, panel.top, panel.right, panel.bottom, 26, theme)
        context.fill(panel.left + 1, panel.top + 1, panel.right - 1, panel.top + 33, theme.secondaryPanel)
        context.fill(panel.left, panel.top + 33, panel.right, panel.top + 34, theme.idleBorder)

        val padding = 20
        val headerHeight = 34
        val left = panel.left + padding
        val right = panel.right - padding
        val labelX = left
        val valueX = left + 88
        val contentBottom = (editButton?.y ?: panel.bottom) - 14
        val valueWidth = (right - valueX).coerceAtLeast(100)

        drawText(context, "Skylist Entry Profile", left, panel.top + 11, 0xFFFFFFFF.toInt())

        var y = panel.top + headerHeight + 12
        y = drawSectionHeader(context, "Player", left, right, y, theme)
        drawValueRow(context, "IGN", currentEntry.username, labelX, valueX, y, theme, theme.hoverAccent)
        y += 16
        drawValueRow(context, "UUID", currentEntry.uuid, labelX, valueX, y, theme, theme.lightTextAccent)
        y += 16

        y = drawSectionHeader(context, "Details", left, right, y, theme)
        drawValueRow(context, "Added", formatAdded(currentEntry.ts), labelX, valueX, y, theme, theme.lightTextAccent)
        y += 16

        drawValueRow(context, "Auto Remove", currentEntry.autoRemoveAfter ?: "None", labelX, valueX, y, theme, theme.lightTextAccent)
        y += 16

        currentEntry.expiresAt?.let { expiresAt ->
            drawValueRow(context, "Expires", formatAdded(expiresAt), labelX, valueX, y, theme, theme.lightTextAccent)
            y += 16
        }

        drawValueRow(context, "Tag", tagText(currentEntry), labelX, valueX, y, theme, tagColor(currentEntry, theme))
        y += 16

        val remainingReasonLines = ((contentBottom - y - 4) / 10).coerceAtLeast(0)
        if (remainingReasonLines > 0) {
            val reasonLines = wrapLines(currentEntry.reason.ifBlank { "None" }, valueWidth).take(remainingReasonLines)
            drawWrappedRow(context, "Reason", reasonLines, labelX, valueX, y, theme)
        }

        super.render(context, mouseX, mouseY, deltaTicks)
        listOfNotNull(editButton, actionButton, copyIgnButton, copyUuidButton, closeButton)
            .forEach { ThemeRenderer.drawButton(context, it, mouseX.toDouble(), mouseY.toDouble(), leftMouseDown, theme) }
    }

    override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
        if (click.button() == 0) leftMouseDown = true
        return super.mouseClicked(click, doubled)
    }

    override fun mouseReleased(click: Click): Boolean {
        if (click.button() == 0) leftMouseDown = false
        return super.mouseReleased(click)
    }

    private fun drawSectionHeader(context: DrawContext, title: String, left: Int, right: Int, y: Int, theme: ThemePalette): Int {
        drawText(context, title, left, y, theme.sectionHeader)
        context.fill(left, y + 16, right, y + 17, theme.idleBorder)
        return y + 22
    }

    private fun drawValueRow(
        context: DrawContext,
        label: String,
        value: String,
        labelX: Int,
        valueX: Int,
        y: Int,
        theme: ThemePalette,
        valueColor: Int,
    ) {
        drawText(context, label, labelX, y, theme.sectionHeader)
        drawText(context, truncateToWidth(value, 236), valueX, y, valueColor)
    }

    private fun drawWrappedRow(
        context: DrawContext,
        label: String,
        lines: List<String>,
        labelX: Int,
        valueX: Int,
        y: Int,
        theme: ThemePalette,
    ) {
        drawText(context, label, labelX, y, theme.sectionHeader)
        lines.forEachIndexed { index, line ->
            drawText(context, line, valueX, y + index * 10, theme.mutedText)
        }
    }

    private fun wrapLines(value: String, maxWidth: Int): List<String> {
        val normalized = value.replace(Regex("\\s*\\R\\s*"), " ").trim().ifBlank { "None" }
        val words = normalized.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) {
            return emptyList()
        }

        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
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
        return lines
    }

    private fun truncateToWidth(text: String, maxWidth: Int): String {
        if (textRenderer.getWidth(text) <= maxWidth) return text
        var end = text.length
        while (end > 0 && textRenderer.getWidth(text.take(end) + "...") > maxWidth) {
            end--
        }
        return text.take(end).trimEnd() + "..."
    }

    private fun formatAdded(ts: Long?): String =
        ts?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(profileDateFormatter) } ?: "None"

    private fun tagText(entry: ThrowerListListScreen.GuiEntry): String =
        entry.tags.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.replaceFirstChar { ch -> ch.uppercase() } } ?: "None"

    private fun tagColor(entry: ThrowerListListScreen.GuiEntry, theme: ThemePalette): Int =
        when (entry.tags.firstOrNull()) {
            "toxic" -> 0xFF9A4C63.toInt()
            "griefer" -> 0xFFB97544.toInt()
            "ratter" -> 0xFF7E5AB6.toInt()
            "cheater" -> 0xFFAA4F78.toInt()
            "scammer" -> 0xFFC08A42.toInt()
            else -> theme.mutedText
        }

    private fun currentEntry(): ThrowerListListScreen.GuiEntry? = parent.findEntry(entry.uuid, entry.source)

    private fun drawText(context: DrawContext, text: String, x: Int, y: Int, color: Int) {
        context.drawTextWithShadow(textRenderer, text, x, y, color)
    }

    private fun panelRect(): PanelRect = PanelRect(width / 2 - 248, height / 2 - 168, width / 2 + 248, height / 2 + 168)

    private data class PanelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun width(): Int = right - left
    }
}
