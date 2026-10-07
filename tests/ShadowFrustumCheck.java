package net.coderbot.iris.shadows.frustum.advanced;

import java.util.Random;
import net.coderbot.iris.shadows.frustum.BoxCuller;
import net.coderbot.iris.vendored.joml.Matrix4f;
import net.coderbot.iris.vendored.joml.Vector3f;
import net.minecraft.world.phys.AABB;

/** Differential math checks only: no client, GL context, world or server is started. */
public final class ShadowFrustumCheck {
	private static long comparisons;

	public static void main(String[] args) {
		checkPlaneBoundaries();
		checkSpecialValues();
		Random random = new Random(620441);
		for (int scene = 0; scene < 256; scene++) {
			Matrix4f view = new Matrix4f().rotationYXZ(random.nextFloat() * 6.28F,
					random.nextFloat() * 3.14F - 1.57F, random.nextFloat() * 0.2F);
			Matrix4f projection = new Matrix4f().setPerspective(0.35F + random.nextFloat() * 2.0F,
					0.5F + random.nextFloat() * 2.5F, 0.05F, 32.0F + random.nextFloat() * 4096.0F);
			projection.m20((random.nextFloat() - 0.5F) * 0.1F);
			Vector3f light = scene % 8 == 0 ? new Vector3f(0, scene % 16 == 0 ? 1 : -1, 0)
					: new Vector3f(random.nextFloat() - 0.5F, random.nextFloat() - 0.5F,
							random.nextFloat() - 0.5F).normalize();
			double distance = 32.0D + random.nextDouble() * 224.0D;
			AdvancedShadowCullingFrustum current = new AdvancedShadowCullingFrustum(view, projection, light,
					scene % 2 == 0 ? null : new BoxCuller(distance));
			LegacyShadowFrustum reference = new LegacyShadowFrustum(view, projection, light,
					scene % 2 == 0 ? null : new BoxCuller(distance));
			for (int move = 0; move < 3; move++) {
				double cx = coordinate(random, scene + move);
				double cy = random.nextDouble() * 320.0D - 64.0D;
				double cz = coordinate(random, scene + move + 1);
				current.prepare(cx, cy, cz);
				reference.prepare(cx, cy, cz);
				for (int box = 0; box < 2048; box++) {
					double x = cx + random.nextDouble() * 1024.0D - 512.0D;
					double y = cy + random.nextDouble() * 512.0D - 256.0D;
					double z = cz + random.nextDouble() * 1024.0D - 512.0D;
					double width = box % 2 == 0 ? 16.0D : random.nextDouble() * 64.0D;
					AABB aabb = new AABB(x, y, z, x + width, y + width * 0.7D, z + width);
					equal(reference.isVisible(aabb), current.isVisible(aabb), "entity AABB");
					equal(reference.fastAabbTest((float) x, (float) y, (float) z,
							(float) aabb.maxX, (float) aabb.maxY, (float) aabb.maxZ),
							current.fastAabbTest((float) x, (float) y, (float) z,
									(float) aabb.maxX, (float) aabb.maxY, (float) aabb.maxZ), "chunk AABB");
				}
			}
		}
		System.out.println("Shadow frustum: " + comparisons + " exact visibility comparisons passed; "
				+ "camera movement, sun directions, FOV, world borders, distance limits, plane boundaries and non-finite values.");
	}

	private static double coordinate(Random random, int scene) {
		return scene % 4 == 0 ? 30000000.0D - random.nextDouble() * 512.0D
				: scene % 4 == 1 ? -30000000.0D + random.nextDouble() * 512.0D
				: random.nextDouble() * 8192.0D - 4096.0D;
	}

	private static void checkPlaneBoundaries() {
		for (int mask = 0; mask < 8; mask++) {
			float x = (mask & 1) == 0 ? 1 : -1;
			float y = (mask & 2) == 0 ? 1 : -1;
			float z = (mask & 4) == 0 ? 1 : -1;
			float[][] planes = {{x, y, z, -(x + (y + z))}};
			ShadowFrustumPlanes packed = pack(planes);
			for (float q : new float[]{Math.nextDown(1.0F), 1.0F, Math.nextUp(1.0F)}) {
				equal(legacy(planes, q, 1, 1, q, 1, 1), packed.isVisible(q, 1, 1, q, 1, 1), "plane boundary");
			}
		}
	}

	private static void checkSpecialValues() {
		Random random = new Random(18723);
		float[] values = {0.0F, -0.0F, 1, -1, Float.MIN_VALUE, -Float.MIN_VALUE,
				Float.MAX_VALUE, -Float.MAX_VALUE, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
		for (int n = 0; n < 1400; n++) {
			float[][] planes = new float[n % 14][4];
			for (float[] plane : planes) {
				for (int i = 0; i < 4; i++) {
					plane[i] = n % 2 == 0 ? values[random.nextInt(values.length)] : Float.intBitsToFloat(random.nextInt());
				}
			}
			ShadowFrustumPlanes packed = pack(planes);
			for (int box = 0; box < 64; box++) {
				float a = values[random.nextInt(values.length)];
				float b = values[random.nextInt(values.length)];
				float c = values[random.nextInt(values.length)];
				equal(legacy(planes, a, b, c, a + 16, b + 16, c + 16),
						packed.isVisible(a, b, c, a + 16, b + 16, c + 16), "special floats");
			}
		}
	}

	private static ShadowFrustumPlanes pack(float[][] planes) {
		ShadowFrustumPlanes result = new ShadowFrustumPlanes(13);
		for (float[] p : planes) {
			result.add(p[0], p[1], p[2], p[3]);
		}
		return result;
	}

	private static boolean legacy(float[][] planes, float ax, float ay, float az, float bx, float by, float bz) {
		for (float[] p : planes) {
			float x = p[0] < 0 ? ax : bx, y = p[1] < 0 ? ay : by, z = p[2] < 0 ? az : bz;
			if (net.coderbot.iris.vendored.joml.Math.fma(p[0], x,
					net.coderbot.iris.vendored.joml.Math.fma(p[1], y, p[2] * z)) < -p[3]) {
				return false;
			}
		}
		return true;
	}

	private static void equal(boolean expected, boolean actual, String context) {
		comparisons++;
		if (expected != actual) {
			throw new AssertionError(context);
		}
	}
}
