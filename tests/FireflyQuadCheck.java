package net.coderbot.iris.diagnostics;

import com.mojang.math.Quaternion;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import net.coderbot.iris.compat.illuminations.FireflyQuadOptimizer;
import net.coderbot.iris.mixin.compat.CompatMixinPlugin;
import net.minecraft.world.phys.Vec3;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import static org.objectweb.asm.Opcodes.*;

/** Actual installed render bytecode + production Mixin/plugin; no game, world, texture or GL. */
public final class FireflyQuadCheck {
	private static final String TARGET = FireflyQuadOptimizer.TARGET;
	private static final Map<String, byte[]> resources = new HashMap<>();
	private static ClassNode before, after;
	private static volatile long sink;
	private static long comparisons;

	public interface Render { void func_225606_a_(Buffer buffer, Camera camera, float partial); }
	public interface Buffer {
		Buffer func_225582_a_(double x, double y, double z);
		Buffer func_225583_a_(float u, float v);
		Buffer func_227885_a_(float r, float g, float b, float alpha);
		Buffer func_227886_a_(int light);
		void func_181675_d();
	}
	public static class Camera {
		public Vec3 position = Vec3.ZERO;
		public Quaternion rotation = new Quaternion(0, 0, 0, 1);
		public Vec3 func_216785_c() { return position; }
		public Quaternion func_227995_f_() { return rotation; }
	}
	public static class Base {
		public double field_187123_c, field_187124_d, field_187125_e, field_187126_f, field_187127_g, field_187128_h;
		public float field_190014_F, field_190015_G, field_82339_as, field_70552_h, field_70553_i, field_70551_j;
		public float size, u0, u1, v0, v1;
		public int sizeCalls;
		public float func_217561_b(float partial) { sizeCalls++; return size * (0.75F + partial * 0.25F); }
		public float func_217563_c() { return u0; }
		public float func_217564_d() { return u1; }
		public float func_217562_e() { return v0; }
		public float func_217560_f() { return v1; }
	}
	public static class Config {
		public static int alpha, reads, failAt;
		public static boolean changing;
		public static final IllegalStateException FAILURE = new IllegalStateException("test config failure");
		public static int getFireflyWhiteAlpha() {
			if (++reads == failAt) { throw FAILURE; }
			return alpha + (changing ? reads : 0);
		}
	}
	public static class Probe implements Buffer {
		public final long[] values = new long[160];
		public int count, vertices, failAt;
		public long checksum;
		public Base mutate;
		public Camera camera;
		public void clear() { count = vertices = 0; checksum = 0; }
		private void value(long value) { values[count++] = value; checksum = checksum * 31 + value; }
		public Buffer func_225582_a_(double x, double y, double z) {
			value(1); value(Double.doubleToLongBits(x)); value(Double.doubleToLongBits(y)); value(Double.doubleToLongBits(z));
			if (++vertices == failAt) { throw Config.FAILURE; }
			if (mutate != null) {
				mutate.field_70552_h += 0.01F;
				// A re-entrant/custom buffer may change the camera after positions were computed.
				camera.rotation.set(0.2F, 0.3F, 0.4F, 0.5F);
			}
			return this;
		}
		public Buffer func_225583_a_(float u, float v) { value(2); value(Float.floatToIntBits(u)); value(Float.floatToIntBits(v)); return this; }
		public Buffer func_227885_a_(float r, float g, float b, float a) {
			value(3); value(Float.floatToIntBits(r)); value(Float.floatToIntBits(g)); value(Float.floatToIntBits(b)); value(Float.floatToIntBits(a)); return this;
		}
		public Buffer func_227886_a_(int light) { value(4); value(light); return this; }
		public void func_181675_d() { value(5); }
	}

