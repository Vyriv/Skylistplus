package dev.ryan.throwerlist

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

object DungeonPuzzleFailPbStore {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val storagePath: Path = FabricLoader.getInstance().configDir
        .resolve("skylistplus")
        .resolve("dungeon_puzzle_fail_pbs.json")

    @Volatile
    private var state = State()

    @Synchronized
    fun load() {
        state = if (Files.exists(storagePath)) {
            runCatching {
                gson.fromJson<State>(
                    Files.readString(storagePath),
                    object : TypeToken<State>() {}.type,
                ) ?: State()
            }.getOrElse {
                ThrowerListMod.logger.error("Failed to load dungeon puzzle fail PB state", it)
                State()
            }
        } else {
            State()
        }.normalized()

        save()
    }

    @Synchronized
    fun save() {
        Files.createDirectories(storagePath.parent)
        Files.writeString(storagePath, gson.toJson(state.normalized()))
    }

    @Synchronized
    fun isEnabled(): Boolean = state.enabled

    @Synchronized
    fun setEnabled(enabled: Boolean): Boolean {
        state.enabled = enabled
        save()
        return state.enabled
    }

    @Synchronized
    fun isAnnounceInChatEnabled(): Boolean = state.announceInChat

    @Synchronized
    fun setAnnounceInChatEnabled(enabled: Boolean): Boolean {
        state.announceInChat = enabled
        save()
        return state.announceInChat
    }

    @Synchronized
    fun getPbMillis(puzzleId: String): Long? = state.pbs[normalizePuzzleId(puzzleId)]

    @Synchronized
    fun updatePbMillis(puzzleId: String, millis: Long): Long {
        state.pbs[normalizePuzzleId(puzzleId)] = millis.coerceAtLeast(0L)
        save()
        return state.pbs.getValue(normalizePuzzleId(puzzleId))
    }

    @Synchronized
    fun resetPb(puzzleId: String): Boolean {
        val removed = state.pbs.remove(normalizePuzzleId(puzzleId)) != null
        if (removed) {
            save()
        }
        return removed
    }

    @Synchronized
    fun resetAll() {
        state.pbs.clear()
        save()
    }

    @Synchronized
    fun allPbs(): Map<String, Long> = linkedMapOf<String, Long>().apply {
        state.pbs.entries
            .sortedBy { it.key }
            .forEach { (key, value) -> put(key, value) }
    }

    fun filePath(): Path = storagePath

    private fun normalizePuzzleId(puzzleId: String): String =
        puzzleId.trim().lowercase(Locale.ROOT)

    data class State(
        var enabled: Boolean = true,
        var announceInChat: Boolean = false,
        var pbs: MutableMap<String, Long> = linkedMapOf(),
    ) {
        fun normalized(): State {
            pbs = pbs.entries
                .mapNotNull { (key, value) ->
                    key.trim()
                        .takeIf { it.isNotEmpty() }
                        ?.lowercase(Locale.ROOT)
                        ?.let { normalizedKey -> normalizedKey to value.coerceAtLeast(0L) }
                }
                .associateTo(linkedMapOf()) { it }
            return this
        }
    }
}
