package net.coderbot.iris.mixin.diagnostics;

import net.coderbot.iris.diagnostics.FrameTimeRecorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.Minecraft;

@Mixin(Minecraft.class)
public class MixinMinecraft_FrameTime {
	@Inject(method = "runTick(Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runAllTasks()V"))
	private void iris$beginTasks(CallbackInfo ci) {
		FrameTimeRecorder.beginPhase(FrameTimeRecorder.TASKS);
	}

	@Inject(method = "runTick(Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runAllTasks()V", shift = At.Shift.AFTER))
	private void iris$endTasks(CallbackInfo ci) {
		FrameTimeRecorder.endPhase(FrameTimeRecorder.TASKS);
	}

	@Inject(method = "runTick(Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;tick()V"))
	private void iris$beginTick(CallbackInfo ci) {
		FrameTimeRecorder.beginPhase(FrameTimeRecorder.TICK);
	}

	@Inject(method = "runTick(Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;tick()V", shift = At.Shift.AFTER))
	private void iris$endTick(CallbackInfo ci) {
		FrameTimeRecorder.endPhase(FrameTimeRecorder.TICK);
	}

	@Inject(method = "runTick(Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render(FJZ)V"))
	private void iris$beginRender(CallbackInfo ci) {
		FrameTimeRecorder.beginPhase(FrameTimeRecorder.RENDER);
	}

	@Inject(method = "runTick(Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render(FJZ)V", shift = At.Shift.AFTER))
	private void iris$endRender(CallbackInfo ci) {
		FrameTimeRecorder.endPhase(FrameTimeRecorder.RENDER);
	}

	@Inject(method = "runTick(Z)V", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;updateDisplay()V"))
	private void iris$beginDisplay(CallbackInfo ci) {
		FrameTimeRecorder.beginPhase(FrameTimeRecorder.DISPLAY);
	}

	@Inject(method = "runTick(Z)V", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;updateDisplay()V", shift = At.Shift.AFTER))
	private void iris$endDisplay(CallbackInfo ci) {
		FrameTimeRecorder.endPhase(FrameTimeRecorder.DISPLAY);
	}

	@Inject(method = "runTick(Z)V", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;limitDisplayFPS(I)V"))
	private void iris$beginLimiter(CallbackInfo ci) {
		FrameTimeRecorder.beginPhase(FrameTimeRecorder.LIMITER);
	}

	@Inject(method = "runTick(Z)V", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;limitDisplayFPS(I)V", shift = At.Shift.AFTER))
	private void iris$endLimiter(CallbackInfo ci) {
		FrameTimeRecorder.endPhase(FrameTimeRecorder.LIMITER);
	}
}
