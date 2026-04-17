package dev.ryan.throwerlist

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.text.ClickEvent
import net.minecraft.text.HoverEvent
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class SkylistPlusPartyListener(
    private val client: MinecraftClient,
    private val kickQueue: KickQueue,
) {
    private val joinedRegex = Regex("""^(?:\[[^\]]+]\s)?([A-Za-z0-9_]{1,16}) joined the party\.$""")
    private val leftRegex = Regex("""^(?:\[[^\]]+]\s)?([A-Za-z0-9_]{1,16}) left the party\.$""")
    private val removedRegex = Regex("""^(?:\[[^\]]+]\s)?([A-Za-z0-9_]{1,16}) was removed from the party\.$""")
    private val partyFinderQueuedRegex = Regex("""^Party Finder > Your party has been queued in the dungeon finder!$""", RegexOption.IGNORE_CASE)
    private val partyFinderRemovedRegex = Regex("""^Party Finder > Your group has been removed from the party finder!$""", RegexOption.IGNORE_CASE)
    private val partyFinderJoinedRegex = Regex("""^Party Finder > ([A-Za-z0-9_]{1,16}) joined the dungeon group! \(.+\)$""", RegexOption.IGNORE_CASE)
    private val usernameRegex = Regex("""(?:\[[^\]]+]\s)?([A-Za-z0-9_]{1,16})""")
    private val tlCheckRegex = Regex("""(?:^|:\s*)!(?:tlcheck|tl\s+check|slcheck|sl\s+check|skylistcheck|skylist\s+check)\s+([A-Za-z0-9_:-]{1,36}|\d{17,20})""", RegexOption.IGNORE_CASE)
    private val pendingJoinChecks = ConcurrentHashMap.newKeySet<String>()
    private val members = ConcurrentHashMap.newKeySet<String>()
    private val recentMessages = ConcurrentHashMap<String, Long>()

    @Volatile
    private var role: PartyRole = PartyRole.NONE

    @Volatile
    private var awaitingPartyList = false

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
        role = PartyRole.NONE
        awaitingPartyList = false
        members.clear()
        pendingJoinChecks.clear()
        kickQueue.clear()
        PartyFinderFloorTracker.clear()
    }

    private fun handleIncomingText(message: Text) {
        val raw = message.string.trim()
        if (raw.isEmpty() || isRecentDuplicate(raw)) {
            return
        }
        val normalizedRaw = ListedPlayerMarker.normalizeDecoratedText(raw)

        kickQueue.handleChatMessage(raw)

        when {
            tlCheckRegex.containsMatchIn(normalizedRaw) -> {
                val checkTarget = tlCheckRegex.find(normalizedRaw)?.groupValues?.get(1) ?: return
                val replyCommand = replyChatCommand(normalizedRaw) ?: return
                handleTlCheck(checkTarget, replyCommand)
            }

            joinedRegex.matches(normalizedRaw) -> {
                val username = joinedRegex.matchEntire(normalizedRaw)?.groupValues?.get(1) ?: return
                members.add(username.lowercase(Locale.ROOT))
                if (shouldAssumeLeader()) {
                    maybeQueueKick(username)
                } else if (role == PartyRole.NONE) {
                    pendingJoinChecks.add(username.lowercase(Locale.ROOT))
                    requestPartyListRefresh()
                } else {
                    maybeQueueKick(username)
                }
            }

            partyFinderQueuedRegex.matches(normalizedRaw) -> {
                role = PartyRole.LEADER
                recordDetectedDungeonFloor()
            }

            partyFinderRemovedRegex.matches(normalizedRaw) -> {
                PartyFinderFloorTracker.clear()
            }

            partyFinderJoinedRegex.matches(normalizedRaw) -> {
                val username = partyFinderJoinedRegex.matchEntire(normalizedRaw)?.groupValues?.get(1) ?: return
                recordDetectedDungeonFloor()
                members.add(username.lowercase(Locale.ROOT))
                if (shouldAssumeLeader()) {
                    maybeQueueKick(username)
                } else if (role == PartyRole.NONE) {
                    pendingJoinChecks.add(username.lowercase(Locale.ROOT))
                    requestPartyListRefresh()
                } else {
                    maybeQueueKick(username)
                }
            }

            leftRegex.matches(normalizedRaw) -> {
                val username = leftRegex.matchEntire(normalizedRaw)?.groupValues?.get(1) ?: return
                members.remove(username.lowercase(Locale.ROOT))
                pendingJoinChecks.remove(username.lowercase(Locale.ROOT))
            }

            removedRegex.matches(normalizedRaw) -> {
                val username = removedRegex.matchEntire(normalizedRaw)?.groupValues?.get(1) ?: return
                members.remove(username.lowercase(Locale.ROOT))
                pendingJoinChecks.remove(username.lowercase(Locale.ROOT))
            }

            normalizedRaw == "You left the party." ||
                normalizedRaw == "You are not currently in a party." ||
                normalizedRaw.startsWith("The party was disbanded") -> {
                role = PartyRole.NONE
                awaitingPartyList = false
                members.clear()
                pendingJoinChecks.clear()
                PartyFinderFloorTracker.clear()
            }

            normalizedRaw.startsWith("You have joined ") && normalizedRaw.endsWith("'s party!") -> {
                role = PartyRole.MEMBER
                members.clear()
            }

            normalizedRaw.startsWith("Party Leader:") -> {
                awaitingPartyList = false
                parsePartySection(normalizedRaw.removePrefix("Party Leader:"))
                if (containsOwnUsername(normalizedRaw)) {
                    role = PartyRole.LEADER
                }
                processPendingJoinChecks()
            }

            normalizedRaw.startsWith("Party Moderators:") -> {
                awaitingPartyList = false
                parsePartySection(normalizedRaw.removePrefix("Party Moderators:"))
                if (role != PartyRole.LEADER && containsOwnUsername(normalizedRaw)) {
                    role = PartyRole.MODERATOR
                }
                processPendingJoinChecks()
            }

            normalizedRaw.startsWith("Party Members:") -> {
                awaitingPartyList = false
                parsePartySection(normalizedRaw.removePrefix("Party Members:"))
                processPendingJoinChecks()
            }

            normalizedRaw.contains("You have been promoted to Party Moderator") -> role = PartyRole.MODERATOR
            normalizedRaw.contains("You have been promoted to Party Leader") -> role = PartyRole.LEADER
            normalizedRaw.contains("You are the party leader") -> {
                role = PartyRole.LEADER
                processPendingJoinChecks()
            }

            normalizedRaw.contains("You are the party moderator") -> {
                role = PartyRole.MODERATOR
                processPendingJoinChecks()
            }

            normalizedRaw.contains("You are no longer a party moderator") -> role = PartyRole.MEMBER
        }
    }

    private fun maybeQueueKick(username: String) {
        UsernameResolver.resolve(username).thenAccept { resolved ->
            client.execute {
                val profile = resolved ?: return@execute

                val localEntry = ConfigManager.findByUuid(profile.uuid)
                    ?: ConfigManager.findByUsername(profile.username)
                if (localEntry != null) {
                    ConfigManager.updateUsername(localEntry.uuid, profile.username)
                    if (localEntry.ignored) {
                        return@execute
                    }
                    handleListedJoin(profile.username, localEntry.uuid, localEntry.reason, false)
                    return@execute
                }

                val remoteReason = RemoteListManager.findReasonByUuid(profile.uuid)
                if (remoteReason != null) {
                    handleListedJoin(profile.username, profile.uuid, remoteReason, true)
                    return@execute
                }

                maybeQueueDungeonKick(profile.username, profile.uuid)
            }
        }
    }

    private fun handleListedJoin(username: String, uuid: String, reason: String, isRemote: Boolean) {
        if (ConfigManager.isAutokickEnabled(isRemote) && canAutokick()) {
            kickQueue.enqueue(KickQueue.KickTarget(username, uuid, reason, isRemote))
            return
        }

        val partyMessage = AutokickMessageFormatter.format(username, reason, isRemote)
        val message = Text.empty()
            .append(Text.literal("[SL] ").formatted(Formatting.AQUA))
            .append(Text.literal("! ").formatted(Formatting.RED, Formatting.BOLD))
            .append(Text.literal(username).formatted(Formatting.RED))
            .append(Text.literal(" ! ").formatted(Formatting.RED, Formatting.BOLD))
            .append(Text.literal("is on Vyriv's Skylist for ").formatted(Formatting.GREEN))
            .append(Text.literal(reason).formatted(Formatting.GRAY))
            .styled {
                it.withClickEvent(ClickEvent.RunCommand("/pc $partyMessage"))
                    .withHoverEvent(HoverEvent.ShowText(Text.literal("click to send this message in party chat")))
            }
        client.player?.sendMessage(message, false)
    }

    private fun maybeQueueDungeonKick(username: String, uuid: String) {
        if (!ConfigManager.hasConfiguredDungeonAutokick()) {
            return
        }

        val floor = PartyFinderFloorTracker.currentFloor() ?: return
        DungeonAutokickService.checkPlayer(username, uuid, floor).thenAccept { result ->
            if (result == null) {
                return@thenAccept
            }

            client.execute {
                if (!members.contains(username.lowercase(Locale.ROOT)) || !canAutokick()) {
                    return@execute
                }
                kickQueue.enqueue(
                    KickQueue.KickTarget(
                        username = username,
                        uuid = uuid,
                        reason = result.reason,
                        isRemote = false,
                        partyMessage = result.partyMessage,
                    ),
                )
            }
        }
    }

    private fun handleTlCheck(target: String, replyCommand: String) {
        resolveTlCheck(target, ScammerCheckService.CheckSource.PREFIX_COMMAND).thenAccept { result ->
            client.execute {
                val message = when (result) {
                    null -> "[SL] $target is not on the Skylist or scammer list"
                    else -> "[SL] ${result.displayName} is on ${result.listPhrase()} for \"${result.reason}\""
                }
                client.player?.networkHandler?.sendChatCommand("$replyCommand $message")
            }
        }
    }

    private fun resolveTlCheck(
        target: String,
        remoteSource: ScammerCheckService.CheckSource,
    ): java.util.concurrent.CompletableFuture<TlCheckResult?> {
        val trimmed = target.trim()
        findDirectTlCheckMatch(trimmed)?.let { match ->
            return java.util.concurrent.CompletableFuture.completedFuture(match)
        }

        if (isUuid(trimmed)) {
            val scammerMatch = ScammerListManager.findEntryByUuid(trimmed)?.toTlCheckResult()
            if (scammerMatch != null) {
                return java.util.concurrent.CompletableFuture.completedFuture(scammerMatch)
            }
            val local = ConfigManager.findByUuid(trimmed)?.toTlCheckResult("your Skylist")
            if (local != null) {
                return java.util.concurrent.CompletableFuture.completedFuture(local)
            }
            val remote = RemoteListManager.findEntryByUuid(trimmed)?.toTlCheckResult("Skylist")
            return java.util.concurrent.CompletableFuture.completedFuture(remote)
        }

        if (isDiscordId(trimmed)) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                ScammerListManager.findEntryByDiscordId(trimmed)?.toTlCheckResult(),
            )
        }

        return UsernameResolver.resolve(trimmed).thenApply { resolved ->
            val resolvedUuid = resolved?.uuid
            val resolvedName = resolved?.username ?: trimmed
            findDirectTlCheckMatch(resolvedUuid ?: resolvedName)
                ?: resolvedUuid?.let { findDirectTlCheckMatch(it) }
                ?: findDirectTlCheckMatch(resolvedName)
        }.thenCompose { existing ->
            if (existing != null) {
                java.util.concurrent.CompletableFuture.completedFuture(existing)
            } else {
                ScammerCheckService.checkTarget(trimmed, remoteSource).thenApply { outcome ->
                    outcome.verdict?.toTlCheckResult()
                }
            }
        }
    }

    private fun findDirectTlCheckMatch(target: String): TlCheckResult? {
        val scammerByUuid = ScammerListManager.findEntryByUuid(target)
        if (scammerByUuid != null) {
            return scammerByUuid.toTlCheckResult()
        }

        val scammerByUsername = ScammerListManager.findEntryByUsername(target)
        if (scammerByUsername != null) {
            return scammerByUsername.toTlCheckResult()
        }

        val localByUuid = ConfigManager.findByUuid(target)
        if (localByUuid != null) {
            return localByUuid.toTlCheckResult("your Skylist")
        }

        val localByUsername = ConfigManager.findByUsername(target)
        if (localByUsername != null) {
            return localByUsername.toTlCheckResult("your Skylist")
        }

        val remoteByUuid = RemoteListManager.findEntryByUuid(target)
        if (remoteByUuid != null) {
            return remoteByUuid.toTlCheckResult("Skylist")
        }

        val remoteByUsername = RemoteListManager.findEntryByUsername(target)
        if (remoteByUsername != null) {
            return remoteByUsername.toTlCheckResult("Skylist")
        }

        return null
    }

    private fun replyChatCommand(normalizedRaw: String): String? {
        val lower = normalizedRaw.lowercase(Locale.ROOT)
        return when {
            lower.startsWith("party >") || lower.contains(" party >") -> "pc"
            lower.startsWith("guild >") || lower.contains(" guild >") -> "gc"
            else -> null
        }
    }

    private fun requestPartyListRefresh() {
        if (awaitingPartyList || client.player == null) {
            return
        }

        awaitingPartyList = true
        client.player?.networkHandler?.sendChatCommand("pl")
    }

    private fun recordDetectedDungeonFloor() {
        DungeonFloorDetector.detectCurrentFloor(client)?.let(PartyFinderFloorTracker::record)
    }

    private fun processPendingJoinChecks() {
        if (!shouldAssumeLeader() && role == PartyRole.NONE) {
            return
        }

        pendingJoinChecks.toList().forEach { username ->
            pendingJoinChecks.remove(username)
            maybeQueueKick(username)
        }
    }

    private fun parsePartySection(section: String) {
        usernameRegex.findAll(section)
            .map { it.groupValues[1] }
            .filter { !it.equals("none", ignoreCase = true) }
            .forEach { username ->
                members.add(username.lowercase(Locale.ROOT))
            }
    }

    private fun containsOwnUsername(raw: String): Boolean {
        val self = client.session.username
        return usernameRegex.findAll(raw).any { it.groupValues[1].equals(self, ignoreCase = true) }
    }

    private fun isAllowedToKick(): Boolean =
        role == PartyRole.LEADER

    private fun canAutokick(): Boolean = shouldAssumeLeader() || isAllowedToKick()

    private fun shouldAssumeLeader(): Boolean = ConfigManager.isAssumePartyLeader()

    private fun isRecentDuplicate(raw: String): Boolean {
        val now = System.currentTimeMillis()
        recentMessages.entries.removeIf { now - it.value > 250L }
        val previous = recentMessages.put(raw, now) ?: return false
        return now - previous <= 250L
    }

    private fun isUuid(value: String): Boolean =
        Regex("""^[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}$""").matches(value)

    private fun isDiscordId(value: String): Boolean =
        Regex("""^\d{17,20}$""").matches(value)

    private fun PlayerEntry.toTlCheckResult(listName: String): TlCheckResult =
        TlCheckResult(username, listName, reason, caseTimeMillis = ts)

    private fun RemoteListManager.RemoteEntry.toTlCheckResult(listName: String): TlCheckResult =
        TlCheckResult(username, listName, reason, caseTimeMillis = ts)

    private fun ScammerListManager.ScammerEntry.toTlCheckResult(): TlCheckResult =
        TlCheckResult(username, "SBZ scammer", reason, severity.color, creationTimeMillis)

    private fun ScammerCheckService.CheckResult.toTlCheckResult(): TlCheckResult =
        TlCheckResult(username, sourceLabel, reason, severityColor, caseTimeMillis)

    private enum class PartyRole {
        NONE,
        MEMBER,
        MODERATOR,
        LEADER,
    }

    private data class TlCheckResult(
        val displayName: String,
        val listName: String,
        val reason: String,
        val severityColor: Int? = null,
        val caseTimeMillis: Long? = null,
    ) {
        fun listPhrase(): String =
            when {
                listName.startsWith("your ", ignoreCase = true) -> "$listName list"
                else -> "the $listName list"
            }
    }
}
