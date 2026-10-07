package net.coderbot.iris.mixin.compat.minecraft;

import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = PoseStack.class, priority = 900)
public abstract class MixinPoseStackScale { }
