package dev.ryan.throwerlist.mixin;

import com.mojang.brigadier.suggestion.Suggestion;
import net.minecraft.client.gui.screen.ChatInputSuggestor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Mixin(ChatInputSuggestor.class)
public abstract class ChatInputSuggestorMixin {
    private static final Set<String> HIDDEN_ROOTS = Set.of("throwerlist", "thrower", "tl", "shitterlist", "shitter", "shit");

    @Inject(method = "sortSuggestions", at = @At("RETURN"), cancellable = true)
    private void throwerlist$hideAliasSuggestions(com.mojang.brigadier.suggestion.Suggestions suggestions, CallbackInfoReturnable<List<Suggestion>> cir) {
        List<Suggestion> filtered = cir.getReturnValue().stream()
            .filter(suggestion -> !HIDDEN_ROOTS.contains(suggestion.getText().toLowerCase()))
            .collect(Collectors.toList());
        cir.setReturnValue(filtered);
    }
}
