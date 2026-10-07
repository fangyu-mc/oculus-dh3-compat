package net.coderbot.iris.shadows.frustum.advanced;

import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import net.coderbot.iris.vendored.joml.Matrix4f;
import net.coderbot.iris.vendored.joml.Vector3f;

/** Optional local CPU benchmark, intentionally not a timing assertion in the build. */
public final class ShadowFrustumBenchmark {
	private static volatile long sink;

	public static void main(String[] args) {
		AdvancedShadowCullingFrustum[] current = new AdvancedShadowCullingFrustum[12];
		LegacyShadowFrustum[] reference = new LegacyShadowFrustum[12];
		for (int scene = 0; scene < current.length; scene++) {
			Matrix4f view = new Matrix4f().rotationYXZ(scene * 0.617F, scene * 0.193F - 0.6F, 0);
			Matrix4f projection = new Matrix4f().setPerspective(1.2F, 1.777F, 0.05F, 256);
			Vector3f light = new Vector3f((float) Math.sin(scene * 0.37D), 0.6F,
					(float) Math.cos(scene * 0.37D)).normalize();
			current[scene] = new AdvancedShadowCullingFrustum(view, projection, light, null);
			reference[scene] = new LegacyShadowFrustum(view, projection, light, null);
			current[scene].prepare(3.25D, -2.5D, 0.35D);
			reference[scene].prepare(3.25D, -2.5D, 0.35D);
		}
		for (String mode : new String[]{"chunk grid", "random boxes", "near camera"}) {
			float[] boxes = boxes(mode);
			for (int i = 0; i < 20; i++) {
				long before = runOld(reference, boxes, 4);
				long after = runNew(current, boxes, 4);
				if (before != after) {
					throw new AssertionError("benchmark visibility differs");
				}
			}
			double[] oldTime = new double[9], newTime = new double[9];
			double count = current.length * (boxes.length / 6.0D) * 24;
			for (int i = 0; i < oldTime.length; i++) {
				// Alternate ordering to reduce thermal / scheduling bias.
				if (i % 2 == 0) {
					oldTime[i] = oldTime(reference, boxes) / count;
					newTime[i] = newTime(current, boxes) / count;
				} else {
					newTime[i] = newTime(current, boxes) / count;
					oldTime[i] = oldTime(reference, boxes) / count;
				}
			}
			Arrays.sort(oldTime);
			Arrays.sort(newTime);
			System.out.printf(Locale.ROOT, "%s: old %.2f ns/box, new %.2f ns/box, %.1f%% less CPU time; checksum=%d%n",
					mode, oldTime[4], newTime[4], (1.0D - newTime[4] / oldTime[4]) * 100.0D, sink);
		}
	}

	private static long oldTime(LegacyShadowFrustum[] frustums, float[] boxes) {
		long start = System.nanoTime();
		runOld(frustums, boxes, 24);
		return System.nanoTime() - start;
	}

	private static long newTime(AdvancedShadowCullingFrustum[] frustums, float[] boxes) {
		long start = System.nanoTime();
		runNew(frustums, boxes, 24);
		return System.nanoTime() - start;
	}

	private static long runOld(LegacyShadowFrustum[] frustums, float[] boxes, int repeats) {
		long count = 0;
		for (int k = 0; k < repeats; k++) {
			for (LegacyShadowFrustum frustum : frustums) {
				for (int i = 0; i < boxes.length; i += 6) {
					if (frustum.fastAabbTest(boxes[i], boxes[i + 1], boxes[i + 2], boxes[i + 3], boxes[i + 4], boxes[i + 5])) {
						count++;
					}
				}
			}
		}
		sink = count;
		return count;
	}

	private static long runNew(AdvancedShadowCullingFrustum[] frustums, float[] boxes, int repeats) {
		long count = 0;
		for (int k = 0; k < repeats; k++) {
			for (AdvancedShadowCullingFrustum frustum : frustums) {
				for (int i = 0; i < boxes.length; i += 6) {
					if (frustum.fastAabbTest(boxes[i], boxes[i + 1], boxes[i + 2], boxes[i + 3], boxes[i + 4], boxes[i + 5])) {
						count++;
					}
				}
			}
		}
		sink = count;
		return count;
	}

	private static float[] boxes(String mode) {
		float[] boxes = new float[8192 * 6];
		Random random = new Random(83132);
		for (int i = 0; i < boxes.length; i += 6) {
			if (mode.equals("chunk grid")) {
				int k = i / 6;
				boxes[i] = (k % 32 - 16) * 16;
				boxes[i + 1] = ((k / 32) % 8 - 4) * 16;
				boxes[i + 2] = (k / 256 - 16) * 16;
				boxes[i + 3] = boxes[i] + 16;
				boxes[i + 4] = boxes[i + 1] + 16;
				boxes[i + 5] = boxes[i + 2] + 16;
			} else {
				float range = mode.equals("near camera") ? 24 : 512;
				boxes[i] = (random.nextFloat() - 0.5F) * range;
				boxes[i + 1] = (random.nextFloat() - 0.5F) * range;
				boxes[i + 2] = (random.nextFloat() - 0.5F) * range;
				boxes[i + 3] = boxes[i] + 0.1F + random.nextFloat() * 16;
				boxes[i + 4] = boxes[i + 1] + 0.1F + random.nextFloat() * 8;
				boxes[i + 5] = boxes[i + 2] + 0.1F + random.nextFloat() * 16;
			}
		}
		return boxes;
	}
}
