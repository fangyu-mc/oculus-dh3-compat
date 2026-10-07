package net.coderbot.iris.diagnostics;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import net.coderbot.iris.compat.tes.TesEntityTypeCacheOptimizer;
import net.coderbot.iris.mixin.compat.CompatMixinPlugin;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.service.MixinService;

import static org.objectweb.asm.Opcodes.*;

/** Runs the installed TES lookup/classifier with stub entities; never starts Minecraft. */
public final class TesEntityTypeCacheCheck {
	private static final String TARGET = TesEntityTypeCacheOptimizer.TARGET;
	private static final String ENTITY = "net/minecraft/entity/LivingEntity";
	private static final String TYPE = "net/tslat/tes/api/TESEntityType";
	private static final String DESC = "(L" + ENTITY + ";)L" + TYPE + ";";
	private static final Map<String, byte[]> resources = new HashMap<>();
	private static volatile int sink;
	public static final RuntimeException ERROR = new IllegalStateException("original entity failure");

	public enum Kind { PLAYER, BOSS, NEUTRAL, HOSTILE, PASSIVE }
	public static class EntityKind {
		public static final EntityKind field_200729_aH = new EntityKind();
		static final EntityKind OTHER = new EntityKind();
	}
	public static class Entity {
		boolean player, changesDimensions = true;
		int failure, typeCalls, dimensionCalls;
		public EntityKind func_200600_R() { typeCalls++; if (failure == 1) { throw ERROR; } return player ? EntityKind.field_200729_aH : EntityKind.OTHER; }
		public boolean func_184222_aU() { dimensionCalls++; if (failure == 2) { throw ERROR; } return changesDimensions; }
	}
	public interface Angry { }
	public static class Neutral extends Entity implements Angry { }
	public static class Monster extends Entity { }
	public static class AngryMonster extends Monster implements Angry { }
	public interface Lookup { Kind type(Entity entity); }

	public static void main(String[] args) throws Exception {
		check(args.length == 2, "expected mode and installed TES jar");
		String mode = args[0];
		ClassNode original;
		try (ZipFile zip = new ZipFile(args[1]); InputStream in = zip.getInputStream(zip.getEntry(TARGET + ".class"))) { original = read(in); }
		ClassNode optimized = copy(original);
		check(TesEntityTypeCacheOptimizer.optimize(optimized) == 1, "installed TES lookup did not match");
		check(TesEntityTypeCacheOptimizer.optimize(optimized) == 0, "lookup optimized twice");
		int changed = 0;
		for (MethodNode m : original.methods) {
			if (!Arrays.equals(methodBytes(m), methodBytes(method(optimized, m.name, m.desc)))) {
				check(m.name.equals("getEntityType"), "unrelated TES method changed: " + m.name); changed++;
			}
		}
		check(changed == 1 && dynamicCalls(method(original, "getEntityType", DESC)) == 1
				&& dynamicCalls(method(optimized, "getEntityType", DESC)) == 0, "lambda call site not removed");
		checkFallbacks(original);

		ClassNode fixture = fixture(original);
		resources.put(TARGET + ".class", bytes(fixture));
		resources.put("tes-entity-type-check.json", ("{\"required\":true,\"minVersion\":\"0.8\",\"compatibilityLevel\":\"JAVA_8\","
				+ "\"package\":\"net.coderbot.iris.mixin.compat.tslatentitystatus\",\"client\":[\"MixinTesEntityTypeCache\"],"
				+ "\"plugin\":\"net.coderbot.iris.diagnostics.TesEntityTypeCacheCheck$Plugin\"}").getBytes(StandardCharsets.UTF_8));
		MixinBootstrap.init();
		MixinEnvironment env = MixinEnvironment.getDefaultEnvironment(); env.setSide(MixinEnvironment.Side.CLIENT);
		Mixins.addConfiguration("tes-entity-type-check.json");
		byte[] transformed = ((Service) MixinService.getService()).transformer().transformClass(env, TARGET.replace('/', '.'), bytes(fixture));
		ClassNode actual = read(new ByteArrayInputStream(transformed));
		check(dynamicCalls(method(actual, "getEntityType", DESC)) == 0, "production Mixin/plugin skipped optimization");
		Harness old = harness(fixture), now = harness(actual);
		int cases = semantics(old, now);
		String allocation = allocations(old, now, mode);
		String report = "Installed TES 1.2.1 lookup and original classifier + real Mixin/plugin + JVM verification passed.\n"
				+ "Only getEntityType changed; original classifier, fields and other method bytecode retained.\n"
				+ cases + " cache/classification cases: player/boss priority, neutral hostile overlap, cache hits, same-class instances, prefilled/null entries, clear and exception retry passed.\n"
				+ "Original null-input exception preserved; unknown lookup, bootstrap, helper and cache signatures left untouched.\n"
				+ "Capturing invokedynamic call site removed from getEntityType, including its first-use bootstrap.\n"
				+ allocation + "\nNo game, world, window or server was started. In-game low FPS still requires player verification.\n";
		Files.createDirectories(Paths.get("build/reports"));
		Files.write(Paths.get("build/reports/tes-entity-type-" + mode + ".txt"), report.getBytes(StandardCharsets.UTF_8));
		System.out.print(report);
	}

