package net.coderbot.iris.mixin.diagnostics;

import net.coderbot.iris.diagnostics.FrameTimeRecorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.renderer.GameRenderer;

@Mixin(GameRenderer.class)
public class MixinGameRenderer_FrameTime {
	@Inject(method = "render(FJZ)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(FJLcom/mojang/blaze3d/vertex/PoseStack;)V"))
	private void iris$beginWorld(CallbackInfo ci) {
		FrameTimeRecorder.beginPhase(FrameTimeRecorder.WORLD);
	}

	@Inject(method = "render(FJZ)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(FJLcom/mojang/blaze3d/vertex/PoseStack;)V", shift = At.Shift.AFTER))
	private void iris$endWorld(CallbackInfo ci) {
		FrameTimeRecorder.endPhase(FrameTimeRecorder.WORLD);
	}
}
