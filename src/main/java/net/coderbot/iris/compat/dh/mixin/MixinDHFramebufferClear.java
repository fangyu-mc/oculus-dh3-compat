package net.coderbot.iris.compat.dh.mixin;

import org.lwjgl.opengl.GL11C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import net.coderbot.iris.compat.dh.DHCompat;

@Mixin(targets = "com.seibel.distanthorizons.common.render.openGl.GlDhMetaRenderer_forge", remap = false)
public class MixinDHFramebufferClear {
	@ModifyArg(
		method = {"setGLState", "clearDhDepthAndColorTextures"},
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL33;glClear(I)V"),
		index = 0,
		require = 0
	)
	private int oculus$preserveShaderPackColorTargets(int mask) {
		return DHCompat.isActive() ? mask & ~GL11C.GL_COLOR_BUFFER_BIT : mask;
	}
}