	private static int semantics(Harness old, Harness now) {
		int cases = 0;
		for (int cls = 0; cls < 4; cls++) {
			for (int flags = 0; flags < 4; flags++) {
				old.cache.clear(); now.cache.clear();
				Entity a = entity(cls, flags), b = entity(cls, flags);
				Kind expected = a.player ? Kind.PLAYER : !a.changesDimensions ? Kind.BOSS : a instanceof Angry ? Kind.NEUTRAL : a instanceof Monster ? Kind.HOSTILE : Kind.PASSIVE;
				compare(old, now, a, b, expected); cases++;
				check(old.cache.size() == 1 && now.cache.size() == 1 && old.cache.equals(now.cache), "class-keyed cache differs");
				// The original class cache deliberately keeps the first instance's classification.
				a = entity(cls, flags ^ 3); b = entity(cls, flags ^ 3); a.failure = b.failure = 1;
				for (int i = 0; i < 20; i++) { compare(old, now, a, b, expected); cases++; }
				check(a.typeCalls == 0 && b.typeCalls == 0, "cache hit evaluated entity classification");
			}
			for (Kind prefilled : Kind.values()) {
				Entity a = entity(cls, 0), b = entity(cls, 0); a.failure = b.failure = 1;
				old.cache.put(a.getClass(), prefilled); now.cache.put(b.getClass(), prefilled);
				compare(old, now, a, b, prefilled); cases++;
			}
			for (int fail = 1; fail <= 2; fail++) {
				old.cache.clear(); now.cache.clear();
				Entity a = entity(cls, 0), b = entity(cls, 0); a.failure = b.failure = fail;
				check(thrown(old.lookup, a) == ERROR && thrown(now.lookup, b) == ERROR, "classifier exception replaced");
				check(old.cache.isEmpty() && now.cache.isEmpty(), "failed classification cached");
				a.failure = b.failure = 0;
				compare(old, now, a, b, cls == 0 ? Kind.PASSIVE : cls == 2 ? Kind.HOSTILE : Kind.NEUTRAL); cases++;
			}
			old.cache.clear(); now.cache.clear();
			Entity a = entity(cls, 0), b = entity(cls, 0);
			old.cache.put(a.getClass(), null); now.cache.put(b.getClass(), null);
			compare(old, now, a, b, cls == 0 ? Kind.PASSIVE : cls == 2 ? Kind.HOSTILE : Kind.NEUTRAL); cases++;
		}
		check(thrown(old.lookup, null) instanceof NullPointerException && thrown(now.lookup, null) instanceof NullPointerException, "null input behavior changed");
		return cases;
	}
	private static Entity entity(int cls, int flags) {
		Entity e = cls == 0 ? new Entity() : cls == 1 ? new Neutral() : cls == 2 ? new Monster() : new AngryMonster();
		e.player = (flags & 1) != 0; e.changesDimensions = (flags & 2) == 0; return e;
	}
	private static void compare(Harness old, Harness now, Entity a, Entity b, Kind expected) {
		check(old.lookup.type(a) == expected && now.lookup.type(b) == expected, "entity category changed");
		check(a.typeCalls == b.typeCalls && a.dimensionCalls == b.dimensionCalls, "entity calls/order changed");
	}
	private static Throwable thrown(Lookup lookup, Entity e) { try { lookup.type(e); return null; } catch (RuntimeException error) { return error; } }

