package net.coderbot.iris.mixin.compat.minecraft;

import com.mojang.math.Matrix3f;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = Matrix3f.class, priority = 900)
public abstract class MixinMatrix3fScale { }
