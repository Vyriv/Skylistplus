package dev.ryan.throwerlist.mixin;

import dev.ryan.throwerlist.IgnoredPlayerManager;
import dev.ryan.throwerlist.ListedPlayerMarker;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatHud.class)
public abstract class AddonChatHudMixin {
    @Inject(method = "addMessage(Lnet/minecraft/text/Text;)V", at = @At("HEAD"), cancellable = true)
    private void skylistplus$suppressIgnoredMessages(Text text, CallbackInfo ci) {
        if (text != null && IgnoredPlayerManager.INSTANCE.shouldSuppressChatMessage(text, null)) {
            ci.cancel();
        }
    }

    @ModifyVariable(method = "addMessage", at = @At("HEAD"), argsOnly = true)
    private Text skylistplus$applyListedMarkers(Text text) {
        if (text == null || !ListedPlayerMarker.INSTANCE.containsListedName(text.getString())) {
            return text;
        }

        return ListedPlayerMarker.INSTANCE.applyMarkerToChatMessage(text);
    }
}
