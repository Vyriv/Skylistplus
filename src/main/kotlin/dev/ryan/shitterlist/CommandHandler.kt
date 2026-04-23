package dev.ryan.throwerlist

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.mojang.brigadier.suggestion.Suggestions
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.command.CommandSource
import net.minecraft.text.ClickEvent
import net.minecraft.text.HoverEvent
import net.minecraft.text.MutableText
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.net.URI
import java.time.Instant
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object CommandHandler {
    private enum class ListScope(val commandName: String, val heading: String, val emptyMessage: String, val missingEntryMessagePrefix: String) {
        LOCAL("local", "Current local Skylist entries: ", "Local Skylist is empty", "No local Skylist entry found for "),
        REMOTE("remote", "Current remote Skylist entries: ", "Remote Skylist is empty", "No remote Skylist entry found for "),
        ALL("all", "Current Skylist entries: ", "Skylist is empty", "No Skylist entry found for "),
    }

    private val minecraftUsernameRegex = Regex("^[A-Za-z0-9_]{1,16}$")
    private const val entriesPerPage = 5
    private const val maxReasonPreviewLength = 48
    private const val clearConfirmationWindowMillis = 15_000L
    private const val throwLobbyDelayMillis = 700L
    private const val throwLimboDelayMillis = 1_400L
    private const val throwBanScreenDelayMillis = 1_900L
    private val ownerUuid: UUID = UUID.fromString("e8a20d35-b48b-4fa1-bd92-4df9049ae76f")
    private val addedTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yy")
    private val throwScheduler = Executors.newSingleThreadScheduledExecutor()
    private val throwRequestCounter = AtomicLong()

    @Volatile
    private var clearConfirmationArmedAt = 0L

    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(buildRoot("skylist"))
            dispatcher.register(buildRoot("sl"))
            dispatcher.register(literal("throw").executes(::openSillyScreen))
        }
    }

    private fun buildRoot(name: String) =
        literal(name)
            .executes { openListGui(ThrowerListListScreen.Tab.ALL) }
            .then(literal("add")
                .then(argument("target", StringArgumentType.word())
                    .executes(::addPlayerMissingReason)
                    .then(argument("reason", StringArgumentType.greedyString())
                        .suggests(::suggestCommonReasons)
                        .executes(::addPlayer),
                    ),
                ),
            )
            .then(literal("remove")
                .then(argument("target", StringArgumentType.word())
                    .executes(::removePlayer),
                ),
            )
            .then(literal("edit")
                .then(argument("username", StringArgumentType.word())
                    .then(argument("reason", StringArgumentType.greedyString())
                        .suggests(::suggestCommonReasons)
                        .executes(::editPlayer),
                    ),
                ),
            )
            .then(literal("list")
                .executes { openListGui(ThrowerListListScreen.Tab.ALL) }
                .then(literal("local").executes { openListGui(ThrowerListListScreen.Tab.LOCAL) })
                .then(literal("remote").executes { openListGui(ThrowerListListScreen.Tab.REMOTE) })
                .then(literal("scammers").executes { openListGui(ThrowerListListScreen.Tab.SCAMMERS) })
                .then(literal("all").executes { openListGui(ThrowerListListScreen.Tab.ALL) }),
            )
            .then(literal("check")
                .then(argument("target", StringArgumentType.word())
                    .suggests(::suggestCheckTargets)
                    .executes(::checkTarget),
                ),
            )
            .then(literal("remindme")
                .then(argument("input", StringArgumentType.greedyString())
                    .executes(::scheduleReminder),
                ),
            )
            .then(literal("gui")
                .executes { openListGui(ThrowerListListScreen.Tab.ALL) }
                .then(argument("target", StringArgumentType.word())
                    .suggests(::suggestCheckTargets)
                    .executes(::openListGuiWithSearch),
                ),
            )
            .then(literal("settings")
                .executes(::printSettingsHelp)
                .then(literal("update").executes(::installLatestUpdate))
                .then(literal("dev")
                    .then(literal("updatelist").executes(::updateRemoteList))
                    .then(literal("assumepartyleader")
                        .then(literal("true").executes { updateAssumePartyLeader(it, true) })
                        .then(literal("false").executes { updateAssumePartyLeader(it, false) }),
                    )
                    .then(literal("versioninfo").executes(::printVersionInfo))
                    .then(literal("sethypixelapikey")
                        .then(argument("key", StringArgumentType.word())
                            .executes(::setHypixelApiKey),
                        ),
                    )
                    .then(literal("getuuid")
                        .then(argument("username", StringArgumentType.word())
                            .executes(::printUuidInfo),
                        ),
                    )
                    .then(literal("getdiscord")
                        .then(argument("username", StringArgumentType.word())
                            .executes(::printDiscordInfo),
                        ),
                    )
                    .then(literal("addtestscammer")
                        .then(argument("name", StringArgumentType.word())
                            .then(argument("reasonAndDate", StringArgumentType.greedyString())
                                .executes(::addTestScammer),
                            ),
                        ),
                    )
                    .then(literal("autoclear")
                        .then(literal("IKnowWhatImDoing").executes(::confirmClear)),
                    ),
                ),
            )

    private fun printHelp(source: FabricClientCommandSource): Int {
        source.sendFeedback(Text.literal("Skylist commands:").formatted(Formatting.GOLD))
        source.sendFeedback(helpLine("add", "<username>", "<reason>"))
        source.sendFeedback(helpLine("remove", "<username>"))
        source.sendFeedback(helpLine("edit", "<username>", "<reason>"))
        source.sendFeedback(helpLine("check", "<username/uuid/discordId>"))
        source.sendFeedback(helpLine("remindme", "<time>", "<reminder>"))
        source.sendFeedback(helpLine("gui"))
        source.sendFeedback(helpLine("list", "<local/remote/scammers/all>"))
        source.sendFeedback(helpLine("settings"))
        return Command.SINGLE_SUCCESS
    }

    private fun printListHelp(context: CommandContext<FabricClientCommandSource>): Int {
        context.source.sendFeedback(
            tlMessage(
                Text.literal("Usage: ").formatted(Formatting.GREEN)
                    .append(helpLine("list", "<local/remote/scammers/all>")),
            ),
        )
        return Command.SINGLE_SUCCESS
    }

    private fun openListGui(tab: ThrowerListListScreen.Tab): Int {
        ThrowerListGuiLauncher.openMainScreen(tab)
        return Command.SINGLE_SUCCESS
    }

    private fun openListGuiWithSearch(context: CommandContext<FabricClientCommandSource>): Int {
        ThrowerListGuiLauncher.openMainScreen(
            ThrowerListListScreen.Tab.ALL,
            StringArgumentType.getString(context, "target"),
        )
        return Command.SINGLE_SUCCESS
    }

    private fun openSillyScreen(context: CommandContext<FabricClientCommandSource>): Int {
        triggerSillyDisconnectSequence()
        return Command.SINGLE_SUCCESS
    }

    fun triggerSillyDisconnectSequence() {
        val requestId = throwRequestCounter.incrementAndGet()
        ThrowerListMod.client.execute {
            val client = ThrowerListMod.client
            val canLeaveServerFirst = client.player != null && client.networkHandler != null

            if (canLeaveServerFirst) {
                client.player?.networkHandler?.sendChatCommand("l")
                throwScheduler.schedule({
                    ThrowerListMod.client.execute {
                        if (throwRequestCounter.get() != requestId) {
                            return@execute
                        }

                        client.player?.networkHandler?.sendChatCommand("limbo")
                    }
                }, throwLimboDelayMillis, TimeUnit.MILLISECONDS)
                throwScheduler.schedule({
                    ThrowerListMod.client.execute {
                        openSillyDisconnectScreen(requestId)
                    }
                }, throwBanScreenDelayMillis, TimeUnit.MILLISECONDS)
                return@execute
            }

            openSillyDisconnectScreen(requestId)
        }
    }

    private fun openSillyDisconnectScreen(requestId: Long) {
        if (throwRequestCounter.get() != requestId) {
            return
        }

        SillyBanScreen.show(ThrowerListMod.client)
    }

    private fun printSettingsHelp(context: CommandContext<FabricClientCommandSource>): Int {
        context.source.sendFeedback(Text.literal("Skylist settings:").formatted(Formatting.GOLD))
        context.source.sendFeedback(helpLine("settings", "update"))
        context.source.sendFeedback(helpLine("settings", "exportlist"))
        context.source.sendFeedback(helpLine("settings", "importlist", "<code>"))
        context.source.sendFeedback(helpLine("settings", "autokick", "on/off"))
        context.source.sendFeedback(helpLine("settings", "notifications", "on/off"))
        return Command.SINGLE_SUCCESS
    }

    private fun exportLocalList(context: CommandContext<FabricClientCommandSource>): Int {
        val source = context.source
        val localEntries = ConfigManager.listPlayers()
        if (localEntries.isEmpty()) {
            source.sendError(tlMessage("You do not have any local entries to export"))
            return 0
        }

        val exportCode = ListShareCodec.export(localEntries)
        source.sendFeedback(
            tlMessage(
                Text.literal("Generated a local list share code. ")
                    .formatted(Formatting.GREEN)
                    .append(
                        Text.literal("Click here to copy it")
                            .formatted(Formatting.AQUA, Formatting.UNDERLINE)
                            .styled {
                                it.withClickEvent(ClickEvent.CopyToClipboard(exportCode))
                                    .withHoverEvent(HoverEvent.ShowText(Text.literal("Copy export code")))
                            },
                    ),
            ),
        )
        source.sendFeedback(
            Text.literal(exportCode).formatted(Formatting.DARK_GRAY),
        )
        return Command.SINGLE_SUCCESS
    }

    private fun importSharedList(context: CommandContext<FabricClientCommandSource>): Int {
        val source = context.source
        val code = StringArgumentType.getString(context, "code")
        val decoded = ListShareCodec.decode(code)
        if (decoded.error != null) {
            source.sendError(tlMessage(decoded.error))
            return 0
        }

        val filteredEntries = ProtectedSkylistEntries.filterImportEntries(decoded.entries.map { it.copy() })
        val blockedCount = decoded.entries.size - filteredEntries.size
        val result = ConfigManager.importPlayers(filteredEntries)
        if (result.importedCount <= 0) {
            source.sendFeedback(
                tlMessage(
                    Text.literal("No new local entries were imported. ")
                        .formatted(Formatting.YELLOW)
                        .append(Text.literal("Skipped ${result.skippedCount + blockedCount}.").formatted(Formatting.GRAY)),
                ),
            )
            return Command.SINGLE_SUCCESS
        }

        val skippedCount = result.skippedCount + blockedCount
        source.sendFeedback(
            tlMessage(
                Text.literal("Imported ${result.importedCount} local entries")
                    .formatted(Formatting.GREEN)
                    .append(
                        if (skippedCount > 0) {
                            Text.literal(" and skipped $skippedCount blocked/duplicate entries").formatted(Formatting.YELLOW)
                        } else {
                            Text.empty()
                        },
                    ),
            ),
        )
        return Command.SINGLE_SUCCESS
    }

    private fun addPlayerMissingReason(context: CommandContext<FabricClientCommandSource>): Int {
        context.source.sendError(
            tlMessage(
                Text.literal("A ").formatted(Formatting.WHITE)
                    .append(Text.literal("reason").formatted(Formatting.GRAY))
                    .append(Text.literal(" is required. Usage: ").formatted(Formatting.WHITE))
                    .append(helpLine("add", "<IGN/UUID>", "<reason>")),
            ),
        )
        return 0
    }

    private fun addPlayer(context: CommandContext<FabricClientCommandSource>): Int {
        val source = context.source
        val target = StringArgumentType.getString(context, "target").trim()
        val reason = StringArgumentType.getString(context, "reason")

        resolveMinecraftTarget(target).thenAccept { resolved ->
            ThrowerListMod.client.execute {
                if (resolved == null) {
                    source.sendError(tlMessage("Could not resolve Minecraft user: $target"))
                    return@execute
                }

                val duplicate = ConfigManager.findByUuid(resolved.uuid) ?: ConfigManager.findByUsername(resolved.username)
                if (duplicate != null) {
                    source.sendError(tlMessage("${resolved.username} is already on your Skylist"))
                    return@execute
                }

                if (ProtectedSkylistEntries.isProtected(resolved.username, resolved.uuid)) {
                    source.sendError(tlMessage(ProtectedSkylistEntries.rejectionMessage()))
                    return@execute
                }

                if (ContentManager.isProtectedCreditUsername(resolved.username)) {
                    source.sendError(tlMessage("My beta testers are not throwers :("))
                    return@execute
                }

                ConfigManager.addPlayer(
                    PlayerEntry(
                        username = resolved.username,
                        uuid = resolved.uuid,
                        reason = reason,
                    ),
                )
                source.sendFeedback(
                    tlMessage(
                        Text.literal("Added ").formatted(Formatting.GREEN)
                            .append(Text.literal(resolved.username).formatted(Formatting.RED))
                            .append(Text.literal(" to your Skylist ").formatted(Formatting.GREEN))
                            .append(Text.literal("for reason: ").formatted(Formatting.GREEN))
                            .append(Text.literal("\"").formatted(Formatting.DARK_GRAY))
                            .append(Text.literal(reason).formatted(Formatting.GRAY))
                            .append(Text.literal("\"").formatted(Formatting.DARK_GRAY)),
                    ),
                )
            }
        }

        return Command.SINGLE_SUCCESS
    }

    private fun removePlayer(context: CommandContext<FabricClientCommandSource>): Int {
        val source = context.source
        val target = StringArgumentType.getString(context, "target").trim()
        resolveMinecraftTarget(target).thenAccept { resolved ->
            ThrowerListMod.client.execute {
                val local = when {
                    resolved != null -> ConfigManager.findByUuid(resolved.uuid) ?: ConfigManager.findByUsername(resolved.username)
                    else -> ConfigManager.findByUuid(target) ?: ConfigManager.findByUsername(target)
                }
                if (local != null) {
                    ConfigManager.removePlayer(local.username)
                    source.sendFeedback(
                        tlMessage(
                            Text.literal("Removed ").formatted(Formatting.GREEN)
                                .append(Text.literal(local.username).formatted(Formatting.RED))
                                .append(Text.literal(" from your Skylist").formatted(Formatting.GREEN)),
                        ),
                    )
                    return@execute
                }

                val remoteEntry = when {
                    resolved != null -> RemoteListManager.findEntryByUuid(resolved.uuid) ?: RemoteListManager.findEntryByUsername(resolved.username)
                    else -> RemoteListManager.findEntryByUuid(target) ?: RemoteListManager.findEntryByUsername(target)
                }
                if (remoteEntry != null) {
                    hideRemoteEntry(source, remoteEntry)
                } else {
                    source.sendError(tlMessage("No thrower entry found for $target"))
                }
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private fun editPlayer(context: CommandContext<FabricClientCommandSource>): Int {
        val username = StringArgumentType.getString(context, "username")
        val newReason = StringArgumentType.getString(context, "reason")
        val updated = ConfigManager.editReason(username, newReason)
        if (updated == null) {
            context.source.sendError(Text.literal("$username is not on your Skylist"))
            return 0
        }

        context.source.sendFeedback(
            tlMessage(
                Text.literal("Updated ").formatted(Formatting.GREEN)
                    .append(Text.literal(updated.username).formatted(Formatting.GRAY))
                    .append(Text.literal("'s reason to: ").formatted(Formatting.GREEN))
                    .append(Text.literal(newReason).formatted(Formatting.GRAY)),
            ),
        )
        return Command.SINGLE_SUCCESS
    }

    private fun updateToggle(context: CommandContext<FabricClientCommandSource>, enabled: Boolean): Int {
        ConfigManager.setAutokickEnabled(enabled)
        val state = if (enabled) "ENABLED" else "DISABLED"
        context.source.sendFeedback(
            tlMessage(
                Text.literal("Skylist local and remote autokick are now ").formatted(Formatting.GREEN)
                    .append(Text.literal(state).formatted(if (enabled) Formatting.GREEN else Formatting.RED)),
            ),
        )
        return Command.SINGLE_SUCCESS
    }

    private fun updateNotifications(context: CommandContext<FabricClientCommandSource>, enabled: Boolean): Int {
        ConfigManager.setLobbyNotificationsEnabled(enabled)
        val state = if (enabled) "ENABLED" else "DISABLED"
        context.source.sendFeedback(
            tlMessage(
                Text.literal("Skylist lobby notifications are now ").formatted(Formatting.GREEN)
                    .append(Text.literal(state).formatted(if (enabled) Formatting.GREEN else Formatting.RED)),
            ),
        )
        return Command.SINGLE_SUCCESS
    }

    private fun updateAssumePartyLeader(context: CommandContext<FabricClientCommandSource>, enabled: Boolean): Int {
        ConfigManager.setAssumePartyLeader(enabled)
        context.source.sendFeedback(
            tlMessage(
                Text.literal("Skylist assume party leader is now ").formatted(Formatting.GREEN)
                    .append(Text.literal(enabled.toString().uppercase()).formatted(if (enabled) Formatting.GREEN else Formatting.RED)),
            ),
        )
        return Command.SINGLE_SUCCESS
    }

    private fun printVersionInfo(context: CommandContext<FabricClientCommandSource>): Int {
        val currentVersion = SkylistPlusRuntimeVersion.currentVersion()
        val latestKnownVersion = GitHubUpdateChecker.latestKnownVersionForCurrentMinecraft()
        context.source.sendFeedback(
            Text.empty()
                .append(Text.literal("[SL] ").formatted(Formatting.AQUA))
                .append(Text.literal("Installed version is currently ").formatted(Formatting.GREEN))
                    .append(
                        Text.literal(currentVersion)
                            .formatted(Formatting.YELLOW)
                            .styled {
                            it.withClickEvent(ClickEvent.OpenUrl(URI.create(ThrowerListLinks.githubReleasesUrl)))
                                 .withHoverEvent(HoverEvent.ShowText(Text.literal("Open Skylist+ GitHub releases")))
                            },
                    )
                .append(
                    latestKnownVersion?.let {
                        Text.literal(" Latest known: ").formatted(Formatting.GREEN)
                            .append(Text.literal(it).formatted(Formatting.AQUA))
                    } ?: Text.empty(),
                ),
        )
        return Command.SINGLE_SUCCESS
    }

    private fun checkTarget(context: CommandContext<FabricClientCommandSource>): Int {
        val source = context.source
        val target = StringArgumentType.getString(context, "target").trim()
        if (target.isEmpty()) {
            source.sendError(tlMessage("Provide a username, UUID, or Discord ID to check"))
            return 0
        }

        resolveCheckTarget(target).thenAccept { result ->
            ThrowerListMod.client.execute {
                if (result == null) {
                    source.sendFeedback(tlMessage("$target is not on the thrower or scammer list"))
                    return@execute
                }

                source.sendFeedback(tlMessage(buildCheckResultText(result)))
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private fun installLatestUpdate(context: CommandContext<FabricClientCommandSource>): Int {
        GitHubUpdateChecker.installLatestUpdate(context.source)
        return Command.SINGLE_SUCCESS
    }

    private fun printUuidInfo(context: CommandContext<FabricClientCommandSource>): Int {
        val source = context.source
        val username = StringArgumentType.getString(context, "username")
        UsernameResolver.resolve(username).thenAccept { resolved ->
            ThrowerListMod.client.execute {
                if (resolved == null) {
                    source.sendError(tlMessage("Could not resolve username: $username"))
                    return@execute
                }

                source.sendFeedback(
                    tlMessage(
                        Text.literal("UUID for ").formatted(Formatting.GREEN)
                            .append(Text.literal(resolved.username).formatted(Formatting.GRAY))
                            .append(Text.literal(" is ").formatted(Formatting.GREEN))
                            .append(
                                Text.literal(resolved.uuid)
                                    .formatted(Formatting.AQUA, Formatting.UNDERLINE)
                                    .styled {
                                        it.withClickEvent(ClickEvent.OpenUrl(URI.create("https://sky.shiiyu.moe/stats/${resolved.username}")))
                                            .withHoverEvent(
                                                HoverEvent.ShowText(
                                                    Text.literal("Open ${resolved.username} on SkyCrypt"),
                                                ),
                                            )
                                    },
                            ),
                    ),
                )
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private fun setHypixelApiKey(context: CommandContext<FabricClientCommandSource>): Int {
        if (!isOwner(context.source)) {
            context.source.sendError(ownerOnlyError())
            return 0
        }
        val apiKey = StringArgumentType.getString(context, "key")
        ConfigManager.setHypixelApiKey(apiKey)
        context.source.sendFeedback(
            tlMessage(
                Text.literal("Saved Hypixel API key for local dev commands.").formatted(Formatting.GREEN),
            ),
        )
        return Command.SINGLE_SUCCESS
    }

    private fun scheduleReminder(context: CommandContext<FabricClientCommandSource>): Int {
        val source = context.source
        val input = StringArgumentType.getString(context, "input").trim()
        val parsed = parseReminderInput(input)
        if (parsed == null) {
            source.sendError(tlMessage("Usage: /skylist remindme <time> <reminder>"))
            return 0
        }

        ReminderManager.schedule(parsed.delayMillis, parsed.reminder)
        source.sendFeedback(
            tlMessage(
                Text.literal("Reminder set for ").formatted(Formatting.GREEN)
                    .append(Text.literal(parsed.canonicalDuration).formatted(Formatting.GRAY))
                    .append(Text.literal(": ").formatted(Formatting.GREEN))
                    .append(Text.literal(parsed.reminder).formatted(Formatting.WHITE)),
            ),
        )
        return Command.SINGLE_SUCCESS
    }

    private fun printDiscordInfo(context: CommandContext<FabricClientCommandSource>): Int {
        val source = context.source
        if (!isOwner(source)) {
            source.sendError(ownerOnlyError())
            return 0
        }
        val username = StringArgumentType.getString(context, "username")

        UsernameResolver.resolve(username).thenAccept { resolved ->
            if (resolved == null) {
                ThrowerListMod.client.execute {
                    source.sendError(
                        tlMessage("Could not resolve username: $username"),
                    )
                }
                return@thenAccept
            }

            UsernameResolver.resolveLinkedDiscord(resolved.uuid).thenAccept { lookup ->
                ThrowerListMod.client.execute {
                    if (!lookup.failureReason.isNullOrBlank()) {
                        source.sendError(
                            tlMessage("Discord lookup failed for ${resolved.username}: ${lookup.failureReason}"),
                        )
                        return@execute
                    }

                    if (lookup.discord.isNullOrBlank()) {
                        source.sendError(
                            tlMessage("No linked Discord found for ${resolved.username}"),
                        )
                        return@execute
                    }

                    source.sendFeedback(
                        tlMessage(
                            Text.literal("Linked Discord for ").formatted(Formatting.GREEN)
                                .append(Text.literal(resolved.username).formatted(Formatting.GRAY))
                                .append(Text.literal(": ").formatted(Formatting.GREEN))
                                .append(Text.literal(lookup.discord).formatted(Formatting.AQUA)),
                        ),
                    )
                }
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private fun addTestScammer(context: CommandContext<FabricClientCommandSource>): Int {
        val source = context.source
        if (!isOwner(source)) {
            source.sendError(ownerOnlyError())
            return 0
        }

        val name = StringArgumentType.getString(context, "name")
        val reasonAndDate = StringArgumentType.getString(context, "reasonAndDate").trim()
        val splitAt = reasonAndDate.lastIndexOf(' ')
        if (splitAt <= 0 || splitAt >= reasonAndDate.lastIndex) {
            source.sendError(tlMessage("Usage: /skylist settings dev addtestscammer <name> <reason> <date>"))
            return 0
        }
        val reason = reasonAndDate.substring(0, splitAt).trim()
        val date = reasonAndDate.substring(splitAt + 1).trim()
        val createdAtMillis = parseScammerDate(date)
        if (createdAtMillis == null) {
            source.sendError(tlMessage("Use a valid date like 2026-04-11, 2026-4-11, 11/04/26, unix-seconds, or unix-millis"))
            return 0
        }

        resolveMinecraftTarget(name).thenAccept { resolved ->
            ThrowerListMod.client.execute {
                val username = resolved?.username ?: name
                val uuid = resolved?.uuid ?: name
                val entry = ScammerListManager.addTestScammer(username, uuid, reason, createdAtMillis)
                source.sendFeedback(tlMessage("${entry.username} was added to the SBZ scammer cache for \"${entry.reason}\""))
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private fun isOwner(source: FabricClientCommandSource): Boolean =
        source.player.gameProfile.id == ownerUuid

    private fun ownerOnlyError(): MutableText = tlMessage("Only the mod owner can use this command")

    private fun resolveMinecraftTarget(target: String): CompletableFuture<UsernameResolver.ResolvedProfile?> {
        val trimmed = target.trim()
        return when {
            isUuid(trimmed) -> UsernameResolver.resolveUuid(trimmed).thenApply { username ->
                username?.let { UsernameResolver.ResolvedProfile(it, trimmed.lowercase()) }
            }
            else -> UsernameResolver.resolve(trimmed)
        }
    }

    private fun parseScammerDate(raw: String): Long? {
        val trimmed = raw.trim()
        trimmed.toLongOrNull()?.let { numeric ->
            return when {
                numeric > 9_999_999_999L -> numeric
                numeric > 0L -> numeric * 1000L
                else -> null
            }
        }

        val zone = ZoneId.systemDefault()
        val patterns = listOf("yyyy-MM-dd", "yyyy-M-d", "dd/MM/yy", "dd/MM/yyyy")
        patterns.forEach { pattern ->
            runCatching {
                java.time.LocalDate.parse(trimmed, DateTimeFormatter.ofPattern(pattern))
                    .atStartOfDay(zone)
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun printDevelopers(context: CommandContext<FabricClientCommandSource>): Int {
        val source = context.source
        requestCreditsLines(
            onReady = { lines ->
                source.sendFeedback(Text.empty().also { block ->
                    lines.forEachIndexed { index, line ->
                        if (index > 0) {
                            block.append(Text.literal("\n"))
                        }
                        block.append(line.copy())
                    }
                })
            },
            onError = { error ->
                source.sendError(Text.literal(error))
            },
        )

        return Command.SINGLE_SUCCESS
    }

    fun requestCreditsLines(onReady: (List<Text>) -> Unit, onError: (String) -> Unit = {}) {
        val developers = ContentManager.developerCredits()
        if (developers.isEmpty()) {
            onError("No developers configured")
            return
        }

        onReady(buildCreditsLines(developers, ContentManager.betaTesterCredits()))
    }

    private fun buildCreditsLines(
        developers: List<ContentManager.LoadedDeveloperCredit>,
        betaTesters: List<ContentManager.LoadedCreditEntry>,
    ): List<Text> {
        val lines = mutableListOf<Text>()
        lines += Text.literal("--------------------------------------------------").formatted(Formatting.WHITE)
        lines += Text.literal("Skylist Contributors").formatted(Formatting.AQUA, Formatting.UNDERLINE)
        lines += Text.empty()
        lines += Text.empty()
            .append(Text.literal("Developers").formatted(Formatting.AQUA))
            .append(Text.literal(":").formatted(Formatting.WHITE))
        developers.forEach { developer ->
            lines += buildDeveloperCreditLine(developer)
        }
        lines += Text.empty()
        lines += Text.empty()
            .append(Text.literal("Beta Testers").formatted(Formatting.AQUA))
            .append(Text.literal(":").formatted(Formatting.WHITE))
        betaTesters.forEach { tester ->
            lines += buildBetaTesterLine(tester)
        }
        lines += Text.literal("--------------------------------------------------").formatted(Formatting.WHITE)
        return lines
    }

    private fun buildDeveloperCreditLine(entry: ContentManager.LoadedDeveloperCredit): MutableText {
        val line = gradientText(
            "${entry.username} (${entry.label}) - ${entry.role}",
            entry.leftColor,
            entry.rightColor,
        ).styled { style ->
            var output = style
            entry.linkUrl?.let { url ->
                output = output.withClickEvent(ClickEvent.OpenUrl(URI.create(url)))
            }
            entry.linkHover?.let { hover ->
                output = output.withHoverEvent(HoverEvent.ShowText(Text.literal(hover)))
            }
            output
        }

        return Text.empty()
            .append(Text.literal("- ").formatted(Formatting.WHITE))
            .append(line)
    }

    private fun buildBetaTesterLine(entry: ContentManager.LoadedCreditEntry): MutableText {
        return Text.empty()
            .append(Text.literal("- ").formatted(Formatting.WHITE))
            .append(Text.literal("${entry.username} (${entry.label})").formatted(Formatting.AQUA))
            .append(Text.literal(" - ").formatted(Formatting.WHITE))
            .append(Text.literal(entry.role).formatted(Formatting.YELLOW))
    }

    private fun buildListScopeBranch(
        name: String,
        scope: ListScope,
        suggester: (
            CommandContext<FabricClientCommandSource>,
            SuggestionsBuilder,
        ) -> CompletableFuture<Suggestions>,
    ) = literal(name)
        .executes { listPlayers(it, scope) }
        .then(argument("page", IntegerArgumentType.integer(1))
            .executes { listPlayers(it, scope) },
        )
        .then(argument("username", StringArgumentType.word())
            .suggests(suggester)
            .executes { listPlayers(it, scope) },
        )

    private fun listPlayers(context: CommandContext<FabricClientCommandSource>, scope: ListScope): Int {
        val requestedUsername = runCatching { StringArgumentType.getString(context, "username") }.getOrNull()
        if (requestedUsername != null) {
            return listSinglePlayer(context.source, requestedUsername, scope)
        }

        val players = ConfigManager.listPlayers()
        val remoteEntries = when (scope) {
            ListScope.LOCAL -> emptyList()
            ListScope.REMOTE -> RemoteListManager.listEntries()
            ListScope.ALL -> {
                val localUuids = players.map { it.uuid.lowercase() }.toHashSet()
                RemoteListManager.listEntries().filterNot { it.uuid.lowercase() in localUuids }
            }
        }
        val page = runCatching { IntegerArgumentType.getInteger(context, "page") }.getOrDefault(1)

        val entries = when (scope) {
            ListScope.LOCAL -> players.map { ListEntry(it.username, it.reason, it.ts) }
            ListScope.REMOTE -> remoteEntries.map { ListEntry(it.username, it.reason, it.ts) }
            ListScope.ALL -> buildList {
                players.forEach { entry ->
                    add(ListEntry(entry.username, entry.reason, entry.ts))
                }
                remoteEntries.forEach { entry ->
                    add(ListEntry(entry.username, entry.reason, entry.ts))
                }
            }
        }
        if (entries.isEmpty()) {
            context.source.sendFeedback(tlMessage(scope.emptyMessage))
            return Command.SINGLE_SUCCESS
        }

        val totalPages = ((entries.size - 1) / entriesPerPage) + 1
        if (page > totalPages) {
            context.source.sendError(Text.literal("Page $page does not exist. Max page is $totalPages"))
            return 0
        }

        val fromIndex = (page - 1) * entriesPerPage
        val toIndex = minOf(fromIndex + entriesPerPage, entries.size)

        context.source.sendFeedback(listSeparator())
        context.source.sendFeedback(
            Text.empty()
                .append(Text.literal(scope.heading).formatted(Formatting.AQUA))
                .append(Text.literal(entries.size.toString()).formatted(Formatting.WHITE)),
        )
        context.source.sendFeedback(Text.empty())
        entries.subList(fromIndex, toIndex).forEach { entry ->
            context.source.sendFeedback(buildListEntry(entry))
        }
        context.source.sendFeedback(listSeparator())
        context.source.sendFeedback(buildPageControls(scope, page, totalPages))
        context.source.sendFeedback(listSeparator())
        return Command.SINGLE_SUCCESS
    }

    private fun listSinglePlayer(source: FabricClientCommandSource, username: String, scope: ListScope): Int {
        val localEntry = ConfigManager.findByUsername(username)
        val remoteEntry = RemoteListManager.findEntryByUsername(username)
        val entry = when {
            scope == ListScope.LOCAL && localEntry != null -> ListEntry(localEntry.username, localEntry.reason, localEntry.ts)
            scope == ListScope.REMOTE && remoteEntry != null -> ListEntry(remoteEntry.username, remoteEntry.reason, remoteEntry.ts)
            scope == ListScope.ALL && localEntry != null -> ListEntry(localEntry.username, localEntry.reason, localEntry.ts)
            scope == ListScope.ALL && remoteEntry != null -> ListEntry(remoteEntry.username, remoteEntry.reason, remoteEntry.ts)
            else -> null
        }

        if (entry == null) {
            source.sendError(tlMessage(scope.missingEntryMessagePrefix + username))
            return 0
        }

        source.sendFeedback(listSeparator())
        source.sendFeedback(
            Text.empty()
                .append(
                    Text.literal(
                        when (scope) {
                            ListScope.LOCAL -> "Local thrower entry for "
                            ListScope.REMOTE -> "Remote thrower entry for "
                            ListScope.ALL -> "Thrower entry for "
                        },
                    ).formatted(Formatting.AQUA),
                )
                .append(Text.literal(entry.username).formatted(Formatting.WHITE)),
        )
        source.sendFeedback(Text.empty())
        source.sendFeedback(buildListEntry(entry))
        source.sendFeedback(listSeparator())
        return Command.SINGLE_SUCCESS
    }

    private fun updateRemoteList(context: CommandContext<FabricClientCommandSource>): Int {
        val source = context.source
        CompletableFuture.allOf(
            RemoteListManager.refreshAsync(),
            ScammerListManager.refreshAsync(),
        ).whenComplete { _, throwable ->
            ThrowerListMod.client.execute {
                if (throwable != null) {
                    source.sendError(tlMessage("Failed to update Skylist."))
                    return@execute
                }

                source.sendFeedback(
                    tlMessage(Text.literal("Skylist updated.").formatted(Formatting.GREEN)),
                )
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private fun confirmClear(context: CommandContext<FabricClientCommandSource>): Int {
        if (ConfigManager.isPlayerListEmpty()) {
            clearConfirmationArmedAt = 0L
            context.source.sendFeedback(tlMessage("Skylist is already empty"))
            return Command.SINGLE_SUCCESS
        }

        val now = System.currentTimeMillis()
        if (now - clearConfirmationArmedAt > clearConfirmationWindowMillis) {
            clearConfirmationArmedAt = now
            context.source.sendFeedback(
                Text.literal("[SL] You are about to clear ALL entries you added to the Skylist. If you are sure you want to do this run the command again")
                    .formatted(Formatting.RED),
            )
            return Command.SINGLE_SUCCESS
        }

        clearConfirmationArmedAt = 0L
        ConfigManager.clearPlayers()
        context.source.sendFeedback(
            tlMessage(Text.literal("Cleared your Skylist").formatted(Formatting.GREEN)),
        )
        return Command.SINGLE_SUCCESS
    }

    private fun helpLine(subcommand: String, vararg arguments: String): MutableText {
        val subcommandColor = when (subcommand) {
            "add" -> Formatting.GREEN
            "remove" -> Formatting.RED
            "edit" -> Formatting.YELLOW
            "settings" -> Formatting.GRAY
            "list" -> Formatting.AQUA
            "gui" -> Formatting.AQUA
            else -> Formatting.WHITE
        }

        val line = Text.empty()
            .append(Text.literal("/skylist").formatted(Formatting.AQUA))
            .append(Text.literal(" "))
            .append(Text.literal(subcommand).formatted(subcommandColor))

        arguments.forEach { argument ->
            line.append(Text.literal(" "))
            val formattedArgument = when {
                argument.equals("<local/remote/scammers/all>", ignoreCase = true) -> listScopeArgument()
                argument.contains("username", ignoreCase = true) -> Text.literal(argument).formatted(Formatting.GRAY)
                argument.contains("reason", ignoreCase = true) -> Text.literal(argument).formatted(Formatting.GRAY)
                argument.equals("credits", ignoreCase = true) -> Text.literal(argument).formatted(Formatting.LIGHT_PURPLE)
                argument.equals("updatelist", ignoreCase = true) -> Text.literal(argument).formatted(Formatting.GREEN)
                argument.equals("assumepartyleader", ignoreCase = true) -> Text.literal(argument).formatted(Formatting.YELLOW)
                argument.equals("true/false", ignoreCase = true) -> Text.literal(argument).formatted(Formatting.GRAY)
                argument.equals("versioninfo", ignoreCase = true) -> Text.literal(argument).formatted(Formatting.GOLD)
                argument.equals("sethypixelapikey", ignoreCase = true) -> Text.literal(argument).formatted(Formatting.GOLD)
                argument.equals("exportlist", ignoreCase = true) -> Text.literal(argument).formatted(Formatting.AQUA)
                argument.equals("importlist", ignoreCase = true) -> Text.literal(argument).formatted(Formatting.AQUA)
                argument.equals("autoclear", ignoreCase = true) -> Text.literal(argument).formatted(Formatting.RED)
                argument.equals("IKnowWhatImDoing", ignoreCase = true) -> Text.literal(argument).formatted(Formatting.RED)
                argument.equals("getuuid", ignoreCase = true) -> Text.literal(argument).formatted(Formatting.AQUA)
                argument.equals("getdiscord", ignoreCase = true) -> Text.literal(argument).formatted(Formatting.AQUA)
                argument.equals("toggle", ignoreCase = true) ->
                    Text.literal(argument).formatted(Formatting.LIGHT_PURPLE)
                argument.equals("on/off", ignoreCase = true) -> Text.literal(argument).formatted(Formatting.GRAY)
                else -> Text.literal(argument)
            }
            line.append(formattedArgument)
        }

        return line
    }

    private fun listScopeArgument(): MutableText =
        Text.empty()
            .append(Text.literal("<").formatted(Formatting.DARK_GRAY))
            .append(Text.literal("local").formatted(Formatting.GREEN))
            .append(Text.literal("/").formatted(Formatting.DARK_GRAY))
            .append(Text.literal("remote").formatted(Formatting.RED))
            .append(Text.literal("/").formatted(Formatting.DARK_GRAY))
            .append(Text.literal("scammers").formatted(Formatting.GOLD))
            .append(Text.literal("/").formatted(Formatting.DARK_GRAY))
            .append(Text.literal("all").formatted(Formatting.AQUA))
            .append(Text.literal(">").formatted(Formatting.DARK_GRAY))

    private fun tlMessage(message: String): MutableText =
        Text.empty()
            .append(Text.literal("[SL] ").formatted(Formatting.AQUA))
            .append(Text.literal(message))

    private fun tlMessage(message: Text): MutableText =
        Text.empty()
            .append(Text.literal("[SL] ").formatted(Formatting.AQUA))
            .append(message)

    private fun suggestSavedUsernames(
        context: CommandContext<FabricClientCommandSource>,
        builder: SuggestionsBuilder,
    ) = CommandSource.suggestMatching(
        ConfigManager.localUsernames(),
        builder,
    )

    private fun suggestAddableUsernames(
        context: CommandContext<FabricClientCommandSource>,
        builder: SuggestionsBuilder,
    ) = CommandSource.suggestMatching(
        buildSet {
            addAll(lobbyUsernames())
            addAll(ThrowerListMod.listener.memberUsernames().filter(::isValidSuggestedUsername))
            remove(ThrowerListMod.client.session.username)
            removeAll(ConfigManager.localUsernames())
        },
        builder,
    )

    private fun suggestListedUsernames(
        context: CommandContext<FabricClientCommandSource>,
        builder: SuggestionsBuilder,
    ) = CommandSource.suggestMatching(
        buildSet {
            addAll(ConfigManager.localUsernames())
            addAll(RemoteListManager.listEntries().map { it.username })
        },
        builder,
    )

    private fun suggestRemoteUsernames(
        context: CommandContext<FabricClientCommandSource>,
        builder: SuggestionsBuilder,
    ) = CommandSource.suggestMatching(
        RemoteListManager.listEntries().map { it.username },
        builder,
    )

    private fun suggestRemovableUsernames(
        context: CommandContext<FabricClientCommandSource>,
        builder: SuggestionsBuilder,
    ) = CommandSource.suggestMatching(
        buildSet {
            addAll(ConfigManager.localUsernames())
            addAll(RemoteListManager.listEntries().map { it.username })
        },
        builder,
    )

    private fun suggestCheckTargets(
        context: CommandContext<FabricClientCommandSource>,
        builder: SuggestionsBuilder,
    ) = CommandSource.suggestMatching(
        buildSet {
            addAll(ConfigManager.localUsernames())
            addAll(RemoteListManager.listEntries().map { it.username })
            addAll(ScammerListManager.listEntries().map { it.username })
            addAll(ScammerListManager.listEntries().map { it.uuid })
            addAll(ScammerListManager.listEntries().flatMap { it.discordIds })
        },
        builder,
    )

    private fun resolveCheckTarget(target: String): CompletableFuture<CheckResult?> {
        val direct = findCheckResult(target)
        if (direct != null) {
            return CompletableFuture.completedFuture(direct)
        }

        return UsernameResolver.resolve(target).thenCompose { resolved ->
            val resolvedName = resolved?.username ?: target
            val resolvedUuid = resolved?.uuid
            val local = findCheckResult(resolvedUuid ?: resolvedName) ?: findCheckResult(resolvedName)
            if (local != null) {
                CompletableFuture.completedFuture(local)
            } else {
                ScammerCheckService.checkTarget(target, ScammerCheckService.CheckSource.SLASH_COMMAND).thenApply { outcome ->
                    outcome.verdict?.toCommandResult()
                }
            }
        }
    }

    private fun findCheckResult(target: String): CheckResult? {
        val localByUuid = ConfigManager.findByUuid(target)
        if (localByUuid != null) {
            return CheckResult(localByUuid.username, "your Skylist", localByUuid.reason)
        }

        val localByUsername = ConfigManager.findByUsername(target)
        if (localByUsername != null) {
            return CheckResult(localByUsername.username, "your Skylist", localByUsername.reason)
        }

        val scammerByUuid = ScammerListManager.findEntryByUuid(target)
        if (scammerByUuid != null) {
            return scammerByUuid.toCheckResult()
        }

        val scammerByUsername = ScammerListManager.findEntryByUsername(target)
        if (scammerByUsername != null) {
            return scammerByUsername.toCheckResult()
        }

        val scammerByDiscord = ScammerListManager.findEntryByDiscordId(target)
        if (scammerByDiscord != null) {
            return scammerByDiscord.toCheckResult()
        }

        val remoteByUuid = RemoteListManager.findEntryByUuid(target)
        if (remoteByUuid != null) {
            return CheckResult(remoteByUuid.username, "Skylist", remoteByUuid.reason)
        }

        val remoteByUsername = RemoteListManager.findEntryByUsername(target)
        if (remoteByUsername != null) {
            return CheckResult(remoteByUsername.username, "Skylist", remoteByUsername.reason)
        }

        return null
    }

    private fun ScammerListManager.ScammerEntry.toCheckResult(): CheckResult =
        CheckResult(resolveDisplayName(username, uuid), "SBZ scammer", reason, severity.color)

    private fun ScammerCheckService.CheckResult.toCommandResult(): CheckResult =
        CheckResult(username, sourceLabel, reason, severityColor)

    private fun isUuid(value: String): Boolean =
        Regex("""^[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}$""").matches(value)

    private fun isDiscordId(value: String): Boolean =
        Regex("""^\d{17,20}$""").matches(value)

    private fun resolveDisplayName(username: String, uuid: String): String =
        if (!isUuid(username)) username else runCatching { UsernameResolver.resolveUuid(uuid).join() }.getOrNull() ?: username

    private fun suggestCommonReasons(
        context: CommandContext<FabricClientCommandSource>,
        builder: SuggestionsBuilder,
    ): CompletableFuture<Suggestions> {
        val remaining = builder.remainingLowerCase
        ContentManager.commonReasons()
            .filter { it.lowercase().startsWith(remaining) }
            .forEach(builder::suggest)
        return builder.buildFuture()
    }

    private fun lobbyUsernames(): Set<String> = buildSet {
        ThrowerListMod.client.networkHandler?.playerList?.forEach { entry ->
            val username = entry.profile?.name ?: return@forEach
            if (!isValidSuggestedUsername(username)) {
                return@forEach
            }
            add(username)
        }
    }

    private fun isValidSuggestedUsername(username: String): Boolean =
        minecraftUsernameRegex.matches(username)

    private fun buildPageControls(scope: ListScope, page: Int, totalPages: Int): Text {
        val previous = if (page > 1) {
            Text.literal("<")
                .formatted(Formatting.AQUA, Formatting.BOLD)
                .styled {
                    it.withClickEvent(ClickEvent.RunCommand("/skylist list ${scope.commandName} ${page - 1}"))
                        .withHoverEvent(HoverEvent.ShowText(Text.literal("Go to page ${page - 1}")))
                }
        } else {
            Text.literal("<").formatted(Formatting.DARK_GRAY, Formatting.BOLD)
        }

        val next = if (page < totalPages) {
            Text.literal(">")
                .formatted(Formatting.AQUA, Formatting.BOLD)
                .styled {
                    it.withClickEvent(ClickEvent.RunCommand("/skylist list ${scope.commandName} ${page + 1}"))
                        .withHoverEvent(HoverEvent.ShowText(Text.literal("Go to page ${page + 1}")))
                }
        } else {
            Text.literal(">").formatted(Formatting.DARK_GRAY, Formatting.BOLD)
        }

        return Text.empty()
            .append(previous)
            .append(Text.literal(" Page $page of $totalPages ").formatted(Formatting.DARK_GRAY))
            .append(next)
    }

    private fun buildListEntry(entry: ListEntry): Text {
        val preview = truncateReason(normalizeReasonPreview(entry.reason))
        val addedText = formatAddedTimestamp(entry.ts)
        val addedHover = formatAddedDuration(entry.username, entry.ts)
        return Text.empty()
            .append(Text.literal("[SL] ").formatted(Formatting.RED))
            .append(Text.literal(entry.username).formatted(Formatting.RED))
            .append(Text.literal("\n"))
            .append(Text.literal("Reason: ").formatted(Formatting.GRAY))
            .append(
                Text.literal(preview)
                    .formatted(Formatting.WHITE)
                    .styled {
                        if (preview == entry.reason) {
                            it
                        } else {
                            it.withHoverEvent(HoverEvent.ShowText(Text.literal(entry.reason).formatted(Formatting.WHITE)))
                        }
                    },
            )
            .append(Text.literal("\n"))
            .append(Text.literal("Added: ").formatted(Formatting.GRAY))
            .append(
                Text.literal(addedText)
                    .formatted(Formatting.WHITE)
                    .styled {
                        addedHover?.let { hover ->
                            it.withHoverEvent(HoverEvent.ShowText(Text.literal(hover).formatted(Formatting.WHITE)))
                        } ?: it
                    },
            )
            .append(Text.literal("\n"))
    }

    private fun truncateReason(reason: String): String {
        if (reason.length <= maxReasonPreviewLength) {
            return reason
        }

        return reason.take(maxReasonPreviewLength - 3).trimEnd() + "..."
    }

    private fun normalizeReasonPreview(reason: String): String =
        reason.replace(Regex("\\s*\\R\\s*"), " ").trim()

    private fun formatAddedTimestamp(ts: Long?): String {
        if (ts == null) {
            return "Unknown"
        }

        return Instant.ofEpochMilli(ts)
            .atZone(ZoneId.systemDefault())
            .format(addedTimeFormatter)
    }

    private fun formatAddedDuration(username: String, ts: Long?): String? {
        if (ts == null) {
            return null
        }

        val zone = ZoneId.systemDefault()
        val addedAt = Instant.ofEpochMilli(ts).atZone(zone).toLocalDateTime()
        val now = LocalDateTime.now(zone)
        if (addedAt.isAfter(now)) {
            return "$username has been on the list for 0 seconds"
        }

        val period = Period.between(addedAt.toLocalDate(), now.toLocalDate())
        val timeAdjusted = addedAt.plusYears(period.years.toLong())
            .plusMonths(period.months.toLong())
            .plusDays(period.days.toLong())

        var secondsRemaining = java.time.Duration.between(timeAdjusted, now).seconds.coerceAtLeast(0)
        val hours = secondsRemaining / 3600
        secondsRemaining %= 3600
        val minutes = secondsRemaining / 60
        val seconds = secondsRemaining % 60

        val parts = when {
            period.years >= 1 -> listOf(
                formatDurationUnit(period.years.toLong(), "year"),
                formatDurationUnit(period.months.toLong(), "month"),
            )
            period.months >= 1 -> listOf(
                formatDurationUnit(period.months.toLong(), "month"),
                formatDurationUnit(period.days.toLong(), "day"),
            )
            period.days >= 1 -> listOf(
                formatDurationUnit(period.days.toLong(), "day"),
                formatDurationUnit(hours, "hour"),
            )
            hours >= 1 -> listOf(
                formatDurationUnit(hours, "hour"),
                formatDurationUnit(minutes, "minute"),
            )
            minutes >= 1 -> listOf(
                formatDurationUnit(minutes, "minute"),
                formatDurationUnit(seconds, "second"),
            )
            else -> listOf(
                formatDurationUnit(seconds, "second"),
            )
        }.filterNotNull()

        return "$username has been on the list for ${parts.joinToString(" ")}"
    }

    private fun formatDurationUnit(value: Long, unit: String): String? {
        if (value <= 0) {
            return null
        }

        val suffix = if (value == 1L) unit else "${unit}s"
        return "$value $suffix"
    }

    private fun listSeparator(): Text = Text.literal("-------------------------").formatted(Formatting.AQUA)

    private fun gradientText(content: String, leftColor: Int, rightColor: Int): MutableText {
        val output = Text.empty()
        val maxIndex = (content.length - 1).coerceAtLeast(1)
        content.forEachIndexed { index, character ->
            val progress = index.toFloat() / maxIndex.toFloat()
            output.append(
                Text.literal(character.toString()).formatted(Formatting.WHITE).styled {
                    it.withColor(interpolateColor(leftColor, rightColor, progress))
                },
            )
        }
        return output
    }

    private fun interpolateColor(start: Int, end: Int, progress: Float): Int {
        val startR = (start shr 16) and 0xFF
        val startG = (start shr 8) and 0xFF
        val startB = start and 0xFF

        val endR = (end shr 16) and 0xFF
        val endG = (end shr 8) and 0xFF
        val endB = end and 0xFF

        val r = (startR + ((endR - startR) * progress)).toInt().coerceIn(0, 255)
        val g = (startG + ((endG - startG) * progress)).toInt().coerceIn(0, 255)
        val b = (startB + ((endB - startB) * progress)).toInt().coerceIn(0, 255)

        return (r shl 16) or (g shl 8) or b
    }

    private fun removeRemoteEntry(source: FabricClientCommandSource, username: String) {
        val remoteEntry = RemoteListManager.findEntryByUsername(username)
        if (remoteEntry != null) {
            hideRemoteEntry(source, remoteEntry)
            return
        }

        UsernameResolver.resolve(username).thenAccept { resolved ->
            ThrowerListMod.client.execute {
                val resolvedRemoteEntry = when {
                    resolved == null -> RemoteListManager.findEntryByUsername(username)
                    else -> RemoteListManager.findEntryByUuid(resolved.uuid)
                        ?: RemoteListManager.findEntryByUsername(resolved.username)
                }

                if (resolvedRemoteEntry == null) {
                    source.sendError(Text.literal("$username is not on your Skylist"))
                    return@execute
                }

                hideRemoteEntry(source, resolvedRemoteEntry)
            }
        }
    }

    private fun hideRemoteEntry(source: FabricClientCommandSource, entry: RemoteListManager.RemoteEntry) {
        val disabled = ConfigManager.toggleRemoteEntryDisabled(entry.uuid)
        source.sendFeedback(
            tlMessage(
                Text.literal(if (disabled) "Disabled remote Skylist entry " else "Re-enabled remote Skylist entry ").formatted(Formatting.GREEN)
                    .append(Text.literal(entry.username).formatted(Formatting.GRAY))
                    .append(Text.literal(" for this user only").formatted(Formatting.GREEN)),
            ),
        )
    }

    private data class ListEntry(
        val username: String,
        val reason: String,
        val ts: Long?,
    )

    private data class CheckResult(
        val displayName: String,
        val listName: String,
        val reason: String,
        val severityColor: Int? = null,
    )

    private data class ParsedReminder(
        val delayMillis: Long,
        val reminder: String,
        val canonicalDuration: String,
    )

    private fun buildCheckResultText(result: CheckResult): MutableText {
        val name = Text.literal(result.displayName).styled { style ->
            result.severityColor?.let { style.withColor(it and 0xFFFFFF) } ?: style.withColor(Formatting.RED)
        }
        return Text.empty()
            .append(name)
            .append(Text.literal(" is on the ${result.listName} list for ").formatted(Formatting.RED))
            .append(Text.literal("\"${result.reason}\"").formatted(Formatting.GRAY))
    }

    private fun parseReminderInput(input: String): ParsedReminder? {
        val words = input.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (words.size < 2) {
            return null
        }

        var bestMatch: ParsedReminder? = null
        val now = System.currentTimeMillis()
        for (splitIndex in 1 until words.size) {
            val durationText = words.take(splitIndex).joinToString(" ")
            val reminderText = words.drop(splitIndex).joinToString(" ").trim()
            if (reminderText.isEmpty()) {
                continue
            }

            val definition = EntryExpiry.parse(durationText) ?: continue
            val triggerAt = definition.applyTo(now)
            val delayMillis = triggerAt - now
            if (delayMillis <= 0L) {
                continue
            }

            bestMatch = ParsedReminder(
                delayMillis = delayMillis,
                reminder = reminderText,
                canonicalDuration = definition.canonical,
            )
        }

        return bestMatch
    }

}
