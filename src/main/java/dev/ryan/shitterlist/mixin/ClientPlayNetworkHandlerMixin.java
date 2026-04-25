package dev.ryan.throwerlist.mixin;

import dev.ryan.throwerlist.DungeonPuzzleFailPbTracker;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.ScoreboardDisplayS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardObjectiveUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardScoreResetS2CPacket;
import net.minecraft.network.packet.s2c.play.ScoreboardScoreUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.TeamS2CPacket;
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
    @Inject(method = "onScoreboardObjectiveUpdate", at = @At("TAIL"))
    private void skylistplus$notifyObjectiveUpdate(ScoreboardObjectiveUpdateS2CPacket packet, CallbackInfo ci) {
        DungeonPuzzleFailPbTracker.INSTANCE.onScoreboardChanged();
    }

    @Inject(method = "onScoreboardScoreUpdate", at = @At("TAIL"))
    private void skylistplus$notifyScoreUpdate(ScoreboardScoreUpdateS2CPacket packet, CallbackInfo ci) {
        DungeonPuzzleFailPbTracker.INSTANCE.onScoreboardChanged();
    }

    @Inject(method = "onScoreboardScoreReset", at = @At("TAIL"))
    private void skylistplus$notifyScoreReset(ScoreboardScoreResetS2CPacket packet, CallbackInfo ci) {
        DungeonPuzzleFailPbTracker.INSTANCE.onScoreboardChanged();
    }

    @Inject(method = "onScoreboardDisplay", at = @At("TAIL"))
    private void skylistplus$notifyDisplayUpdate(ScoreboardDisplayS2CPacket packet, CallbackInfo ci) {
        DungeonPuzzleFailPbTracker.INSTANCE.onScoreboardChanged();
    }

    @Inject(method = "onTeam", at = @At("TAIL"))
    private void skylistplus$notifyTeamUpdate(TeamS2CPacket packet, CallbackInfo ci) {
        DungeonPuzzleFailPbTracker.INSTANCE.onScoreboardChanged();
    }

    @Inject(method = "onTitle", at = @At("TAIL"))
    private void skylistplus$notifyTitle(TitleS2CPacket packet, CallbackInfo ci) {
        DungeonPuzzleFailPbTracker.INSTANCE.onTitleMessage(packet.text());
    }

    @Inject(method = "onSubtitle", at = @At("TAIL"))
    private void skylistplus$notifySubtitle(SubtitleS2CPacket packet, CallbackInfo ci) {
        DungeonPuzzleFailPbTracker.INSTANCE.onTitleMessage(packet.text());
    }

    @Inject(method = "onOverlayMessage", at = @At("TAIL"))
    private void skylistplus$notifyOverlay(OverlayMessageS2CPacket packet, CallbackInfo ci) {
        DungeonPuzzleFailPbTracker.INSTANCE.onTitleMessage(packet.text());
    }

    @Inject(method = "onBlockUpdate", at = @At("TAIL"))
    private void skylistplus$notifyBlockUpdate(BlockUpdateS2CPacket packet, CallbackInfo ci) {
        DungeonPuzzleFailPbTracker.INSTANCE.onBlockUpdated(packet.getPos());
    }

    @Inject(method = {"clearWorld", "unloadWorld"}, at = @At("TAIL"))
    private void skylistplus$resetFailPbTracker(CallbackInfo ci) {
        DungeonPuzzleFailPbTracker.INSTANCE.reset();
    }
}
