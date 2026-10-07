package net.coderbot.iris.mixin.compat.minecraft;

import com.mojang.math.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;

/** CompatMixinPlugin derives a scalar scale operation only from a recognized matrix implementation. */
@Mixin(value = Matrix4f.class, priority = 900)
public abstract class MixinMatrix4fScale { }
