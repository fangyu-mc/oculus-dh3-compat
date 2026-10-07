package net.coderbot.iris.compat.embeddium;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.ZipFile;
import net.coderbot.iris.mixin.compat.CompatMixinPlugin;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.launch.platform.container.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.transformer.*;
import org.spongepowered.asm.service.*;

import static org.objectweb.asm.Opcodes.*;

/** Runs real Mixin injection and the production plugin in an isolated JVM, without a game or GL. */
public final class BiomeColorCallbackCheck {

	private static final String ROOT = "com/teampotato/embeddiumextension/";
	private static final String EXTERNAL = ROOT + "mixin/biome_colors/MixinBiomeColors";
	private static final String MARKER = "net/coderbot/iris/mixin/compat/embeddiumextension/MixinBiomeColors";
	private static final String NAMED = "net/minecraft/client/renderer/BiomeColors";
	private static final String SRG = "net/minecraft/world/biome/BiomeColors";
	private static final String CIR = Type.getInternalName(CallbackInfoReturnable.class);
	private static String target;
	private static String[] names;
	private static String extensionJar;
	private static ClassNode external, before, after;
	private static final Map<String, byte[]> resources = new HashMap<>();
	private static boolean hooks;
	private static long comparisons;
	private static volatile long checksum;

	public interface Query { int color(int kind, int input); }
	public static class Details { public boolean biomeColors = true; }
	public static class Options { public Details detailSettings = new Details(); }
	public static class Client {
		public static Options current = new Options();
		public static boolean fail;
		public static int reads;
		public static Options options() {
			reads++;
			if (fail) { throw new IllegalStateException("options failure"); }
			return current;
		}
	}
	public static class Provider {
		public static int calls, beforeCalls, afterCalls, beforeSeen, afterSeen;
		public static boolean fail, toggle, cancelBefore, cancelAfter;
		public static int sample(int input) {
			calls++;
			if (fail) { throw new IllegalArgumentException("provider failure"); }
			if (toggle) { Client.current.detailSettings.biomeColors = !Client.current.detailSettings.biomeColors; }
			return input;
		}
	}
	public static class EarlierHook {
		private static void onColor(CallbackInfoReturnable<Integer> cir) {
			Provider.beforeCalls++;
			Provider.beforeSeen = cir.getReturnValueI();
			if (Provider.cancelBefore) { cir.setReturnValue(0x123456); }
		}
	}
	public static class LaterHook {
		private static void onColor(CallbackInfoReturnable<Integer> cir) {
			Provider.afterCalls++;
			Provider.afterSeen = cir.getReturnValueI();
			if (Provider.cancelAfter) { cir.setReturnValue(0xabcdef); }
		}
	}

	public static void main(String[] args) throws Exception {
		check(args.length == 2, "expected mode and installed Extension jar");
		hooks = args[0].equals("hooks");
		boolean srg = !args[0].equals("named");
		extensionJar = args[1];
		target = srg ? SRG : NAMED;
		names = srg ? new String[]{"func_228358_a_", "func_228361_b_", "func_228363_c_"}
				: new String[]{"getAverageGrassColor", "getAverageFoliageColor", "getAverageWaterColor"};
		try (ZipFile zip = new ZipFile(args[1]); InputStream in = zip.getInputStream(zip.getEntry(EXTERNAL + ".class"))) {
			external = read(in);
		}
		prepareResources();
		MixinBootstrap.init();
		MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
		Mixins.addConfiguration("callback-extension.json");
		Mixins.addConfiguration("callback-oculus.json");
		if (hooks) { Mixins.addConfiguration("callback-hooks.json"); }
		IMixinTransformer transformer = ((Service) MixinService.getService()).transformer();
		byte[] transformed = transformer.transformClass(MixinEnvironment.getDefaultEnvironment(), target.replace('/', '.'), fixture());
		check(before != null && after != null, "production postApply did not run");
		check(countCallbacks(before) == (hooks ? 5 : 3), "real Mixin injection fixture has unexpected callbacks");
		check(countCallbacks(after) == (hooks ? 2 : 0), "Extension callback allocation remains");
		check(countCallbacks(read(transformed)) == countCallbacks(after), "final Mixin writer changed optimization");
		check(BiomeColorCallbackOptimizer.optimize(after) == 0, "rewrite must be idempotent");
		Query legacy = query(bytes(before)), optimized = query(transformed);
		Random random = new Random(82161004);
		int[] edges = {0, -1, 1, 127, 128, -128, -129, 0xffffff, 0xff000000, Integer.MIN_VALUE, Integer.MAX_VALUE};
		for (int kind = 0; kind < 3; kind++) {
			for (int flags = 0; flags < 128; flags++) {
				for (int value : edges) { compare(legacy, optimized, kind, value, flags); }
				for (int i = 0; i < 150; i++) { compare(legacy, optimized, kind, random.nextInt(), flags); }
			}
		}
		checkFallbacks();
		String allocation = hooks ? "Other mods' earlier/later callbacks, cancellation and observed values preserved."
				: allocation(legacy, optimized);
		String report = args[0] + ": real Extension jar + Mixin " + MixinEnvironment.getDefaultEnvironment().getVersion()
				+ " + production postApply: 3 callback sites removed; " + comparisons + " behavior comparisons passed.\n"
				+ "Live option replacement/toggles, provider side effects and failures, options failures, signed colors, "
				+ "unknown handler/callback shapes, missing mod and repeated application checked.\n" + allocation + "\n";
		Files.createDirectories(Paths.get("build/reports"));
		Files.write(Paths.get("build/reports/biome-color-callback-" + args[0] + ".txt"), report.getBytes(StandardCharsets.UTF_8));
		System.out.print(report);
	}

