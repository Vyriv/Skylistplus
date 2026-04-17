package dev.ryan.throwerlist

import net.minecraft.client.gui.DrawContext
import net.minecraft.component.DataComponentTypes
import net.minecraft.item.ItemStack
import net.minecraft.screen.slot.Slot
import net.minecraft.text.Text

object PartyFinderUiHighlighter {
    private const val highlightColor = 0xFFFF5555.toInt()
    private val directFloorRegex = Regex("""\b(F7|M[1-7])\b""", RegexOption.IGNORE_CASE)
    private val romanFloorRegex = Regex("""\b(?:FLOOR|CATACOMBS FLOOR)\s+(I|II|III|IV|V|VI|VII|[1-7])\b""", RegexOption.IGNORE_CASE)

    fun isPartyFinderScreen(title: Text?): Boolean {
        val normalized = ListedPlayerMarker.normalizeDecoratedText(title?.string.orEmpty())
        return normalized.contains("party finder", ignoreCase = true) ||
            normalized.contains("group builder", ignoreCase = true)
    }

    fun detectFloor(stack: ItemStack): String? {
        if (stack.isEmpty) {
            return null
        }

        val lines = buildList {
            add(stack.name.string)
            stack.get(DataComponentTypes.LORE)
                ?.styledLines()
                ?.mapTo(this) { it.string }
        }.map { ListedPlayerMarker.normalizeDecoratedText(it) }

        lines.forEach { line ->
            directFloorRegex.find(line)?.groupValues?.getOrNull(1)?.let { return it.uppercase() }
        }

        val isMaster = lines.any { it.contains("master mode", ignoreCase = true) }
        lines.forEach { line ->
            val raw = romanFloorRegex.find(line)?.groupValues?.getOrNull(1) ?: return@forEach
            val number = parseFloorNumber(raw) ?: return@forEach
            return if (isMaster) "M$number" else "F$number"
        }

        return null
    }

    fun shouldCaptureFloor(stack: ItemStack): Boolean {
        if (stack.isEmpty) {
            return false
        }

        // Party listing entries can contain floors in their lore, which should not overwrite the queued floor.
        if (stack.get(DataComponentTypes.PROFILE)?.gameProfile != null) {
            return false
        }

        return true
    }

    fun detectConfiguredFloor(screenTitle: Text?, slots: List<Slot>): String? {
        if (!isPartyFinderScreen(screenTitle)) {
            return null
        }

        var isMasterMode = false
        var floorNumber: Int? = null

        slots.forEach { slot ->
            val stack = slot.stack ?: return@forEach
            if (stack.isEmpty) {
                return@forEach
            }

            val lines = buildList {
                add(stack.name.string)
                stack.get(DataComponentTypes.LORE)?.styledLines()?.mapTo(this) { it.string }
            }.map { ListedPlayerMarker.normalizeDecoratedText(it) }

            if (lines.any { it.contains("currently selected", ignoreCase = true) }) {
                if (lines.any { it.contains("master mode", ignoreCase = true) }) {
                    isMasterMode = true
                }
                if (lines.any { it.contains("the catacombs", ignoreCase = true) && !it.contains("master mode", ignoreCase = true) }) {
                    isMasterMode = false
                }

                lines.firstNotNullOfOrNull { line ->
                    directFloorRegex.find(line)?.groupValues?.getOrNull(1)?.let { value ->
                        val normalized = value.uppercase()
                        if (normalized.startsWith("M")) {
                            isMasterMode = true
                        }
                        normalized.drop(1).toIntOrNull()
                    } ?: romanFloorRegex.find(line)?.groupValues?.getOrNull(1)?.let(::parseFloorNumber)
                }?.let { floorNumber = it }
            }
        }

        val number = floorNumber ?: return null
        return if (isMasterMode) "M$number" else "F$number"
    }

    fun decorateTooltipIfNeeded(screenTitle: Text?, stack: ItemStack, tooltip: List<Text>): List<Text> {
        if (!isPartyFinderScreen(screenTitle) || stack.isEmpty || tooltip.isEmpty()) {
            return tooltip
        }

        var changed = false
        val decorated = ArrayList<Text>(tooltip.size)
        tooltip.forEach { line ->
            val marked = if (ListedPlayerMarker.containsListedName(line.string)) {
                ListedPlayerMarker.applyMarker(line)
            } else {
                line
            }
            if (marked !== line) {
                changed = true
            }
            decorated += marked
        }

        return if (changed) decorated else tooltip
    }

    fun shouldHighlightEntry(screenTitle: Text?, stack: ItemStack): Boolean {
        if (!isPartyFinderScreen(screenTitle) || stack.isEmpty) {
            return false
        }

        val profile = stack.get(DataComponentTypes.PROFILE)?.gameProfile
        if (profile != null && ListedPlayerMarker.isListedProfile(profile)) {
            return true
        }

        if (ListedPlayerMarker.containsListedName(stack.name.string)) {
            return true
        }

        return stack.get(DataComponentTypes.LORE)
            ?.styledLines()
            ?.any { ListedPlayerMarker.containsListedName(it.string) }
            ?: false
    }

    fun drawHeadHighlight(context: DrawContext, screenX: Int, screenY: Int) {
        context.drawStrokedRectangle(screenX, screenY, 16, 16, highlightColor)
    }

    private fun parseFloorNumber(value: String): Int? =
        when (value.uppercase()) {
            "1", "I" -> 1
            "2", "II" -> 2
            "3", "III" -> 3
            "4", "IV" -> 4
            "5", "V" -> 5
            "6", "VI" -> 6
            "7", "VII" -> 7
            else -> null
        }
}
