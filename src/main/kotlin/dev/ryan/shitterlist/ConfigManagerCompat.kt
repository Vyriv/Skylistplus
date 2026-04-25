package dev.ryan.throwerlist

import java.util.Locale

object ConfigManagerCompat {
    private const val fallbackRefreshNanos = 1_000_000_000L

    data class ListedMarkerState(
        val versionToken: Long,
        val localListedUsernames: Set<String>,
        val localIgnoredUsernames: Set<String>,
        val miscIgnoredUsernameSet: Set<String>,
    )

    private data class ListedMarkerStateCache(
        val checkedAtNanos: Long = Long.MIN_VALUE,
        val state: ListedMarkerState? = null,
    )

    private data class RemoteDisableVersionCache(
        val checkedAtNanos: Long = Long.MIN_VALUE,
        val uuidsSignature: Long = Long.MIN_VALUE,
        val versionToken: Long = Long.MIN_VALUE,
    )

    private val lookupVersionMethod = runCatching { ConfigManager::class.java.getMethod("lookupVersion") }.getOrNull()
    private val miscIgnoredUsernameSetMethod = runCatching { ConfigManager::class.java.getMethod("miscIgnoredUsernameSet") }.getOrNull()

    @Volatile
    private var listedMarkerStateCache = ListedMarkerStateCache()

    @Volatile
    private var remoteDisableVersionCache = RemoteDisableVersionCache()

    fun listedMarkerState(): ListedMarkerState {
        val reflectedVersion = lookupVersionOrNull()
        val now = System.nanoTime()
        val cached = listedMarkerStateCache
        if (reflectedVersion != null) {
            cached.state?.takeIf { it.versionToken == reflectedVersion }?.let { return it }
        } else if (cached.state != null && now - cached.checkedAtNanos < fallbackRefreshNanos) {
            return cached.state
        }

        synchronized(this) {
            val refreshed = listedMarkerStateCache
            if (reflectedVersion != null) {
                refreshed.state?.takeIf { it.versionToken == reflectedVersion }?.let { return it }
            } else if (refreshed.state != null && now - refreshed.checkedAtNanos < fallbackRefreshNanos) {
                return refreshed.state
            }

            val localListedUsernames = ConfigManager.localListedUsernames()
            val localIgnoredUsernames = ConfigManager.localIgnoredUsernames()
            val miscIgnoredUsernameSet = reflectedMiscIgnoredUsernameSet()
                ?: ConfigManager.miscIgnoredUsernames()
                    .mapNotNull(::normalizeUsernameKey)
                    .toCollection(linkedSetOf())
            val versionToken = reflectedVersion ?: stableSignature(
                localListedUsernames,
                localIgnoredUsernames,
                miscIgnoredUsernameSet,
            )
            val rebuilt = ListedMarkerState(
                versionToken = versionToken,
                localListedUsernames = localListedUsernames,
                localIgnoredUsernames = localIgnoredUsernames,
                miscIgnoredUsernameSet = miscIgnoredUsernameSet,
            )
            listedMarkerStateCache = ListedMarkerStateCache(
                checkedAtNanos = now,
                state = rebuilt,
            )
            return rebuilt
        }
    }

    fun remoteDisableVersion(uuids: Collection<String>): Long {
        val reflectedVersion = lookupVersionOrNull()
        if (reflectedVersion != null) {
            return reflectedVersion
        }

        val normalizedUuids = uuids
            .mapNotNull(::normalizeUsernameKey)
            .sorted()
        val uuidsSignature = stableSignature(normalizedUuids)
        val now = System.nanoTime()
        val cached = remoteDisableVersionCache
        if (cached.uuidsSignature == uuidsSignature && now - cached.checkedAtNanos < fallbackRefreshNanos) {
            return cached.versionToken
        }

        synchronized(this) {
            val refreshed = remoteDisableVersionCache
            if (refreshed.uuidsSignature == uuidsSignature && now - refreshed.checkedAtNanos < fallbackRefreshNanos) {
                return refreshed.versionToken
            }

            val disabledSignature = normalizedUuids.fold(1L) { acc, uuid ->
                val disabledFlag = if (ConfigManager.isRemoteDisabled(uuid)) 1 else 0
                31L * acc + uuid.hashCode() * 17L + disabledFlag
            }
            remoteDisableVersionCache = RemoteDisableVersionCache(
                checkedAtNanos = now,
                uuidsSignature = uuidsSignature,
                versionToken = disabledSignature,
            )
            return disabledSignature
        }
    }

    private fun reflectedMiscIgnoredUsernameSet(): Set<String>? {
        val method = miscIgnoredUsernameSetMethod ?: return null
        val raw = runCatching { method.invoke(ConfigManager) }.getOrNull() as? Collection<*> ?: return null
        return raw.mapNotNullTo(linkedSetOf(), ::normalizeUsernameKey)
    }

    private fun lookupVersionOrNull(): Long? =
        (runCatching { lookupVersionMethod?.invoke(ConfigManager) }.getOrNull() as? Number)?.toLong()

    private fun normalizeUsernameKey(value: Any?): String? =
        value?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.lowercase(Locale.ROOT)

    private fun stableSignature(values: Collection<String>): Long =
        values.sorted().fold(1L) { acc, value -> 31L * acc + value.hashCode() }

    private fun stableSignature(vararg values: Collection<String>): Long =
        values.fold(1L) { acc, collection -> 31L * acc + stableSignature(collection) }
}
