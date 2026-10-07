package net.coderbot.iris.mixin.compat.minecraft;

import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;

/** Inspect the merged sky sampler after the normal client-world mixins. */
@Mixin(value = ClientLevel.class, priority = 900)
public abstract class MixinSkyRgbSampler { }
