package net.coderbot.iris.mixin.compat.tslatentitystatus;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** The plugin validates TES's complete cache-lookup method before removing its lambda. */
@Pseudo
@Mixin(targets = "net.tslat.tes.api.util.TESUtil", priority = 400, remap = false)
public abstract class MixinTesEntityTypeCache { }