	private static String allocations(Harness old, Harness now, String mode) {
		old.cache.clear(); now.cache.clear(); Entity e = entity(0, 0);
		com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		check(bean.isThreadAllocatedMemorySupported(), "allocation counter unavailable"); bean.setThreadAllocatedMemoryEnabled(true);
		work(old.lookup, e, 100_000); work(now.lookup, e, 100_000);
		long thread = Thread.currentThread().getId(), before = bean.getThreadAllocatedBytes(thread);
		work(old.lookup, e, 200_000); long oldBytes = bean.getThreadAllocatedBytes(thread) - before;
		before = bean.getThreadAllocatedBytes(thread);
		work(now.lookup, e, 200_000); long newBytes = bean.getThreadAllocatedBytes(thread) - before;
		check(newBytes < 200_000, "optimized cache hit still allocates >=1 byte/call");
		if (mode.equals("no-ea")) { check(oldBytes >= 3_200_000 && newBytes < oldBytes / 50, "capturing-function allocation not removed"); }
		return "200000 warmed cache hits (" + mode + "): allocated bytes " + oldBytes + " -> " + newBytes
				+ ". Allocation counters are isolated measurements, not game FPS gains.";
	}
	private static void work(Lookup lookup, Entity e, int count) { int value = 0; for (int i = 0; i < count; i++) { value += lookup.type(e).ordinal(); } sink = value; }

	private static void checkFallbacks(ClassNode original) {
		for (int scenario = 0; scenario < 5; scenario++) {
			ClassNode changed = copy(original); MethodNode lookup = method(changed, "getEntityType", DESC);
			if (scenario == 0) { lookup.instructions.insert(new InsnNode(NOP)); }
			if (scenario == 1) { changed.name += "Other"; }
			if (scenario == 2) { for (FieldNode f : changed.fields) { if (f.name.equals("ENTITY_TYPE_MAP")) { f.access &= ~ACC_FINAL; } } }
			for (AbstractInsnNode insn : lookup.instructions) {
				if (insn instanceof InvokeDynamicInsnNode) {
					InvokeDynamicInsnNode lambda = (InvokeDynamicInsnNode) insn;
					if (scenario == 3) { lambda.bsm = new Handle(H_INVOKESTATIC, "example/OtherFactory", lambda.bsm.getName(), lambda.bsm.getDesc(), false); }
					if (scenario == 4) { Handle h = (Handle) lambda.bsmArgs[1]; lambda.bsmArgs[1] = new Handle(H_INVOKESTATIC, TARGET, "missingClassifier", h.getDesc(), false); }
				}
			}
			byte[] before = plainBytes(changed);
			check(TesEntityTypeCacheOptimizer.optimize(changed) == 0 && Arrays.equals(before, plainBytes(changed)), "unknown shape mutated: " + scenario);
		}
	}

