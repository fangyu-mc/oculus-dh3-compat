package net.coderbot.iris.compat.dh;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryStack;

import com.google.common.primitives.Ints;
import com.mojang.blaze3d.platform.GlStateManager;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiRenderPass;
import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiShaderProgram;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3f;

import net.coderbot.iris.gl.IrisRenderSystem;
import net.coderbot.iris.gl.blending.BlendMode;
import net.coderbot.iris.gl.blending.BlendModeOverride;
import net.coderbot.iris.gl.blending.BufferBlendOverride;
import net.coderbot.iris.gl.program.ProgramImages;
import net.coderbot.iris.gl.program.ProgramSamplers;
import net.coderbot.iris.gl.program.ProgramUniforms;
import net.coderbot.iris.gl.shader.GlShader;
import net.coderbot.iris.gl.shader.ProgramCreator;
import net.coderbot.iris.gl.shader.ShaderType;
import net.coderbot.iris.pipeline.DeferredWorldRenderingPipeline;
import net.coderbot.iris.pipeline.PatchedShaderPrinter;
import net.coderbot.iris.pipeline.SodiumTerrainPipeline;
import net.coderbot.iris.pipeline.transform.PatchShaderType;
import net.coderbot.iris.pipeline.transform.TransformPatcher;
import net.coderbot.iris.shaderpack.ProgramSource;
import net.coderbot.iris.vendored.joml.Matrix4f;

public final class IrisLodRenderProgram implements IDhApiShaderProgram {
	private static final BlendModeOverride DEFAULT_TRANSLUCENT_BLEND = new BlendModeOverride(new BlendMode(
		GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA,
		GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA));

	private final ProgramState solid;
	private final ProgramState translucent;
	private final int vao;
	private final Matrix4f projectionInverseScratch = new Matrix4f();
	private final Matrix4f modelViewInverseScratch = new Matrix4f();
	private final Matrix4f normalMatrixScratch = new Matrix4f();
	private ProgramState active;

	private IrisLodRenderProgram(ProgramState solid, ProgramState translucent) {
		this.solid = solid;
		this.translucent = translucent;
		this.active = solid;
		this.vao = GL30C.glGenVertexArrays();
	}

	public static IrisLodRenderProgram create(ProgramSource terrain, Optional<ProgramSource> water,
			DeferredWorldRenderingPipeline pipeline) {
		ProgramState solid = ProgramState.create(terrain, pipeline.getSodiumTerrainPipeline());
		ProgramState translucent = water.isPresent()
			? ProgramState.create(water.get(), pipeline.getSodiumTerrainPipeline())
			: solid;
		return new IrisLodRenderProgram(solid, translucent);
	}

	@Override
	public boolean overrideThisFrame() {
		return DHCompat.isActive();
	}

	@Override
	public int getId() {
		return active.id;
	}

	@Override
	public void free() {
		solid.destroy();
		if (translucent != solid) {
			translucent.destroy();
		}
		GL30C.glDeleteVertexArrays(vao);
	}

	@Override
	public void bind() {
		active.bind();
		GL30C.glBindVertexArray(vao);
	}

	@Override
	public void unbind() {
		GL30C.glBindVertexArray(0);
		ProgramUniforms.clearActiveUniforms();
		ProgramSamplers.clearActiveSamplers();
		GlStateManager._glUseProgram(0);
		BlendModeOverride.restore();
	}

	@Override
	public void fillUniformData(DhApiRenderParam param) {
		active = param.renderPass == EDhApiRenderPass.TRANSPARENT ? translucent : solid;
		Matrix4f projection = DHCompat.createProjectionMatrix(param);
		Matrix4f modelView = DHCompat.getModelViewMatrix(param);
		projectionInverseScratch.set(projection).invert();
		modelViewInverseScratch.set(modelView).invert();
		normalMatrixScratch.set(modelViewInverseScratch).transpose();
		DHCompat.updateRenderParameters(projection, projectionInverseScratch);
		active.bind();
		GL30C.glBindVertexArray(vao);
		active.updateResources();

		setMatrix(active.projection, projection);
		setMatrix(active.projectionInverse, projectionInverseScratch);
		setMatrix(active.modelView, modelView);
		setMatrix(active.modelViewInverse, modelViewInverseScratch);
		setMatrix(active.normalMatrix, normalMatrixScratch);
		setMatrix(active.dhProjection, projection);
		setMatrix(active.dhProjectionInverse, projectionInverseScratch);
		setFloat(active.worldYOffset, param.worldYOffset);
		setFloat(active.microOffset, 0.01F);
	}

	@Override
	public void setModelOffsetPos(DhApiVec3f pos) {
		if (active.modelOffset != -1) {
			GL20C.glUniform3f(active.modelOffset, pos.x, pos.y, pos.z);
		}
	}

