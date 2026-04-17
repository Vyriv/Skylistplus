package dev.ryan.throwerlist

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.text.ClickEvent
import net.minecraft.text.HoverEvent
import net.minecraft.text.MutableText
import net.minecraft.text.Text
import net.minecraft.util.Formatting

object LobbyDetector {
    private val lastSeenListedUuids = linkedSetOf<String>()
    private var tickCounter = 0

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register(::onEndTick)
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
    }

    private fun onEndTick(client: MinecraftClient) {
        if (client.player == null || client.networkHandler == null) {
            reset()
            return
        }

        tickCounter++
        if (tickCounter < 20) {
            return
        }
        tickCounter = 0

        val currentListedUuids = linkedSetOf<String>()
        client.networkHandler?.playerList?.forEach { entry ->
            val profile = entry.profile ?: return@forEach
            val uuid = profile.id?.toString()?.lowercase() ?: return@forEach
            val username = profile.name ?: return@forEach
            if (username.equals(client.session.username, ignoreCase = true)) {
                return@forEach
            }

            val localEntry = ConfigManager.findByUuid(uuid) ?: ConfigManager.findByUsername(username)
            val scammerEntry = ScammerListManager.findEntryByUuid(uuid) ?: ScammerListManager.findEntryByUsername(username)
            val remoteReason = if (localEntry == null) RemoteListManager.findReasonByUuid(uuid) else null
            if (localEntry == null && remoteReason == null && scammerEntry == null) {
                return@forEach
            }

            currentListedUuids.add(uuid)
            if (uuid !in lastSeenListedUuids && ConfigManager.isLobbyNotificationsEnabled()) {
                localEntry?.let { ConfigManager.updateUsername(it.uuid, username) }
                RemoteListManager.updateUsername(uuid, username)
                client.player?.sendMessage(buildDetectionMessage(username, detectionKind(localEntry != null || remoteReason != null, scammerEntry != null)), false)
            } else if (uuid !in lastSeenListedUuids) {
                localEntry?.let { ConfigManager.updateUsername(it.uuid, username) }
                RemoteListManager.updateUsername(uuid, username)
            }
        }

        lastSeenListedUuids.clear()
        lastSeenListedUuids.addAll(currentListedUuids)
    }

    private fun reset() {
        tickCounter = 0
        lastSeenListedUuids.clear()
    }

    private fun buildDetectionMessage(username: String, kind: DetectionKind): MutableText =
        Text.empty()
            .append(Text.literal("[SL] ").formatted(Formatting.AQUA))
            .append(Text.literal("Detected Skylist ${kind.label} ").formatted(Formatting.GREEN))
            .append(
                Text.literal(username)
                    .formatted(Formatting.RED)
                    .styled {
                        it.withClickEvent(ClickEvent.RunCommand("/skylist gui $username"))
                            .withHoverEvent(
                                HoverEvent.ShowText(
                                    Text.literal("Click to open Skylist and search for $username"),
                                ),
                            )
                    },
            )
            .append(Text.literal(" in your lobby.").formatted(Formatting.GREEN))

    private fun detectionKind(isThrower: Boolean, isScammer: Boolean): DetectionKind =
        when {
            isScammer -> DetectionKind.SCAMMER
            isThrower -> DetectionKind.THROWER
            else -> DetectionKind.THROWER
        }

    private enum class DetectionKind(val label: String) {
        SCAMMER("scammer"),
        THROWER("thrower"),
    }
}
