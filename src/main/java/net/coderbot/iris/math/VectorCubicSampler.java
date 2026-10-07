package net.coderbot.iris.math;

import net.minecraft.util.CubicSampler;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Gaussian sampling with scalar accumulators instead of two temporary vectors per sample. */
public final class VectorCubicSampler {
	private static final double[] KERNEL = {0.0D, 1.0D, 4.0D, 6.0D, 4.0D, 1.0D, 0.0D};

	private VectorCubicSampler() {
	}

	/** A packed-color fetcher still implements vanilla's API for other sampler implementations. */
	@FunctionalInterface
	public interface RgbFetcher extends CubicSampler.Vec3Fetcher {
		int fetchRgb(int x, int y, int z);

		@Override
		default Vec3 fetch(int x, int y, int z) {
			return Vec3.fromRGB24(fetchRgb(x, y, z));
		}
	}

	public static Vec3 sample(Vec3 position, CubicSampler.Vec3Fetcher fetcher) {
		if (fetcher instanceof RgbFetcher) {
			return sampleRgb(position, (RgbFetcher) fetcher);
		}
		int baseX = Mth.floor(position.x());
		int baseY = Mth.floor(position.y());
		int baseZ = Mth.floor(position.z());
		double fractionX = position.x() - (double) baseX;
		double fractionY = position.y() - (double) baseY;
		double fractionZ = position.z() - (double) baseZ;
		double totalWeight = 0.0D;
		double red = 0.0D;
		double green = 0.0D;
		double blue = 0.0D;

		// Keep vanilla's fetch order, zero-weight fetches, and floating-point operation order.
		// No reusable scratch state: fetchers may re-enter the sampler or run on another thread.
		for (int x = 0; x < 6; x++) {
			double weightX = Mth.lerp(fractionX, KERNEL[x + 1], KERNEL[x]);
			int sampleX = baseX - 2 + x;
			for (int y = 0; y < 6; y++) {
				double weightY = Mth.lerp(fractionY, KERNEL[y + 1], KERNEL[y]);
				int sampleY = baseY - 2 + y;
				for (int z = 0; z < 6; z++) {
					double weightZ = Mth.lerp(fractionZ, KERNEL[z + 1], KERNEL[z]);
					int sampleZ = baseZ - 2 + z;
					double weight = weightX * weightY * weightZ;
					totalWeight += weight;
					Vec3 sample = fetcher.fetch(sampleX, sampleY, sampleZ);
					red += sample.x * weight;
					green += sample.y * weight;
					blue += sample.z * weight;
				}
			}
		}

		double scale = 1.0D / totalWeight;
		return new Vec3(red * scale, green * scale, blue * scale);
	}

	private static Vec3 sampleRgb(Vec3 position, RgbFetcher fetcher) {
		int baseX = Mth.floor(position.x());
		int baseY = Mth.floor(position.y());
		int baseZ = Mth.floor(position.z());
		double fractionX = position.x() - (double) baseX;
		double fractionY = position.y() - (double) baseY;
		double fractionZ = position.z() - (double) baseZ;
		double totalWeight = 0.0D;
		double red = 0.0D;
		double green = 0.0D;
		double blue = 0.0D;

		// Same 216 live fetches as the vector path, including zero weights. Divide each
		// component before multiplying by its weight, exactly as Vec3.fromRGB24 does.
		for (int x = 0; x < 6; x++) {
			double weightX = Mth.lerp(fractionX, KERNEL[x + 1], KERNEL[x]);
			int sampleX = baseX - 2 + x;
			for (int y = 0; y < 6; y++) {
				double weightY = Mth.lerp(fractionY, KERNEL[y + 1], KERNEL[y]);
				int sampleY = baseY - 2 + y;
				for (int z = 0; z < 6; z++) {
					double weightZ = Mth.lerp(fractionZ, KERNEL[z + 1], KERNEL[z]);
					int sampleZ = baseZ - 2 + z;
					double weight = weightX * weightY * weightZ;
					totalWeight += weight;
					int rgb = fetcher.fetchRgb(sampleX, sampleY, sampleZ);
					red += (double) (rgb >> 16 & 255) / 255.0D * weight;
					green += (double) (rgb >> 8 & 255) / 255.0D * weight;
					blue += (double) (rgb & 255) / 255.0D * weight;
				}
			}
		}

		double scale = 1.0D / totalWeight;
		return new Vec3(red * scale, green * scale, blue * scale);
	}
}
