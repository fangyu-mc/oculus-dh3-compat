package net.coderbot.iris.compat.dh;

import java.util.Optional;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiRenderPass;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiFogDrawMode;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeGenericObjectRenderEvent;
import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiFramebuffer;
import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiShaderProgram;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import com.seibel.distanthorizons.coreapi.DependencyInjection.OverrideInjector;

import net.coderbot.iris.Iris;
import net.coderbot.iris.gl.framebuffer.GlFramebuffer;
import net.coderbot.iris.gl.texture.DepthBufferFormat;
import net.coderbot.iris.gl.texture.DepthCopyStrategy;
import net.coderbot.iris.pipeline.DeferredWorldRenderingPipeline;
import net.coderbot.iris.rendertarget.DepthTexture;
import net.coderbot.iris.shaderpack.CloudSetting;
import net.coderbot.iris.shaderpack.ProgramSet;
import net.coderbot.iris.shaderpack.ProgramSource;
import net.coderbot.iris.shadows.Matrix4fAccess;
import net.coderbot.iris.uniforms.CapturedRenderingState;
import net.coderbot.iris.vendored.joml.Matrix4f;

public final class DHCompat implements DHCompatBridge.Lifecycle {
	private static final float[] IDENTITY = new Matrix4f().get(new float[16]);
	private static DHCompat active;
	private static final float[] projection = new Matrix4f().get(new float[16]);
	private static final float[] projectionInverse = new Matrix4f().get(new float[16]);
	private static int depthTexture;
	private static boolean transparentPass;
	private static boolean cloudEventBound;
	private static boolean configOverridesApplied;
	private static boolean transparentDeferralApplied;

	private final IrisLodRenderProgram shaderProgram;
	private final DhFrameBufferWrapper framebufferWrapper;
	private final GlFramebuffer terrainFramebuffer;
	private final GlFramebuffer translucentFramebuffer;
	private final boolean disableDhClouds;
	private DepthTexture depthTextureNoTranslucents;
	private int depthTextureWidth = -1;
	private int depthTextureHeight = -1;
	private boolean opaqueDepthCopyPending = true;

	public DHCompat(DeferredWorldRenderingPipeline pipeline, ProgramSet programs) {
		CloudSetting dhCloudSetting = programs.getPackDirectives().getDHCloudSetting();
		disableDhClouds = dhCloudSetting == CloudSetting.OFF
			|| (dhCloudSetting == CloudSetting.DEFAULT
				&& programs.getPackDirectives().getCloudSetting() == CloudSetting.OFF);
		bindCloudEventHandler();

		Optional<ProgramSource> terrain = programs.getDhTerrain();
		if (!terrain.isPresent()) {
			Iris.logger.warn("The active shader pack does not provide dh_terrain; DH shader integration is disabled.");
			shaderProgram = null;
			framebufferWrapper = null;
			terrainFramebuffer = null;
			translucentFramebuffer = null;
			return;
		}

		shaderProgram = IrisLodRenderProgram.create(terrain.get(), programs.getDhWater(), pipeline);
		terrainFramebuffer = pipeline.createDHFramebuffer(terrain.get(), false);
		translucentFramebuffer = programs.getDhWater()
			.map(source -> pipeline.createDHFramebuffer(source, true))
			.orElse(terrainFramebuffer);
		framebufferWrapper = new DhFrameBufferWrapper(terrainFramebuffer, translucentFramebuffer);
		active = this;
		OverrideInjector.INSTANCE.bind(IDhApiShaderProgram.class, shaderProgram);
		OverrideInjector.INSTANCE.bind(IDhApiFramebuffer.class, framebufferWrapper);
		refreshDepthTexture();
		applyDhConfigOverrides();
		Iris.logger.info("Enabled Distant Horizons shader-pack GBuffer integration.");
	}

	private static void bindCloudEventHandler() {
		if (cloudEventBound) {
			return;
		}

		DhApi.events.bind(DhApiBeforeGenericObjectRenderEvent.class,
			new DhApiBeforeGenericObjectRenderEvent() {
				@Override
				public void beforeRender(DhApiCancelableEventParam<EventParam> event) {
					if (active != null && active.disableDhClouds
							&& "Clouds".equalsIgnoreCase(event.value.resourceLocationPath)) {
						event.cancelEvent();
					}
				}
			});
		cloudEventBound = true;
	}

	public void destroy() {
		if (shaderProgram == null) {
			return;
		}

		OverrideInjector.INSTANCE.unbind(IDhApiShaderProgram.class, shaderProgram);
		OverrideInjector.INSTANCE.unbind(IDhApiFramebuffer.class, framebufferWrapper);
		shaderProgram.free();
		// These framebuffers are registered in RenderTargets.ownedFramebuffers and are
		// destroyed with the rest of the pipeline's render targets.
		if (depthTextureNoTranslucents != null) {
			depthTextureNoTranslucents.destroy();
			depthTextureNoTranslucents = null;
		}
		if (active == this) {
			clearDhConfigOverrides();
			active = null;
			depthTexture = 0;
			transparentPass = false;
			System.arraycopy(IDENTITY, 0, projection, 0, IDENTITY.length);
			System.arraycopy(IDENTITY, 0, projectionInverse, 0, IDENTITY.length);
		}
	}

	static void setDepthTexture(int texture) {
		if (texture != depthTexture && active != null) {
			active.opaqueDepthCopyPending = true;
		}
		depthTexture = texture;
	}

