package net.coderbot.iris.compat.dh.mixin;

import net.coderbot.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.seibel.distanthorizons.forge.wrappers.modAccessor.OculusAccessor", remap = false)
public class MixinDHOculusAccessor {
	@Inject(method = "getModName", at = @At("HEAD"), cancellable = true)
	private void oculus$reportModName(CallbackInfoReturnable<String> cir) {
		cir.setReturnValue(Iris.MODID);
	}

	@Inject(method = "isShaderPackInUse", at = @At("HEAD"), cancellable = true)
	private void oculus$reportShaderState(CallbackInfoReturnable<Boolean> cir) {
		cir.setReturnValue(IrisApi.getInstance().isShaderPackInUse());
	}

	@Inject(method = "isRenderingShadowPass", at = @At("HEAD"), cancellable = true)
	private void oculus$reportShadowState(CallbackInfoReturnable<Boolean> cir) {
		cir.setReturnValue(IrisApi.getInstance().isRenderingShadowPass());
	}
}
