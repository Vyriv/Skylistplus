package dev.ryan.throwerlist

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

object RemoteListManager {
    private const val remoteUrl = "https://jsonhosting.com/api/json/e7619cc9/raw"

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val remoteReasons = ConcurrentHashMap<String, String>()
    private val remoteTimestamps = ConcurrentHashMap<String, Long?>()
    private val remoteUsernames = ConcurrentHashMap<String, String>()
    private val remoteTags = ConcurrentHashMap<String, List<String>>()

    @Volatile
    private var started = false

    @Volatile
    private var lastRefreshCompletedAt: Long? = null

    fun start() {
        if (started) {
            return
        }

        started = true
        refreshSafely()
    }

    fun findReasonByUuid(uuid: String): String? {
        val normalizedUuid = uuid.lowercase()
        if (ProtectedSkylistEntries.isProtectedUuid(normalizedUuid)) {
            return null
        }

        if (ConfigManager.isRemoteDisabled(normalizedUuid)) {
            return null
        }

        return remoteReasons[normalizedUuid]
    }

    fun findReasonByUsername(username: String): String? =
        findEntryByUsername(username)?.takeUnless { it.isDisabled }?.reason

    fun findEntryByUuid(uuid: String): RemoteEntry? {
        val normalizedUuid = uuid.lowercase()
        if (ProtectedSkylistEntries.isProtectedUuid(normalizedUuid)) {
            return null
        }

        val reason = remoteReasons[normalizedUuid] ?: return null
        return RemoteEntry(
            username = remoteUsernames[normalizedUuid] ?: normalizedUuid,
            uuid = normalizedUuid,
            reason = reason,
            ts = remoteTimestamps[normalizedUuid],
            isDisabled = ConfigManager.isRemoteDisabled(normalizedUuid),
            tags = remoteTags[normalizedUuid].orEmpty(),
        )
    }

    fun findEntryByUsername(username: String): RemoteEntry? {
        if (ProtectedSkylistEntries.isProtectedUsername(username)) {
            return null
        }

        return remoteUsernames.entries.firstOrNull {
            it.value.equals(username, ignoreCase = true)
        }?.let { entry ->
            if (ProtectedSkylistEntries.isProtected(entry.value, entry.key)) {
                return@let null
            }

            val reason = remoteReasons[entry.key] ?: return@let null
            RemoteEntry(
                username = entry.value,
                uuid = entry.key,
                reason = reason,
                ts = remoteTimestamps[entry.key],
                isDisabled = ConfigManager.isRemoteDisabled(entry.key),
                tags = remoteTags[entry.key].orEmpty(),
            )
        }
    }

    fun updateUsername(uuid: String, username: String) {
        val normalizedUuid = uuid.lowercase()
        if (ProtectedSkylistEntries.isProtected(username, normalizedUuid)) {
            remoteReasons.remove(normalizedUuid)
            remoteTimestamps.remove(normalizedUuid)
            remoteUsernames.remove(normalizedUuid)
            remoteTags.remove(normalizedUuid)
            return
        }

        remoteUsernames[normalizedUuid] = username
    }

    fun listEntries(): List<RemoteEntry> = remoteReasons.entries
        .filterNot { ProtectedSkylistEntries.isProtected(uuid = it.key) }
        .map { entry ->
            val uuid = entry.key
            RemoteEntry(
                username = remoteUsernames[uuid] ?: uuid,
                uuid = uuid,
                reason = entry.value,
                ts = remoteTimestamps[uuid],
                isDisabled = ConfigManager.isRemoteDisabled(uuid),
                tags = remoteTags[uuid].orEmpty(),
            )
        }
        .sortedBy { it.username.lowercase() }

    fun listedUsernames(): Set<String> = listEntries()
        .filterNot { it.isDisabled }
        .mapTo(linkedSetOf()) { it.username.lowercase() }

    fun lastRefreshCompletedAt(): Long? = lastRefreshCompletedAt

    private fun refreshSafely() {
        runCatching { refresh() }
            .onFailure { ThrowerListMod.logger.warn("Failed to refresh remote thrower list", it) }
    }

    fun refreshAsync(): CompletableFuture<Unit> {
        return CompletableFuture.runAsync({
            refresh()
        }).thenApply { Unit }
    }

    private fun refresh() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(remoteUrl))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            error("Unexpected response ${response.statusCode()}")
        }

        val parsed = JsonParser.parseString(response.body()).asJsonObject

        remoteReasons.clear()
        remoteTimestamps.clear()
        remoteUsernames.clear()
        remoteTags.clear()
        parsed.entrySet().forEach { (uuid, data) ->
            val normalizedUuid = uuid.lowercase()
            if (ProtectedSkylistEntries.isProtectedUuid(normalizedUuid)) {
                return@forEach
            }

            val remoteData = parseRemoteData(data) ?: return@forEach
            remoteReasons[normalizedUuid] = remoteData.reason
            remoteTimestamps[normalizedUuid] = remoteData.ts ?: ConfigManager.getOrCreateRemoteImportTimestamp(normalizedUuid)
            remoteTags[normalizedUuid] = remoteData.tags
            remoteUsernames.computeIfAbsent(normalizedUuid) { normalizedUuid }
            UsernameResolver.resolveUuid(normalizedUuid).thenAccept { username ->
                if (username.isNullOrBlank()) {
                    return@thenAccept
                }

                if (ProtectedSkylistEntries.isProtected(username, normalizedUuid)) {
                    remoteReasons.remove(normalizedUuid)
                    remoteTimestamps.remove(normalizedUuid)
                    remoteUsernames.remove(normalizedUuid)
                    remoteTags.remove(normalizedUuid)
                } else {
                    remoteUsernames[normalizedUuid] = username
                }
            }
        }

        lastRefreshCompletedAt = System.currentTimeMillis()

        ThrowerListMod.logger.info("Loaded {} remote thrower entries", remoteReasons.size)
    }

    data class RemoteEntry(
        val username: String,
        val uuid: String,
        val reason: String,
        val ts: Long? = null,
        val isDisabled: Boolean = false,
        val tags: List<String> = emptyList(),
    )

    private data class ParsedRemoteData(
        val reason: String,
        val ts: Long?,
        val tags: List<String> = emptyList(),
    )

    private fun parseRemoteData(data: JsonElement): ParsedRemoteData? {
        if (data.isJsonPrimitive && data.asJsonPrimitive.isString) {
            return ParsedRemoteData(
                reason = data.asString,
                ts = null,
                tags = emptyList(),
            )
        }

        if (!data.isJsonObject) {
            return null
        }

        val json = data.asJsonObject
        val reason = json.getString("reason") ?: return null
        return ParsedRemoteData(
            reason = reason,
            ts = json.getLong("ts"),
            tags = json.getStringArray("tags"),
        )
    }

    private fun JsonObject.getString(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.asString

    private fun JsonObject.getLong(key: String): Long? =
        get(key)?.takeIf { !it.isJsonNull }?.asLong

    private fun JsonObject.getStringArray(key: String): List<String> {
        val element = get(key) ?: return emptyList()
        if (!element.isJsonArray) {
            return emptyList()
        }
        return element.asJsonArray.mapNotNull { item ->
            item.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() }
        }.distinct()
    }
}
