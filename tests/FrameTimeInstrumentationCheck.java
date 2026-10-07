package net.coderbot.iris.diagnostics;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import net.coderbot.iris.mixin.compat.CompatMixinPlugin;
import org.lwjgl.glfw.GLFW;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.launch.platform.container.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.transformer.*;
import org.spongepowered.asm.service.*;

import static org.objectweb.asm.Opcodes.*;

/** CPU-only verifier: real installed bytecode + real Mixin, no game, window or OpenGL context. */
public final class FrameTimeInstrumentationCheck {
	private static final String TARGET = "xaero/map/graphics/TextureUploader";
	private static final String WINDOW = "com/mojang/blaze3d/systems/RenderSystem";
	private static final String RECORDER = "net/coderbot/iris/diagnostics/FrameTimeRecorder";
	private static final Map<String, byte[]> resources = new HashMap<>();
	private static ClassNode before;
	private static int realMethods;

	public interface Upload { void uploadTextures(); }
	public static class Fixture implements Upload {
		public synchronized void uploadTextures() {
			check(Thread.holdsLock(this), "synchronized method lost monitor");
			Probe.body++;
			try {
				if (Probe.mode == 0) { return; }
				if (Probe.mode == 1) { throw Probe.ERROR; }
				if (Probe.mode == 2) { throw new IllegalArgumentException("caught"); }
				if (Probe.mode == 3) { synchronized (Probe.LOCK) { throw Probe.ERROR; } }
				if (Probe.mode == 4 && Probe.body == 1) { uploadTextures(); }
			} catch (IllegalArgumentException expected) { Probe.caught++; }
			finally {
				Probe.cleanup++;
				if (Probe.mode == 5) { throw Probe.ERROR; }
			}
		}
	}
	@Mixin(targets = "xaero.map.graphics.TextureUploader", remap = false)
	public static class EarlierHook {
		@Inject(method = "uploadTextures", at = @At("HEAD"), cancellable = true)
		private void before(CallbackInfo ci) {
			Probe.head++;
			if (Probe.mode == 6) { ci.cancel(); }
			if (Probe.mode == 7) { throw Probe.ERROR; }
		}
	}
	public static class WindowFixture {
		public static void flipFrame(long window) {
			GLFW.glfwPollEvents(); replayQueue(); GLFW.glfwSwapBuffers(window); GLFW.glfwPollEvents();
		}
		public static void replayQueue() { Probe.steps.add("queue"); if (Probe.mode == 3) { throw Probe.ERROR; } }
	}
	public static class CallbackFixture {
		private double accumulatedScroll;
		public void onMove(long window, int x, int y) { callback(); }
		public void onResize(long window, int x, int y) { callback(); }
		public void onFramebufferResize(long window, int x, int y) { callback(); }
		public void onFocus(long window, boolean value) { callback(); }
		public void onEnter(long window, boolean value) { callback(); }
		public void onMove(long window, double x, double y) { callback(); }
		public void onScroll(long window, double x, double y) { accumulatedScroll += y; callback(); }
		public void onPress(long window, int button, int action, int mods) { buttonCall(window, button, action); }
		public void onDrop(long window, List<?> paths) { callback(); }
		public void charTyped(long window, int code, int mods) { callback(); }
		public void keyPress(long window, int key, int scan, int action, int mods) { callback(); }
		private static void callback() { Probe.body++; if (Probe.mode == 1) { throw Probe.ERROR; } }
		private static void buttonCall(long window, int button, int action) { callback(); }
	}
	// Installed zoom hooks cancel before FIELD/INVOKE instructions, with operands still on
	// the stack. RETURN may discard those operands, but a shared GOTO exit cannot merge them.
	@Mixin(targets = "net.minecraft.client.MouseHandler", remap = false)
	public static class CallbackCancelHook {
		@Inject(method = "onScroll", at = @At(value = "FIELD", target = "Lnet/minecraft/client/MouseHandler;accumulatedScroll:D", opcode = GETFIELD), cancellable = true)
		private void cancelScroll(CallbackInfo ci) { if (Probe.mode == 2) { ci.cancel(); } if (Probe.mode == 3) { throw Probe.ERROR; } }
		@Inject(method = "onPress", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;buttonCall(JII)V"), cancellable = true)
		private void cancelButton(CallbackInfo ci) { if (Probe.mode == 2) { ci.cancel(); } if (Probe.mode == 3) { throw Probe.ERROR; } }
	}
	public static class WindowCalls {
		public static void glfwPollEvents() {
			Probe.polls++; Probe.steps.add("poll");
			if (Probe.mode == Probe.polls) { throw Probe.ERROR; }
		}
		public static void glfwSwapBuffers(long window) { check(window == 123L, "window handle"); Probe.steps.add("swap"); if (Probe.mode == 4) { throw Probe.ERROR; } }
	}
	public static class Probe {
		public static final RuntimeException ERROR = new IllegalStateException("same throwable");
		public static final Object LOCK = new Object();
		public static int mode, body, head, cleanup, caught, polls;
		static long clock;
		static FrameTimeDetails details;
		public static final List<String> steps = new ArrayList<>();
		public static final List<Class<?>> workTypes = new ArrayList<>();
		public static long beginDetail(int scope) { steps.add("begin:" + scope); return details.begin(scope, ++clock, Thread.currentThread()); }
		public static void endDetail(int scope, long token, boolean failed) { details.end(scope, token, ++clock, Thread.currentThread(), failed); steps.add("end:" + scope); }
		public static void endWorkDetail(int scope, long token, boolean failed, Object work) {
			details.end(scope, token, ++clock, Thread.currentThread(), failed, work);
			workTypes.add(work == null ? null : work.getClass());
		}
		public static void beginPhase(int phase) { steps.add("phase:" + phase); }
		public static void endPhase(int phase) { steps.add("phase-end:" + phase); }
		static void reset(int scenario) {
			mode = scenario; body = head = cleanup = caught = polls = 0; clock = 100; steps.clear(); workTypes.clear();
			details = new FrameTimeDetails(1, 20, Thread.currentThread());
		}
	}

