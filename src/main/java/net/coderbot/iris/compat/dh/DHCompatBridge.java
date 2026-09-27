package net.coderbot.iris.compat.dh;

import java.lang.reflect.InvocationTargetException;

import net.coderbot.iris.pipeline.DeferredWorldRenderingPipeline;
import net.coderbot.iris.shaderpack.ProgramSet;
import net.minecraftforge.fml.loading.FMLLoader;

/**
 * Keeps Distant Horizons API types out of classes that load when DH is absent.
 */
public final class DHCompatBridge {
	private static final float[] IDENTITY = {
		1.0F, 0.0F, 0.0F, 0.0F,
		0.0F, 1.0F, 0.0F, 0.0F,
		0.0F, 0.0F, 1.0F, 0.0F,
		0.0F, 0.0F, 0.0F, 1.0F
	};
	private static final Lifecycle NOOP = new Lifecycle() {
		@Override
		public void destroy() {
		}

		@Override
		public int bridgeGetRenderDistance() {
			return 0;
		}

		@Override
		public float[] bridgeGetProjection() {
			return IDENTITY;
		}

		@Override
		public float[] bridgeGetProjectionInverse() {
			return IDENTITY;
		}

		@Override
		public int bridgeGetDepthTexture() {
			return 0;
		}

		@Override
		public int bridgeGetDepthTextureNoTranslucents() {
			return 0;
		}
	};

	private static Lifecycle active = NOOP;
	private final Lifecycle delegate;

	private DHCompatBridge(Lifecycle delegate) {
		this.delegate = delegate;
	}

	public static DHCompatBridge create(DeferredWorldRenderingPipeline pipeline, ProgramSet programs) {
		if (FMLLoader.getLoadingModList().getModFileById("distanthorizons") == null) {
			return new DHCompatBridge(NOOP);
		}

		try {
			Class<?> type = Class.forName("net.coderbot.iris.compat.dh.DHCompat", true,
				DHCompatBridge.class.getClassLoader());
			Lifecycle lifecycle = (Lifecycle) type
				.getConstructor(DeferredWorldRenderingPipeline.class, ProgramSet.class)
				.newInstance(pipeline, programs);
			active = lifecycle;
			return new DHCompatBridge(lifecycle);
		} catch (ReflectiveOperationException e) {
			Throwable cause = e instanceof InvocationTargetException && e.getCause() != null
				? e.getCause()
				: e;
			throw new RuntimeException("Failed to initialize Distant Horizons compatibility", cause);
		}
	}

	public void destroy() {
		delegate.destroy();
		if (active == delegate) {
			active = NOOP;
		}
	}

	public static int getRenderDistance() {
		return active.bridgeGetRenderDistance();
	}

	public static float[] getProjection() {
		return active.bridgeGetProjection();
	}

	public static float[] getProjectionInverse() {
		return active.bridgeGetProjectionInverse();
	}

	public static float[] getPreviousProjection() {
		return active.bridgeGetPreviousProjection();
	}

	public static int getDepthTexture() {
		return active.bridgeGetDepthTexture();
	}

	public static int getDepthTextureNoTranslucents() {
		return active.bridgeGetDepthTextureNoTranslucents();
	}

	public static float[] getModelView() { return active.bridgeGetModelView(); }
	public static float[] getModelViewInverse() { return active.bridgeGetModelViewInverse(); }
	public static float[] getPreviousModelView() { return active.bridgeGetPreviousModelView(); }
	public static float getNearPlane() { return active.bridgeGetNearPlane(); }
	public static float getFarPlane() { return active.bridgeGetFarPlane(); }

	public interface Lifecycle {
		default float[] bridgeGetModelView() { return IDENTITY; }
		default float[] bridgeGetModelViewInverse() { return IDENTITY; }
		default float[] bridgeGetPreviousModelView() { return IDENTITY; }
		default float bridgeGetNearPlane() { return 0; }
		default float bridgeGetFarPlane() { return 0; }
		void destroy();

		int bridgeGetRenderDistance();

		float[] bridgeGetProjection();

		float[] bridgeGetProjectionInverse();

		default float[] bridgeGetPreviousProjection() { return IDENTITY; }

		int bridgeGetDepthTexture();

		int bridgeGetDepthTextureNoTranslucents();
	}
}
