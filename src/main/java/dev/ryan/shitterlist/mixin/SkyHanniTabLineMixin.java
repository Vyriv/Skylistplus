package dev.ryan.throwerlist.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "at.hannibal2.skyhanni.features.misc.compacttablist.TabLine", remap = false)
public abstract class SkyHanniTabLineMixin {}