	@Override
	public void bindVertexBuffer(int vbo) {
		GL30C.glBindVertexArray(vao);
		GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, vbo);
		GL20C.glEnableVertexAttribArray(0);
		GL20C.glEnableVertexAttribArray(1);
		GL20C.glEnableVertexAttribArray(2);
		GL30C.glVertexAttribIPointer(0, 4, GL20C.GL_UNSIGNED_SHORT, 16, 0L);
		GL20C.glVertexAttribPointer(1, 4, GL20C.GL_UNSIGNED_BYTE, true, 16, 8L);
		GL30C.glVertexAttribIPointer(2, 4, GL20C.GL_UNSIGNED_BYTE, 16, 12L);
	}

	private static void setFloat(int location, float value) {
		if (location != -1) {
			GL20C.glUniform1f(location, value);
		}
	}

	private static void setMatrix(int location, Matrix4f matrix) {
		if (location == -1) {
			return;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			FloatBuffer buffer = stack.mallocFloat(16);
			matrix.get(buffer);
			IrisRenderSystem.uniformMatrix4fv(location, false, buffer);
		}
	}

	private static final class ProgramState {
		private final int id;
		private final ProgramUniforms uniforms;
		private final ProgramSamplers samplers;
		private final ProgramImages images;
		private final BlendModeOverride blend;
		private final List<BufferBlendOverride> bufferBlends;
		private final int modelOffset;
		private final int worldYOffset;
		private final int microOffset;
		private final int modelView;
		private final int modelViewInverse;
		private final int projection;
		private final int projectionInverse;
		private final int normalMatrix;
		private final int dhProjection;
		private final int dhProjectionInverse;

		private ProgramState(int id, ProgramUniforms uniforms, ProgramSamplers samplers, ProgramImages images,
				ProgramSource source) {
			this.id = id;
			this.uniforms = uniforms;
			this.samplers = samplers;
			this.images = images;
			this.blend = source.getDirectives().getBlendModeOverride().orElse(null);
			this.bufferBlends = new ArrayList<>();
			source.getDirectives().getBufferBlendOverrides().forEach(info -> {
				int index = Ints.indexOf(source.getDirectives().getDrawBuffers(), info.getIndex());
				if (index >= 0) {
					bufferBlends.add(new BufferBlendOverride(index, info.getBlendMode()));
				}
			});
			modelOffset = uniform("modelOffset");
			worldYOffset = uniform("worldYOffset");
			microOffset = uniform("mircoOffset");
			modelView = uniform("iris_ModelViewMatrix");
			modelViewInverse = uniform("iris_ModelViewMatrixInverse");
			projection = uniform("iris_ProjectionMatrix");
			projectionInverse = uniform("iris_ProjectionMatrixInverse");
			normalMatrix = uniform("iris_NormalMatrix");
			dhProjection = uniform("dhProjection");
			dhProjectionInverse = uniform("dhProjectionInverse");
		}

		private static ProgramState create(ProgramSource source, SodiumTerrainPipeline pipeline) {
			Map<PatchShaderType, String> transformed = TransformPatcher.patchDH(
				source.getVertexSource().orElseThrow(IllegalStateException::new),
				source.getGeometrySource().orElse(null),
				source.getFragmentSource().orElseThrow(IllegalStateException::new));
			String vertex = transformed.get(PatchShaderType.VERTEX);
			String geometry = transformed.get(PatchShaderType.GEOMETRY);
			String fragment = transformed.get(PatchShaderType.FRAGMENT);
			PatchedShaderPrinter.debugPatchedShaders(source.getName() + "_dh", vertex, geometry, fragment);

			GlShader vertexShader = new GlShader(ShaderType.VERTEX, source.getName() + "_dh.vsh", vertex);
			GlShader fragmentShader = new GlShader(ShaderType.FRAGMENT, source.getName() + "_dh.fsh", fragment);
			GlShader geometryShader = geometry == null ? null
				: new GlShader(ShaderType.GEOMETRY, source.getName() + "_dh.gsh", geometry);
			int id = geometryShader == null
				? ProgramCreator.create(source.getName() + "_dh", vertexShader, fragmentShader)
				: ProgramCreator.create(source.getName() + "_dh", vertexShader, geometryShader, fragmentShader);
			vertexShader.destroy();
			fragmentShader.destroy();
			if (geometryShader != null) {
				geometryShader.destroy();
			}

			return new ProgramState(id, pipeline.initUniforms(id), pipeline.initTerrainSamplers(id),
				pipeline.initTerrainImages(id), source);
		}

		private int uniform(String name) {
			return GL20C.glGetUniformLocation(id, name);
		}

		private void bind() {
			GlStateManager._glUseProgram(id);
			if (DHCompat.isTransparentPass()) {
				(blend != null ? blend : DEFAULT_TRANSLUCENT_BLEND).apply();
				for (BufferBlendOverride bufferBlend : bufferBlends) {
					bufferBlend.apply();
				}
			} else {
				// DH sets blend factors but leaves GL_BLEND unchanged. Opaque LODs must
				// not inherit blending from the surrounding translucent render stage.
				BlendModeOverride.OFF.apply();
			}
		}

		private void updateResources() {
			uniforms.update();
			samplers.update();
			images.update();
		}

		private void destroy() {
			GlStateManager.glDeleteProgram(id);
		}
	}
}
