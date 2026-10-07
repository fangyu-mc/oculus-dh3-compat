package net.coderbot.iris.diagnostics;

import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeZoomer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.service.MixinService;

import static org.objectweb.asm.Opcodes.*;

/** Differential check against actual Minecraft bytecode. No game, world or renderer is started. */
public final class BiomeZoomAllocationCheck {
	private static final String TARGET = "net.minecraft.world.level.biome.FuzzyOffsetBiomeZoomer";
	private static final int[] COORDINATES = {Integer.MIN_VALUE, Integer.MIN_VALUE + 1, Integer.MIN_VALUE + 2,
			-30_000_001, -30_000_000, -17, -16, -15, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 15, 16, 17,
			29_999_999, 30_000_000, Integer.MAX_VALUE - 2, Integer.MAX_VALUE - 1, Integer.MAX_VALUE};
	private static final long[] SEEDS = {0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 0x123456789abcdef0L, 0x5555555555555555L, 0xaaaaaaaaaaaaaaaaL};
	private static volatile long resultSink;

	public static void main(String[] args) throws Exception {
		byte[] original;
		try (InputStream in = BiomeZoomAllocationCheck.class.getClassLoader().getResourceAsStream(TARGET.replace('.', '/') + ".class")) {
			check(in != null, "Minecraft biome zoomer missing");
			ClassReader reader = new ClassReader(in);
			ClassWriter writer = new ClassWriter(0); reader.accept(writer, 0); original = writer.toByteArray();
		}
		MixinBootstrap.init();
		MixinEnvironment env = MixinEnvironment.getDefaultEnvironment(); env.setSide(MixinEnvironment.Side.CLIENT);
		Mixins.addConfiguration("biome-zoom-check.json");
		IMixinTransformer transformer = ((FrameTimeInstrumentationCheck.Service) MixinService.getService()).transformer();
		byte[] transformed = transformer.transformClass(env, TARGET, original);
		check(arrayAllocations(original) == 1 && arrayAllocations(transformed) == 0, "double array allocation was not removed by real Mixin");
		BiomeZoomer before = load(original), after = load(transformed);
		Probe a = new Probe(), b = new Probe();
		int cases = 0;
		for (long seed : SEEDS) {
			for (int x : COORDINATES) { for (int y : COORDINATES) { for (int z : COORDINATES) {
				compare(before, after, a, b, seed, x, y, z); cases++;
			} } }
		}
		Random random = new Random(0x53f08da27L);
		for (int i = 0; i < 200_000; i++) {
			compare(before, after, a, b, random.nextLong(), random.nextInt(), random.nextInt(), random.nextInt()); cases++;
		}
		// A mutable source is consulted exactly once for every query; no biome result is cached.
		for (int i = 0; i < 100; i++) { compare(before, after, a, b, 42L, 1, 2, 3); }
		RuntimeException error = new IllegalStateException("source exception");
		BiomeManager.NoiseBiomeSource throwing = (x, y, z) -> { throw error; };
		for (BiomeZoomer zoomer : new BiomeZoomer[]{before, after}) {
			try { zoomer.getBiome(42L, -1, 70, 1, throwing); throw new AssertionError("source exception swallowed"); }
			catch (RuntimeException e) { check(e == error, "source exception replaced"); }
			try { zoomer.getBiome(42L, -1, 70, 1, null); throw new AssertionError("null source accepted"); }
			catch (NullPointerException expected) { }
		}
		for (boolean nan : new boolean[]{false, true}) {
			BiomeZoomer oldTie = load(withDistances(original, nan)), newTie = load(withDistances(transformed, nan));
			compare(oldTie, newTie, a, b, 42L, 2, 2, 2);
			check(b.x == 0 && b.y == 0 && b.z == 0, "first-corner tie/NaN behavior changed");
		}
		checkConcurrent(before, after);
		// Run identical workloads through separate class loaders so the VM measures actual before/after code.
		runQueries(before, a, 100_000); runQueries(after, b, 100_000);
		Object bean = ManagementFactory.getThreadMXBean();
		Class<?> extended = Class.forName("com.sun.management.ThreadMXBean");
		check((Boolean) extended.getMethod("isThreadAllocatedMemorySupported").invoke(bean), "allocation counter unavailable");
		extended.getMethod("setThreadAllocatedMemoryEnabled", boolean.class).invoke(bean, true);
		Method allocated = extended.getMethod("getThreadAllocatedBytes", long.class);
		long beforeBytes = allocatedBy(bean, allocated, before, a), afterBytes = allocatedBy(bean, allocated, after, b);
		check(a.checksum == b.checksum, "allocation workload returned different biome coordinates");
		check(afterBytes < 200_000, "optimized query still allocates >= 1 byte per call");
		String mode = args.length == 0 ? "default" : args[0];
		if (mode.equals("no-ea")) { check(beforeBytes >= 12_000_000 && afterBytes < beforeBytes / 50, "before/after allocation improvement missing"); }
		String report = "Actual Mixin " + env.getVersion() + ": " + cases + " seed/coordinate comparisons identical to original Minecraft bytecode.\n"
				+ "Negative coordinates, quart boundaries, integer overflow, extreme seeds, source call count/errors, ties/NaN and four concurrent threads verified.\n"
				+ "Per-query array sites: 1 -> 0. Allocated bytes per 200000 queries (" + mode + "): " + beforeBytes + " -> " + afterBytes + ".\n";
		Files.createDirectories(Paths.get("build/reports"));
		Files.write(Paths.get("build/reports/biome-zoom-" + mode + ".txt"), report.getBytes(StandardCharsets.UTF_8));
		System.out.print(report);
	}

