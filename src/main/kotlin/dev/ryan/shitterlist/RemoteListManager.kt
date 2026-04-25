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
import java.util.Locale

object RemoteListManager {
    private const val remoteUrl = "https://jsonhosting.com/api/json/e7619cc9/raw"

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val remoteReasons = ConcurrentHashMap<String, String>()
    private val remoteTimestamps = ConcurrentHashMap<String, Long?>()
    private val remoteUsernames = ConcurrentHashMap<String, String>()
    private val remoteTags = ConcurrentHashMap<String, List<String>>()
    private data class RemoteLookupSnapshot(
        val dataVersion: Long = Long.MIN_VALUE,
        val configVersion: Long = Long.MIN_VALUE,
        val usernameByUuid: Map<String, String> = emptyMap(),
        val uuidByUsername: Map<String, String> = emptyMap(),
        val listedUsernames: Set<String> = emptySet(),
    )

    @Volatile
    private var started = false

    @Volatile
    private var lastRefreshCompletedAt: Long? = null

    @Volatile
    private var remoteDataVersion = 0L

    @Volatile
    private var lookupSnapshot = RemoteLookupSnapshot()

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

        val normalizedUsername = normalizeUsernameKey(username) ?: return null
        val snapshot = currentLookupSnapshot()
        val uuid = snapshot.uuidByUsername[normalizedUsername] ?: return null
        val resolvedUsername = snapshot.usernameByUuid[uuid] ?: return null
        return uuid.let { normalizedUuid ->
            if (ProtectedSkylistEntries.isProtected(resolvedUsername, normalizedUuid)) {
                return@let null
            }

            val reason = remoteReasons[normalizedUuid] ?: return@let null
            RemoteEntry(
                username = resolvedUsername,
                uuid = normalizedUuid,
                reason = reason,
                ts = remoteTimestamps[normalizedUuid],
                isDisabled = ConfigManager.isRemoteDisabled(normalizedUuid),
                tags = remoteTags[normalizedUuid].orEmpty(),
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
            markLookupDirty()
            return
        }

        remoteUsernames[normalizedUuid] = username
        markLookupDirty()
    }

    fun listEntries(): List<RemoteEntry> {
        val snapshot = currentLookupSnapshot()
        return remoteReasons.entries
            .filterNot { ProtectedSkylistEntries.isProtected(uuid = it.key) }
            .map { entry ->
                val uuid = entry.key
            RemoteEntry(
                username = snapshot.usernameByUuid[uuid] ?: uuid,
                uuid = uuid,
                reason = entry.value,
                ts = remoteTimestamps[uuid],
                isDisabled = ConfigManager.isRemoteDisabled(uuid),
                tags = remoteTags[uuid].orEmpty(),
            )
            }
            .sortedBy { it.username.lowercase(Locale.ROOT) }
    }

    fun listedUsernames(): Set<String> = currentLookupSnapshot().listedUsernames

    fun dataVersion(): Long = remoteDataVersion

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
                markLookupDirty()
            }
        }

        markLookupDirty()
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

    private fun currentLookupSnapshot(): RemoteLookupSnapshot {
        val configVersion = ConfigManagerCompat.remoteDisableVersion(remoteReasons.keys)
        val dataVersion = remoteDataVersion
        val current = lookupSnapshot
        if (current.dataVersion == dataVersion && current.configVersion == configVersion) {
            return current
        }

        synchronized(this) {
            val refreshed = lookupSnapshot
            if (refreshed.dataVersion == dataVersion && refreshed.configVersion == configVersion) {
                return refreshed
            }

            val usernameByUuid = remoteUsernames.entries.associate { (uuid, username) -> uuid to username }
            val uuidByUsername = linkedMapOf<String, String>()
            val listedUsernames = linkedSetOf<String>()
            usernameByUuid.forEach { (uuid, username) ->
                val normalizedUsername = normalizeUsernameKey(username) ?: return@forEach
                uuidByUsername.putIfAbsent(normalizedUsername, uuid)
                if (!ConfigManager.isRemoteDisabled(uuid)) {
                    listedUsernames.add(normalizedUsername)
                }
            }

            val rebuilt = RemoteLookupSnapshot(
                dataVersion = dataVersion,
                configVersion = configVersion,
                usernameByUuid = usernameByUuid,
                uuidByUsername = uuidByUsername,
                listedUsernames = listedUsernames,
            )
            lookupSnapshot = rebuilt
            return rebuilt
        }
    }

    private fun markLookupDirty() {
        remoteDataVersion++
    }

    private fun normalizeUsernameKey(username: String?): String? =
        username?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.lowercase(Locale.ROOT)
}
