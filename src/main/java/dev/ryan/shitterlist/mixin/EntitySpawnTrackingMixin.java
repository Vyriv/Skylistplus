package dev.ryan.throwerlist.mixin;

import dev.ryan.throwerlist.DungeonPuzzleFailPbTracker;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class EntitySpawnTrackingMixin {
    @Inject(method = "onEntitySpawn", at = @At("TAIL"))
    private void skylistplus$trackEntitySpawn(EntitySpawnS2CPacket packet, CallbackInfo ci) {
        DungeonPuzzleFailPbTracker.INSTANCE.onEntitySpawn(
            packet.getEntityType(),
            packet.getX(),
            packet.getY(),
            packet.getZ()
        );
    }
}
