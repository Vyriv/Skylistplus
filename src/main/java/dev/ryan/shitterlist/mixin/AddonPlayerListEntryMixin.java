package dev.ryan.throwerlist.mixin;

import com.mojang.authlib.GameProfile;
import dev.ryan.throwerlist.ListedPlayerMarker;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerListEntry.class)
public abstract class AddonPlayerListEntryMixin {
    @Shadow @Final private GameProfile profile;
    @Shadow private Text displayName;

    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
    private void skylistplus$applyListedMarkers(CallbackInfoReturnable<Text> cir) {
        Text current = cir.getReturnValue();
        if (current == null || !ListedPlayerMarker.INSTANCE.isListedProfile(this.profile)) {
            return;
        }
        cir.setReturnValue(ListedPlayerMarker.INSTANCE.applyMarker(current, this.profile));
    }

    @Inject(method = "setDisplayName", at = @At("HEAD"), cancellable = true)
    private void skylistplus$styleIncomingDisplayName(Text text, CallbackInfo ci) {
        if (text == null || !ListedPlayerMarker.INSTANCE.isListedProfile(this.profile)) {
            return;
        }

        this.displayName = ListedPlayerMarker.INSTANCE.applyMarker(text, this.profile);
        ci.cancel();
    }
}
