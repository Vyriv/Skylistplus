package dev.ryan.throwerlist.mixin;

import dev.ryan.throwerlist.PartyFinderFloorTracker;
import dev.ryan.throwerlist.PartyFinderUiHighlighter;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(HandledScreen.class)
public abstract class PartyFinderScreenMixin {
    @Shadow @Final protected ScreenHandler handler;
    @Shadow @Nullable protected Slot focusedSlot;

    @Inject(method = "getTooltipFromItem", at = @At("RETURN"), cancellable = true)
    private void throwerlist$decoratePartyFinderTooltip(ItemStack stack, CallbackInfoReturnable<List<Text>> cir) {
        cir.setReturnValue(PartyFinderUiHighlighter.INSTANCE.decorateTooltipIfNeeded(this.throwerlist$getTitle(), stack, cir.getReturnValue()));
    }

    @Inject(method = "drawSlot", at = @At("TAIL"))
    private void throwerlist$highlightPartyFinderHeads(DrawContext context, Slot slot, CallbackInfo ci) {
        if (!(this.handler instanceof GenericContainerScreenHandler containerHandler)) {
            return;
        }
        if (slot.id >= containerHandler.getRows() * 9 || !slot.hasStack()) {
            return;
        }

        ItemStack stack = slot.getStack();
        if (!PartyFinderUiHighlighter.INSTANCE.shouldHighlightEntry(this.throwerlist$getTitle(), stack)) {
            return;
        }

        PartyFinderUiHighlighter.INSTANCE.drawHeadHighlight(context, slot.x, slot.y);
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void throwerlist$capturePartyFinderFloor(DrawContext context, int mouseX, int mouseY, float deltaTicks, CallbackInfo ci) {
        if (!PartyFinderUiHighlighter.INSTANCE.isPartyFinderScreen(this.throwerlist$getTitle())) {
            return;
        }

        String detectedFloor = PartyFinderUiHighlighter.INSTANCE.detectConfiguredFloor(this.throwerlist$getTitle(), this.handler.slots);
        if (detectedFloor != null) {
            PartyFinderFloorTracker.INSTANCE.record(detectedFloor);
        }
    }

    private Text throwerlist$getTitle() {
        return ((Screen) (Object) this).getTitle();
    }
}
