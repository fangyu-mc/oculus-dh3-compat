package net.coderbot.iris.diagnostics;

import java.lang.reflect.InvocationTargetException;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;

import static org.objectweb.asm.Opcodes.*;

/** Executes generated call wrappers with Java substitutes for Xaero/GL, including DH-Xaero deferral. */
public final class FrameTimeMapInstrumentationCheck {
	static final String PROCESSOR = "xaero/map/MapProcessor";
	private static final String TEXTURE = "xaero/map/region/texture/RegionTexture";
	private static final String REGION = "xaero/map/region/LeveledRegion";
	private static final String UPLOAD = "(Lxaero/map/highlight/DimensionHighlighterHandler;Lxaero/map/graphics/TextureUploader;L" + REGION + ";Lxaero/map/region/texture/BranchTextureRenderer;II)J";
	private static final String STATE = Type.getInternalName(State.class);
	private static final String BUDGET = "redirect$test$dhXaero$boundTextureUploads";
	private static final String BUDGET_DESC = "(L" + TEXTURE + ";" + UPLOAD.substring(1);

	public static class State {
		public static final Object highlights = new Object(), uploader = new Object(), renderer = new Object();
		public static final Region region = new Region();
		public static final RuntimeException ERROR = new IllegalStateException("same map exception");
		public static Texture texture;
		public static boolean defer;
		public static int mode, calls, glCalls, intResult;
		public static long longResult;
	}
	public static class Region {
		public Texture getTexture(int x, int z) { check(x == 17 && z == -29, "lookup argument order"); return State.texture; }
	}
	public static class Texture {
		public long uploadBuffer(Object highlights, Object uploader, Region region, Object renderer, int x, int z) { throw new AssertionError("virtual dispatch lost"); }
	}
	public static class ActualTexture extends Texture {
		@Override public long uploadBuffer(Object highlights, Object uploader, Region region, Object renderer, int x, int z) {
			State.calls++;
			check(highlights == State.highlights && uploader == State.uploader && region == State.region && renderer == State.renderer && x == 17 && z == -29, "upload arguments changed");
			if (State.mode == 2) { throw State.ERROR; }
			return State.mode == 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
		}
	}
	public static class Gl {
		public static int glGetError() { State.glCalls++; if (State.mode == 5) { throw State.ERROR; } return 0x76543210; }
		public static void glClearColor(float a, float b, float c, float d) {
			State.glCalls++;
			check(Float.floatToRawIntBits(a) == Float.floatToRawIntBits(-0.0F) && b == 0.5F && c == 1.0F && d == -1.0F, "float argument bits/order");
		}
	}

