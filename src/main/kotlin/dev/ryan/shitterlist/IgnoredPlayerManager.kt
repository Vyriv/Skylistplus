package dev.ryan.throwerlist

import com.mojang.authlib.GameProfile
import net.minecraft.text.Text

object IgnoredPlayerManager {
    private val usernameRegex = Regex("""^[A-Za-z0-9_]{1,16}$""")
    private val leadingBracketedSectionRegex = Regex("""^\[[^\]]*]\s*""")
    private val inlineBracketedSectionRegex = Regex("""\[[^\]]*]""")
    private val senderPrefixes = listOf("Party >", "Guild >", "Officer >", "From ", "To ")

    fun isIgnoredProfile(profile: GameProfile?): Boolean {
        if (profile == null) {
            return false
        }

        return isIgnoredUsername(profile.name)
    }

    fun isIgnoredUsername(username: String?): Boolean =
        !username.isNullOrBlank() && ConfigManager.isIgnoredUsername(username)

    fun shouldSuppressChatMessage(message: Text, sender: GameProfile?): Boolean {
        if (isIgnoredProfile(sender)) {
            return true
        }

        return extractMessageUsername(message.string)?.let(::isIgnoredUsername) == true
    }

    fun extractMessageUsername(rawMessage: String): String? {
        val normalized = ListedPlayerMarker.normalizeDecoratedText(rawMessage.trim())
        val header = normalized.substringBefore(": ", missingDelimiterValue = "").trim()
        if (header.isEmpty()) {
            return null
        }

        val withoutPrefix = stripSenderPrefixes(header)
        val withoutBrackets = inlineBracketedSectionRegex.replace(withoutPrefix, " ")
        return withoutBrackets
            .split(Regex("""\s+"""))
            .asSequence()
            .map { token -> token.trim { !it.isLetterOrDigit() && it != '_' } }
            .firstOrNull { token -> token.matches(usernameRegex) }
    }

    private fun stripSenderPrefixes(header: String): String {
        var current = header.trim()
        var changed: Boolean
        do {
            changed = false
            current = leadingBracketedSectionRegex.replace(current, "").trimStart()
            val matchedPrefix = senderPrefixes.firstOrNull { prefix -> current.startsWith(prefix, ignoreCase = true) }
            if (matchedPrefix != null) {
                current = current.substring(matchedPrefix.length).trimStart()
                changed = true
            }
        } while (changed)

        return current
    }
}
