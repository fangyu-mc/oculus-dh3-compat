package net.coderbot.iris.mixin.color;

import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.FoliageColor;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;

/** Compares actual Minecraft methods with the compiled overwrites without starting Minecraft. */
public final class BiomeColorCheck {

	private static final String BIOME = "net/minecraft/world/level/biome/Biome";
	private static final String MIXIN = "net/coderbot/iris/mixin/color/MixinBiome";
	private static final String[] METHODS = {"getGrassColor", "getFoliageColor"};
	private static long comparisons;
	private static volatile long checksum;
	private static final PlainModifier NONE = new PlainModifier(0);
	private static final Loader LOADER = new Loader();

	public interface Colors { int getGrassColor(double x, double z); int getFoliageColor(); }
	public abstract static class FixtureBiome implements Colors {
		public Climate climateSettings = new Climate();
		public Effects specialEffects = new Effects();
		public int grassReads, foliageReads;
		public boolean failTexture;
		public Effects replaceEffectsDuringLookup;
		public void beforeTexture(boolean grass) {
			if (failTexture) { throw new IllegalStateException("texture fixture"); }
			if (grass) { grassReads++; } else { foliageReads++; }
			if (replaceEffectsDuringLookup != null) { specialEffects = replaceEffectsDuringLookup; }
		}
	}
	public static final class Climate { public float temperature, downfall; }
	public static class Effects {
		public Optional<Integer> grass = Optional.empty(), foliage = Optional.empty();
		public Modifier modifier = NONE;
		public Optional<Integer> getGrassColorOverride() { return grass; }
		public Optional<Integer> getFoliageColorOverride() { return foliage; }
		public Modifier getGrassColorModifier() { return modifier; }
	}
	public abstract static class Modifier { public abstract int modifyColor(double x, double z, int base); }
	public static class PlainModifier extends Modifier {
		final int mode;
		PlainModifier(int mode) { this.mode = mode; }
		@Override public int modifyColor(double x, double z, int base) {
			if (mode == 0) { return base; }
			if (mode == 1) { return ((base & 16711422) + 2634762) >> 1; } // dark forest
			// Exercise position-dependent and arbitrary mod-provided modifiers, including unusual coordinates.
			if (mode == 2) { return Math.sin(x * .0225 + z * .0175) < -.1 ? 5011004 : 6975545; }
			return base ^ (int) Double.doubleToRawLongBits(x) ^ (int) (Double.doubleToRawLongBits(z) >>> 32);
		}
	}
	private static final class RecordingModifier extends Modifier {
		long xBits, zBits; int baseColor, calls, output;
		@Override public int modifyColor(double x, double z, int base) {
			xBits = Double.doubleToRawLongBits(x); zBits = Double.doubleToRawLongBits(z); baseColor = base; calls++; return output;
		}
	}