	private static void reset(int flags) {
		Client.current = new Options();
		Client.current.detailSettings.biomeColors = (flags & 1) != 0;
		Client.fail = (flags & 2) != 0;
		Client.reads = Provider.calls = Provider.beforeCalls = Provider.afterCalls = 0;
		Provider.beforeSeen = Provider.afterSeen = 0;
		Provider.fail = (flags & 4) != 0;
		Provider.toggle = (flags & 8) != 0;
		Provider.cancelBefore = (flags & 16) != 0;
		Provider.cancelAfter = (flags & 32) != 0;
		if ((flags & 64) != 0) { Client.current.detailSettings = null; }
	}

	private static List<Object> result(Query query, int kind, int input, int flags) {
		reset(flags);
		Object output;
		try { output = query.color(kind, input); }
		catch (RuntimeException exception) { output = exception.getClass().getName(); }
		return Arrays.asList(output, Client.reads, Provider.calls, Provider.beforeCalls, Provider.afterCalls,
				Provider.beforeSeen, Provider.afterSeen, Client.current.detailSettings == null ? null : Client.current.detailSettings.biomeColors);
	}

	private static void compare(Query a, Query b, int kind, int input, int flags) {
		List<Object> expected = result(a, kind, input, flags), actual = result(b, kind, input, flags);
		check(expected.equals(actual), "behavior differs: " + expected + " / " + actual + ", flags=" + flags);
		comparisons++;
	}

	private static String allocation(Query a, Query b) {
		com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		check(bean.isThreadAllocatedMemorySupported(), "allocation counter unavailable");
		bean.setThreadAllocatedMemoryEnabled(true);
		reset(1);
		for (int i = 0; i < 100000; i++) { checksum += a.color(i % 3, 0x546789 + i); checksum += b.color(i % 3, 0x546789 + i); }
		long id = Thread.currentThread().getId(), lo = bean.getThreadAllocatedBytes(id);
		for (int i = 0; i < 600000; i++) { checksum += a.color(i % 3, 0x546789 + i); }
		long old = bean.getThreadAllocatedBytes(id) - lo;
		lo = bean.getThreadAllocatedBytes(id);
		for (int i = 0; i < 600000; i++) { checksum += b.color(i % 3, 0x546789 + i); }
		long current = bean.getThreadAllocatedBytes(id) - lo;
		check(old > 15000000 && current < 4096, "explicit allocation not eliminated: " + old + " -> " + current);
		return "600000 color queries (test JVM escape analysis disabled): " + old + " -> " + current
				+ " allocated bytes. This does not measure game FPS or runtime allocation rate.";
	}

