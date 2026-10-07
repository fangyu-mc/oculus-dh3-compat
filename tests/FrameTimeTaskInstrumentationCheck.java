package net.coderbot.iris.diagnostics;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;

/** CPU-only task/packet dispatch fixtures, also passed through the real Mixin writer. */
final class FrameTimeTaskInstrumentationCheck {
	static final String TASK = "net/minecraft/util/thread/BlockableEventLoop";
	static final String PACKETS = "net/minecraft/network/protocol/PacketUtils";
	private static final String PACKET = "net/minecraft/network/protocol/Packet";
	private static final String LISTENER = "net/minecraft/network/PacketListener";
	public static class State {
		public static int count;
		public static Throwable caught;
		static final RuntimeException ERROR = new IllegalStateException("same task failure");
	}
	public static class TaskHost {
		public void doRunTask(Runnable work) {
			try { work.run(); } catch (Exception e) { State.caught = e; }
		}
	}
	public static class Listener { public boolean connected = true; }
	public interface Packet { void handle(Listener listener); }
	public static class PacketHost {
		public static void dispatch(Listener listener, Packet packet) {
			if (listener.connected) { packet.handle(listener); }
		}
	}
	public static class Work implements Runnable, Packet {
		final boolean fail;
		Work(boolean fail) { this.fail = fail; }
		public void run() { State.count++; if (fail) { throw State.ERROR; } }
		public void handle(Listener listener) { run(); }
	}

	static byte[] fixture(boolean task) throws Exception {
		Class<?> source = task ? TaskHost.class : PacketHost.class;
		Map<String, String> names = new HashMap<>();
		names.put(Type.getInternalName(source), task ? TASK : PACKETS);
		names.put(Type.getInternalName(Packet.class), PACKET);
		names.put(Type.getInternalName(Listener.class), LISTENER);
		try (InputStream in = source.getClassLoader().getResourceAsStream(Type.getInternalName(source) + ".class")) {
			ClassWriter w = new ClassWriter(0);
			new ClassReader(in).accept(new ClassRemapper(w, new SimpleRemapper(names)), 0);
			return w.toByteArray();
		}
	}

	static void run() throws Exception {
		for (boolean task : new boolean[]{true, false}) {
			byte[] original = fixture(task);
			ClassNode target = new ClassNode(); new ClassReader(original).accept(target, 0);
			check(FrameTimeDetailInstrumentation.instrument(target) == 1, "dispatch timer missing");
			check(FrameTimeDetailInstrumentation.instrument(target) == 0, "duplicate dispatch timer");
			ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS); target.accept(w);
			verify(original, w.toByteArray(), task);
		}
	}

	static void verify(byte[] before, byte[] after, boolean task) throws Exception {
		Class<?> old = load(before, task), current = load(after, task);
		for (int scenario = 0; scenario < 4; scenario++) {
			List<Object> expected = invoke(old, task, scenario), actual = invoke(current, task, scenario);
			check(expected.equals(actual), "dispatch behavior changed: " + expected + " / " + actual);
			int calls = !task && scenario == 3 ? 0 : 1;
			FrameTimeDetails details = FrameTimeInstrumentationCheck.Probe.details;
			int scope = task ? FrameTimeDetails.MAIN_TASK : FrameTimeDetails.PACKET_APPLY;
			check(details.valid[0] && details.calls[scope][0] == calls, "dispatch balance/disconnected packet");
			check(details.failures[scope][0] == (scenario == 1 || scenario == 2 ? 1 : 0), "original exception attribution");
			check(FrameTimeInstrumentationCheck.Probe.workTypes.size() == calls, "work type hook missing");
			if (calls != 0) check(FrameTimeInstrumentationCheck.Probe.workTypes.get(0) == (scenario == 2 ? null : Work.class), "wrong dispatched type");
		}
	}

	private static List<Object> invoke(Class<?> host, boolean task, int scenario) throws Exception {
		FrameTimeInstrumentationCheck.Probe.reset(0);
		State.count = 0; State.caught = null;
		Work work = scenario == 2 ? null : new Work(scenario == 1);
		Throwable thrown = null;
		try {
			if (task) { host.getMethod("doRunTask", Runnable.class).invoke(host.getDeclaredConstructor().newInstance(), work); }
			else {
				Listener listener = new Listener(); listener.connected = scenario != 3;
				host.getMethod("dispatch", Listener.class, Packet.class).invoke(null, listener, work);
			}
		} catch (InvocationTargetException e) { thrown = e.getCause(); }
		FrameTimeInstrumentationCheck.Probe.details.finishFrame(0, 1000);
		return Arrays.asList(State.count, normalize(State.caught), normalize(thrown));
	}
	private static Object normalize(Throwable t) { return t instanceof NullPointerException ? NullPointerException.class : t; }
	private static Class<?> load(byte[] bytes, boolean task) {
		Map<String, String> names = new HashMap<>();
		names.put(task ? TASK : PACKETS, task ? "fixture/Tasks" : "fixture/Packets");
		names.put(PACKET, Type.getInternalName(Packet.class)); names.put(LISTENER, Type.getInternalName(Listener.class));
		names.put("net/coderbot/iris/diagnostics/FrameTimeRecorder", Type.getInternalName(FrameTimeInstrumentationCheck.Probe.class));
		ClassWriter w = new ClassWriter(0); new ClassReader(bytes).accept(new ClassRemapper(w, new SimpleRemapper(names)), 0);
		return new ClassLoader(FrameTimeTaskInstrumentationCheck.class.getClassLoader()) {
			Class<?> define() { byte[] data = w.toByteArray(); return defineClass(null, data, 0, data.length); }
		}.define();
	}
	private static void check(boolean ok, String message) { if (!ok) { throw new AssertionError(message); } }
}