	private static void compare(BiomeZoomer before, BiomeZoomer after, Probe a, Probe b, long seed, int x, int y, int z) {
		a.calls = b.calls = 0;
		Biome oldResult = before.getBiome(seed, x, y, z, a), newResult = after.getBiome(seed, x, y, z, b);
		check(oldResult == newResult && a.calls == 1 && b.calls == 1 && a.x == b.x && a.y == b.y && a.z == b.z,
				"biome lookup changed at seed=" + seed + " position=" + x + "," + y + "," + z);
	}

	private static void checkConcurrent(BiomeZoomer before, BiomeZoomer after) throws Exception {
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread[] workers = new Thread[4];
		for (int index = 0; index < workers.length; index++) {
			final int workerIndex = index;
			workers[index] = new Thread(() -> {
				try {
					Random r = new Random(9357 + workerIndex); Probe a = new Probe(), b = new Probe();
					for (int i = 0; i < 25_000; i++) { compare(before, after, a, b, r.nextLong(), r.nextInt(), r.nextInt(), r.nextInt()); }
				} catch (Throwable t) { failure.compareAndSet(null, t); }
			}, "biome-zoom-check-" + index);
			workers[index].start();
		}
		for (Thread worker : workers) { worker.join(); }
		check(failure.get() == null, "concurrent query failed: " + failure.get());
	}

	private static long allocatedBy(Object bean, Method allocated, BiomeZoomer zoomer, Probe source) throws Exception {
		long id = Thread.currentThread().getId();
		long start = (Long) allocated.invoke(bean, id);
		runQueries(zoomer, source, 200_000);
		return (Long) allocated.invoke(bean, id) - start;
	}

	private static void runQueries(BiomeZoomer zoomer, Probe source, int count) {
		source.checksum = 0;
		for (int i = 0; i < count; i++) { zoomer.getBiome(0x321aed4546L + i, i * 163, i % 256 - 64, i * -313, source); }
		resultSink = source.checksum;
	}

	private static int arrayAllocations(byte[] bytes) {
		ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
		int count = 0;
		for (MethodNode m : node.methods) {
			if (!m.name.equals("getBiome")) { continue; }
			for (AbstractInsnNode insn : m.instructions) { if (insn.getOpcode() == NEWARRAY || insn.getOpcode() == ANEWARRAY || insn.getOpcode() == MULTIANEWARRAY) { count++; } }
		}
		return count;
	}

	private static byte[] withDistances(byte[] bytes, boolean nan) {
		ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
		for (MethodNode m : node.methods) {
			if (!m.name.equals("getFiddledDistance")) { continue; }
			m.instructions.clear(); m.tryCatchBlocks.clear(); m.localVariables = null;
			if (nan) {
				// The first X plane is NaN, while the later X plane has finite distances.
				LabelNode finite = new LabelNode();
				m.instructions.add(new VarInsnNode(DLOAD, 5)); m.instructions.add(new InsnNode(DCONST_0));
				m.instructions.add(new InsnNode(DCMPG)); m.instructions.add(new JumpInsnNode(IFLT, finite));
				m.instructions.add(new LdcInsnNode(Double.NaN)); m.instructions.add(new InsnNode(DRETURN));
				m.instructions.add(finite);
			}
			m.instructions.add(new InsnNode(DCONST_0)); m.instructions.add(new InsnNode(DRETURN));
		}
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
	}

	private static BiomeZoomer load(byte[] bytes) throws Exception { return (BiomeZoomer) new Loader().define(bytes).getField("INSTANCE").get(null); }
	private static class Loader extends ClassLoader {
		Loader() { super(BiomeZoomAllocationCheck.class.getClassLoader()); }
		Class<?> define(byte[] bytes) { return defineClass(TARGET, bytes, 0, bytes.length); }
	}
	private static class Probe implements BiomeManager.NoiseBiomeSource {
		int x, y, z, calls;
		long checksum;
		@Override public Biome getNoiseBiome(int x, int y, int z) {
			this.x = x; this.y = y; this.z = z; calls++;
			checksum = checksum * 31 + x; checksum = checksum * 31 + y; checksum = checksum * 31 + z;
			return null;
		}
	}
	private static void check(boolean okay, String message) { if (!okay) { throw new AssertionError(message); } }
}