	private static void checkFallbacks() {
		// Unknown bytecode must be byte-for-byte unchanged, rather than partly stripped.
		fallback(node -> node.methods.removeIf(BiomeColorCallbackCheck::isHandler), "absent Extension handlers");
		fallback(node -> node.name = "example/OtherColors", "other target");
		fallback(node -> {
			for (MethodNode m : node.methods) { if (isHandler(m)) { m.visibleAnnotations = null; } }
		}, "missing provenance");
		fallback(node -> {
			for (MethodNode m : node.methods) { if (isHandler(m)) { m.instructions.insert(new InsnNode(NOP)); } }
		}, "changed handler body");
		fallback(node -> {
			for (MethodNode m : node.methods) {
				if (isHandler(m)) {
					for (AbstractInsnNode n : m.instructions.toArray()) {
						if (n instanceof FieldInsnNode && ((FieldInsnNode) n).name.equals("biomeColors")) { ((FieldInsnNode) n).name = "futureOption"; }
					}
				}
			}
		}, "changed config semantics");
		fallback(node -> {
			for (MethodNode m : node.methods) {
				for (AbstractInsnNode n : m.instructions.toArray()) {
					if (n.getOpcode() == NEW && ((TypeInsnNode) n).desc.equals(CIR)) { m.instructions.insertBefore(n, new InsnNode(NOP)); }
				}
			}
		}, "changed callback lifetime");
		fallback(node -> {
			for (MethodNode m : node.methods) {
				for (AbstractInsnNode n : m.instructions.toArray()) {
					if (n.getOpcode() == ASTORE) {
						InsnList use = new InsnList(); use.add(new VarInsnNode(ALOAD, ((VarInsnNode) n).var)); use.add(new InsnNode(POP));
						m.instructions.insertBefore(m.instructions.getLast(), use);
					}
				}
			}
		}, "callback local escapes");
	}

	private static void fallback(Consumer<ClassNode> change, String label) {
		ClassNode node = copy(before); change.accept(node);
		byte[] original = rawBytes(node);
		check(BiomeColorCallbackOptimizer.optimize(node) == 0, label + " should be skipped");
		check(Arrays.equals(original, rawBytes(node)), label + " was modified");
	}

	private static boolean isHandler(MethodNode method) {
		return method.name.contains("grassColor") || method.name.contains("foliageColor") || method.name.contains("waterColor");
	}

	private static void prepareResources() throws IOException {
		resources.put("callback-extension.json", config(ROOT.replace('/', '.') + "mixin.biome_colors", "MixinBiomeColors"));
		resources.put("callback-oculus.json", config("net.coderbot.iris.mixin.compat.embeddiumextension", "MixinBiomeColors"));
		resources.put("callback-hooks.json", config("net.coderbot.iris.compat.embeddium", "BiomeColorCallbackCheck$EarlierHook", "BiomeColorCallbackCheck$LaterHook"));
	}

