package net.coderbot.iris.compat.dh.mixin;

import com.seibel.distanthorizons.core.render.RenderParams;
import net.coderbot.iris.compat.dh.DHCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.seibel.distanthorizons.common.render.openGl.GlDhMetaRenderer_forge", remap = false)
public class MixinDHRenderPassState {
	@Inject(method = "runRenderPassSetup", at = @At("HEAD"))
	private void oculus$selectDhFramebuffer(RenderParams params, CallbackInfo ci) {
		DHCompat.setRenderPass(params.renderPass);
	}
}
