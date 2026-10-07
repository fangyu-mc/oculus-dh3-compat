package net.coderbot.iris.uniforms;

import java.util.function.Supplier;

/**
 * Shares a sky sample between programs in one rendered view. Render-thread only.
 * Coordinates match ClientLevel.getSkyColor's block-position input; direction is not an input.
 */
final class SkyColorCache<T> {
	private final Supplier<T> sampler;
	private boolean valid;
	private long frame;
	private Object world;
	private Object camera;
	private int x;
	private int y;
	private int z;
	private int tickDeltaBits;
	private T value;

	SkyColorCache(Supplier<T> sampler) {
		this.sampler = sampler;
	}

	T get(long frame, Object world, Object camera, int x, int y, int z, float tickDelta) {
		int tickDeltaBits = Float.floatToRawIntBits(tickDelta);
		if (valid && this.frame == frame && this.world == world && this.camera == camera
				&& this.x == x && this.y == y && this.z == z && this.tickDeltaBits == tickDeltaBits) {
			return value;
		}

		// Publish the key only after sampling succeeds, so a failed sample cannot poison the cache.
		invalidate();
		T sampled = sampler.get();
		this.frame = frame;
		this.world = world;
		this.camera = camera;
		this.x = x;
		this.y = y;
		this.z = z;
		this.tickDeltaBits = tickDeltaBits;
		this.value = sampled;
		this.valid = true;
		return sampled;
	}

	void invalidate() {
		valid = false;
		world = null;
		camera = null;
		value = null;
	}
}
