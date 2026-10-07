package net.coderbot.iris.compat.sodium.impl;

import me.jellysquid.mods.sodium.client.model.quad.ModelQuadView;
import me.jellysquid.mods.sodium.client.model.quad.blender.BiomeColorBlender;
import net.minecraft.client.color.block.BlockColor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/** Worker-owned scratch cache, valid only while blending one vanilla water quad. */
public final class FluidQuadColorCache implements BlockColor {

	private final int[] colors = new int[9];
	private BlockColor provider;
	private BlockAndTintGetter world;
	private BlockState state;
	private int x, y, z, tint, valid;

	public int[] getColors(BiomeColorBlender blender, BlockColor source, BlockAndTintGetter level,
			BlockState blockState, BlockPos origin, ModelQuadView quad) {
		Fluid fluid = blockState.getFluidState().getType();
		// Keep custom fluids' per-call behavior. A reentrant blend must
		// also leave the outer quad's state intact.
		if (provider != null || (fluid != Fluids.WATER && fluid != Fluids.FLOWING_WATER)) {
			return blender.getColors(source, level, blockState, origin, quad);
		}
		provider = source;
		try {
			world = level;
			state = blockState;
			x = origin.getX();
			y = origin.getY();
			z = origin.getZ();
			tint = quad.getColorIndex();
			valid = 0;
			// Retain the original blender and provider, including biome blending,
			// color options and other mods' hooks. Only duplicate lookups are skipped.
			return blender.getColors(this, level, blockState, origin, quad);
		} finally {
			provider = null;
			world = null;
			state = null;
		}
	}

	@Override
	public int getColor(BlockState blockState, BlockAndTintGetter level, BlockPos pos, int tintIndex) {
		int dx = pos.getX() - x;
		int dz = pos.getZ() - z;
		if (level != world || blockState != state || tintIndex != tint || pos.getY() != y
				|| dx < 0 || dx > 2 || dz < 0 || dz > 2) {
			return provider.getColor(blockState, level, pos, tintIndex);
		}
		// SmoothBiomeColorBlender samples the surrounding 3x3 positions at the
		// block's Y. A bit mask handles every color value, including 0 and -1.
		int index = dx * 3 + dz;
		int bit = 1 << index;
		if ((valid & bit) == 0) {
			colors[index] = provider.getColor(blockState, level, pos, tintIndex);
			valid |= bit;
		}
		return colors[index];
	}
}
