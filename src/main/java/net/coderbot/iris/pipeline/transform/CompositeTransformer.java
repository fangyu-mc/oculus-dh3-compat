package net.coderbot.iris.pipeline.transform;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.expression.Expression;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;

class CompositeTransformer {
	public static void transform(
			ASTParser t,
			TranslationUnit tree,
			Root root) {
		CompositeDepthTransformer.transform(t, tree, root);
		patchDistantHorizonsTaaDepth(t, tree, root);

		// if using a lod texture sampler and on version 120, patch in the extension
		// #extension GL_ARB_shader_texture_lod : require
		if (tree.getVersionStatement().version.number <= 120
				&& Stream.concat(
						root.identifierIndex.getStream("texture2DLod"),
						root.identifierIndex.getStream("texture3DLod"))
						.filter(id -> id.getParent() instanceof FunctionCallExpression)
						.findAny().isPresent()) {
			tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
					"#extension GL_ARB_shader_texture_lod : require\n");
		}
	}

	private static void patchDistantHorizonsTaaDepth(ASTParser t, TranslationUnit tree, Root root) {
		if (!root.identifierIndex.has("DoTAA") || !root.identifierIndex.has("dhDepthTex1")) {
			return;
		}

		List<FunctionCallExpression> depthFetches = root.identifierIndex.getStream("texelFetch")
			.map(identifier -> identifier.getAncestor(FunctionCallExpression.class))
			.filter(call -> call != null && call.getParameters().size() == 3)
			.filter(call -> {
				Expression sampler = call.getParameters().get(0);
				return sampler instanceof ReferenceExpression
					&& ((ReferenceExpression) sampler).getIdentifier().getName().equals("depthtex1");
			})
			.distinct()
			.collect(Collectors.toList());

		if (depthFetches.isEmpty()) {
			return;
		}

		for (FunctionCallExpression fetch : depthFetches) {
			fetch.getFunctionName().setName("iris_DhDepthTexelFetch");
		}

		List<FunctionCallExpression> historySamples = root.identifierIndex.getStream("texture2D")
			.map(identifier -> identifier.getAncestor(FunctionCallExpression.class))
			.filter(call -> call != null && call.getParameters().size() == 2)
			.filter(call -> {
				Expression sampler = call.getParameters().get(0);
				return sampler instanceof ReferenceExpression
					&& ((ReferenceExpression) sampler).getIdentifier().getName().equals("colortex2");
			})
			.distinct()
			.collect(Collectors.toList());

		for (FunctionCallExpression sample : historySamples) {
			sample.getFunctionName().setName("iris_DhTaaHistorySample");
		}

		tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_FUNCTIONS,
			"vec4 iris_DhDepthTexelFetch(sampler2D depthSampler, ivec2 coord, int lod) {"
				+ " vec4 mcSample = texelFetch(depthSampler, coord, lod);"
				+ " if (mcSample.r < 1.0) return mcSample;"
				+ " float dhDepth = texelFetch(dhDepthTex1, coord, lod).r;"
				+ " if (dhDepth >= 1.0) return mcSample;"
				+ " vec2 uv = (vec2(coord) + vec2(0.5)) / vec2(viewWidth, viewHeight);"
				+ " vec4 dhView = dhProjectionInverse * vec4(uv * 2.0 - 1.0, dhDepth * 2.0 - 1.0, 1.0);"
				+ " dhView /= dhView.w;"
				+ " vec4 mcClip = gbufferProjection * dhView;"
				+ " mcSample.r = mcClip.z / mcClip.w * 0.5 + 0.5;"
				+ " return mcSample;"
				+ " }");

		if (!historySamples.isEmpty()) {
			tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_FUNCTIONS,
				"vec4 iris_DhTaaHistorySample(sampler2D historySampler, vec2 historyCoord) {"
					+ " float dhHere = texelFetch(dhDepthTex1, texelCoord, 0).r;"
					+ " float dhAtHistory = texture2D(dhDepthTex1, historyCoord).r;"
					+ " if (dhHere < 1.0 || dhAtHistory < 1.0)"
					+ " return vec4(texelFetch(colortex3, texelCoord, 0).rgb, 1.0);"
					+ " return texture2D(historySampler, historyCoord);"
					+ " }");
		}
	}
}
