package net.coderbot.iris.compat.dh.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.coderbot.iris.compat.dh.DHCompat;
import net.coderbot.iris.shadows.ShadowRenderingState;

@Mixin(targets = "com.seibel.distanthorizons.core.api.internal.ClientApi", remap = false)
public class MixinDHShadowPassGuard {
	@Inject(
		method = {"renderLods", "renderDeferredLodsForShaders"},
		at = @At("HEAD"),
		cancellable = true
	)
	private void oculus$skipDhWithoutShadowProgram(CallbackInfo ci) {
		if (ShadowRenderingState.areShadowsCurrentlyBeingRendered()) {
			ci.cancel();
		}
	}

	@Inject(
		method = {"renderFadeOpaque", "renderFadeTransparent"},
		at = @At("HEAD"),
		cancellable = true
	)
	private void oculus$skipVanillaFadeRenderer(CallbackInfo ci) {
		if (DHCompat.isActive() || ShadowRenderingState.areShadowsCurrentlyBeingRendered()) {
			ci.cancel();
		}
	}
}
