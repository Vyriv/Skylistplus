package dev.ryan.throwerlist.mixin;

import dev.ryan.throwerlist.ConfigManager;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(method = "getHandSwingDuration", at = @At("HEAD"), cancellable = true)
    private void throwerlist$onGetHandSwingDuration(CallbackInfoReturnable<Integer> cir) {
        if (ConfigManager.INSTANCE.isSwingSpeedEnabled()) {
            float speed = ConfigManager.INSTANCE.getSwingSpeedValue();
            int duration = Math.round(6.0f / speed);
            cir.setReturnValue(duration);
        }
    }
}
