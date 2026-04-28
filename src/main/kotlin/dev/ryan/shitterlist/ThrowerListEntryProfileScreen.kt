package dev.ryan.throwerlist

import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text
import net.minecraft.util.Util
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class ThrowerListEntryProfileScreen(
    private val parent: ThrowerListListScreen,
    private val entry: ThrowerListListScreen.GuiEntry,
) : Screen(Text.literal("Skylist Entry Profile")) {
    private val profileDateFormatter = DateTimeFormatter.ofPattern("HH:mm:ss d-M-yyyy", Locale.ENGLISH)
    private var copyIgnButton: ButtonWidget? = null
    private var copyUuidButton: ButtonWidget? = null
    private var closeButton: ButtonWidget? = null
    private var leftMouseDown = false
    private var hoveredDiscordIndex: Int? = null

    override fun init() {
        super.init()
        val panel = panelRect()
        val padding = 20
        val buttonGap = 12
        val buttonWidth = ((panel.width() - padding * 2 - buttonGap) / 2).coerceAtLeast(112)
        val actionY = panel.top + 36
        val leftX = panel.left + padding

        copyIgnButton = ThemedButtonWidget.builder(Text.literal("Copy IGN")) {
            currentEntry()?.let(parent::copyIgn)
        }.dimensions(leftX, actionY, buttonWidth, 20).build().also { addDrawableChild(it) }
        copyUuidButton = ThemedButtonWidget.builder(Text.literal("Copy UUID")) {
            currentEntry()?.let(parent::copyUuid)
        }.dimensions(leftX + buttonWidth + buttonGap, actionY, buttonWidth, 20).build().also { addDrawableChild(it) }
        closeButton = ThemedButtonWidget.builder(Text.literal("X")) {
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
        hoveredDiscordIndex = hoveredDiscordIndex(currentEntry, mouseX.toDouble(), mouseY.toDouble())
        context.fill(0, 0, width, height, theme.overlayBackground)

        val panel = panelRect()
        ThemeRenderer.drawPanel(context, panel.left, panel.top, panel.right, panel.bottom, 26, theme)
        context.fill(panel.left + 1, panel.top + 1, panel.right - 1, panel.top + 33, theme.secondaryPanel)
        context.fill(panel.left, panel.top + 33, panel.right, panel.top + 34, theme.idleBorder)

        val padding = 20
        val left = panel.left + padding
        val right = panel.right - padding
        val labelX = left
        val valueX = left + 88
        val contentBottom = panel.bottom - 16

        drawText(context, if (currentEntry.isScammer) "Scammer Profile" else "Skylist Entry Profile", left, panel.top + 11, 0xFFFFFFFF.toInt())

        if (currentEntry.isScammer) {
            renderScammerProfile(context, mouseX, mouseY, currentEntry, labelX, valueX, right, panel.top + 68, contentBottom, theme)
        } else {
            renderStandardProfile(context, currentEntry, labelX, valueX, right, panel.top + 68, contentBottom, theme)
        }

        super.render(context, mouseX, mouseY, deltaTicks)
        listOfNotNull(copyIgnButton, copyUuidButton, closeButton)
            .forEach { ThemeRenderer.drawButton(context, it, mouseX.toDouble(), mouseY.toDouble(), leftMouseDown, theme) }
        hoveredDiscordIndex?.let { index ->
            val discordUser = currentEntry.discordUsers.getOrNull(index) ?: return@let
            val discordId = currentEntry.discordIds.getOrNull(index) ?: discordUser.id
            context.drawTooltip(textRenderer, Text.literal("Click to copy Discord ID: $discordId"), mouseX, mouseY)
        }
    }

    override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
        if (click.button() == 0) {
            leftMouseDown = true
            val currentEntry = currentEntry() ?: entry
            if (currentEntry.isScammer) {
                hoveredDiscordIndex(currentEntry, click.x(), click.y())?.let { index ->
                    val discordUser = currentEntry.discordUsers.getOrNull(index) ?: return@let
                    val discordId = currentEntry.discordIds.getOrNull(index) ?: discordUser.id
                    client?.keyboard?.setClipboard(discordId)
                    return true
                }
                currentEntry.evidence?.takeIf { evidenceLinkRect(currentEntry).contains(click.x(), click.y()) }?.let {
                    Util.getOperatingSystem().open(URI.create(it))
                    return true
                }
            }
        }
        return super.mouseClicked(click, doubled)
    }

    override fun mouseReleased(click: Click): Boolean {
        if (click.button() == 0) leftMouseDown = false
        return super.mouseReleased(click)
    }

    private fun renderStandardProfile(
        context: DrawContext,
        currentEntry: ThrowerListListScreen.GuiEntry,
        labelX: Int,
        valueX: Int,
        right: Int,
        startY: Int,
        contentBottom: Int,
        theme: ThemePalette,
    ) {
        var y = startY
        y = drawSectionHeader(context, "Player", labelX, right, y, theme)
        drawValueRow(context, "IGN", currentEntry.username, labelX, valueX, y, theme, theme.hoverAccent)
        y += 16
        drawValueRow(context, "UUID", currentEntry.uuid, labelX, valueX, y, theme, theme.lightTextAccent)
        y += 16

        y = drawSectionHeader(context, "Details", labelX, right, y, theme)
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
            val reasonLines = wrapLines(currentEntry.reason.ifBlank { "None" }, (right - valueX).coerceAtLeast(100)).take(remainingReasonLines)
            drawWrappedRow(context, "Reason", reasonLines, labelX, valueX, y, theme)
        }
    }

    private fun renderScammerProfile(
        context: DrawContext,
        mouseX: Int,
        mouseY: Int,
        currentEntry: ThrowerListListScreen.GuiEntry,
        labelX: Int,
        valueX: Int,
        right: Int,
        startY: Int,
        contentBottom: Int,
        theme: ThemePalette,
    ) {
        var y = startY
        y = drawSectionHeader(context, "Info", labelX, right, y, theme)
        drawValueRow(context, "Minecraft user", currentEntry.username, labelX, valueX, y, theme, theme.hoverAccent)
        y += 16
        drawValueRow(context, "UUID", currentEntry.uuid, labelX, valueX, y, theme, theme.lightTextAccent)
        y += 16
        drawValueRow(context, "Alts", currentEntry.altUsernames.ifEmpty { listOf("None") }.joinToString(", "), labelX, valueX, y, theme, theme.lightTextAccent)
        y += 16
        y = drawDiscordRow(context, currentEntry, labelX, valueX, y, theme, hoveredDiscordIndex)

        val evidenceColor = if (currentEntry.evidence != null && evidenceLinkRect(currentEntry).contains(mouseX.toDouble(), mouseY.toDouble())) theme.hoverAccent else 0xFF7FD6FF.toInt()
        drawValueRow(context, "Evidence", currentEntry.evidence ?: "None", labelX, valueX, y, theme, evidenceColor)
        y += 16
        drawValueRow(context, "Date", formatAdded(currentEntry.ts), labelX, valueX, y, theme, parent.scamRecencyColor(currentEntry.ts))
        y += 16
        drawValueRow(context, "Severity", currentEntry.severity?.label ?: "Unknown", labelX, valueX, y, theme, currentEntry.severity?.color ?: theme.lightTextAccent)
        y += 16
        currentEntry.severityBreakdown?.let { result ->
            drawValueRow(context, "Score", result.score?.let(parent::formatScore) ?: "?", labelX, valueX, y, theme, theme.lightTextAccent)
            y += 16
            drawValueRow(context, "Action", result.actionLabel ?: "unknown", labelX, valueX, y, theme, theme.lightTextAccent)
            y += 16
        }

        val remainingReasonLines = ((contentBottom - y - 4) / 10).coerceAtLeast(0)
        if (remainingReasonLines > 0) {
            val availableWidth = (right - valueX).coerceAtLeast(100)
            val reasonLines = wrapLines(currentEntry.reason.ifBlank { "None" }, availableWidth)
            val severityLines = currentEntry.severityBreakdown?.reasons?.flatMap { wrapLines(it, availableWidth) }.orEmpty()
            val combinedLines = buildList {
                add("Reason:")
                addAll(reasonLines)
                if (severityLines.isNotEmpty()) {
                    add("Why:")
                    addAll(severityLines)
                }
            }.take(remainingReasonLines)
            drawWrappedRow(context, if (severityLines.isNotEmpty()) "Breakdown" else "Reason", combinedLines, labelX, valueX, y, theme)
        }
    }

    private fun drawDiscordRow(
        context: DrawContext,
        currentEntry: ThrowerListListScreen.GuiEntry,
        labelX: Int,
        valueX: Int,
        y: Int,
        theme: ThemePalette,
        hoveredIndex: Int?,
    ): Int {
        drawText(context, "Discord", labelX, y, theme.sectionHeader)
        if (currentEntry.discordUsers.isEmpty()) {
            drawText(context, "None", valueX, y, theme.lightTextAccent)
            return y + 16
        }

        currentEntry.discordUsers.forEachIndexed { index, discordUser ->
            val color = if (hoveredIndex == index) theme.hoverAccent else theme.lightTextAccent
            drawText(context, truncateToWidth(discordUser.label, 236), valueX, y + index * 10, color)
        }
        return y + maxOf(16, currentEntry.discordUsers.size * 10 + 6)
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

    private fun hoveredDiscordIndex(entry: ThrowerListListScreen.GuiEntry, mouseX: Double, mouseY: Double): Int? {
        if (!entry.isScammer || entry.discordUsers.isEmpty()) {
            return null
        }

        val discordRect = discordValueRect(entry)
        entry.discordUsers.forEachIndexed { index, discordUser ->
            val y = discordRect.top + index * 10
            val width = textRenderer.getWidth(truncateToWidth(discordUser.label, 236))
            if (mouseX >= discordRect.left && mouseX <= discordRect.left + width && mouseY >= y && mouseY <= y + 10) {
                return index
            }
        }
        return null
    }

    private fun evidenceLinkRect(entry: ThrowerListListScreen.GuiEntry): PanelRect {
        val panel = panelRect()
        val width = textRenderer.getWidth(truncateToWidth(entry.evidence ?: "None", 236))
        val rowTop = evidenceRowTop(entry)
        val left = panel.left + 108
        val top = rowTop
        return PanelRect(left, top, left + width, top + 10)
    }

    private fun discordValueRect(entry: ThrowerListListScreen.GuiEntry): PanelRect {
        val panel = panelRect()
        val top = panel.top + 138
        return PanelRect(panel.left + 108, top, panel.right - 20, top + maxOf(16, entry.discordUsers.size * 10 + 6))
    }

    private fun evidenceRowTop(entry: ThrowerListListScreen.GuiEntry): Int = discordValueRect(entry).bottom - 6

    private fun drawText(context: DrawContext, text: String, x: Int, y: Int, color: Int) {
        ThemeRenderer.drawText(context, textRenderer, text, x, y, color)
    }

    private fun panelRect(): PanelRect {
        val layout = UiLayoutManager.entryProfilePanel()
        val halfWidth = layout.width / 2
        val halfHeight = layout.height / 2
        return PanelRect(width / 2 - halfWidth, height / 2 - halfHeight, width / 2 + halfWidth, height / 2 + halfHeight)
    }

    private data class PanelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun width(): Int = right - left

        fun contains(x: Double, y: Double): Boolean = x >= left && x <= right && y >= top && y <= bottom
    }
}
