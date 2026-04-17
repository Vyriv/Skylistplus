package dev.ryan.throwerlist.mixin;

import dev.ryan.throwerlist.ListedPlayerMarker;
import dev.ryan.throwerlist.NameStyler;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Pseudo
@Mixin(targets = "at.hannibal2.skyhanni.features.misc.compacttablist.AdvancedPlayerList", remap = false)
public abstract class SkyHanniCompactTabListMixin {
    @ModifyArgs(
        method = "createTabLine",
        at = @At(
            value = "INVOKE",
            target = "Lat/hannibal2/skyhanni/features/misc/compacttablist/TabLine;<init>(Lnet/minecraft/class_2561;Lat/hannibal2/skyhanni/features/misc/compacttablist/TabStringType;Lnet/minecraft/class_2561;)V"
        ),
        remap = false
    )
    private void throwerlist$styleSkyHanniCompactTabListName(Args args) {
        Text component = (Text) args.get(0);
        Text current = (Text) args.get(2);
        if (current == null) {
            return;
        }

        Text styled = current;
        if (NameStyler.INSTANCE.containsStyledTargetName(styled.getString())) {
            styled = NameStyler.INSTANCE.applyNameplateDecorations(styled);
        }
        if (ListedPlayerMarker.INSTANCE.containsListedName(styled.getString())) {
            styled = ListedPlayerMarker.INSTANCE.applyMarker(styled);
        }
        String guestToken = throwerlist$extractGuestToken(component);
        if (guestToken != null && !styled.getString().contains(guestToken)) {
            styled = styled.copy().append(Text.literal(" " + guestToken));
        }
        args.set(2, styled);
    }

    private String throwerlist$extractGuestToken(Text component) {
        if (component == null) {
            return null;
        }

        String raw = component.getString();
        int iconIndex = raw.indexOf('✌');
        if (iconIndex < 0) {
            return null;
        }

        int tokenStart = raw.lastIndexOf('[', iconIndex);
        int tokenEnd = raw.indexOf(']', iconIndex);
        if (tokenStart >= 0 && tokenEnd > tokenStart) {
            return raw.substring(tokenStart, tokenEnd + 1);
        }

        return "✌";
    }
}
