package net.coderbot.iris.mixin.diagnostics;

import net.coderbot.iris.diagnostics.FrameTimeRecorder;
import net.coderbot.iris.diagnostics.FrameTimeDetails;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import com.mojang.blaze3d.systems.RenderSystem;

@Mixin(RenderSystem.class)
public class MixinRenderSystem_FrameTime {
	@Redirect(method = "flipFrame", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwPollEvents()V", ordinal = 0, remap = false), require = 1, allow = 1)
	private static void iris$pollBefore() {
		long token = FrameTimeRecorder.beginDetail(FrameTimeDetails.POLL_BEFORE);
		boolean failed = true;
		try {
			GLFW.glfwPollEvents();
			failed = false;
		} finally {
			FrameTimeRecorder.endDetail(FrameTimeDetails.POLL_BEFORE, token, failed);
		}
	}

	@Redirect(method = "flipFrame", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwPollEvents()V", ordinal = 1, remap = false), require = 1, allow = 1)
	private static void iris$pollAfter() {
		long token = FrameTimeRecorder.beginDetail(FrameTimeDetails.POLL_AFTER);
		boolean failed = true;
		try {
			GLFW.glfwPollEvents();
			failed = false;
		} finally {
			FrameTimeRecorder.endDetail(FrameTimeDetails.POLL_AFTER, token, failed);
		}
	}

	@Redirect(method = "flipFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;replayQueue()V", remap = false), require = 1, allow = 1)
	private static void iris$replayQueue() {
		long token = FrameTimeRecorder.beginDetail(FrameTimeDetails.RENDER_QUEUE);
		boolean failed = true;
		try {
			RenderSystem.replayQueue();
			failed = false;
		} finally {
			FrameTimeRecorder.endDetail(FrameTimeDetails.RENDER_QUEUE, token, failed);
		}
	}

	@Redirect(method = "flipFrame", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V", remap = false), require = 1, allow = 1)
	private static void iris$swap(long window) {
		FrameTimeRecorder.beginPhase(FrameTimeRecorder.SWAP);
		long token = FrameTimeRecorder.beginDetail(FrameTimeDetails.SWAP_BUFFERS);
		boolean failed = true;
		try {
			GLFW.glfwSwapBuffers(window);
			failed = false;
		} finally {
			FrameTimeRecorder.endDetail(FrameTimeDetails.SWAP_BUFFERS, token, failed);
			FrameTimeRecorder.endPhase(FrameTimeRecorder.SWAP);
		}
	}
}