	public static void main(String[] args) throws Exception {
		verifyReal(null); // Compile-time Rubidium (named mappings).
		for (String jar : args) { try (ZipFile zip = new ZipFile(jar)) { verifyReal(zip); } }
		FrameTimeMapInstrumentationCheck.run();
		FrameTimeTickInstrumentationCheck.run();
		FrameTimeTaskInstrumentationCheck.run();
		verifyReturnStacks();
		ClassNode fixture = readResource(Type.getInternalName(Fixture.class));
		resources.put(TARGET + ".class", remap(fixture, Type.getInternalName(Fixture.class), TARGET));
		resources.put(WINDOW + ".class", remap(readResource(Type.getInternalName(WindowFixture.class)), Type.getInternalName(WindowFixture.class), WINDOW));
		// The optional marker has three targets; only the uploader fixture is transformed here.
		for (String other : new String[]{"xaero/map/MapProcessor", "xaero/map/region/texture/RegionTexture"}) {
			ClassWriter w = new ClassWriter(0); w.visit(V1_8, ACC_PUBLIC, other, null, "java/lang/Object", null); w.visitEnd();
			resources.put(other + ".class", w.toByteArray());
		}
		resources.put(FrameTimeMapInstrumentationCheck.PROCESSOR + ".class", FrameTimeMapInstrumentationCheck.fixture(true));
		resources.put(FrameTimeTickInstrumentationCheck.MC + ".class", FrameTimeTickInstrumentationCheck.fixture());
		resources.put(FrameTimeTaskInstrumentationCheck.TASK + ".class", FrameTimeTaskInstrumentationCheck.fixture(true));
		resources.put(FrameTimeTaskInstrumentationCheck.PACKETS + ".class", FrameTimeTaskInstrumentationCheck.fixture(false));
		String[] callbackTargets = {"com/mojang/blaze3d/platform/Window", "net/minecraft/client/MouseHandler", "net/minecraft/client/KeyboardHandler"};
		for (String name : callbackTargets) {
			resources.put(name + ".class", remap(readResource(Type.getInternalName(CallbackFixture.class)), Type.getInternalName(CallbackFixture.class), name));
		}
		config("detail-marker.json", "net.coderbot.iris.mixin.compat", "xaeroworldmap.MixinFrameTimeDetails", Plugin.class.getName());
		config("detail-early.json", "net.coderbot.iris.diagnostics", "FrameTimeInstrumentationCheck$EarlierHook", null);
		config("detail-window.json", "net.coderbot.iris.mixin.diagnostics", "MixinRenderSystem_FrameTime", null);
		config("detail-tick.json", "net.coderbot.iris.mixin.compat", "minecraft.MixinFrameTimeDetails", Plugin.class.getName());
		config("detail-callback-cancel.json", "net.coderbot.iris.diagnostics", "FrameTimeInstrumentationCheck$CallbackCancelHook", null);
		MixinBootstrap.init();
		MixinEnvironment env = MixinEnvironment.getDefaultEnvironment(); env.setSide(MixinEnvironment.Side.CLIENT);
		Mixins.addConfiguration("detail-marker.json"); Mixins.addConfiguration("detail-early.json"); Mixins.addConfiguration("detail-window.json");
		Mixins.addConfiguration("detail-tick.json");
		Mixins.addConfiguration("detail-callback-cancel.json");
		IMixinTransformer transformer = ((Service) MixinService.getService()).transformer();
		byte[] result = transformer.transformClass(env, TARGET.replace('/', '.'), resources.get(TARGET + ".class"));
		check(before != null, "production plugin did not run");
		Upload legacy = upload(bytes(before)), current = upload(result);
		for (int mode = 0; mode < 8; mode++) {
			List<Object> expected = run(legacy, mode), actual = run(current, mode);
			check(actual.equals(expected), "behavior changed: " + mode + " " + expected + " / " + actual);
			int calls = mode == 4 ? 2 : 1;
			check(Probe.details.valid[0] && Probe.details.calls[FrameTimeDetails.MAP_UPLOAD][0] == calls, "early return or nested call unbalanced");
			check(Probe.details.failures[FrameTimeDetails.MAP_UPLOAD][0] == (mode == 1 || mode == 3 || mode == 5 || mode == 7 ? 1 : 0), "exception boundary");
		}
		byte[] window = transformer.transformClass(env, WINDOW.replace('/', '.'), resources.get(WINDOW + ".class"));
		Class<?> windowType = load(window, WINDOW, "fixture/Window");
		for (int mode = 0; mode <= 4; mode++) {
			Probe.reset(mode);
			try { windowType.getMethod("flipFrame", long.class).invoke(null, 123L); check(mode == 0, "window exception swallowed"); }
			catch (java.lang.reflect.InvocationTargetException e) { check(e.getCause() == Probe.ERROR, "window exception replaced"); }
			Probe.details.finishFrame(0, 100);
			check(Probe.details.valid[0], "window finally unbalanced");
			if (mode == 0) {
				check(Probe.steps.equals(Arrays.asList("begin:0", "poll", "end:0", "begin:2", "queue", "end:2", "phase:6", "begin:49", "swap", "end:49", "phase-end:6", "begin:1", "poll", "end:1")), "window order/call count: " + Probe.steps);
			} else {
				int scope = mode == 1 ? FrameTimeDetails.POLL_BEFORE : mode == 2 ? FrameTimeDetails.POLL_AFTER : mode == 3 ? FrameTimeDetails.RENDER_QUEUE : FrameTimeDetails.SWAP_BUFFERS;
				check(Probe.details.failures[scope][0] == 1, "window failure attribution");
			}
		}
		byte[] mapInput = resources.get(FrameTimeMapInstrumentationCheck.PROCESSOR + ".class");
		byte[] mapOutput = transformer.transformClass(env, FrameTimeMapInstrumentationCheck.PROCESSOR.replace('/', '.'), mapInput);
		FrameTimeMapInstrumentationCheck.verify(mapInput, mapOutput, true);
		byte[] tickInput = resources.get(FrameTimeTickInstrumentationCheck.MC + ".class");
		byte[] tickOutput = transformer.transformClass(env, FrameTimeTickInstrumentationCheck.MC.replace('/', '.'), tickInput);
		FrameTimeTickInstrumentationCheck.verify(tickInput, tickOutput);
		for (boolean task : new boolean[]{true, false}) {
			String name = task ? FrameTimeTaskInstrumentationCheck.TASK : FrameTimeTaskInstrumentationCheck.PACKETS;
			byte[] input = resources.get(name + ".class");
			FrameTimeTaskInstrumentationCheck.verify(input, transformer.transformClass(env, name.replace('/', '.'), input), task);
		}
		for (int index = 0; index < callbackTargets.length; index++) {
			String name = callbackTargets[index];
			byte[] output = transformer.transformClass(env, name.replace('/', '.'), resources.get(name + ".class"));
			Class<?> type = load(output, name, "fixture/Callback" + index);
			Object target = type.getConstructor().newInstance();
			for (int scenario = 0; scenario < 2; scenario++) {
				Probe.reset(scenario);
				for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
					if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())) { continue; }
					Class<?>[] parameters = method.getParameterTypes(); Object[] values = new Object[parameters.length];
					for (int i = 0; i < values.length; i++) {
						if (parameters[i] == long.class) { values[i] = 123L; }
						else if (parameters[i] == int.class) { values[i] = 7; }
						else if (parameters[i] == double.class) { values[i] = 2.5D; }
						else if (parameters[i] == boolean.class) { values[i] = true; }
						else { values[i] = Collections.emptyList(); }
					}
					try { method.invoke(target, values); check(scenario == 0, "input exception swallowed"); }
					catch (java.lang.reflect.InvocationTargetException e) { check(e.getCause() == Probe.ERROR, "input exception replaced"); }
				}
				Probe.details.finishFrame(0, 100);
				int calls = 0, failures = 0;
				for (int scope = FrameTimeDetails.WINDOW_RESIZE; scope <= FrameTimeDetails.KEYBOARD_INPUT; scope++) {
					calls += Probe.details.calls[scope][0]; failures += Probe.details.failures[scope][0];
				}
				int expected = new int[]{5, 4, 2}[index];
				check(Probe.body == 11 && Probe.details.valid[0] && calls == expected && failures == scenario * expected, "callback count/exception boundary: " + name);
			}
			if (index == 1) {
				for (int scenario = 2; scenario <= 3; scenario++) {
					Probe.reset(scenario);
					for (String method : new String[]{"onScroll", "onPress"}) {
						try {
							if (method.equals("onScroll")) { type.getMethod(method, long.class, double.class, double.class).invoke(target, 123L, 2.0D, 3.0D); }
							else { type.getMethod(method, long.class, int.class, int.class, int.class).invoke(target, 123L, 1, 1, 0); }
							check(scenario == 2, "callback hook exception swallowed");
						} catch (java.lang.reflect.InvocationTargetException e) { check(e.getCause() == Probe.ERROR, "callback hook exception replaced"); }
					}
					Probe.details.finishFrame(0, 100);
					check(Probe.body == 0 && Probe.details.valid[0] && Probe.details.calls[FrameTimeDetails.MOUSE_INPUT][0] == 2
							&& Probe.details.failures[FrameTimeDetails.MOUSE_INPUT][0] == (scenario == 3 ? 2 : 0), "FIELD/INVOKE cancellation or exception changed");
				}
			}
		}
		String report = "Frame detail instrumentation: " + realMethods + " installed/compile-time methods verified.\n"
				+ "Real Mixin " + env.getVersion() + " + production plugin verified early cancellation, nested calls, existing catches/finally, synchronized methods/blocks and original throwable identity.\n"
				+ "Real window Mixin verified both poll ordinals, queue/swap order and exceptional exits; no OpenGL calls executed.\n"
				+ "Window/keyboard/mouse callbacks verified through the actual Mixin writer, including exceptional exits; installed named/SRG handlers verified.\n"
				+ "Mouse FIELD/INVOKE cancellation with live operands preserves cancellation and original hook exceptions through frame recomputation and JVM verification.\n"
				+ "Void/reference/int/long/float/double returns preserve results with both empty and nonempty residual operand stacks.\n"
				+ "Map call-site helpers preserve return values, argument order, virtual dispatch, null/exception exits and DH-Xaero upload deferral; real Mixin writer verified.\n"
				+ "Installed map maintenance calls verified in six distinct categories, with one timer per original call.\n"
				+ "Task/packet dispatch preserves concrete receiver types, exception identity, original catch behavior and disconnected-packet guards through the real Mixin writer.\n"
				+ "Client tick timers preserve ten catch-up calls, call order and original exceptions through the real Mixin writer. Named/SRG client tick, packet handlers and installed Dynamic Surroundings bytecode verified.\n";
		Files.createDirectories(Paths.get("build/reports"));
		Files.write(Paths.get("build/reports/frame-time-instrumentation.txt"), report.getBytes(StandardCharsets.UTF_8));
		System.out.print(report);
	}

	private static void verifyReturnStacks() throws Exception {
		String[] returns = {"V", "Ljava/lang/String;", "I", "J", "F", "D"};
		Object[] values = {null, "preserved", 17, 18L, 19.0F, 20.0D};
		for (int i = 0; i < returns.length; i++) {
			ClassNode node = new ClassNode();
			node.visit(V1_8, ACC_PUBLIC, "fixture/ReturnSource" + i, null, "java/lang/Object", null);
			MethodNode method = new MethodNode(ACC_PUBLIC | ACC_STATIC, "result", "(Z)" + returns[i], null, null);
			node.methods.add(method);
			LabelNode empty = new LabelNode();
			method.instructions.add(new VarInsnNode(ILOAD, 0));
			method.instructions.add(new JumpInsnNode(IFEQ, empty));
			// Category-1 and category-2 operands may legally remain below a returned value.
			method.instructions.add(new InsnNode(ACONST_NULL));
			method.instructions.add(new InsnNode(LCONST_0));
			if (values[i] != null) { method.instructions.add(new LdcInsnNode(values[i])); }
			method.instructions.add(new InsnNode(Type.getType(returns[i]).getOpcode(IRETURN)));
			method.instructions.add(empty);
			if (values[i] != null) { method.instructions.add(new LdcInsnNode(values[i])); }
			method.instructions.add(new InsnNode(Type.getType(returns[i]).getOpcode(IRETURN)));
			method.maxStack = 5; method.maxLocals = 1;
			Class<?> original = load(bytes(node), node.name, "fixture/ReturnOriginal" + i);
			check(FrameTimeDetailInstrumentation.wrap(method, FrameTimeDetails.MOUSE_INPUT), "return fixture not instrumented");
			Class<?> wrapped = load(bytes(node), node.name, "fixture/ReturnWrapped" + i);
			for (boolean residual : new boolean[]{false, true}) {
				Probe.reset(0);
				Object expected = original.getMethod("result", boolean.class).invoke(null, residual);
				Object actual = wrapped.getMethod("result", boolean.class).invoke(null, residual);
				Probe.details.finishFrame(0, 100);
				check(Objects.equals(expected, actual) && Objects.equals(actual, values[i]) && Probe.details.valid[0]
						&& Probe.details.calls[FrameTimeDetails.MOUSE_INPUT][0] == 1 && Probe.details.failures[FrameTimeDetails.MOUSE_INPUT][0] == 0,
						"return value or timer changed for " + returns[i] + " residual=" + residual);
			}
		}
	}

	private static void verifyReal(ZipFile zip) throws Exception {
		String s = "me/jellysquid/mods/sodium/client/", d = "com/seibel/distanthorizons/";
		String[] targets = {s + "render/chunk/ChunkRenderManager", s + "render/chunk/backends/multidraw/MultidrawChunkRenderBackend",
				s + "gl/device/GLRenderDevice$ImmediateCommandList", d + "common/render/openGl/glObject/buffer/GLBuffer_forge",
				d + "core/render/renderer/LodRenderer", "xaero/map/MapProcessor", TARGET, "xaero/map/region/texture/RegionTexture",
				"net/minecraft/client/Minecraft", "net/minecraft/client/multiplayer/ClientPacketListener",
				"net/minecraft/client/network/play/ClientPlayNetHandler", "org/orecruncher/environs/handlers/AreaBlockEffects",
				"org/orecruncher/environs/scanner/Scanner", "org/orecruncher/environs/scanner/CuboidScanner",
				"net/minecraft/util/thread/BlockableEventLoop", "net/minecraft/util/concurrent/ThreadTaskExecutor",
				"net/minecraft/network/protocol/PacketUtils", "net/minecraft/network/PacketThreadUtil",
				"com/mojang/blaze3d/platform/Window", "net/minecraft/client/MainWindow", "net/minecraft/client/MouseHandler", "net/minecraft/client/MouseHelper",
				"net/minecraft/client/KeyboardHandler", "net/minecraft/client/KeyboardListener"};
		int[] expected = {2, 2, 3, 1, 2, 24, 1, 2, 15, 4, 4, 1, 1, 2, 1, 1, 1, 1, 5, 5, 4, 4, 2, 2};
		int found = 0;
		for (int i = 0; i < targets.length; i++) {
			String path = targets[i] + ".class";
			if (zip == null && i >= 3 && i != 8 && i != 9 && i != 14 && i != 16 && i != 18 && i != 20 && i != 22 || zip != null && zip.getEntry(path) == null) { continue; }
			ClassNode node;
			try (InputStream in = zip == null ? FrameTimeInstrumentationCheck.class.getClassLoader().getResourceAsStream(path) : zip.getInputStream(zip.getEntry(path))) {
				check(in != null, "missing target " + path); node = read(in);
			}
			int changed = FrameTimeDetailInstrumentation.instrument(node);
			check(changed == expected[i], path + " expected " + expected[i] + " hooks, got " + changed);
			if (targets[i].equals(FrameTimeMapInstrumentationCheck.PROCESSOR)) {
				FrameTimeMapInstrumentationCheck.verifyMaintenanceScopes(node);
			}
			for (MethodNode method : node.methods) {
				if ((method.access & (ACC_ABSTRACT | ACC_NATIVE)) == 0) { new Analyzer<>(new BasicVerifier()).analyze(node.name, method); }
			}
			check(FrameTimeDetailInstrumentation.instrument(node) == 0, "double instrumentation");
			found += changed;
		}
		check(found > 0, "no known targets in " + (zip == null ? "Rubidium" : zip.getName()));
		realMethods += found;
	}
	private static List<Object> run(Upload target, int mode) {
		Probe.reset(mode); Throwable error = null;
		try { target.uploadTextures(); } catch (RuntimeException e) { error = e; }
		Probe.details.finishFrame(0, 100);
		return Arrays.asList(Probe.body, Probe.head, Probe.cleanup, Probe.caught, error);
	}
	private static Upload upload(byte[] bytes) throws Exception { return (Upload) load(bytes, TARGET, "fixture/Upload").getDeclaredConstructor().newInstance(); }
	private static Class<?> load(byte[] original, String target, String replacement) {
		Map<String, String> names = new HashMap<>(); names.put(target, replacement); names.put(RECORDER, Type.getInternalName(Probe.class));
		names.put("org/lwjgl/glfw/GLFW", Type.getInternalName(WindowCalls.class));
		ClassWriter writer = new ClassWriter(0); new ClassReader(original).accept(new ClassRemapper(writer, new SimpleRemapper(names)), 0);
		return new Loader().define(writer.toByteArray());
	}
	private static byte[] remap(ClassNode node, String from, String to) {
		ClassWriter writer = new ClassWriter(0); node.accept(new ClassRemapper(writer, new SimpleRemapper(from, to))); return writer.toByteArray();
	}
	private static void config(String name, String pkg, String mixin, String plugin) {
		String json = "{\"required\":true,\"minVersion\":\"0.8\",\"compatibilityLevel\":\"JAVA_8\",\"package\":\"" + pkg
				+ "\",\"client\":[\"" + mixin + "\"]" + (plugin == null ? "" : ",\"plugin\":\"" + plugin + "\"") + "}";
		resources.put(name, json.getBytes(StandardCharsets.UTF_8));
	}
	public static class Plugin extends CompatMixinPlugin {
		@Override public boolean shouldApplyMixin(String target, String mixin) { return true; }
		@Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {
			before = new ClassNode(); node.accept(before); super.postApply(target, node, mixin, info);
		}
	}
	public static class Service extends MixinServiceAbstract implements IClassProvider, IClassBytecodeProvider, IClassTracker, IGlobalPropertyService {
		private static final Map<String, Object> properties = new HashMap<>();
		public String getName() { return "FrameTimeDetailCheck"; }
		public boolean isValid() { return true; }
		public MixinEnvironment.Phase getInitialPhase() { return MixinEnvironment.Phase.DEFAULT; }
		public IClassProvider getClassProvider() { return this; }
		public IClassBytecodeProvider getBytecodeProvider() { return this; }
		public ITransformerProvider getTransformerProvider() { return null; }
		public IClassTracker getClassTracker() { return this; }
		public IMixinAuditTrail getAuditTrail() { return null; }
		public Collection<String> getPlatformAgents() { return Collections.emptyList(); }
		public IContainerHandle getPrimaryContainer() { return new ContainerHandleVirtual("frame-time-detail-check"); }
		public URL[] getClassPath() { return new URL[0]; }
		public Class<?> findClass(String name) throws ClassNotFoundException { return findClass(name, false); }
		public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException { return Class.forName(name, initialize, getClass().getClassLoader()); }
		public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException { return findClass(name, initialize); }
		public void registerInvalidClass(String name) { }
		public boolean isClassLoaded(String name) { return false; }
		public String getClassRestrictions(String name) { return ""; }
		public InputStream getResourceAsStream(String name) { byte[] data = resources.get(name); return data == null ? getClass().getClassLoader().getResourceAsStream(name) : new ByteArrayInputStream(data); }
		public ClassNode getClassNode(String name) throws IOException, ClassNotFoundException { return getClassNode(name, true); }
		public ClassNode getClassNode(String name, boolean transform) throws IOException, ClassNotFoundException {
			try (InputStream in = getResourceAsStream(name.replace('.', '/') + ".class")) {
				if (in == null) { throw new ClassNotFoundException(name); } return read(in);
			}
		}
		public IPropertyKey resolveKey(String name) { return new Key(name); }
		@SuppressWarnings("unchecked") public <T> T getProperty(IPropertyKey key) { return (T) properties.get(((Key) key).name); }
		public void setProperty(IPropertyKey key, Object value) { properties.put(((Key) key).name, value); }
		public <T> T getProperty(IPropertyKey key, T fallback) { T value = getProperty(key); return value == null ? fallback : value; }
		public String getPropertyString(IPropertyKey key, String fallback) { Object value = getProperty(key); return value == null ? fallback : value.toString(); }
		IMixinTransformer transformer() { return getInternal(IMixinTransformerFactory.class).createTransformer(); }
	}
	private static class Key implements IPropertyKey { final String name; Key(String name) { this.name = name; } }
	private static class Loader extends ClassLoader {
		Loader() { super(FrameTimeInstrumentationCheck.class.getClassLoader()); }
		Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
	}
	private static ClassNode read(InputStream input) throws IOException { ClassNode node = new ClassNode(); new ClassReader(input).accept(node, 0); return node; }
	private static ClassNode readResource(String name) throws IOException { try (InputStream in = FrameTimeInstrumentationCheck.class.getClassLoader().getResourceAsStream(name + ".class")) { return read(in); } }
	private static byte[] bytes(ClassNode node) { ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray(); }
	public static void check(boolean okay, String message) { if (!okay) { throw new AssertionError(message); } }
}
