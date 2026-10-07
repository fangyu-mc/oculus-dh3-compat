package net.coderbot.iris.shadows.frustum.advanced;

/** Immutable after construction; queries need no scratch storage or per-box allocation. */
final class ShadowFrustumPlanes {
	// Four adjacent coefficients per plane avoid chasing a Vector4f for each tested box.
	private final float[] coefficients;
	private int used;

	ShadowFrustumPlanes(int capacity) {
		coefficients = new float[capacity * 4];
	}

	void add(float x, float y, float z, float w) {
		coefficients[used++] = x;
		coefficients[used++] = y;
		coefficients[used++] = z;
		coefficients[used++] = -w;
	}

	boolean isVisible(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		for (int i = 0; i < used; i += 4) {
			float x = coefficients[i];
			float y = coefficients[i + 1];
			float z = coefficients[i + 2];
			float outsideX = x < 0.0F ? minX : maxX;
			float outsideY = y < 0.0F ? minY : maxY;
			float outsideZ = z < 0.0F ? minZ : maxZ;

			// Match the old nested JOML fma calls exactly (this Java 8 fork uses a * b + c).
			// Do not reassociate sums, normalize planes, or replace < with >=: boundaries and
			// non-finite planes must keep their previous visibility decisions.
			if (x * outsideX + (y * outsideY + z * outsideZ) < coefficients[i + 3]) {
				return false;
			}
		}
		return true;
	}
}
