package net.coderbot.iris.compat.dh.mixin;

import net.coderbot.iris.compat.dh.DHCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.seibel.distanthorizons.common.render.openGl.GlDhMetaRenderer_forge", remap = false)
public class MixinDHApplyShader {
	@Inject(method = "applyToMcTexture", at = @At("HEAD"), cancellable = true)
	private void oculus$skipApplyWhenRenderingIntoGbuffer(CallbackInfo ci) {
		if (DHCompat.isActive()) {
			ci.cancel();
		}
	}
}
