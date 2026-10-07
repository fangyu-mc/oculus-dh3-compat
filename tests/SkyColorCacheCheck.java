package net.coderbot.iris.uniforms;

public final class SkyColorCacheCheck {
	private static final class Fixture {
		private int samples;
		private boolean fail;
		private final SkyColorCache<Object> cache = new SkyColorCache<>(() -> {
			samples++;
			if (fail) {
				throw new IllegalStateException("sample failed");
			}
			return new Object();
		});
		private final SystemTimeUniforms.FrameCounter frames = SystemTimeUniforms.COUNTER;
		private Object world = new Object();
		private Object camera = new Object();
		private int x;
		private int y;
		private int z;
		private float tickDelta = 0.25F;

		Object get() {
			return cache.get(frames.getFrameId(), world, camera, x, y, z, tickDelta);
		}

		Object miss(Object previous) {
			int before = samples;
			Object current = get();
			check(current != previous && samples == before + 1, "changed view reused a stale sample");
			check(get() == current && samples == before + 1, "unchanged view resampled");
			return current;
		}
	}

	public static void main(String[] args) {
		Fixture f = new Fixture();
		Object value = f.get();
		for (int program = 0; program < 64; program++) {
			check(f.get() == value, "programs did not share the sample");
		}
		check(f.samples == 1, "more than one sample in the same view");
		f.frames.beginFrame();
		value = f.miss(value);
		f.world = new Object();
		value = f.miss(value);
		f.camera = new Object();
		value = f.miss(value);
		f.x--;
		value = f.miss(value);
		f.y++;
		value = f.miss(value);
		f.z++;
		value = f.miss(value);
		f.tickDelta = 0.5F;
		value = f.miss(value);
		f.cache.invalidate();
		value = f.miss(value);

		f.frames.reset();
		value = f.miss(value);
		check(f.frames.getAsInt() == 0, "shader frameCounter no longer resets to zero");
		f.frames.reset();
		value = f.miss(value);
		long frameId = f.frames.getFrameId();
		for (int i = 0; i < 720720; i++) {
			f.frames.beginFrame();
		}
		check(f.frames.getAsInt() == 0, "shader frameCounter wrap changed");
		check(f.frames.getFrameId() != frameId, "internal frame identity wrapped with the uniform");
		value = f.miss(value);

		f.frames.beginFrame();
		f.fail = true;
		try {
			f.get();
			throw new AssertionError("sample exception swallowed");
		} catch (IllegalStateException expected) {
			// A retry in this same frame must sample again.
		}
		f.fail = false;
		f.miss(value);
		System.out.println("Sky cache: 64 programs share one sample; frame/world/camera/position/partial tick, "
				+ "unload invalidation, pipeline reset, counter wrap and failed-sample retry passed.");
	}

	private static void check(boolean value, String message) {
		if (!value) {
			throw new AssertionError(message);
		}
	}
}
