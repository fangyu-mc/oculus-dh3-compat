package net.coderbot.iris.mixin.math;

import net.coderbot.iris.math.VectorCubicSampler;
import net.minecraft.util.CubicSampler;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(CubicSampler.class)
public class MixinCubicSampler {
	/**
	 * @author Fangyu
	 * @reason Preserve Gaussian sky/fog sampling while avoiding temporary accumulator vectors.
	 */
	@Overwrite
	public static Vec3 gaussianSampleVec3(Vec3 position, CubicSampler.Vec3Fetcher fetcher) {
		return VectorCubicSampler.sample(position, fetcher);
	}
}
