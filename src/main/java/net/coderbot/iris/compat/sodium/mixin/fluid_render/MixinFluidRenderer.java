package net.coderbot.iris.compat.sodium.mixin.fluid_render;

import me.jellysquid.mods.sodium.client.model.quad.ModelQuadView;
import me.jellysquid.mods.sodium.client.model.quad.blender.BiomeColorBlender;
import me.jellysquid.mods.sodium.client.render.pipeline.FluidRenderer;
import net.coderbot.iris.compat.sodium.impl.FluidQuadColorCache;
import net.minecraft.client.color.block.BlockColor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(FluidRenderer.class)
public class MixinFluidRenderer {

	@Unique
	private final FluidQuadColorCache iris$fluidQuadColors = new FluidQuadColorCache();

	@Redirect(method = "getCornerHeight", remap = false, at = @At(value = "INVOKE", remap = true,
			target = "Lnet/minecraft/world/level/material/FluidState;getHeight(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)F"),
			require = 1, allow = 1)
	private float iris$heightAfterAboveCheck(FluidState state, BlockGetter world, BlockPos pos) {
		Fluid fluid = state.getType();
		// getCornerHeight already returns 1 when matching fluid exists above.
		// Native water/lava getHeight would repeat that query and allocate pos.above().
		// Custom fluids can have different height rules, so keep their original call.
		if (fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER
				|| fluid == Fluids.LAVA || fluid == Fluids.FLOWING_LAVA) {
			return state.getOwnHeight();
		}
		return state.getHeight(world, pos);
	}

	@Redirect(method = "calculateQuadColors", remap = false, at = @At(value = "INVOKE", remap = true,
			target = "Lme/jellysquid/mods/sodium/client/model/quad/blender/BiomeColorBlender;getColors(Lnet/minecraft/client/color/block/BlockColor;Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lme/jellysquid/mods/sodium/client/model/quad/ModelQuadView;)[I"),
			require = 1, allow = 1)
	private int[] iris$reuseQuadColorSamples(BiomeColorBlender blender, BlockColor provider,
			BlockAndTintGetter world, BlockState state, BlockPos pos, ModelQuadView quad) {
		return iris$fluidQuadColors.getColors(blender, provider, world, state, pos, quad);
	}
}
