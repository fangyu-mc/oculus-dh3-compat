package net.coderbot.iris.diagnostics;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;

import static org.objectweb.asm.Opcodes.*;

/** Runs the generated tick wrappers with plain Java fixtures; never starts Minecraft. */
final class FrameTimeTickInstrumentationCheck {
	static final String MC = "net/minecraft/client/Minecraft";
	private static final String LEVEL = "net/minecraft/client/multiplayer/ClientLevel";
	private static final String HOOKS = "net/minecraftforge/fml/hooks/BasicEventHooks";
	private static final String STATE = Type.getInternalName(State.class);

	public static class State {
		public static final RuntimeException ERROR = new IllegalStateException("tick body failed");
		public static final List<String> steps = new ArrayList<>();
		public static Level level;
		public static int mode, count;
		public static void onPreClientTick() { steps.add("pre"); if (mode == 1) { throw ERROR; } }
		public static void onPostClientTick() { steps.add("post"); if (mode == 3) { throw ERROR; } }
	}
	public static class Level {
		public void tickEntities() { throw new AssertionError("virtual dispatch lost"); }
		public void animateTick(int x, int y, int z) { State.steps.add("animate:" + x + ":" + y + ":" + z); }
	}
	public static class ActualLevel extends Level {
		@Override public void tickEntities() { State.steps.add("entities"); State.count++; if (State.mode == 2) { throw State.ERROR; } }
	}

	static void run() throws Exception {
		byte[] original = fixture();
		ClassNode target = new ClassNode(); new ClassReader(original).accept(target, 0);
		check(FrameTimeDetailInstrumentation.instrument(target) == 5, "tick call scopes missing");
		check(FrameTimeDetailInstrumentation.instrument(target) == 0, "tick hooks duplicated");
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS); target.accept(writer);
		verify(original, writer.toByteArray());
	}

	static void verify(byte[] original, byte[] transformed) throws Exception {
		Object old = load(original), current = load(transformed);
		for (int mode = 0; mode <= 4; mode++) {
			List<Object> expected = invoke(old, mode), actual = invoke(current, mode);
			check(expected.equals(actual), "tick behavior changed: " + expected + " / " + actual);
			FrameTimeDetails details = FrameTimeInstrumentationCheck.Probe.details;
			check(details.valid[0], "tick timers unbalanced");
			int calls = mode == 0 ? 10 : 1;
			check(details.calls[FrameTimeDetails.CLIENT_TICK][0] == calls, "catch-up tick count lost");
			check(details.failures[FrameTimeDetails.CLIENT_TICK][0] == (mode == 0 ? 0 : 1), "tick failure lost");
			check(details.calls[FrameTimeDetails.TICK_PRE][0] == calls, "pre events lost");
			check(details.calls[FrameTimeDetails.TICK_POST][0] == (mode == 0 ? 10 : mode == 3 ? 1 : 0), "post events moved before failure");
		}
		System.out.println("Tick wrappers: ten catch-up ticks, call order, integer arguments, virtual dispatch, null receiver and original exceptions preserved.");
	}

	static byte[] fixture() {
		ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		w.visit(V1_8, ACC_PUBLIC, MC, null, "java/lang/Object", null);
		MethodVisitor m = w.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
		m.visitCode(); m.visitVarInsn(ALOAD, 0); m.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false); m.visitInsn(RETURN); m.visitMaxs(0, 0); m.visitEnd();
		m = w.visitMethod(ACC_PUBLIC, "tick", "()V", null, null);
		m.visitCode();
		m.visitMethodInsn(INVOKESTATIC, HOOKS, "onPreClientTick", "()V", false);
		m.visitFieldInsn(GETSTATIC, STATE, "level", "L" + LEVEL + ";");
		m.visitMethodInsn(INVOKEVIRTUAL, LEVEL, "tickEntities", "()V", false);
		m.visitFieldInsn(GETSTATIC, STATE, "level", "L" + LEVEL + ";");
		m.visitIntInsn(BIPUSH, -31); m.visitIntInsn(BIPUSH, 127); m.visitIntInsn(SIPUSH, 1024);
		m.visitMethodInsn(INVOKEVIRTUAL, LEVEL, "animateTick", "(III)V", false);
		m.visitMethodInsn(INVOKESTATIC, HOOKS, "onPostClientTick", "()V", false);
		m.visitInsn(RETURN); m.visitMaxs(0, 0); m.visitEnd();
		w.visitEnd(); return w.toByteArray();
	}

	private static List<Object> invoke(Object instance, int mode) throws Exception {
		FrameTimeInstrumentationCheck.Probe.reset(mode);
		State.mode = mode; State.count = 0; State.steps.clear(); State.level = mode == 4 ? null : new ActualLevel();
		Object error = null;
		try { for (int i = 0; i < 10; i++) { instance.getClass().getMethod("tick").invoke(instance); } }
		catch (InvocationTargetException e) { error = e.getCause() instanceof NullPointerException ? NullPointerException.class : e.getCause(); }
		FrameTimeInstrumentationCheck.Probe.details.finishFrame(0, 1000);
		return Arrays.asList(new ArrayList<>(State.steps), State.count, error);
	}
	private static Object load(byte[] bytes) throws Exception {
		Map<String, String> names = new HashMap<>();
		names.put(MC, "fixture/ClientTicks"); names.put(LEVEL, Type.getInternalName(Level.class)); names.put(HOOKS, STATE);
		names.put("net/coderbot/iris/diagnostics/FrameTimeRecorder", Type.getInternalName(FrameTimeInstrumentationCheck.Probe.class));
		ClassWriter w = new ClassWriter(0); new ClassReader(bytes).accept(new ClassRemapper(w, new SimpleRemapper(names)), 0);
		return new Loader().define(w.toByteArray()).getDeclaredConstructor().newInstance();
	}
	private static class Loader extends ClassLoader {
		Loader() { super(FrameTimeTickInstrumentationCheck.class.getClassLoader()); }
		Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
	}
	private static void check(boolean value, String message) { if (!value) { throw new AssertionError(message); } }
}
