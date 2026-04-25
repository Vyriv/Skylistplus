package dev.ryan.throwerlist

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.block.Blocks
import net.minecraft.entity.EntityType
import net.minecraft.scoreboard.ScoreboardDisplaySlot
import net.minecraft.text.MutableText
import net.minecraft.text.Style
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.BlockPos
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object DungeonPuzzleFailPbTracker {
    private const val duplicateWindowMillis = 300L

    private val recentMessages = ConcurrentHashMap<String, Long>()
    private val recentAnnouncements = ConcurrentHashMap<String, Long>()
    private val activeStarts = ConcurrentHashMap<Puzzle, Long>()
    private val recentStartCandidates = ConcurrentHashMap<Puzzle, Long>()
    private val completedThisDungeon = ConcurrentHashMap.newKeySet<Puzzle>()
    private var visiblePuzzles: Set<Puzzle> = emptySet()
    private var inDungeon = false

    fun register() {
        ClientReceiveMessageEvents.GAME.register { message, overlay ->
            if (!overlay) {
                handleIncomingText(message)
            }
        }
        ClientReceiveMessageEvents.CHAT.register { message, _, _, _, _ ->
            handleIncomingText(message)
        }
    }

    fun reset() {
        activeStarts.clear()
        completedThisDungeon.clear()
        recentMessages.clear()
        recentAnnouncements.clear()
        recentStartCandidates.clear()
        visiblePuzzles = emptySet()
        inDungeon = false
    }

    fun onScoreboardChanged() {
        val snapshot = readSidebarSnapshot()
        if (snapshot == null || !snapshot.isDungeon) {
            if (inDungeon) {
                reset()
            }
            return
        }

        inDungeon = true
        val currentVisible = Puzzle.entries.filterTo(linkedSetOf()) { puzzle ->
            snapshot.lines.any { line -> puzzle.matchesSidebarLine(line) }
        }

        currentVisible.subtract(visiblePuzzles).forEach(::startPuzzle)
        visiblePuzzles = currentVisible
    }

    fun onTitleMessage(text: Text?) {
        val raw = text?.string?.trim().orEmpty()
        if (raw.isEmpty() || !DungeonPuzzleFailPbStore.isEnabled()) {
            return
        }

        Puzzle.entries.firstOrNull { puzzle -> puzzle.matchesSidebarLine(raw) }?.let(::startPuzzle)
    }

    fun onBlockInteracted(pos: BlockPos) {
        maybeStartTicTacToeFromBoard(pos)
    }

    fun onBlockUpdated(pos: BlockPos) {
        maybeStartTicTacToeFromBoard(pos)
    }

    private fun maybeStartTicTacToeFromBoard(pos: BlockPos) {
        if (!DungeonPuzzleFailPbStore.isEnabled()) {
            return
        }

        val client = ThrowerListMod.client
        val world = client.world ?: return
        val state = world.getBlockState(pos)
        if (!state.isOf(Blocks.STONE_BUTTON)) {
            return
        }

        val buttonCount = BlockPos.iterate(pos.add(-2, -2, -1), pos.add(2, 2, 1))
            .count { nearby -> world.getBlockState(nearby).isOf(Blocks.STONE_BUTTON) }
        if (buttonCount >= 6) {
            startPuzzle(Puzzle.TIC_TAC_TOE)
        }
    }

    fun onEntitySpawn(entityType: EntityType<*>, x: Double, y: Double, z: Double) {
        if (!DungeonPuzzleFailPbStore.isEnabled() || entityType != EntityType.BLAZE) {
            return
        }

        val player = ThrowerListMod.client.player ?: return
        val dx = player.x - x
        val dy = player.y - y
        val dz = player.z - z
        if (dx * dx + dy * dy + dz * dz <= 64.0 * 64.0) {
            startPuzzle(Puzzle.HIGHER_OR_LOWER)
        }
    }

    private fun handleIncomingText(message: Text) {
        if (!inDungeon) {
            onScoreboardChanged()
        }

        val raw = ListedPlayerMarker.normalizeDecoratedText(message.string.trim())
        if (raw.isEmpty() || isRecentDuplicate(raw)) {
            return
        }

        if (!DungeonPuzzleFailPbStore.isEnabled()) {
            markPuzzleFinished(raw)
            return
        }

        when {
            isThreeWeirdosStart(raw) -> startPuzzle(Puzzle.THREE_WEIRDOS)
            isQuizStart(raw) -> startPuzzle(Puzzle.QUIZ)
            isThreeWeirdosFail(raw) -> recordFailure(Puzzle.THREE_WEIRDOS)
            isTicTacToeFail(raw) -> recordFailure(Puzzle.TIC_TAC_TOE)
            isHigherOrLowerFail(raw) -> recordFailure(Puzzle.HIGHER_OR_LOWER)
            isQuizFail(raw) -> recordFailure(Puzzle.QUIZ)
            isGenericPuzzleFail(raw) -> recordGenericFailure()
            else -> markPuzzleFinished(raw)
        }
    }

    private fun startPuzzle(puzzle: Puzzle) {
        if (!DungeonPuzzleFailPbStore.isEnabled() || puzzle in completedThisDungeon) {
            return
        }

        val now = System.nanoTime()
        recentStartCandidates[puzzle] = now
        if (!inDungeon && !puzzle.canStartOutsideSidebar) {
            return
        }
        activeStarts.putIfAbsent(puzzle, now)
    }

    private fun recordFailure(puzzle: Puzzle) {
        if (!inDungeon && !puzzle.canStartOutsideSidebar && activeStarts[puzzle] == null) {
            return
        }

        val now = System.nanoTime()
        val startNanos = activeStarts.remove(puzzle)
            ?: recentStartCandidates.remove(puzzle)
            ?.takeIf { candidate -> (now - candidate) <= puzzle.maxStartAgeNanos }
            ?: return
        completedThisDungeon.add(puzzle)
        recentStartCandidates.remove(puzzle)

        val elapsedMillis = ((now - startNanos) / 1_000_000L).coerceAtLeast(0L)
        val previousPb = DungeonPuzzleFailPbStore.getPbMillis(puzzle.id)
        val isNewPb = previousPb == null || elapsedMillis < previousPb
        if (isNewPb) {
            DungeonPuzzleFailPbStore.updatePbMillis(puzzle.id, elapsedMillis)
        }
        if (shouldSuppressAnnouncement(puzzle, elapsedMillis)) {
            return
        }

        val message = buildFailMessage(puzzle, elapsedMillis, isNewPb)
        val client = ThrowerListMod.client
        if (DungeonPuzzleFailPbStore.isAnnounceInChatEnabled()) {
            val sent = runCatching {
                client.player?.networkHandler?.sendChatCommand("pc ${message.string}")
            }.isSuccess && client.player?.networkHandler != null
            if (!sent) {
                client.player?.sendMessage(message, false)
            }
        } else {
            client.player?.sendMessage(message, false)
        }
    }

    private fun buildFailMessage(puzzle: Puzzle, elapsedMillis: Long, isNewPb: Boolean): MutableText {
        val client = ThrowerListMod.client
        val username = client.player?.gameProfile?.name?.takeIf { it.isNotBlank() } ?: client.session.username
        val usernameStyle = resolveCurrentPlayerNameStyle(username)

        return Text.empty()
            .append(Text.literal("[TL] ").formatted(Formatting.AQUA))
            .append(Text.literal(username).setStyle(usernameStyle))
            .append(Text.literal(" Failed ").formatted(Formatting.RED))
            .append(Text.literal(puzzle.displayName).formatted(Formatting.GOLD))
            .append(Text.literal(" in ").formatted(Formatting.RED))
            .append(Text.literal("${formatSeconds(elapsedMillis)}s").formatted(Formatting.LIGHT_PURPLE))
            .append(
                if (isNewPb) {
                    Text.literal(" (NEW PB!)").formatted(Formatting.GREEN)
                } else {
                    Text.empty()
                },
            )
    }

    private fun resolveCurrentPlayerNameStyle(username: String): Style {
        val client = ThrowerListMod.client
        val entry = client.networkHandler?.getPlayerListEntry(username)
        val displayName = entry?.displayName ?: client.player?.displayName ?: return Style.EMPTY.withColor(Formatting.WHITE)
        return NameStyleExtractor.findStyleForLiteral(displayName, username)
            ?: displayName.style
            ?: Style.EMPTY.withColor(Formatting.WHITE)
    }

    private fun markPuzzleFinished(raw: String) {
        when {
            raw.contains("PUZZLE FAIL!", ignoreCase = true) -> {
                Puzzle.entries.firstOrNull { puzzle -> raw.contains(puzzle.displayName, ignoreCase = true) }
                    ?.let { puzzle ->
                        activeStarts.remove(puzzle)
                        recentStartCandidates.remove(puzzle)
                        completedThisDungeon.add(puzzle)
                    }
            }

            raw.contains("PUZZLE SOLVED!", ignoreCase = true) -> {
                Puzzle.entries.firstOrNull { puzzle -> raw.contains(puzzle.displayName, ignoreCase = true) }
                    ?.let { puzzle ->
                        activeStarts.remove(puzzle)
                        recentStartCandidates.remove(puzzle)
                        completedThisDungeon.add(puzzle)
                    }
            }

            raw.contains("answered the final question correctly!", ignoreCase = true) -> {
                activeStarts.remove(Puzzle.QUIZ)
                recentStartCandidates.remove(Puzzle.QUIZ)
                completedThisDungeon.add(Puzzle.QUIZ)
            }
        }
    }

    private fun recordGenericFailure() {
        val activePuzzle = Puzzle.entries.firstOrNull { puzzle ->
            activeStarts.containsKey(puzzle) || recentStartCandidates.containsKey(puzzle)
        } ?: return
        recordFailure(activePuzzle)
    }

    private fun readSidebarSnapshot(): SidebarSnapshot? {
        val client = ThrowerListMod.client
        val scoreboard = client.world?.scoreboard ?: return null
        val objective = scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR) ?: return null
        val lines = scoreboard.getScoreboardEntries(objective)
            .filterNot { it.hidden() }
            .sortedWith(compareByDescending<net.minecraft.scoreboard.ScoreboardEntry> { it.value() }.thenBy { it.owner() })
            .map { entry ->
                val team = scoreboard.getScoreHolderTeam(entry.owner())
                val rendered = if (team != null) team.decorateName(entry.name()) else entry.name().copy()
                Formatting.strip(rendered.string)?.trim().orEmpty()
            }
            .filter { it.isNotEmpty() }

        val title = Formatting.strip(objective.displayName.string)?.trim().orEmpty()
        val isDungeon = lines.any {
            it.contains("SKYBLOCK", ignoreCase = true) ||
            it.contains("The Catacombs", ignoreCase = true) ||
                it.contains("Cleared:", ignoreCase = true) ||
                it.contains("Puzzles:", ignoreCase = true) ||
                it.contains("Dungeon Cleared:", ignoreCase = true)
        }

        return SidebarSnapshot(lines, isDungeon)
    }

    private fun isThreeWeirdosStart(raw: String): Boolean =
        raw.startsWith("[NPC] ") && (
            raw.contains("The reward", ignoreCase = true) ||
                raw.contains("telling the truth", ignoreCase = true) ||
                raw.contains("lying", ignoreCase = true)
            )

    private fun isQuizStart(raw: String): Boolean =
        raw.startsWith("[STATUE] Oruo the Omniscient:") && (
            raw.contains("Prove your knowledge by answering 3 questions", ignoreCase = true) ||
                raw.contains("Answer incorrectly, and your moment of ineptitude", ignoreCase = true) ||
                raw.contains("thinks the answer is", ignoreCase = true)
            )

    private fun isThreeWeirdosFail(raw: String): Boolean =
        raw.startsWith("PUZZLE FAIL!") && raw.contains("was fooled by", ignoreCase = true)

    private fun isTicTacToeFail(raw: String): Boolean =
        raw.startsWith("PUZZLE FAIL!") && raw.contains("lost Tic Tac Toe", ignoreCase = true)

    private fun isHigherOrLowerFail(raw: String): Boolean =
        raw.startsWith("PUZZLE FAIL!") && raw.contains("killed a Blaze in the wrong order", ignoreCase = true)

    private fun isQuizFail(raw: String): Boolean =
        raw.startsWith("[STATUE] Oruo the Omniscient:") && raw.contains("chose the wrong answer", ignoreCase = true)

    private fun isGenericPuzzleFail(raw: String): Boolean =
        raw.contains("PUZZLE FAILED!", ignoreCase = true) && raw.contains("failed a puzzle", ignoreCase = true)

    private fun shouldSuppressAnnouncement(puzzle: Puzzle, elapsedMillis: Long): Boolean {
        val now = System.currentTimeMillis()
        recentAnnouncements.entries.removeIf { now - it.value > duplicateWindowMillis }
        val key = "${puzzle.id}|$elapsedMillis"
        val previous = recentAnnouncements.put(key, now) ?: return false
        return now - previous <= duplicateWindowMillis
    }

    private fun isRecentDuplicate(raw: String): Boolean {
        val now = System.currentTimeMillis()
        recentMessages.entries.removeIf { now - it.value > duplicateWindowMillis }
        val previous = recentMessages.put(raw, now) ?: return false
        return now - previous <= duplicateWindowMillis
    }

    fun formatSeconds(elapsedMillis: Long): String =
        String.format(Locale.US, "%.2f", elapsedMillis / 1000.0)

    enum class Puzzle(
        val id: String,
        val displayName: String,
        val aliases: List<String>,
        val canStartOutsideSidebar: Boolean,
        val maxStartAgeNanos: Long = 120_000_000_000L,
    ) {
        THREE_WEIRDOS("three_weirdos", "Three Weirdos", listOf("three weirdos", "weirdos"), true),
        TIC_TAC_TOE("tic_tac_toe", "Tic Tac Toe", listOf("tic tac toe", "tictactoe"), false),
        HIGHER_OR_LOWER("higher_or_lower", "Higher Or Lower", listOf("higher or lower", "blaze"), false),
        QUIZ("quiz", "Quiz", listOf("quiz", "oruo"), true);

        companion object {
            fun fromInput(input: String): Puzzle? {
                val normalized = input.trim().lowercase(Locale.ROOT)
                return entries.firstOrNull { puzzle ->
                    normalized == puzzle.id ||
                        normalized == puzzle.displayName.lowercase(Locale.ROOT) ||
                        normalized in puzzle.aliases
                }
            }
        }

        fun matchesSidebarLine(line: String): Boolean {
            if (line.contains(displayName, ignoreCase = true)) {
                return true
            }
            return aliases.any { alias -> line.contains(alias, ignoreCase = true) }
        }
    }

    private data class SidebarSnapshot(
        val lines: List<String>,
        val isDungeon: Boolean,
    )

    private object NameStyleExtractor {
        fun findStyleForLiteral(text: Text, target: String): Style? {
            var resolved: Style? = null
            text.visit({ style, string ->
                if (resolved == null && string.contains(target)) {
                    resolved = style
                    java.util.Optional.of(Unit)
                } else {
                    java.util.Optional.empty()
                }
            }, Style.EMPTY)
            return resolved
        }
    }
}
