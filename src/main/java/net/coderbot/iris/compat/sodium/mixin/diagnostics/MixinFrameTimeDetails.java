package net.coderbot.iris.compat.sodium.mixin.diagnostics;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** The plugin wraps complete methods after all injected callbacks have been applied. */
@Pseudo
@Mixin(targets = {
		"me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderManager",
		"me.jellysquid.mods.sodium.client.render.chunk.backends.multidraw.MultidrawChunkRenderBackend",
		"me.jellysquid.mods.sodium.client.gl.device.GLRenderDevice$ImmediateCommandList"
}, priority = 400, remap = false)
public abstract class MixinFrameTimeDetails { }
