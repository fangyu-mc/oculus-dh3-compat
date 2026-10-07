package net.coderbot.iris.math;

import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.minecraft.util.CubicSampler;
import net.minecraft.world.phys.Vec3;

/** Compares with the actual, untransformed Minecraft sampler; does not launch Minecraft. */
public final class VectorCubicSamplerCheck {
	private static final class Survey implements CubicSampler.Vec3Fetcher {
		private final int mode;
		private int count;
		private long trace;

		Survey(int mode) {
			this.mode = mode;
		}

		@Override
		public Vec3 fetch(int x, int y, int z) {
			count++;
			trace = ((trace * 31 + x) * 31 + y) * 31 + z;
			if (mode == 0) {
				return Vec3.fromRGB24(x * 73428767 ^ y * 912931 ^ z * 438289);
			}
			if (mode == 1) {
				// Dynamic Surroundings visibility surveys use only the first component.
				return new Vec3(((x ^ y ^ z) & 255) / 255.0D, 0.0D, 0.0D);
			}
			return new Vec3(x * 0.003D, y * -0.007D, z * 0.011D);
		}
	}

	public static void main(String[] args) throws Exception {
		double[] boundaries = {-30000000.0D, -2048.25D, -1.0D, -0.0000001D, -0.0D,
				0.0D, 0.25D, 0.9999999D, 1.0D, 255.75D, 30000000.0D};
		for (double x : boundaries) {
			for (double y : boundaries) {
				for (int mode = 0; mode < 3; mode++) {
					compare(new Vec3(x, y, -x), mode);
				}
			}
		}
		randomChecks(137L, 3000);

		Vec3 position = new Vec3(-12.25D, 63.7D, 512.5D);
		Vec3 constant = new Vec3(0.2D, 0.5D, 0.9D);
		Vec3 fixed = VectorCubicSampler.sample(position, (x, y, z) -> constant);
		check(Math.abs(fixed.x - constant.x) < 1e-14D, "constant color changed");
		same(constant, new Vec3(0.2D, 0.5D, 0.9D));
		Vec3 linear = VectorCubicSampler.sample(position, (x, y, z) -> new Vec3(x, y, z));
		check(linear.distanceTo(position) < 1e-10D, "linear field interpolation changed");

		Vec3 nestedExpected = CubicSampler.gaussianSampleVec3(position, (x, y, z) ->
				CubicSampler.gaussianSampleVec3(new Vec3(x * 0.25D, y * 0.25D, z * 0.25D), new Survey(0)));
		Vec3 nestedActual = VectorCubicSampler.sample(position, (x, y, z) ->
				VectorCubicSampler.sample(new Vec3(x * 0.25D, y * 0.25D, z * 0.25D), new Survey(0)));
		same(nestedExpected, nestedActual);

		RuntimeException expectedFailure = new RuntimeException("fetch failed");
		try {
			VectorCubicSampler.sample(position, (x, y, z) -> { throw expectedFailure; });
			throw new AssertionError("fetch exception swallowed");
		} catch (RuntimeException actualFailure) {
			check(actualFailure == expectedFailure, "fetch exception replaced");
		}
		compare(position, 0);

		ExecutorService workers = Executors.newFixedThreadPool(4);
		try {
			Future<?>[] futures = new Future<?>[4];
			for (int i = 0; i < futures.length; i++) {
				final int seed = i;
				futures[i] = workers.submit(() -> randomChecks(seed, 250));
			}
			for (Future<?> future : futures) {
				future.get();
			}
		} finally {
			workers.shutdown();
		}
		System.out.println("Cubic sampler: 4363 boundary/random/concurrent comparisons match vanilla bit-for-bit; "
				+ "216 fetches and order preserved; constant/linear fields, nested sampling and exceptions passed.");
	}

	private static void randomChecks(long seed, int count) {
		Random random = new Random(seed);
		for (int i = 0; i < count; i++) {
			compare(new Vec3(random.nextDouble() * 60000000.0D - 30000000.0D,
					random.nextDouble() * 512.0D - 128.0D,
					random.nextDouble() * 60000000.0D - 30000000.0D), i % 3);
		}
	}

	private static void compare(Vec3 position, int mode) {
		Survey expected = new Survey(mode);
		Survey actual = new Survey(mode);
		same(CubicSampler.gaussianSampleVec3(position, expected), VectorCubicSampler.sample(position, actual));
		check(expected.count == 216 && actual.count == 216, "fetch count changed");
		check(expected.trace == actual.trace, "fetch positions or order changed");
	}

	private static void same(Vec3 expected, Vec3 actual) {
		check(Double.doubleToLongBits(expected.x) == Double.doubleToLongBits(actual.x)
				&& Double.doubleToLongBits(expected.y) == Double.doubleToLongBits(actual.y)
				&& Double.doubleToLongBits(expected.z) == Double.doubleToLongBits(actual.z),
				"vector mismatch: " + expected + " vs " + actual);
	}

	private static void check(boolean value, String message) {
		if (!value) {
			throw new AssertionError(message);
		}
	}
}
