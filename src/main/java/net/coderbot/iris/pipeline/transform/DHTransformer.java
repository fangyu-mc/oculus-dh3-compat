package net.coderbot.iris.pipeline.transform;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.query.match.AutoHintedMatcher;
import io.github.douira.glsl_transformer.ast.query.match.Matcher;
import io.github.douira.glsl_transformer.ast.node.expression.Expression;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import net.coderbot.iris.gl.shader.ShaderType;

final class DHTransformer {
	private static final AutoHintedMatcher<Expression> TEXTURE_MATRIX_0 =
		new AutoHintedMatcher<>("gl_TextureMatrix[0]", Matcher.expressionPattern);
	private static final AutoHintedMatcher<Expression> TEXTURE_MATRIX_1 =
		new AutoHintedMatcher<>("gl_TextureMatrix[1]", Matcher.expressionPattern);
	private static final AutoHintedMatcher<Expression> PLAYER_POSITION_CYLINDER_DISTANCE =
		new AutoHintedMatcher<>("max(length(playerPos.xz), abs(playerPos.y))", Matcher.expressionPattern);

	private DHTransformer() {
	}

	static void transform(ASTParser parser, TranslationUnit tree, Root root, Parameters parameters) {
		root.replaceExpressionMatches(parser, TEXTURE_MATRIX_0, "mat4(1.0)");
		root.replaceExpressionMatches(parser, TEXTURE_MATRIX_1, "mat4(1.0)");
		root.rename("gl_ModelViewMatrix", "iris_ModelViewMatrix");
		root.rename("gl_ModelViewMatrixInverse", "iris_ModelViewMatrixInverse");
		root.rename("gl_ProjectionMatrix", "iris_ProjectionMatrix");
		root.rename("gl_ProjectionMatrixInverse", "iris_ProjectionMatrixInverse");
		root.replaceReferenceExpressions(parser, "gl_ModelViewProjectionMatrix",
			"(iris_ProjectionMatrix * iris_ModelViewMatrix)");
		root.replaceReferenceExpressions(parser, "gl_NormalMatrix", "mat3(iris_NormalMatrix)");

		tree.parseAndInjectNodes(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
			"uniform mat4 iris_ModelViewMatrix;",
			"uniform mat4 iris_ModelViewMatrixInverse;",
			"uniform mat4 iris_ProjectionMatrix;",
			"uniform mat4 iris_ProjectionMatrixInverse;",
			"uniform mat4 iris_NormalMatrix;");

		if (parameters.type.glShaderType == ShaderType.FRAGMENT
				&& root.identifierIndex.has("ViewToPlayer")
				&& root.identifierIndex.has("viewPos")
				&& root.replaceExpressionMatches(parser, PLAYER_POSITION_CYLINDER_DISTANCE,
					"iris_dhLengthCylinderFromDepth(viewPos)")) {
			// Large DH triangles can produce unstable interpolated playerPos values on AMD.
			// Reconstruct the fade distance from per-fragment depth, as current
			// Complementary versions do, so the near transition cannot discard whole faces.
			tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_FUNCTIONS,
				"float iris_dhLengthCylinderFromDepth(vec3 viewPosition) {"
					+ " vec3 position = ViewToPlayer(viewPosition);"
					+ " return max(length(position.xz), abs(position.y));"
					+ " }");
		}

		if (parameters.type.glShaderType != ShaderType.VERTEX) {
			return;
		}

		root.replaceReferenceExpressions(parser, "gl_Vertex", "iris_getVertexPosition()");
		root.replaceReferenceExpressions(parser, "gl_MultiTexCoord0", "vec4(0.0, 0.0, 0.0, 1.0)");
		root.replaceReferenceExpressions(parser, "gl_MultiTexCoord1", "vec4(iris_LightCoord, 0.0, 1.0)");
		root.replaceReferenceExpressions(parser, "gl_MultiTexCoord2", "vec4(iris_LightCoord, 0.0, 1.0)");
		root.rename("gl_Color", "iris_vertexColor");
		root.rename("gl_Normal", "iris_vertexNormal");
		root.rename("ftransform", "iris_ftransform");

		tree.parseAndInjectNodes(parser, ASTInjectionPoint.BEFORE_FUNCTIONS,
			"in uvec4 vPosition;",
			"in vec4 iris_color;",
			"in uvec4 irisExtra;",
			"uniform vec3 modelOffset;",
			"uniform float mircoOffset;",
			"vec3 iris_vertexPosition;",
			"vec2 iris_LightCoord;",
			"int dhMaterialId;",
			"vec4 iris_vertexColor;",
			"vec3 iris_vertexNormal;",
			"const vec3 iris_dhNormals[6] = vec3[](vec3(0,-1,0), vec3(0,1,0), vec3(0,0,-1), vec3(0,0,1), vec3(-1,0,0), vec3(1,0,0));",
			"vec4 iris_getVertexPosition() { return vec4(modelOffset + iris_vertexPosition, 1.0); }",
			"vec4 iris_ftransform() { return (iris_ProjectionMatrix * iris_ModelViewMatrix) * iris_getVertexPosition(); }",
			"void iris_dhInit() {"
				+ " uint meta = vPosition.a;"
				+ " uint micro = (meta & 0xFF00u) >> 8u;"
				+ " float mx = (micro & 1u) != 0u ? mircoOffset : 0.0;"
				+ " mx = (micro & 2u) != 0u ? -mx : mx;"
				+ " float my = (micro & 4u) != 0u ? mircoOffset : 0.0;"
				+ " my = (micro & 8u) != 0u ? -my : my;"
				+ " float mz = (micro & 16u) != 0u ? mircoOffset : 0.0;"
				+ " mz = (micro & 32u) != 0u ? -mz : mz;"
				+ " uint lights = meta & 0xFFu;"
				+ " iris_vertexPosition = vec3(vPosition.xyz) + vec3(mx, my, mz);"
				+ " iris_vertexNormal = iris_dhNormals[min(irisExtra.y, 5u)];"
				+ " dhMaterialId = int(irisExtra.x);"
				+ " iris_LightCoord = vec2((float(lights / 16u) + 0.5) / 16.0, (float(lights % 16u) + 0.5) / 16.0);"
				+ " iris_vertexColor = iris_color;"
				+ " }");
		tree.prependMain(parser, "iris_dhInit();");
	}
}