	static void run() throws Exception {
		for (boolean budget : new boolean[]{false, true}) {
			byte[] original = fixture(budget);
			ClassNode target = new ClassNode(); new ClassReader(original).accept(target, 0);
			check(FrameTimeDetailInstrumentation.instrument(target) == 5, "call wrappers missing");
			check(FrameTimeDetailInstrumentation.instrument(target) == 0, "call wrappers not idempotent");
			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS); target.accept(writer);
			verify(original, writer.toByteArray(), budget);
		}
	}

	/** Verify that the installed map's six maintenance calls each have one distinct timer. */
	static void verifyMaintenanceScopes(ClassNode target) {
		Map<String, Integer> expected = new HashMap<>();
		expected.put(TEXTURE + ".postUpload", FrameTimeDetails.MAP_POST_UPLOAD);
		expected.put(REGION + ".onProcessingEnd", FrameTimeDetails.MAP_PROCESS_END);
		expected.put(REGION + ".processWhenLoadedChunksExist", FrameTimeDetails.MAP_LOADED_CHUNKS);
		expected.put(PROCESSOR + ".updateConfigValuesForMultipleThreads", FrameTimeDetails.MAP_CONFIG);
		expected.put(PROCESSOR + ".updateCaveStart", FrameTimeDetails.MAP_CAVE);
		expected.put("xaero/map/MapFullReloader.onRenderProcess", FrameTimeDetails.MAP_RELOAD);
		Set<Integer> scopes = new HashSet<>();
		for (MethodNode helper : target.methods) {
			if (!helper.name.startsWith("iris$frameDetail$map$")) { continue; }
			int scope = -1, timers = 0;
			String original = null;
			for (AbstractInsnNode insn : helper.instructions) {
				if (!(insn instanceof MethodInsnNode)) { continue; }
				MethodInsnNode call = (MethodInsnNode) insn;
				if (call.owner.equals("net/coderbot/iris/diagnostics/FrameTimeRecorder") && call.name.equals("beginDetail")) {
					scope = (Integer) ((LdcInsnNode) call.getPrevious()).cst; timers++;
				}
				if (expected.containsKey(call.owner + "." + call.name)) { original = call.owner + "." + call.name; }
			}
			if (original == null) { continue; }
			check(timers == 1 && scope == expected.remove(original), "maintenance call mislabeled or timed twice: " + original);
			check(scopes.add(scope), "maintenance categories still share a timer");
			check((FrameTimeDetails.availableHooks() & (1L << scope)) != 0, "maintenance availability bit missing");
		}
		check(expected.isEmpty() && scopes.size() == 6, "installed maintenance call coverage changed: " + expected);
	}

	static void verify(byte[] original, byte[] transformed, boolean budget) throws Exception {
		Object old = load(original), current = load(transformed);
		for (int mode = 0; mode <= 5; mode++) {
			List<Object> expected = invoke(old, mode, budget), actual = invoke(current, mode, budget);
			check(actual.equals(expected), "map behavior changed: " + expected + " / " + actual);
			FrameTimeDetails details = FrameTimeInstrumentationCheck.Probe.details;
			check(details.valid[0], "map instrumentation did not balance");
			int uploads = mode == 4 && budget ? 0 : 1;
			check(details.calls[FrameTimeDetails.MAP_BUFFER][0] == uploads, "deferred work was counted as an actual buffer call");
			check(details.calls[FrameTimeDetails.MAP_TEXTURE_LOOKUP][0] == 1, "reference return lost");
			check(details.failures[FrameTimeDetails.MAP_BUFFER][0] == (mode == 2 || mode == 3 ? 1 : 0), "buffer exception accounting");
			check(details.failures[FrameTimeDetails.MAP_GL_CHECK][0] == (mode == 5 ? 1 : 0), "primitive-return exception accounting");
		}
		System.out.println("Map call timers: " + (budget ? "DH-Xaero budget" : "native calls") + ": long/int/reference returns, float arguments, virtual dispatch, null receiver, original exceptions and deferral preserved.");
	}

	private static List<Object> invoke(Object instance, int mode, boolean budget) throws Exception {
		FrameTimeInstrumentationCheck.Probe.reset(mode);
		State.mode = mode; State.defer = mode == 4 && budget; State.texture = mode == 3 ? null : new ActualTexture();
		State.calls = State.glCalls = State.intResult = 0; State.longResult = 0;
		Object thrown = null;
		try { instance.getClass().getMethod("onRenderProcess", Object.class).invoke(instance, new Object()); }
		catch (InvocationTargetException e) { thrown = e.getCause() instanceof NullPointerException ? NullPointerException.class : e.getCause(); }
		FrameTimeInstrumentationCheck.Probe.details.finishFrame(0, 1000);
		return Arrays.asList(State.calls, State.glCalls, State.longResult, State.intResult, thrown);
	}

	static byte[] fixture(boolean budget) {
		ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		w.visit(V1_8, ACC_PUBLIC, PROCESSOR, null, "java/lang/Object", null);
		MethodVisitor m = w.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
		m.visitCode(); m.visitVarInsn(ALOAD, 0); m.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false); m.visitInsn(RETURN); m.visitMaxs(0, 0); m.visitEnd();
		m = w.visitMethod(ACC_PUBLIC, "onRenderProcess", "(Lnet/minecraft/client/Minecraft;)V", null, null);
		m.visitCode();
		// Keeping the original argument evaluation stack is part of the call-wrapper contract.
		if (budget) { m.visitVarInsn(ALOAD, 0); }
		m.visitFieldInsn(GETSTATIC, STATE, "region", "L" + Type.getInternalName(Region.class) + ";");
		m.visitIntInsn(BIPUSH, 17); m.visitIntInsn(BIPUSH, -29);
		m.visitMethodInsn(INVOKEVIRTUAL, REGION, "getTexture", "(II)L" + TEXTURE + ";", false);
		for (String field : new String[]{"highlights", "uploader", "region", "renderer"}) {
			m.visitFieldInsn(GETSTATIC, STATE, field, field.equals("region") ? "L" + Type.getInternalName(Region.class) + ";" : "Ljava/lang/Object;");
		}
		m.visitIntInsn(BIPUSH, 17); m.visitIntInsn(BIPUSH, -29);
		m.visitMethodInsn(budget ? INVOKESPECIAL : INVOKEVIRTUAL, budget ? PROCESSOR : TEXTURE, budget ? BUDGET : "uploadBuffer", budget ? BUDGET_DESC : UPLOAD, false);
		m.visitFieldInsn(PUTSTATIC, STATE, "longResult", "J");
		m.visitMethodInsn(INVOKESTATIC, "org/lwjgl/opengl/GL11", "glGetError", "()I", false);
		m.visitFieldInsn(PUTSTATIC, STATE, "intResult", "I");
		m.visitLdcInsn(-0.0F); m.visitLdcInsn(0.5F); m.visitLdcInsn(1.0F); m.visitLdcInsn(-1.0F);
		m.visitMethodInsn(INVOKESTATIC, "org/lwjgl/opengl/GL11", "glClearColor", "(FFFF)V", false);
		m.visitInsn(RETURN); m.visitMaxs(0, 0); m.visitEnd();
		if (budget) {
			m = w.visitMethod(ACC_PRIVATE, BUDGET, BUDGET_DESC, null, null);
			AnnotationVisitor merged = m.visitAnnotation("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;", true);
			merged.visit("mixin", "com.fangyu.dhxaero.mixin.MapProcessorBudgetMixin"); merged.visitEnd();
			m.visitCode(); Label admit = new Label();
			m.visitFieldInsn(GETSTATIC, STATE, "defer", "Z"); m.visitJumpInsn(IFEQ, admit);
			m.visitLdcInsn(4_000_000L); m.visitInsn(LRETURN);
			m.visitLabel(admit);
			for (int slot = 1; slot <= 5; slot++) { m.visitVarInsn(ALOAD, slot); }
			m.visitVarInsn(ILOAD, 6); m.visitVarInsn(ILOAD, 7);
			m.visitMethodInsn(INVOKEVIRTUAL, TEXTURE, "uploadBuffer", UPLOAD, false);
			m.visitInsn(LRETURN); m.visitMaxs(0, 0); m.visitEnd();
		}
		w.visitEnd(); return w.toByteArray();
	}

	private static Object load(byte[] bytes) throws Exception {
		Map<String, String> names = new HashMap<>();
		names.put(PROCESSOR, "fixture/MapProcessor"); names.put(TEXTURE, Type.getInternalName(Texture.class)); names.put(REGION, Type.getInternalName(Region.class));
		for (String name : new String[]{"net/minecraft/client/Minecraft", "xaero/map/highlight/DimensionHighlighterHandler", "xaero/map/graphics/TextureUploader", "xaero/map/region/texture/BranchTextureRenderer"}) { names.put(name, "java/lang/Object"); }
		names.put("org/lwjgl/opengl/GL11", Type.getInternalName(Gl.class));
		names.put("net/coderbot/iris/diagnostics/FrameTimeRecorder", Type.getInternalName(FrameTimeInstrumentationCheck.Probe.class));
		ClassWriter writer = new ClassWriter(0); new ClassReader(bytes).accept(new ClassRemapper(writer, new SimpleRemapper(names)), 0);
		return new Loader().define(writer.toByteArray()).getDeclaredConstructor().newInstance();
	}
	private static class Loader extends ClassLoader {
		Loader() { super(FrameTimeMapInstrumentationCheck.class.getClassLoader()); }
		Class<?> define(byte[] data) { return defineClass(null, data, 0, data.length); }
	}
	private static void check(boolean okay, String message) { if (!okay) { throw new AssertionError(message); } }
}
