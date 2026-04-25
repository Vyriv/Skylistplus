package dev.ryan.throwerlist

import com.mojang.authlib.GameProfile
import net.minecraft.text.MutableText
import net.minecraft.text.OrderedText
import net.minecraft.text.StringVisitable
import net.minecraft.text.Style
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object ListedPlayerMarker {
    private data class TokenRange(val start: Int, val endExclusive: Int)
    private data class MarkerRange(val start: Int, val endExclusive: Int, val kind: MarkerKind)
    private data class MarkerLookupSnapshot(
        val configVersion: Long = Long.MIN_VALUE,
        val remoteVersion: Long = Long.MIN_VALUE,
        val usernameMarkerKinds: Map<String, MarkerKind> = emptyMap(),
        val hasMarkers: Boolean = false,
    )
    private data class CachedProfileResolution(
        val normalizedUsername: String?,
        val markerKind: CachedMarkerKind,
    )

    private enum class MarkerKind(
        val legacyPrefix: String,
        val legacySuffix: String,
        val formatting: Formatting,
    ) {
        LISTED("\u00A7c\u00A7l! ", "\u00A7c\u00A7l !", Formatting.RED),
        IGNORED("\u00A77\u00A7l! ", "\u00A77\u00A7l !", Formatting.GRAY),
    }

    private enum class CachedMarkerKind {
        LISTED,
        IGNORED,
        NONE;

        fun toMarkerKind(): MarkerKind? =
            when (this) {
                LISTED -> MarkerKind.LISTED
                IGNORED -> MarkerKind.IGNORED
                NONE -> null
            }
    }

    private const val legacyFormat = '\u00A7'
    private val suppressWorldLabels = ThreadLocal.withInitial { false }
    @Volatile
    private var lookupSnapshot = MarkerLookupSnapshot()
    private val usernameMarkerCache = ConcurrentHashMap<String, CachedMarkerKind>()
    private val profileMarkerCache = ConcurrentHashMap<UUID, CachedProfileResolution>()

    fun containsListedName(text: String?): Boolean = containsMarkedName(text)

    fun containsMarkedName(text: String?): Boolean {
        if (text.isNullOrEmpty()) {
            return false
        }

        val snapshot = currentLookupSnapshot()
        if (!snapshot.hasMarkers) {
            return false
        }
        val normalizedText = stripLegacyFormatting(text)
        return findTokenRanges(normalizedText).any { range ->
            !isMarked(normalizedText, range.start, range.endExclusive) &&
                markerKindForToken(normalizedText.substring(range.start, range.endExclusive)) != null
        }
    }

    fun isListedProfile(profile: GameProfile?): Boolean = markerKindForProfile(profile) != null

    fun applyMarker(message: Text): Text = rebuildVisitable(message)

    fun applyMarker(message: Text, profile: GameProfile?): Text {
        val profileName = profile?.name ?: return message
        return applyMarker(message, profileName)
    }

    fun applyMarker(message: Text, username: String): Text = rebuildVisitable(message, username)

    fun applyMarkerToChatMessage(message: Text): Text = rebuildVisitable(message, chatHeaderOnly = true)

    fun applyMarkerToString(text: String): String =
        if (legacyFormat in text) {
            applyMarkerToLegacyString(text)
        } else {
            applyMarkerToPlainString(text)
        }

    fun applyMarkerToPlainString(text: String): String {
        if (!containsMarkedName(text)) {
            return text
        }

        val plain = stripLegacyFormatting(text)
        val markerRanges = findMarkerRanges(plain)
        if (markerRanges.isEmpty()) {
            return text
        }

        val output = StringBuilder(plain.length + markerRanges.size * 4)
        var index = 0
        markerRanges.forEach { range ->
            if (range.start > index) {
                output.append(plain, index, range.start)
            }
            output.append("! ")
            output.append(plain, range.start, range.endExclusive)
            output.append(" !")
            index = range.endExclusive
        }
        if (index < plain.length) {
            output.append(plain.substring(index))
        }
        return output.toString()
    }

    fun applyMarker(message: StringVisitable): StringVisitable = rebuildVisitable(message)

    fun applyMarker(text: OrderedText): OrderedText {
        val rebuilt = rebuildOrderedText(text) ?: return text
        return rebuilt.asOrderedText()
    }

    fun applyMarkerToLegacyString(text: String): String {
        if (!containsMarkedName(text) || legacyFormat !in text) {
            return text
        }

        return applyMarkerToLegacyString(text) { candidate -> markerKindForToken(candidate) }
    }

    fun areWorldLabelsSuppressed(): Boolean = suppressWorldLabels.get()

    fun pushWorldLabelSuppression() {
        suppressWorldLabels.set(true)
    }

    fun popWorldLabelSuppression() {
        suppressWorldLabels.set(false)
    }

    fun normalizeDecoratedText(text: String): String =
        stripLegacyFormatting(text)
            .replace(Regex("""!\s+((?:\[[^\]]+]\s+)?[A-Za-z0-9_]{1,16})\s+!"""), "$1")

    private fun rebuildVisitable(
        message: StringVisitable,
        targetUsername: String? = null,
        chatHeaderOnly: Boolean = false,
    ): Text {
        val rebuilt = Text.empty()
        val headerBoundary = if (chatHeaderOnly) findChatHeaderBoundary(message) else Int.MAX_VALUE
        var changed = false
        var visibleIndex = 0

        message.visit({ style, segment ->
            val segmentStart = visibleIndex
            val segmentEnd = segmentStart + segment.length
            visibleIndex = segmentEnd

            val decoratedLength = (headerBoundary - segmentStart).coerceIn(0, segment.length)
            val decoratedSegment = segment.substring(0, decoratedLength)
            val untouchedSegment = segment.substring(decoratedLength)

            if (decoratedSegment.isNotEmpty()) {
                if (legacyFormat in decoratedSegment) {
                    val markedLegacy = when {
                        targetUsername != null && decoratedSegment.contains(targetUsername, ignoreCase = true) ->
                            applyMarkerToLegacyString(decoratedSegment) { candidate ->
                                if (candidate.equals(targetUsername, ignoreCase = true)) {
                                    markerKindForUsername(targetUsername)
                                } else {
                                    markerKindForToken(candidate)
                                }
                            }

                        containsMarkedName(decoratedSegment) ->
                            applyMarkerToLegacyString(decoratedSegment) { candidate -> markerKindForToken(candidate) }

                        else -> decoratedSegment
                    }
                    appendLegacySegment(rebuilt, markedLegacy, style)
                    if (markedLegacy != decoratedSegment) {
                        changed = true
                    }
                } else if (!containsMarkedName(decoratedSegment)) {
                    rebuilt.append(Text.literal(decoratedSegment).setStyle(style))
                } else {
                    changed = true
                    appendMarkedSegment(rebuilt, decoratedSegment, style, targetUsername)
                }
            }

            if (untouchedSegment.isNotEmpty()) {
                rebuilt.append(Text.literal(untouchedSegment).setStyle(style))
            }
            Optional.empty<Unit>()
        }, Style.EMPTY)

        return if (changed) rebuilt else if (message is Text) message else rebuilt
    }

    private fun findChatHeaderBoundary(message: StringVisitable): Int {
        val plain = buildString {
            message.visit({ _, segment ->
                append(segment)
                Optional.empty<Unit>()
            }, Style.EMPTY)
        }
        val delimiterIndex = plain.indexOf(": ")
        return if (delimiterIndex == -1) Int.MAX_VALUE else delimiterIndex
    }

    private fun rebuildOrderedText(message: OrderedText): Text? {
        val segments = mutableListOf<Pair<Style, StringBuilder>>()
        var currentStyle: Style? = null
        var currentBuilder = StringBuilder()

        fun flush() {
            val style = currentStyle ?: return
            if (currentBuilder.isNotEmpty()) {
                segments.add(style to currentBuilder)
                currentBuilder = StringBuilder()
            }
        }

        message.accept { _, style, codePoint ->
            if (currentStyle != null && currentStyle != style) {
                flush()
            }
            currentStyle = style
            currentBuilder.appendCodePoint(codePoint)
            true
        }
        flush()

        if (segments.none { containsMarkedName(it.second.toString()) }) {
            return null
        }

        val rebuilt = Text.empty()
        var changed = false
        segments.forEach { (style, builder) ->
            val segment = builder.toString()
            if (containsMarkedName(segment)) {
                changed = true
                appendMarkedSegment(rebuilt, segment, style, null)
            } else {
                rebuilt.append(Text.literal(segment).setStyle(style))
            }
        }

        return if (changed) rebuilt else null
    }

    private fun appendMarkedSegment(
        target: MutableText,
        segment: String,
        style: Style,
        targetUsername: String?,
    ) {
        val markerRanges = findMarkerRanges(segment, targetUsername)
        var index = 0
        markerRanges.forEach { range ->
            if (range.start < index) {
                return@forEach
            }
            if (range.start > index) {
                target.append(Text.literal(segment.substring(index, range.start)).setStyle(style))
            }
            target.append(Text.literal("! ").formatted(range.kind.formatting, Formatting.BOLD))
            target.append(Text.literal(segment.substring(range.start, range.endExclusive)).setStyle(style))
            target.append(Text.literal(" !").formatted(range.kind.formatting, Formatting.BOLD))
            index = range.endExclusive
        }

        if (index < segment.length) {
            target.append(Text.literal(segment.substring(index)).setStyle(style))
        }
    }

    private fun findMarkerRanges(segment: String, targetUsername: String? = null): List<MarkerRange> =
        findTokenRanges(segment)
            .mapNotNull { range ->
                val token = segment.substring(range.start, range.endExclusive)
                val kind = when {
                    targetUsername != null && token.equals(targetUsername, ignoreCase = true) -> markerKindForUsername(targetUsername)
                    else -> markerKindForToken(token)
                }
                if (kind == null || isMarked(segment, range.start, range.endExclusive)) {
                    null
                } else {
                    MarkerRange(findMarkerStart(segment, range.start), range.endExclusive, kind)
                }
            }
            .sortedBy { it.start }
            .toList()

    private fun markerKindForProfile(profile: GameProfile?): MarkerKind? {
        if (profile == null) {
            return null
        }

        val normalizedUsername = normalizeUsernameKey(profile.name)
        val profileId = profile.id
        if (profileId != null) {
            profileMarkerCache[profileId]?.takeIf { it.normalizedUsername == normalizedUsername }?.let { cached ->
                return cached.markerKind.toMarkerKind()
            }
        }

        val markerKind = markerKindForUsername(profile.name)
        if (profileId != null) {
            profileMarkerCache[profileId] = CachedProfileResolution(
                normalizedUsername = normalizedUsername,
                markerKind = markerKind.toCachedMarkerKind(),
            )
        }
        return markerKind
    }

    private fun markerKindForUsername(username: String?): MarkerKind? {
        val normalized = normalizeUsernameKey(username) ?: return null
        return usernameMarkerCache.computeIfAbsent(normalized) {
            currentLookupSnapshot().usernameMarkerKinds[normalized].toCachedMarkerKind()
        }.toMarkerKind()
    }

    private fun markerKindForToken(token: String): MarkerKind? =
        markerKindForUsername(token.removePrefix("[").substringAfterLast("] ").trim())

    private fun currentLookupSnapshot(): MarkerLookupSnapshot {
        val configState = ConfigManagerCompat.listedMarkerState()
        val configVersion = configState.versionToken
        val remoteVersion = RemoteListManager.dataVersion()
        val current = lookupSnapshot
        if (current.configVersion == configVersion && current.remoteVersion == remoteVersion) {
            return current
        }

        synchronized(this) {
            val refreshed = lookupSnapshot
            if (refreshed.configVersion == configVersion && refreshed.remoteVersion == remoteVersion) {
                return refreshed
            }

            val usernameMarkerKinds = linkedMapOf<String, MarkerKind>()
            configState.localIgnoredUsernames.forEach { usernameMarkerKinds[it] = MarkerKind.IGNORED }
            configState.miscIgnoredUsernameSet.forEach { usernameMarkerKinds[it] = MarkerKind.IGNORED }
            configState.localListedUsernames.forEach { username ->
                usernameMarkerKinds.putIfAbsent(username, MarkerKind.LISTED)
            }
            RemoteListManager.listedUsernames().forEach { username ->
                usernameMarkerKinds.putIfAbsent(username, MarkerKind.LISTED)
            }

            val rebuilt = MarkerLookupSnapshot(
                configVersion = configVersion,
                remoteVersion = remoteVersion,
                usernameMarkerKinds = usernameMarkerKinds,
                hasMarkers = usernameMarkerKinds.isNotEmpty(),
            )
            lookupSnapshot = rebuilt
            usernameMarkerCache.clear()
            profileMarkerCache.clear()
            return rebuilt
        }
    }

    private fun normalizeUsernameKey(username: String?): String? =
        username?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.lowercase()

    private fun MarkerKind?.toCachedMarkerKind(): CachedMarkerKind =
        when (this) {
            MarkerKind.LISTED -> CachedMarkerKind.LISTED
            MarkerKind.IGNORED -> CachedMarkerKind.IGNORED
            null -> CachedMarkerKind.NONE
        }

    private fun stripLegacyFormatting(text: String): String {
        if (legacyFormat !in text) {
            return text
        }

        val output = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (character == legacyFormat && index + 1 < text.length) {
                index += 2
                continue
            }

            output.append(character)
            index++
        }
        return output.toString()
    }

    private fun activeLegacyCodes(text: String, endExclusive: Int): String {
        var colorCode: Char? = null
        val formats = linkedSetOf<Char>()
        var index = 0
        while (index < endExclusive - 1) {
            if (text[index] != legacyFormat) {
                index++
                continue
            }

            val code = text[index + 1].lowercaseChar()
            when (code) {
                in '0'..'9', in 'a'..'f' -> {
                    colorCode = code
                    formats.clear()
                }
                'k', 'l', 'm', 'n', 'o' -> formats.add(code)
                'r' -> {
                    colorCode = null
                    formats.clear()
                }
            }
            index += 2
        }

        return buildString {
            colorCode?.let {
                append(legacyFormat)
                append(it)
            }
            formats.forEach {
                append(legacyFormat)
                append(it)
            }
        }
    }

    private fun appendLegacySegment(target: MutableText, raw: String, baseStyle: Style) {
        parseLegacyFormattedText(raw).visit({ style, segment ->
            target.append(Text.literal(segment).setStyle(style.withParent(baseStyle)))
            Optional.empty<Unit>()
        }, Style.EMPTY)
    }

    private fun parseLegacyFormattedText(raw: String): Text {
        val output = Text.empty()
        var style = Style.EMPTY
        val buffer = StringBuilder()
        var index = 0

        fun flush() {
            if (buffer.isNotEmpty()) {
                output.append(Text.literal(buffer.toString()).setStyle(style))
                buffer.clear()
            }
        }

        while (index < raw.length) {
            val character = raw[index]
            if (character == legacyFormat && index + 1 < raw.length) {
                flush()
                style = applyLegacyCode(style, raw[index + 1].lowercaseChar())
                index += 2
                continue
            }

            buffer.append(character)
            index++
        }
        flush()

        return output
    }

    private fun applyLegacyCode(style: Style, code: Char): Style =
        when (code) {
            in '0'..'9', in 'a'..'f' -> {
                val formatting = Formatting.byCode(code) ?: return Style.EMPTY
                Style.EMPTY.withColor(formatting)
            }
            'k' -> style.withObfuscated(true)
            'l' -> style.withBold(true)
            'm' -> style.withStrikethrough(true)
            'n' -> style.withUnderline(true)
            'o' -> style.withItalic(true)
            'r' -> Style.EMPTY
            else -> style
        }

    private fun findTokenRanges(text: String): Sequence<TokenRange> = sequence {
        var index = 0
        while (index < text.length) {
            if (!isUsernameCharacter(text[index])) {
                index++
                continue
            }

            val start = index
            while (index < text.length && isUsernameCharacter(text[index])) {
                index++
            }
            yield(TokenRange(start, index))
        }
    }

    private fun isMarked(text: String, tokenStart: Int, tokenEndExclusive: Int): Boolean {
        val markerStart = findMarkerStart(text, tokenStart)
        val hasPrefix = markerStart >= 2 && text[markerStart - 2] == '!' && text[markerStart - 1] == ' '
        val hasSuffix = tokenEndExclusive + 1 < text.length &&
            text[tokenEndExclusive] == ' ' &&
            text[tokenEndExclusive + 1] == '!'
        return hasPrefix && hasSuffix
    }

    private fun findMarkerStart(text: String, tokenStart: Int): Int {
        val prefix = text.substring(0, tokenStart)
        val match = Regex("""\[[^\]]+]\s*$""").find(prefix) ?: return tokenStart
        val bracketContent = match.value
            .removeSuffix(" ")
            .removePrefix("[")
            .removeSuffix("]")
        if (!bracketContent.any { it.isLetter() || it == '+' }) {
            return tokenStart
        }
        return match.range.first
    }

    private fun isUsernameCharacter(character: Char): Boolean =
        character == '_' || character.isLetterOrDigit()

    private fun applyMarkerToLegacyString(
        text: String,
        kindResolver: (String) -> MarkerKind?,
    ): String {
        val visible = StringBuilder()
        val mapping = ArrayList<Int>(text.length)
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (character == legacyFormat && index + 1 < text.length) {
                index += 2
                continue
            }

            visible.append(character)
            mapping.add(index)
            index++
        }

        val plain = visible.toString()
        val match = findTokenRanges(plain)
            .firstNotNullOfOrNull { range ->
                val token = plain.substring(range.start, range.endExclusive)
                val kind = kindResolver(token)
                if (kind == null || isMarked(plain, range.start, range.endExclusive)) {
                    null
                } else {
                    Triple(range, kind, token)
                }
            } ?: return text

        val markerStart = findMarkerStart(plain, match.first.start)
        val rawStart = mapping[markerStart]
        val rawEndExclusive = mapping[match.first.endExclusive - 1] + 1
        val activeStyle = activeLegacyCodes(text, rawStart)

        return buildString(text.length + match.second.legacyPrefix.length + match.second.legacySuffix.length + (activeStyle.length * 2)) {
            append(text, 0, rawStart)
            append(match.second.legacyPrefix)
            append(activeStyle)
            append(text, rawStart, rawEndExclusive)
            append(match.second.legacySuffix)
            append(activeStyle)
            append(text.substring(rawEndExclusive))
        }
    }
}
