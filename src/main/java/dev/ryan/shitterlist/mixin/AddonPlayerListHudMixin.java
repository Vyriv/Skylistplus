package dev.ryan.throwerlist.mixin;

import dev.ryan.throwerlist.ListedPlayerMarker;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerListHud.class)
public abstract class AddonPlayerListHudMixin {
    @Inject(method = "getPlayerName", at = @At("RETURN"), cancellable = true)
    private void skylistplus$markPlayerListName(PlayerListEntry entry, CallbackInfoReturnable<Text> cir) {
        Text current = cir.getReturnValue();
        if (current == null || !ListedPlayerMarker.INSTANCE.isListedProfile(entry.getProfile())) {
            return;
        }
        cir.setReturnValue(ListedPlayerMarker.INSTANCE.applyMarker(current, entry.getProfile()));
    }

    @ModifyArg(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;III)V"
        ),
        index = 1
    )
    private Text skylistplus$markRenderedTabName(Text text) {
        if (text == null || !ListedPlayerMarker.INSTANCE.containsListedName(text.getString())) {
            return text;
        }

        return ListedPlayerMarker.INSTANCE.applyMarker(text);
    }
}
