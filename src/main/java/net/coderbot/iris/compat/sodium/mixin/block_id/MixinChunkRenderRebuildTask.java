package net.coderbot.iris.compat.sodium.mixin.block_id;

import me.jellysquid.mods.sodium.client.render.chunk.compile.buffers.ChunkModelBuffers;
import me.jellysquid.mods.sodium.client.render.pipeline.FluidRenderer;
import net.coderbot.iris.vertices.ExtendedDataHelper;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import me.jellysquid.mods.sodium.client.render.chunk.tasks.ChunkRenderRebuildTask;
import me.jellysquid.mods.sodium.client.render.pipeline.context.ChunkRenderCacheLocal;
import net.coderbot.iris.compat.sodium.impl.block_context.ChunkBuildBuffersExt;
import net.minecraft.core.BlockPos;

/**
 * Passes additional information indirectly to the vertex writer to support the mc_Entity and at_midBlock parts of the vertex format.
 */
@Mixin(ChunkRenderRebuildTask.class)
public class MixinChunkRenderRebuildTask {
	@Redirect(method = "performBuild", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/state/BlockState;getSeed(Lnet/minecraft/core/BlockPos;)J"))
	private long iris$wrapGetBlockLayer(BlockState blockState, BlockPos pos, ChunkRenderCacheLocal cache, ChunkBuildBuffers buffers) {
		if (buffers instanceof ChunkBuildBuffersExt) {
			// Sections are aligned to 16 blocks. Masking also gives 0..15 for negative world coordinates.
			// Set this at the existing material hook instead of allocating a callback for every block.
			((ChunkBuildBuffersExt) buffers).iris$setLocalPos(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
			((ChunkBuildBuffersExt) buffers).iris$setMaterialId(blockState, ExtendedDataHelper.BLOCK_RENDER_TYPE);
		}

		return blockState.getSeed(pos);
	}

	@Redirect(method = "performBuild", at = @At(value = "INVOKE",
			target = "Lme/jellysquid/mods/sodium/client/render/pipeline/FluidRenderer;render(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/core/BlockPos;Lme/jellysquid/mods/sodium/client/render/chunk/compile/buffers/ChunkModelBuffers;)Z"))
	private boolean iris$wrapGetFluidLayer(FluidRenderer renderer, BlockAndTintGetter world, FluidState fluidState, BlockPos pos, ChunkModelBuffers modelBuffers, ChunkRenderCacheLocal cache, ChunkBuildBuffers buffers) {
		if (buffers instanceof ChunkBuildBuffersExt) {
			// Fluid-only blocks need their own position; do not depend on a model/seed hook having run.
			((ChunkBuildBuffersExt) buffers).iris$setLocalPos(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
			((ChunkBuildBuffersExt) buffers).iris$setMaterialId(fluidState.createLegacyBlock(), ExtendedDataHelper.FLUID_RENDER_TYPE);
		}

		return renderer.render(world, fluidState, pos, modelBuffers);
	}

	@Redirect(method = "performBuild",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;hasTileEntity()Z"))
	private boolean iris$resetContext(BlockState blockState, ChunkRenderCacheLocal cache, ChunkBuildBuffers buffers) {
		if (buffers instanceof ChunkBuildBuffersExt) {
			((ChunkBuildBuffersExt) buffers).iris$resetBlockContext();
		}
		return blockState.hasTileEntity();
	}
}
