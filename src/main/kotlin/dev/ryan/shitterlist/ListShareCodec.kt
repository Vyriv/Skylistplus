package dev.ryan.throwerlist

import com.google.gson.reflect.TypeToken
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object ListShareCodec {
    private const val shareVersion = 1
    private val usernamePattern = Regex("^[A-Za-z0-9_]{1,16}$")

    fun export(entries: List<PlayerEntry>): String {
        val payload = SharePayload(
            version = shareVersion,
            entries = entries.map {
                SharedEntry(
                    username = it.username,
                    uuid = it.uuid,
                    reason = it.reason,
                    ts = it.ts,
                    tags = it.tags,
                    autoRemoveAfter = it.autoRemoveAfter,
                    expiresAt = it.expiresAt,
                )
            },
        )
        val json = ConfigManager.gson.toJson(payload)
        val compressed = ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(json)
            }
            output.toByteArray()
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)
    }

    fun decode(code: String): DecodeResult {
        val normalizedCode = code.trim()
        if (normalizedCode.isEmpty()) {
            return DecodeResult(error = "Import code is empty")
        }

        return runCatching {
            val compressed = Base64.getUrlDecoder().decode(normalizedCode)
            val json = GZIPInputStream(ByteArrayInputStream(compressed)).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val payload = ConfigManager.gson.fromJson<SharePayload>(
                json,
                object : TypeToken<SharePayload>() {}.type,
            ) ?: return DecodeResult(error = "Import code is invalid")

            if (payload.version != shareVersion) {
                return DecodeResult(error = "Unsupported import code version")
            }

            val entries = payload.entries.mapIndexedNotNull { index, entry ->
                validateEntry(entry, index)
            }
            if (entries.isEmpty()) {
                return DecodeResult(error = "Import code does not contain any valid entries")
            }

            DecodeResult(entries = entries)
        }.getOrElse {
            DecodeResult(error = "Import code is invalid")
        }
    }

    private fun validateEntry(entry: SharedEntry, index: Int): PlayerEntry? {
        val username = entry.username.trim()
        val uuid = entry.uuid.trim().lowercase()
        val reason = entry.reason.trim()
        if (!usernamePattern.matches(username)) {
            ThrowerListMod.logger.warn("Skipping shared entry {} because username '{}' is invalid", index, entry.username)
            return null
        }
        if (reason.isEmpty()) {
            ThrowerListMod.logger.warn("Skipping shared entry {} because reason is blank", index)
            return null
        }
        val parsedUuid = runCatching { UUID.fromString(uuid) }.getOrNull()
        if (parsedUuid == null) {
            ThrowerListMod.logger.warn("Skipping shared entry {} because uuid '{}' is invalid", index, entry.uuid)
            return null
        }

        return PlayerEntry(
            username = username,
            uuid = parsedUuid.toString(),
            reason = reason,
            ts = entry.ts,
            tags = entry.tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().toMutableList(),
            autoRemoveAfter = entry.autoRemoveAfter,
            expiresAt = entry.expiresAt,
        )
    }

    data class DecodeResult(
        val entries: List<PlayerEntry> = emptyList(),
        val error: String? = null,
    )

    private data class SharePayload(
        val version: Int = shareVersion,
        val entries: List<SharedEntry> = emptyList(),
    )

    private data class SharedEntry(
        val username: String = "",
        val uuid: String = "",
        val reason: String = "",
        val ts: Long? = null,
        val tags: List<String> = emptyList(),
        val autoRemoveAfter: String? = null,
        val expiresAt: Long? = null,
    )
}