	public static void main(String[] args) throws Exception {
		ClassNode source = read(BIOME), mixin = read(MIXIN);
		validate(source, mixin);
		Class<?> legacy = generate(source, mixin, false), current = generate(source, mixin, true);
		setTextures(21907);
		FixtureBiome before = fixture(legacy), after = fixture(current);
		Random random = new Random(195307);
		float[] climateEdges = {-Float.MAX_VALUE, -1, -Float.MIN_VALUE, -0.0f, 0, Float.MIN_VALUE,
				.5f, 1, 2, Float.MAX_VALUE, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NaN};
		double[] positions = {-30000000, -1, -Double.MIN_VALUE, -0.0, 0, Double.MIN_VALUE, 1,
				30000000, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
		Integer[] overrides = {null, 0, 1, -1, 0x76a931, 0xff000000, Integer.MAX_VALUE};
		for (float temperature : climateEdges) {
			for (float rainfall : climateEdges) {
				for (int mode = 0; mode < 4; mode++) {
					configure(before, after, temperature, rainfall, overrides[mode], overrides[mode + 1], new PlainModifier(mode));
					for (double x : positions) { compare(before, after, x, -x); }
				}
			}
		}
		for (int i = 0; i < 30000; i++) {
			configure(before, after, random.nextFloat() * 4 - 2, random.nextFloat() * 3 - 1,
					i % 3 == 0 ? random.nextInt() : null, i % 5 == 0 ? random.nextInt() : null, new PlainModifier(i % 4));
			compare(before, after, random.nextDouble() * 60000000 - 30000000, random.nextDouble() * 60000000 - 30000000);
		}
		checkLiveChanges(legacy, current);
		checkDispatch(current);
		checkConcurrent(legacy, current);
		String allocation = checkAllocation(legacy, current);
		String report = "Actual Minecraft/compiled overwrite comparisons passed: " + comparisons + " grass/foliage queries.\n"
				+ "Explicit overrides (including zero/negative colors), lazy texture fallback, climate/coordinate boundaries, "
				+ "resource color-map reloads, live effect changes, coordinate-dependent/custom modifiers, "
				+ "fallback failures, subclass dispatch and four concurrent readers checked.\n"
				+ "Original native texture lookup methods remain in use; no cached colors or new biome fields.\n"
				+ allocation + "\n";
		Files.createDirectories(Paths.get("build/reports"));
		Files.write(Paths.get("build/reports/biome-color.txt"), report.getBytes(StandardCharsets.UTF_8));
		System.out.print(report);
	}

	private static void configure(FixtureBiome a, FixtureBiome b, float temperature, float rain,
			Integer grass, Integer foliage, Modifier modifier) {
		for (FixtureBiome biome : new FixtureBiome[]{a, b}) {
			biome.climateSettings.temperature = temperature; biome.climateSettings.downfall = rain;
			biome.specialEffects.grass = Optional.ofNullable(grass); biome.specialEffects.foliage = Optional.ofNullable(foliage);
			biome.specialEffects.modifier = modifier; biome.grassReads = biome.foliageReads = 0;
		}
	}
	private static void compare(FixtureBiome a, FixtureBiome b, double x, double z) {
		check(a.getGrassColor(x, z) == b.getGrassColor(x, z), "grass color changed");
		check(a.getFoliageColor() == b.getFoliageColor(), "foliage color changed");
		check(a.grassReads == b.grassReads && a.foliageReads == b.foliageReads, "fallback evaluation count changed");
		comparisons += 2;
	}
	private static void checkLiveChanges(Class<?> legacy, Class<?> current) throws Exception {
		FixtureBiome a = fixture(legacy), b = fixture(current);
		configure(a, b, .35f, .8f, null, null, NONE);
		int grass = b.getGrassColor(12, -44), foliage = b.getFoliageColor();
		a.getGrassColor(12, -44); a.getFoliageColor();
		setTextures(19017); compare(a, b, 12, -44);
		int reloadedGrass = b.getGrassColor(12, -44), reloadedFoliage = b.getFoliageColor();
		check(grass != reloadedGrass && foliage != reloadedFoliage, "texture reload retained stale colors");
		a.getGrassColor(12, -44); a.getFoliageColor();
		configure(a, b, .35f, .8f, 0, -1, NONE);
		a.failTexture = b.failTexture = true; compare(a, b, 5, 9);
		check(a.grassReads == 0 && a.foliageReads == 0, "present override evaluated texture fallback");
		for (FixtureBiome biome : new FixtureBiome[]{a, b}) {
			biome.specialEffects.grass = Optional.empty(); biome.specialEffects.foliage = Optional.empty();
			try { biome.getGrassColor(0, 0); throw new AssertionError("grass fallback failure swallowed"); }
			catch (IllegalStateException expected) { check(expected.getMessage().equals("texture fixture"), "wrong failure"); }
			try { biome.getFoliageColor(); throw new AssertionError("foliage fallback failure swallowed"); }
			catch (IllegalStateException expected) { }
			biome.failTexture = false;
			RecordingModifier modifier = new RecordingModifier(); modifier.output = 0x5932a1;
			Effects replacement = new Effects(); replacement.modifier = modifier;
			biome.replaceEffectsDuringLookup = replacement;
			double x = Double.longBitsToDouble(0x7ff8000000000123L), z = -0.0;
			check(biome.getGrassColor(x, z) == modifier.output, "replaced special effects were not read live");
			check(modifier.calls == 1 && modifier.xBits == Double.doubleToRawLongBits(x)
					&& modifier.zBits == Double.doubleToRawLongBits(z), "modifier arguments/call count changed");
			check(modifier.baseColor == reloadedGrass, "native texture base color changed");
		}
	}
	private static void checkConcurrent(Class<?> legacy, Class<?> current) throws Exception {
		final FixtureBiome a = fixture(legacy), b = fixture(current);
		configure(a, b, .61f, .74f, null, null, new PlainModifier(3));
		ExecutorService pool = Executors.newFixedThreadPool(4);
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (int worker = 0; worker < 4; worker++) {
				final int seed = worker;
				futures.add(pool.submit(() -> {
					for (int i = 0; i < 10000; i++) {
						double x = seed * 3000 - i * .137, z = seed * -913 + i * .219;
						check(a.getGrassColor(x, z) == b.getGrassColor(x, z) && a.getFoliageColor() == b.getFoliageColor(), "concurrent color changed");
					}
				}));
			}
			for (Future<?> f : futures) { f.get(); } comparisons += 80000;
		} finally { pool.shutdownNow(); }
	}
	private static void checkDispatch(Class<?> current) throws Exception {
		String name = current.getName().replace('.', '/') + "Subclass";
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, current.getName().replace('.', '/'), null);
		constructor(writer, current.getName().replace('.', '/'));
		for (String method : METHODS) {
			MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, method, method.equals("getGrassColor") ? "(DD)I" : "()I", null, null);
			mv.visitLdcInsn(0x175639); mv.visitInsn(Opcodes.IRETURN); mv.visitMaxs(0, 0); mv.visitEnd();
		}
		writer.visitEnd(); Colors subclass = (Colors) LOADER.define(writer.toByteArray()).getConstructor().newInstance();
		check(subclass.getGrassColor(0, 0) == 0x175639 && subclass.getFoliageColor() == 0x175639, "custom biome override bypassed");
	}
	private static String checkAllocation(Class<?> legacy, Class<?> current) throws Exception {
		check(ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-XX:-DoEscapeAnalysis"), "allocation check requires controlled EA setting");
		Class<?> api = Class.forName("com.sun.management.ThreadMXBean"); Object bean = ManagementFactory.getThreadMXBean();
		if (!api.isInstance(bean) || !(Boolean) api.getMethod("isThreadAllocatedMemorySupported").invoke(bean)) {
			return "Allocation counter unavailable; behavior and no-allocation-bytecode checks passed.";
		}
		api.getMethod("setThreadAllocatedMemoryEnabled", boolean.class).invoke(bean, true);
		Method bytes = api.getMethod("getThreadAllocatedBytes", long.class); long id = Thread.currentThread().getId();
		FixtureBiome a = fixture(legacy), b = fixture(current); configure(a, b, .31f, .85f, null, null, NONE);
		run(a, 100000); run(b, 100000);
		long start = (Long) bytes.invoke(bean, id); long expected = run(a, 400000); long oldBytes = (Long) bytes.invoke(bean, id) - start;
		start = (Long) bytes.invoke(bean, id); long actual = run(b, 400000); long newBytes = (Long) bytes.invoke(bean, id) - start;
		check(expected == actual, "allocation workload color mismatch");
		check(oldBytes > 1000000 && newBytes < 8192, "boxing remains: legacy=" + oldBytes + ", new=" + newBytes);
		return "800,000 warmed color queries with escape analysis disabled: legacy=" + oldBytes + " B, primitive=" + newBytes
				+ " B (counter overhead included). Controlled explicit-allocation check, not an in-game allocation-rate/FPS measurement.";
	}
	private static long run(Colors colors, int pairs) {
		long result = 0;
		for (int i = 0; i < pairs; i++) { result += colors.getGrassColor(i * .01, -i * .03); result += colors.getFoliageColor(); }
		checksum = result; return result;
	}
	private static void setTextures(int seed) {
		Random random = new Random(seed); int[] grass = new int[65536], foliage = new int[65536];
		for (int i = 0; i < grass.length; i++) { grass[i] = 0x10000 | random.nextInt(0x1000000); foliage[i] = 0x10000 | random.nextInt(0x1000000); }
		GrassColor.init(grass); FoliageColor.init(foliage);
	}

	private static void validate(ClassNode source, ClassNode mixin) {
		check(mixin.version == Opcodes.V1_8 && mixin.fields.size() == 1 && mixin.fields.get(0).name.equals("specialEffects"), "unexpected biome state added");
		for (String name : METHODS) {
			MethodNode old = method(source, name), current = method(mixin, name);
			check(old.desc.equals(current.desc) && (old.access & Opcodes.ACC_PUBLIC) != 0, "overwrite descriptor changed");
			check(current.visibleAnnotations.stream().anyMatch(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Overwrite;")), "overwrite annotation missing");
			int optionalCalls = 0, suppliers = 0;
			for (AbstractInsnNode n : old.instructions) {
				if (n instanceof InvokeDynamicInsnNode) { suppliers++; }
				if (n instanceof MethodInsnNode && ((MethodInsnNode) n).name.equals("orElseGet")) { optionalCalls++; }
			}
			check(optionalCalls == 1 && suppliers == 1, "native fallback shape changed");
			for (AbstractInsnNode n : current.instructions) {
				check(!(n instanceof InvokeDynamicInsnNode) && n.getOpcode() != Opcodes.NEW && n.getOpcode() != Opcodes.PUTFIELD, "allocation or retained color added");
				if (n instanceof MethodInsnNode) {
					MethodInsnNode call = (MethodInsnNode) n;
					check(!(call.owner.equals("java/lang/Integer") && call.name.equals("valueOf")), "boxed fallback remains");
				}
			}
		}
	}
	private static Class<?> generate(ClassNode source, ClassNode mixin, boolean optimized) {
		String generated = "net/coderbot/iris/mixin/color/GeneratedBiome" + (optimized ? "New" : "Old");
		ClassNode node = new ClassNode(); node.version = Opcodes.V1_8; node.access = Opcodes.ACC_PUBLIC;
		node.name = generated; node.superName = Type.getInternalName(FixtureBiome.class);
		constructor(node, node.superName);
		Map<String, String> names = new HashMap<>(); names.put(BIOME, generated); names.put(MIXIN, generated);
		names.put(BIOME + "$ClimateSettings", Type.getInternalName(Climate.class));
		names.put("net/minecraft/world/level/biome/BiomeSpecialEffects", Type.getInternalName(Effects.class));
		names.put("net/minecraft/world/level/biome/BiomeSpecialEffects$GrassColorModifier", Type.getInternalName(Modifier.class));
		for (String name : new String[]{"getGrassColor", "getFoliageColor", "getGrassColorFromTexture", "getFoliageColorFromTexture"}) {
			MethodNode original = method(optimized && !name.endsWith("FromTexture") ? mixin : source, name);
			MethodNode copy = new MethodNode(original.access, original.name, original.desc, null, null); original.accept(copy);
			if (name.endsWith("FromTexture")) {
				InsnList hook = new InsnList(); hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
				hook.add(new InsnNode(name.startsWith("getGrass") ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
				hook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.superName, "beforeTexture", "(Z)V", false)); copy.instructions.insert(hook);
			}
			node.methods.add(copy);
		}
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		node.accept(new ClassRemapper(writer, new SimpleRemapper(names))); return LOADER.define(writer.toByteArray());
	}
	private static void constructor(ClassVisitor writer, String parent) {
		MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		mv.visitVarInsn(Opcodes.ALOAD, 0); mv.visitMethodInsn(Opcodes.INVOKESPECIAL, parent, "<init>", "()V", false);
		mv.visitInsn(Opcodes.RETURN); mv.visitMaxs(0, 0); mv.visitEnd();
	}
	private static FixtureBiome fixture(Class<?> type) throws Exception { return (FixtureBiome) type.getConstructor().newInstance(); }
	private static MethodNode method(ClassNode node, String name) { return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError(name)); }
	private static ClassNode read(String name) throws Exception {
		try (InputStream in = BiomeColorCheck.class.getClassLoader().getResourceAsStream(name + ".class")) {
			check(in != null, "missing class " + name); ClassNode out = new ClassNode(); new ClassReader(in).accept(out, 0); return out;
		}
	}
	private static void check(boolean okay, String message) { if (!okay) { throw new AssertionError(message); } }
	private static final class Loader extends ClassLoader {
		Loader() { super(BiomeColorCheck.class.getClassLoader()); }
		Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
	}
}
