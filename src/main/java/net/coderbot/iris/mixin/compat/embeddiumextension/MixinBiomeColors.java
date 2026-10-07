package net.coderbot.iris.mixin.compat.embeddiumextension;

import net.minecraft.client.renderer.BiomeColors;
import org.spongepowered.asm.mixin.Mixin;

/** Runs the guarded compatibility rewrite after all RETURN callbacks have been injected. */
@Mixin(value = BiomeColors.class, priority = 900)
public abstract class MixinBiomeColors { }