	private static byte[] config(String pkg, String... mixins) {
		StringBuilder list = new StringBuilder();
		for (String name : mixins) { if (list.length() > 0) { list.append(','); } list.append('"').append(name).append('"'); }
		String json = "{\"required\":true,\"minVersion\":\"0.8\",\"compatibilityLevel\":\"JAVA_8\",\"package\":\"" + pkg
				+ "\",\"plugin\":\"" + Plugin.class.getName() + "\",\"mixins\":[" + list + "],\"injectors\":{\"defaultRequire\":1}}";
		return json.getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] fixture() {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		writer.visit(V1_8, ACC_PUBLIC, target, null, "java/lang/Object", null);
		for (String name : names) {
			MethodVisitor m = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, name, "(I)I", null, null);
			m.visitCode(); m.visitVarInsn(ILOAD, 0);
			m.visitMethodInsn(INVOKESTATIC, Type.getInternalName(Provider.class), "sample", "(I)I", false);
			m.visitInsn(IRETURN); m.visitMaxs(0, 0); m.visitEnd();
		}
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static Query query(byte[] source) throws Exception {
		ClassNode node = read(source);
		Map<String, String> map = new HashMap<>();
		map.put(target, "fixture/Colors");
		map.put(ROOT + "client/SodiumExtraClientMod", Type.getInternalName(Client.class));
		map.put(ROOT + "client/gui/SodiumExtraGameOptions", Type.getInternalName(Options.class));
		map.put(ROOT + "client/gui/SodiumExtraGameOptions$DetailSettings", Type.getInternalName(Details.class));
		ClassNode remapped = new ClassNode(); node.accept(new ClassRemapper(remapped, new SimpleRemapper(map)));
		// Preserve the final Mixin writer's stack maps; do not silently repair them in this loader.
		Loader loader = new Loader(); loader.define(rawBytes(remapped));
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		writer.visit(V1_8, ACC_PUBLIC, "fixture/Query", null, "java/lang/Object", new String[]{Type.getInternalName(Query.class)});
		MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
		constructor.visitCode(); constructor.visitVarInsn(ALOAD, 0); constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		constructor.visitInsn(RETURN); constructor.visitMaxs(0, 0); constructor.visitEnd();
		MethodVisitor m = writer.visitMethod(ACC_PUBLIC, "color", "(II)I", null, null);
		m.visitCode(); m.visitVarInsn(ILOAD, 1);
		Label[] labels = {new Label(), new Label(), new Label()};
		m.visitTableSwitchInsn(0, 2, labels[0], labels);
		for (int i = 0; i < 3; i++) {
			m.visitLabel(labels[i]); m.visitVarInsn(ILOAD, 2);
			m.visitMethodInsn(INVOKESTATIC, "fixture/Colors", names[i], "(I)I", false); m.visitInsn(IRETURN);
		}
		m.visitMaxs(0, 0); m.visitEnd(); writer.visitEnd();
		return (Query) loader.define(writer.toByteArray()).getDeclaredConstructor().newInstance();
	}

	public static class Plugin extends CompatMixinPlugin {
		@Override public boolean shouldApplyMixin(String targetName, String mixinName) { return true; }
		@Override public void postApply(String targetName, ClassNode node, String mixinName, IMixinInfo info) {
			if (mixinName.equals(MARKER.replace('/', '.'))) { before = copy(node); }
			super.postApply(targetName, node, mixinName, info);
			if (mixinName.equals(MARKER.replace('/', '.'))) { after = copy(node); }
		}
	}