	public static void main(String[] args) throws Exception {
		check(args.length == 2, "expected allocation mode and installed Illuminations jar");
		ClassNode installed;
		try (ZipFile zip = new ZipFile(args[1]); InputStream in = zip.getInputStream(zip.getEntry(TARGET + ".class"))) {
			installed = read(in);
		}
		ClassNode actualAfter = copy(installed);
		check(FireflyQuadOptimizer.optimize(actualAfter) == 1, "Installed Illuminations renderer unsupported: " + FireflyQuadOptimizer.fingerprint(render(installed)));
		for (int i = 0; i < installed.methods.size(); i++) {
			if (installed.methods.get(i) != render(installed)) {
				check(methodBytes(installed.methods.get(i)).equals(methodBytes(actualAfter.methods.get(i))), "non-render behavior changed");
			}
		}
		ClassNode fixture = fixture(render(installed));
		resources.put(TARGET + ".class", bytes(fixture));
		String config = "{\"required\":true,\"minVersion\":\"0.8\",\"compatibilityLevel\":\"JAVA_8\","
				+ "\"package\":\"net.coderbot.iris.mixin.compat.illuminations\",\"plugin\":\"" + Plugin.class.getName()
				+ "\",\"client\":[\"MixinFireflyParticle\"]}";
		resources.put("firefly-check.json", config.getBytes(StandardCharsets.UTF_8));
		MixinBootstrap.init();
		MixinEnvironment env = MixinEnvironment.getDefaultEnvironment(); env.setSide(MixinEnvironment.Side.CLIENT);
		Mixins.addConfiguration("firefly-check.json");
		byte[] transformed = ((Service) MixinService.getService()).transformer().transformClass(env, TARGET.replace('/', '.'), bytes(fixture));
		check(before != null && after != null, "production postApply did not run");
		check(FireflyQuadOptimizer.optimize(after) == 0, "rewrite is not idempotent");
		check(vectorAllocations(before) == 6 && vectorAllocations(after) == 0, "quad vectors/array were not removed");
		Render legacy = load(bytes(before)), optimized = load(transformed);
		Base a = (Base) legacy, b = (Base) optimized;
		Camera camera = new Camera(); Probe pa = new Probe(), pb = new Probe();
		Random random = new Random(0x51005f1L);
		float[] edges = {0.0F, -0.0F, 1, -1, Float.MIN_VALUE, Float.MAX_VALUE, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
		for (int i = 0; i < 25000; i++) {
			fill(a, random);
			float delta = random.nextFloat() * 1.5F - 0.25F;
			Quaternion q = new Quaternion(random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1);
			camera.position = new Vec3(random.nextDouble() * 60000000 - 30000000, random.nextDouble() * 512 - 128, random.nextDouble() * 60000000 - 30000000);
			if (i < edges.length * edges.length) {
				a.field_82339_as = edges[i % edges.length]; a.size = edges[i / edges.length];
				delta = edges[(i / edges.length + i) % edges.length];
				q = new Quaternion(edges[(i + 1) % edges.length], edges[(i + 2) % edges.length], edges[(i + 3) % edges.length], edges[(i + 4) % edges.length]);
			}
			if (i % 2 == 0) { a.field_190014_F = 0; }
			compare(legacy, optimized, a, b, camera, q, delta, pa, pb, i % 13 == 0, i % 17 == 0 ? 3 : 0, i % 19 == 0 ? 6 : 0);
		}
		checkFallbacks(installed);
		String allocation = allocation(legacy, optimized, a, b, camera, pa, pb, args[0]);
		String report = "Installed Illuminations 0.0.4.11 render bytecode + actual Mixin " + env.getVersion()
				+ " + production plugin: " + comparisons + " bitwise vertex-stream comparisons passed.\n"
				+ "Eight vertices, emission/UV/color/alpha order, live white-alpha config reads, roll/partial tick, extreme coordinates, non-unit quaternions, float edge values, consumer side effects/errors verified.\n"
				+ "Only render changed; unknown method shapes keep original code; repeated application is inert. Quad vector/array sites: 6 -> 0.\n" + allocation + "\n";
		Files.createDirectories(Paths.get("build/reports"));
		Files.write(Paths.get("build/reports/firefly-quad-" + args[0] + ".txt"), report.getBytes(StandardCharsets.UTF_8));
		System.out.print(report);
	}

	private static void fill(Base p, Random r) {
		p.field_187123_c = r.nextDouble() * 60000000 - 30000000; p.field_187124_d = r.nextDouble() * 512; p.field_187125_e = -p.field_187123_c;
		p.field_187126_f = p.field_187123_c + r.nextDouble(); p.field_187127_g = p.field_187124_d + r.nextDouble(); p.field_187128_h = p.field_187125_e + r.nextDouble();
		p.field_190014_F = r.nextFloat() * 6; p.field_190015_G = r.nextFloat() * 6;
		p.field_82339_as = r.nextFloat() * 3 - 1; p.field_70552_h = r.nextFloat(); p.field_70553_i = r.nextFloat(); p.field_70551_j = r.nextFloat();
		p.size = r.nextFloat() * 8; p.u0 = r.nextFloat(); p.u1 = r.nextFloat(); p.v0 = r.nextFloat(); p.v1 = r.nextFloat();
	}
	private static void copyState(Base a, Base b) throws Exception {
		for (java.lang.reflect.Field f : Base.class.getFields()) { f.set(b, f.get(a)); }
	}
	private static void compare(Render a, Render b, Base ap, Base bp, Camera camera, Quaternion rotation, float delta,
			Probe pa, Probe pb, boolean mutate, int configFailure, int vertexFailure) throws Exception {
		copyState(ap, bp);
		pa.clear(); pb.clear(); pa.mutate = mutate ? ap : null; pb.mutate = mutate ? bp : null; pa.camera = pb.camera = camera;
		pa.failAt = pb.failAt = vertexFailure;
		Config.alpha = (int) comparisons % 301 - 100; Config.failAt = configFailure; Config.changing = true;
		Config.reads = ap.sizeCalls = 0; camera.rotation = new Quaternion(rotation);
		Throwable oldError = draw(a, pa, camera, delta); int oldReads = Config.reads;
		Config.reads = bp.sizeCalls = 0; camera.rotation = new Quaternion(rotation);
		Throwable newError = draw(b, pb, camera, delta);
		check(oldError == newError && oldReads == Config.reads && ap.sizeCalls == 1 && bp.sizeCalls == 1 && pa.count == pb.count,
				"render callbacks/exception behavior changed at " + comparisons);
		for (int i = 0; i < pa.count; i++) { check(pa.values[i] == pb.values[i], "vertex stream differs at comparison " + comparisons + " field " + i); }
		check(oldError != null || pa.vertices == 8 && Config.reads == 4, "expected two quad layers and four config reads");
		comparisons++;
	}
	private static Throwable draw(Render r, Probe out, Camera camera, float delta) {
		try { r.func_225606_a_(out, camera, delta); return null; } catch (RuntimeException e) { return e; }
	}
	private static String allocation(Render a, Render b, Base ap, Base bp, Camera camera, Probe pa, Probe pb, String mode) throws Exception {
		fill(ap, new Random(777)); ap.field_190014_F = 0; ap.field_82339_as = 0.5F; copyState(ap, bp);
		camera.position = Vec3.ZERO; camera.rotation = new Quaternion(0.1F, 0.2F, 0.3F, 0.9F);
		pa.mutate = pb.mutate = null; pa.failAt = pb.failAt = Config.failAt = 0; Config.alpha = 100; Config.changing = false;
		com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		check(bean.isThreadAllocatedMemorySupported(), "allocation counter unavailable"); bean.setThreadAllocatedMemoryEnabled(true);
		work(a, pa, camera, 100000); work(b, pb, camera, 100000);
		long id = Thread.currentThread().getId(), at = bean.getThreadAllocatedBytes(id), time = System.nanoTime();
		work(a, pa, camera, 200000); long oldTime = System.nanoTime() - time, oldBytes = bean.getThreadAllocatedBytes(id) - at;
		at = bean.getThreadAllocatedBytes(id); time = System.nanoTime();
		work(b, pb, camera, 200000); long newTime = System.nanoTime() - time, newBytes = bean.getThreadAllocatedBytes(id) - at;
		check(pa.checksum == pb.checksum, "allocation workload vertices differ");
		check(newBytes < 200000, "optimized ordinary firefly draw still allocates >=1 byte/call");
		if (mode.equals("no-ea")) { check(oldBytes > 100000000 && newBytes < oldBytes / 50, "temporary allocation improvement missing"); }
		return "200000 ordinary (zero-roll) draws, " + mode + ": allocated bytes " + oldBytes + " -> " + newBytes
				+ "; isolated CPU workload ms " + oldTime / 1000000.0 + " -> " + newTime / 1000000.0
				+ ". Nonzero roll keeps the original rotation branch. These are not game FPS measurements.";
	}
	private static void work(Render r, Probe probe, Camera camera, int count) {
		for (int i = 0; i < count; i++) { probe.clear(); r.func_225606_a_(probe, camera, (i & 255) / 255.0F); sink = probe.checksum; }
	}
	private static void checkFallbacks(ClassNode original) {
		ClassNode changed = copy(original); render(changed).instructions.insert(new InsnNode(NOP));
		String hash = FireflyQuadOptimizer.fingerprint(render(changed));
		check(FireflyQuadOptimizer.optimize(changed) == 0 && hash.equals(FireflyQuadOptimizer.fingerprint(render(changed))), "unknown injected render body was changed");
		changed = copy(original); changed.name += "Other";
		check(FireflyQuadOptimizer.optimize(changed) == 0, "other particle was changed");
	}

	public static class Plugin extends CompatMixinPlugin {
		@Override public boolean shouldApplyMixin(String target, String mixin) { return true; }
		@Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {
			before = copy(node); super.postApply(target, node, mixin, info); after = copy(node);
		}
	}
	public static class Service extends FrameTimeInstrumentationCheck.Service {
		@Override public InputStream getResourceAsStream(String name) {
			byte[] data = resources.get(name); return data == null ? super.getResourceAsStream(name) : new ByteArrayInputStream(data);
		}
	}
	private static ClassNode fixture(MethodNode original) {
		ClassNode c = new ClassNode(); c.version = V1_8; c.access = ACC_PUBLIC; c.name = TARGET; c.superName = Type.getInternalName(Base.class);
		MethodNode ctor = new MethodNode(ACC_PUBLIC, "<init>", "()V", null, null);
		ctor.instructions.add(new VarInsnNode(ALOAD, 0)); ctor.instructions.add(new MethodInsnNode(INVOKESPECIAL, c.superName, "<init>", "()V", false)); ctor.instructions.add(new InsnNode(RETURN)); c.methods.add(ctor);
		MethodNode render = new MethodNode(ACC_PUBLIC, original.name, original.desc, null, null); original.accept(render); c.methods.add(render);
		return c;
	}
	private static Render load(byte[] bytes) throws Exception {
		Map<String, String> m = new HashMap<>();
		m.put("com/mojang/blaze3d/vertex/IVertexBuilder", Type.getInternalName(Buffer.class));
		m.put("net/minecraft/client/renderer/ActiveRenderInfo", Type.getInternalName(Camera.class));
		m.put("ladysnake/illuminations/client/Config", Type.getInternalName(Config.class));
		String v = "net/minecraft/util/math/vector/Vector3f", q = "net/minecraft/util/math/vector/Quaternion", d = "net/minecraft/util/math/vector/Vector3d", h = "net/minecraft/util/math/MathHelper";
		m.put(v, "com/mojang/math/Vector3f"); m.put(q, "com/mojang/math/Quaternion"); m.put(d, "net/minecraft/world/phys/Vec3"); m.put(h, "net/minecraft/util/Mth");
		m.put(v + ".field_229183_f_", "ZP");
		m.put(v + ".func_195899_a()F", "x"); m.put(v + ".func_195900_b()F", "y"); m.put(v + ".func_195902_c()F", "z");
		m.put(v + ".func_214905_a(L" + q + ";)V", "transform"); m.put(v + ".func_195898_a(F)V", "mul"); m.put(v + ".func_195904_b(FFF)V", "add"); m.put(v + ".func_229193_c_(F)L" + q + ";", "rotation");
		m.put(q + ".func_195889_a()F", "i"); m.put(q + ".func_195891_b()F", "j"); m.put(q + ".func_195893_c()F", "k"); m.put(q + ".func_195894_d()F", "r"); m.put(q + ".func_195890_a(L" + q + ";)V", "mul");
		m.put(d + ".func_82615_a()D", "x"); m.put(d + ".func_82617_b()D", "y"); m.put(d + ".func_82616_c()D", "z");
		m.put(h + ".func_219803_d(DDD)D", "lerp"); m.put(h + ".func_219799_g(FFF)F", "lerp");
		ClassNode remapped = new ClassNode(); new ClassReader(bytes).accept(new ClassRemapper(remapped, new SimpleRemapper(m)), 0);
		remapped.interfaces.add(Type.getInternalName(Render.class));
		return (Render) new Loader().define(bytes(remapped)).getConstructor().newInstance();
	}
	private static class Loader extends ClassLoader { Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); } }
	private static MethodNode render(ClassNode c) { for (MethodNode m : c.methods) { if (m.name.equals("func_225606_a_")) { return m; } } throw new AssertionError("render missing"); }
	private static String methodBytes(MethodNode m) { return FireflyQuadOptimizer.fingerprint(m); }
	private static int vectorAllocations(ClassNode c) { int n = 0; for (AbstractInsnNode i : render(c).instructions) { if (i instanceof TypeInsnNode && (i.getOpcode() == NEW || i.getOpcode() == ANEWARRAY) && ((TypeInsnNode) i).desc.endsWith("/Vector3f")) { n++; } } return n; }
	private static ClassNode copy(ClassNode original) { ClassNode c = new ClassNode(); original.accept(c); return c; }
	private static ClassNode read(InputStream in) throws IOException { ClassNode c = new ClassNode(); new ClassReader(in).accept(c, 0); return c; }
	private static byte[] bytes(ClassNode c) { ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS); c.accept(w); return w.toByteArray(); }
	private static void check(boolean b, String message) { if (!b) { throw new AssertionError(message); } }
}
