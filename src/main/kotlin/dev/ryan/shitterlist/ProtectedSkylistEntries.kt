package dev.ryan.throwerlist

object ProtectedSkylistEntries {
    private val protectedUsernames = setOf(
        "ChaseCatlantic",
        "Tomato_XD",
    ).map { it.lowercase() }.toSet()

    private val protectedUuids = setOf(
        "e8a20d35b48b4fa1bd924df9049ae76f",
        "9a3335fd4b234618af6cdecff24004f9",
    )

    fun isProtected(username: String? = null, uuid: String? = null): Boolean =
        isProtectedUsername(username) || isProtectedUuid(uuid)

    fun isProtectedUsername(username: String?): Boolean =
        !username.isNullOrBlank() && username.lowercase() in protectedUsernames

    fun isProtectedUuid(uuid: String?): Boolean =
        !uuid.isNullOrBlank() && normalizeUuid(uuid) in protectedUuids

    fun filterImportEntries(entries: Collection<PlayerEntry>): List<PlayerEntry> =
        entries.filterNot { isProtected(it.username, it.uuid) }

    fun rejectionMessage(): String = "Error: Unable to parse username, try again or dm @Vyirv on discord."

    private fun normalizeUuid(uuid: String): String = uuid.replace("-", "").lowercase()
}
