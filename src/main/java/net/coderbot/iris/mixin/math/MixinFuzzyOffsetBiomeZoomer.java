package net.coderbot.iris.mixin.math;

import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.FuzzyOffsetBiomeZoomer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(FuzzyOffsetBiomeZoomer.class)
public abstract class MixinFuzzyOffsetBiomeZoomer {
	@Shadow
	private static double getFiddledDistance(long seed, int x, int y, int z, double dx, double dy, double dz) {
		throw new AssertionError("Mixin shadow");
	}

	/**
	 * @author Fangyu
	 * @reason Select the same nearest corner without allocating a double[8] for every biome query.
	 */
	@Overwrite
	public Biome getBiome(long seed, int x, int y, int z, BiomeManager.NoiseBiomeSource source) {
		int shiftedX = x - 2, shiftedY = y - 2, shiftedZ = z - 2;
		int cellX = shiftedX >> 2, cellY = shiftedY >> 2, cellZ = shiftedZ >> 2;
		double fracX = (double) (shiftedX & 3) / 4.0D;
		double fracY = (double) (shiftedY & 3) / 4.0D;
		double fracZ = (double) (shiftedZ & 3) / 4.0D;
		int closest = 0;
		double minimum = 0.0D;
		for (int corner = 0; corner < 8; corner++) {
			boolean lowerX = (corner & 4) == 0, lowerY = (corner & 2) == 0, lowerZ = (corner & 1) == 0;
			double distance = getFiddledDistance(seed,
					lowerX ? cellX : cellX + 1, lowerY ? cellY : cellY + 1, lowerZ ? cellZ : cellZ + 1,
					lowerX ? fracX : fracX - 1.0D, lowerY ? fracY : fracY - 1.0D, lowerZ ? fracZ : fracZ - 1.0D);
			// The original reduction starts with element zero and uses strict >. Keep both its
			// first-corner tie break and its NaN behavior, without changing the distance function.
			if (corner == 0 || minimum > distance) {
				closest = corner;
				minimum = distance;
			}
		}
		return source.getNoiseBiome((closest & 4) == 0 ? cellX : cellX + 1,
				(closest & 2) == 0 ? cellY : cellY + 1, (closest & 1) == 0 ? cellZ : cellZ + 1);
	}
}
