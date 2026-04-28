package dev.ryan.throwerlist

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents

class SkylistPlusMod : ClientModInitializer {
    override fun onInitializeClient() {
        DungeonPuzzleFailPbStore.load()
        RemoteListManager.start()
        LobbyDetector.register()
        GitHubUpdateChecker.register()
        ReminderManager.register()
        DungeonPuzzleFailPbTracker.register()

        ThrowerListGuiLauncher.registerMainScreenProvider { request ->
            val tab = when (request.view) {
                ThrowerListGuiLauncher.View.LOCAL -> ThrowerListListScreen.Tab.LOCAL
                ThrowerListGuiLauncher.View.REMOTE -> ThrowerListListScreen.Tab.REMOTE
                ThrowerListGuiLauncher.View.SCAMMERS -> ThrowerListListScreen.Tab.SCAMMERS
                else -> ThrowerListListScreen.Tab.ALL
            }
            ThrowerListListScreen(tab, openFromCenter = true, startFromDungeonWidth = false, initialSearch = request.initialSearch)
        }

        val kickQueue = KickQueue(ThrowerListMod.client) { username -> ThrowerListMod.listener.isMemberPresent(username) }
        plusListener = SkylistPlusPartyListener(ThrowerListMod.client, kickQueue)
        plusListener.register()

        CommandHandler.register()

        ClientReceiveMessageEvents.ALLOW_CHAT.register { message, _, sender, _, _ ->
            if (IgnoredPlayerManager.shouldSuppressChatMessage(message, sender)) {
                return@register false
            }

            if (!ListedPlayerMarker.isListedProfile(sender)) {
                return@register true
            }

            ThrowerListMod.client.inGameHud.chatHud.addMessage(
                ListedPlayerMarker.applyMarkerToChatMessage(message),
            )
            false
        }
        ClientReceiveMessageEvents.ALLOW_GAME.register { message, overlay ->
            overlay || !IgnoredPlayerManager.shouldSuppressChatMessage(message, null)
        }
        ClientReceiveMessageEvents.MODIFY_GAME.register { message, _ ->
            message
        }

        ThrowerListMod.logger.info("Skylist+ cosmetic name styling disabled; Skylist handles custom names")

        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            plusListener.reset()
            DungeonPuzzleFailPbTracker.reset()
        }
    }

    companion object {
        lateinit var plusListener: SkylistPlusPartyListener
            private set
    }
}
