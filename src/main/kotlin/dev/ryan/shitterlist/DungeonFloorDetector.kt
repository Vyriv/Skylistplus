package dev.ryan.throwerlist

import net.minecraft.client.MinecraftClient
import net.minecraft.scoreboard.ScoreboardDisplaySlot
import net.minecraft.util.Formatting

object DungeonFloorDetector {
    private val explicitFloorRegex = Regex("""\b([FM][1-7])\b""", RegexOption.IGNORE_CASE)
    private val romanFloorRegex = Regex("""\bFLOOR\s+(I|II|III|IV|V|VI|VII|[1-7])\b""", RegexOption.IGNORE_CASE)

    fun detectCurrentFloor(client: MinecraftClient): String? {
        PartyFinderFloorTracker.currentFloor()?.let { return it }

        val scoreboard = client.world?.scoreboard ?: return null
        val objective = scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR) ?: return null
        val lines = buildList {
            add(objective.displayName.string)
            scoreboard.getKnownScoreHolders().forEach { scoreHolder ->
                val objectives = scoreboard.getScoreHolderObjectives(scoreHolder)
                if (objectives.containsKey(objective)) {
                    add(scoreHolder.nameForScoreboard)
                }
            }
        }.map(::clean)

        lines.forEach { line ->
            explicitFloorRegex.find(line)?.groupValues?.getOrNull(1)?.let { return it.uppercase() }
        }

        val hasMasterMode = lines.any { it.contains("master mode", ignoreCase = true) }
        val hasCatacombs = lines.any { it.contains("catacombs", ignoreCase = true) || it.contains("dungeon", ignoreCase = true) }
        val floorNumber = lines.firstNotNullOfOrNull { line ->
            val value = romanFloorRegex.find(line)?.groupValues?.getOrNull(1) ?: return@firstNotNullOfOrNull null
            parseFloorNumber(value)
        } ?: return null

        if (!hasCatacombs || floorNumber !in 1..7) {
            return null
        }

        return if (hasMasterMode) "M$floorNumber" else "F$floorNumber"
    }

    private fun clean(value: String): String = Formatting.strip(value)?.trim().orEmpty()

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