	public static void setRenderPass(EDhApiRenderPass renderPass) {
		transparentPass = renderPass == EDhApiRenderPass.TRANSPARENT;
		applyDhConfigOverrides();
		if (depthTexture == 0) {
			refreshDepthTexture();
		}

		if (active == null) {
			return;
		}
		if (renderPass == EDhApiRenderPass.OPAQUE) {
			active.opaqueDepthCopyPending = true;
		} else if (renderPass == EDhApiRenderPass.OPAQUE_AND_TRANSPARENT) {
			active.opaqueDepthCopyPending = false;
		} else if (active.opaqueDepthCopyPending && active.copyOpaqueDepth()) {
			active.opaqueDepthCopyPending = false;
		}
	}

	private static void refreshDepthTexture() {
		if (active == null || DhApi.Delayed.renderProxy == null) {
			return;
		}

		DhApiResult<Integer> result = DhApi.Delayed.renderProxy.getDhDepthTextureId();
		if (result.success && result.payload != null && result.payload > 0) {
			depthTexture = result.payload;
			active.framebufferWrapper.attachDepthTexture(depthTexture);
		}
	}

	private static void applyDhConfigOverrides() {
		if (active == null) {
			return;
		}
		if (!configOverridesApplied && DhApi.Delayed.configs != null) {
			DhApi.Delayed.configs.graphics().ambientOcclusion().enabled().setValue(false);
			DhApi.Delayed.configs.graphics().fog().drawMode().setValue(EDhApiFogDrawMode.FOG_DISABLED);
			configOverridesApplied = true;
		}
		if (!transparentDeferralApplied && DhApi.Delayed.renderProxy != null) {
			DhApi.Delayed.renderProxy.setDeferTransparentRendering(true);
			transparentDeferralApplied = true;
		}
	}

	private static void clearDhConfigOverrides() {
		if (configOverridesApplied && DhApi.Delayed.configs != null) {
			DhApi.Delayed.configs.graphics().ambientOcclusion().enabled().clearValue();
			DhApi.Delayed.configs.graphics().fog().drawMode().clearValue();
		}
		if (transparentDeferralApplied && DhApi.Delayed.renderProxy != null) {
			DhApi.Delayed.renderProxy.setDeferTransparentRendering(false);
		}
		configOverridesApplied = false;
		transparentDeferralApplied = false;
	}

	static boolean isTransparentPass() {
		return transparentPass;
	}

	public static boolean isActive() {
		return active != null;
	}

	public static int getDepthTexture() {
		return depthTexture;
	}

	public static int getDepthTextureNoTranslucents() {
		return active == null || active.depthTextureNoTranslucents == null
			? depthTexture
			: active.depthTextureNoTranslucents.getTextureId();
	}

	private boolean copyOpaqueDepth() {
		if (depthTexture == 0) {
			return false;
		}

		int width = net.minecraft.client.Minecraft.getInstance().getMainRenderTarget().width;
		int height = net.minecraft.client.Minecraft.getInstance().getMainRenderTarget().height;
		if (width <= 0 || height <= 0) {
			return false;
		}
		if (depthTextureNoTranslucents == null || width != depthTextureWidth || height != depthTextureHeight) {
			if (depthTextureNoTranslucents != null) {
				depthTextureNoTranslucents.destroy();
			}
			depthTextureNoTranslucents = new DepthTexture(width, height, DepthBufferFormat.DEPTH32F);
			depthTextureWidth = width;
			depthTextureHeight = height;
		}

		DepthCopyStrategy.fastest(false).copy(terrainFramebuffer, depthTexture, null,
			depthTextureNoTranslucents.getTextureId(), width, height);
		return true;
	}

	public static int getRenderDistance() {
		if (DhApi.Delayed.configs == null) {
			return 0;
		}
		return DhApi.Delayed.configs.graphics().chunkRenderDistance().getValue() * 16;
	}

	public static float[] getProjection() {
		return projection;
	}

	public static float[] getProjectionInverse() {
		return projectionInverse;
	}

	@Override
	public int bridgeGetRenderDistance() {
		return getRenderDistance();
	}

	@Override
	public float[] bridgeGetProjection() {
		return getProjection();
	}

	@Override
	public float[] bridgeGetProjectionInverse() {
		return getProjectionInverse();
	}

	@Override
	public int bridgeGetDepthTexture() {
		return getDepthTexture();
	}

	@Override
	public int bridgeGetDepthTextureNoTranslucents() {
		return getDepthTextureNoTranslucents();
	}

	static void updateRenderParameters(Matrix4f dhProjection, Matrix4f dhProjectionInverse) {
		dhProjection.get(projection);
		dhProjectionInverse.get(projectionInverse);
	}

	static Matrix4f createProjectionMatrix(DhApiRenderParam param) {
		com.mojang.math.Matrix4f captured = CapturedRenderingState.INSTANCE.getGbufferProjection();
		if (captured == null) {
			return toJomlMatrix(param.dhProjectionMatrix);
		}

		Matrix4f projection = ((Matrix4fAccess) (Object) captured).convertToJOML();
		return new Matrix4f().setPerspective(projection.perspectiveFov(),
			projection.m11() / projection.m00(), param.nearClipPlane, param.farClipPlane);
	}

	static Matrix4f getModelViewMatrix(DhApiRenderParam param) {
		com.mojang.math.Matrix4f captured = CapturedRenderingState.INSTANCE.getGbufferModelView();
		return captured == null
			? toJomlMatrix(param.dhModelViewMatrix)
			: ((Matrix4fAccess) (Object) captured).convertToJOML();
	}

	private static Matrix4f toJomlMatrix(DhApiMat4f matrix) {
		// DhApiMat4f exposes row-major values; JOML's array setter expects column-major values.
		return new Matrix4f().set(matrix.getValuesAsArray()).transpose();
	}
}