	private static ClassNode fixture(ClassNode original) {
		ClassNode c = new ClassNode(); c.visit(V1_8, ACC_PUBLIC, TARGET, null, "java/lang/Object", null);
		for (FieldNode f : original.fields) { if (f.name.equals("ENTITY_TYPE_MAP")) { c.fields.add(new FieldNode(f.access, f.name, f.desc, f.signature, f.value)); } }
		for (MethodNode m : original.methods) {
			if (m.name.equals("getEntityType") || m.name.startsWith("lambda$getEntityType$")) {
				MethodNode cloned = new MethodNode(m.access, m.name, m.desc, m.signature, m.exceptions.toArray(new String[0])); m.accept(cloned); c.methods.add(cloned);
			}
		}
		MethodNode init = new MethodNode(ACC_STATIC, "<clinit>", "()V", null, null);
		init.instructions.add(new TypeInsnNode(NEW, "it/unimi/dsi/fastutil/objects/Object2ObjectOpenHashMap")); init.instructions.add(new InsnNode(DUP));
		init.instructions.add(new MethodInsnNode(INVOKESPECIAL, "it/unimi/dsi/fastutil/objects/Object2ObjectOpenHashMap", "<init>", "()V", false));
		init.instructions.add(new FieldInsnNode(PUTSTATIC, TARGET, "ENTITY_TYPE_MAP", "Ljava/util/Map;")); init.instructions.add(new InsnNode(RETURN)); c.methods.add(init);
		return c;
	}
	@SuppressWarnings("unchecked")
	private static Harness harness(ClassNode source) throws Exception {
		Map<String, String> map = new HashMap<>();
		map.put(TARGET, "fixture/TesEntityType"); map.put(ENTITY, Type.getInternalName(Entity.class));
		map.put("net/minecraft/entity/EntityType", Type.getInternalName(EntityKind.class)); map.put(TYPE, Type.getInternalName(Kind.class));
		map.put("net/minecraft/entity/IAngerable", Type.getInternalName(Angry.class)); map.put("net/minecraft/entity/monster/MonsterEntity", Type.getInternalName(Monster.class));
		ClassNode remapped = new ClassNode(); source.accept(new ClassRemapper(remapped, new SimpleRemapper(map)));
		remapped.interfaces.add(Type.getInternalName(Lookup.class));
		MethodNode ctor = new MethodNode(ACC_PUBLIC, "<init>", "()V", null, null);
		ctor.instructions.add(new VarInsnNode(ALOAD, 0)); ctor.instructions.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)); ctor.instructions.add(new InsnNode(RETURN)); remapped.methods.add(ctor);
		String desc = "(L" + Type.getInternalName(Entity.class) + ";)L" + Type.getInternalName(Kind.class) + ";";
		MethodNode bridge = new MethodNode(ACC_PUBLIC, "type", desc, null, null);
		bridge.instructions.add(new VarInsnNode(ALOAD, 1)); bridge.instructions.add(new MethodInsnNode(INVOKESTATIC, remapped.name, "getEntityType", desc, false)); bridge.instructions.add(new InsnNode(ARETURN)); remapped.methods.add(bridge);
		Class<?> type = new Loader().define(bytes(remapped));
		java.lang.reflect.Field cache = type.getDeclaredField("ENTITY_TYPE_MAP"); cache.setAccessible(true);
		return new Harness((Lookup) type.getConstructor().newInstance(), (Map<Class<?>, Kind>) cache.get(null));
	}
	private static class Harness {
		final Lookup lookup; final Map<Class<?>, Kind> cache;
		Harness(Lookup lookup, Map<Class<?>, Kind> cache) { this.lookup = lookup; this.cache = cache; }
	}
	public static class Plugin extends CompatMixinPlugin { @Override public boolean shouldApplyMixin(String target, String mixin) { return true; } }
	public static class Service extends FrameTimeInstrumentationCheck.Service {
		@Override public InputStream getResourceAsStream(String name) { byte[] data = resources.get(name); return data == null ? super.getResourceAsStream(name) : new ByteArrayInputStream(data); }
	}
	private static class Loader extends ClassLoader { Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); } }
	private static MethodNode method(ClassNode c, String name, String desc) { for (MethodNode m : c.methods) { if (m.name.equals(name) && m.desc.equals(desc)) { return m; } } throw new AssertionError(name + desc); }
	private static int dynamicCalls(MethodNode m) { int n = 0; for (AbstractInsnNode i : m.instructions) { if (i instanceof InvokeDynamicInsnNode) { n++; } } return n; }
	private static ClassNode read(InputStream in) throws IOException { ClassNode c = new ClassNode(); new ClassReader(in).accept(c, 0); return c; }
	private static ClassNode copy(ClassNode source) {
		// ASM's visitor copy shares invokedynamic bootstrap argument arrays. Round-trip
		// through bytes so malformed-input cases cannot mutate the original fixture.
		ClassNode c = new ClassNode(); new ClassReader(plainBytes(source)).accept(c, 0); return c;
	}
	private static byte[] bytes(ClassNode c) { ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS); c.accept(w); return w.toByteArray(); }
	private static byte[] plainBytes(ClassNode c) { ClassWriter w = new ClassWriter(0); c.accept(w); return w.toByteArray(); }
	private static byte[] methodBytes(MethodNode m) { ClassNode c = new ClassNode(); c.visit(V1_8, ACC_PUBLIC, "MethodFingerprint", null, "java/lang/Object", null); c.methods.add(m); return plainBytes(c); }
	private static void check(boolean okay, String message) { if (!okay) { throw new AssertionError(message); } }
}