	/** Minimal test host for the unmodified Mixin transformer; never boots Forge or Minecraft. */
	public static class Service extends MixinServiceAbstract implements IClassProvider, IClassBytecodeProvider, IClassTracker, IGlobalPropertyService {
		private static final Map<String, Object> properties = new HashMap<>();
		public String getName() { return "ColorCallbackCheck"; }
		public boolean isValid() { return true; }
		public MixinEnvironment.Phase getInitialPhase() { return MixinEnvironment.Phase.DEFAULT; }
		public IClassProvider getClassProvider() { return this; }
		public IClassBytecodeProvider getBytecodeProvider() { return this; }
		public ITransformerProvider getTransformerProvider() { return null; }
		public IClassTracker getClassTracker() { return this; }
		public IMixinAuditTrail getAuditTrail() { return null; }
		public Collection<String> getPlatformAgents() { return Collections.emptyList(); }
		public IContainerHandle getPrimaryContainer() { return new ContainerHandleVirtual("color-callback-check"); }
		public URL[] getClassPath() { return new URL[0]; }
		public Class<?> findClass(String name) throws ClassNotFoundException { return findClass(name, false); }
		public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException { return Class.forName(name, initialize, getClass().getClassLoader()); }
		public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException { return findClass(name, initialize); }
		public void registerInvalidClass(String name) { }
		public boolean isClassLoaded(String name) { return false; }
		public String getClassRestrictions(String name) { return ""; }
		public InputStream getResourceAsStream(String name) {
			byte[] data = resources.get(name);
			return data != null ? new ByteArrayInputStream(data) : getClass().getClassLoader().getResourceAsStream(name);
		}
		public ClassNode getClassNode(String name) throws IOException, ClassNotFoundException { return getClassNode(name, true); }
		public ClassNode getClassNode(String name, boolean runTransformers) throws IOException, ClassNotFoundException {
			String internal = name.replace('.', '/');
			if (internal.equals(target)) { return read(fixture()); }
			if (internal.equals(EXTERNAL)) {
				ClassNode node = remapTarget(copy(external), SRG);
				for (MethodNode method : node.methods) {
					int kind = method.name.equals("grassColor") ? 0 : method.name.equals("foliageColor") ? 1 : method.name.equals("waterColor") ? 2 : -1;
					if (kind >= 0) { changeSelector(method, names[kind]); }
				}
				return node;
			}
			if (internal.startsWith(ROOT)) {
				try (ZipFile zip = new ZipFile(extensionJar)) {
					if (zip.getEntry(internal + ".class") == null) { throw new ClassNotFoundException(name); }
					try (InputStream in = zip.getInputStream(zip.getEntry(internal + ".class"))) { return read(in); }
				}
			}
			ClassNode node;
			try (InputStream input = getResourceAsStream(internal + ".class")) {
				if (input == null) { throw new ClassNotFoundException(name); }
				node = read(input);
			}
			if (internal.equals(MARKER)) { return remapTarget(node, NAMED); }
			if (internal.equals(Type.getInternalName(EarlierHook.class)) || internal.equals(Type.getInternalName(LaterHook.class))) {
				AnnotationNode mixin = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
				mixin.values = new ArrayList<>(Arrays.asList("value", Arrays.asList(Type.getObjectType(target)), "priority",
						internal.equals(Type.getInternalName(EarlierHook.class)) ? 1100 : 800));
				node.invisibleAnnotations = new ArrayList<>(Collections.singletonList(mixin));
				for (MethodNode method : node.methods) {
					if (!method.name.equals("onColor")) { continue; }
					AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;"); at.values = Arrays.asList("value", "RETURN");
					AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
					inject.values = Arrays.asList("method", Arrays.asList(names[0]), "at", Arrays.asList(at), "cancellable", true);
					method.visibleAnnotations = new ArrayList<>(Collections.singletonList(inject));
				}
			}
			return node;
		}
		public IPropertyKey resolveKey(String name) { return new Key(name); }
		@SuppressWarnings("unchecked") public <T> T getProperty(IPropertyKey key) { return (T) properties.get(((Key) key).name); }
		public void setProperty(IPropertyKey key, Object value) { properties.put(((Key) key).name, value); }
		public <T> T getProperty(IPropertyKey key, T defaultValue) { T value = getProperty(key); return value == null ? defaultValue : value; }
		public String getPropertyString(IPropertyKey key, String defaultValue) { Object value = getProperty(key); return value == null ? defaultValue : value.toString(); }
		IMixinTransformer transformer() { return getInternal(IMixinTransformerFactory.class).createTransformer(); }
	}

	private static class Key implements IPropertyKey { final String name; Key(String name) { this.name = name; } }
	private static class Loader extends ClassLoader {
		Loader() { super(BiomeColorCallbackCheck.class.getClassLoader()); }
		Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
	}
	private static void changeSelector(MethodNode method, String selector) {
		for (AnnotationNode annotation : method.visibleAnnotations) {
			if (!annotation.desc.endsWith("/Inject;")) { continue; }
			for (int i = 0; i < annotation.values.size(); i += 2) {
				if (annotation.values.get(i).equals("method")) { annotation.values.set(i + 1, Arrays.asList(selector)); }
			}
		}
	}
	private static ClassNode remapTarget(ClassNode node, String original) {
		ClassNode result = new ClassNode(); node.accept(new ClassRemapper(result, new SimpleRemapper(original, target))); return result;
	}
	private static int countCallbacks(ClassNode node) {
		int count = 0;
		for (MethodNode m : node.methods) {
			for (AbstractInsnNode n : m.instructions.toArray()) { if (n.getOpcode() == NEW && ((TypeInsnNode) n).desc.equals(CIR)) { count++; } }
		}
		return count;
	}
	private static ClassNode read(InputStream input) throws IOException { ClassNode n = new ClassNode(); new ClassReader(input).accept(n, 0); return n; }
	private static ClassNode read(byte[] bytes) { ClassNode n = new ClassNode(); new ClassReader(bytes).accept(n, 0); return n; }
	private static ClassNode copy(ClassNode node) { ClassNode result = new ClassNode(); node.accept(result); return result; }
	private static byte[] bytes(ClassNode node) { ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES); node.accept(writer); return writer.toByteArray(); }
	private static byte[] rawBytes(ClassNode node) { ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray(); }
	private static void check(boolean success, String message) { if (!success) { throw new AssertionError(message); } }
}
