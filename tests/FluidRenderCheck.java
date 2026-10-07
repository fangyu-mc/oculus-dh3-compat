package net.coderbot.iris.compat.sodium.impl;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Executes compiled hooks and real Rubidium/Embeddium corner/blender bytecode on small world fixtures. */
public final class FluidRenderCheck {

	private static final String ROOT = "net/coderbot/iris/compat/sodium/";
	private static final String MIXIN = ROOT + "mixin/fluid_render/MixinFluidRenderer";
	private static final String CACHE = ROOT + "impl/FluidQuadColorCache";
	private static final String SODIUM = "me/jellysquid/mods/sodium/client/";
	private static final String RENDERER = SODIUM + "render/pipeline/FluidRenderer";
	private static final String GENERATED = ROOT + "impl/FluidCheckGenerated";
	private static final Loader LOADER = new Loader();
	private static final Map<String, String> NAMES = names();
	private static final Flow CUSTOM = new Flow(3);
	private static final Flow[] TYPES = {Fluids.WATER, Fluids.FLOWING_WATER, Fluids.LAVA, Fluids.FLOWING_LAVA, CUSTOM};
	private static int heightCases, colorCases;
	private static long oldHeightQueries, newHeightQueries, oldWaterColors, newWaterColors;

	public interface Hooks {
		float iris$heightAfterAboveCheck(FluidState state, World world, Pos pos);
		int[] iris$reuseQuadColorSamples(Blender blender, Color provider, World world, State state, Pos pos, Quad quad);
	}
	public interface Corner { float getCornerHeight(World world, int x, int y, int z, Flow fluid); }
	public interface Blender { int[] getColors(Color color, World world, State state, Pos pos, Quad quad); }
	public interface Color { int getColor(State state, World world, Pos pos, int tint); }
	public interface World { FluidState getFluidState(Pos pos); State getBlockState(Pos pos); }
	public interface Quad { int getFlags(); int getColorIndex(); float getX(int vertex); float getZ(int vertex); }

	public static void main(String[] args) throws Exception {
		ClassNode cache = resource(CACHE);
		check(cache.version == Opcodes.V1_8, "cache is not Java 8 bytecode");
		LOADER.define(remap(cache, NAMES));
		ClassNode hooks = resource(MIXIN);
		check(hooks.fields.size() == 1 && (hooks.fields.get(0).access & Opcodes.ACC_STATIC) == 0,
				"renderers must not share a cache");
		hooks.interfaces.add(internal(Hooks.class));
		for (MethodNode method : hooks.methods) {
			if (!method.name.equals("<init>")) {
				method.access = Opcodes.ACC_PUBLIC;
				for (AbstractInsnNode instruction : method.instructions) {
					check(instruction.getOpcode() != Opcodes.NEW && instruction.getOpcode() != Opcodes.NEWARRAY
							&& instruction.getOpcode() != Opcodes.ANEWARRAY, "allocation added to fluid hook");
				}
			}
		}
		Class<?> hookClass = LOADER.define(remap(hooks, NAMES));
		verifyRenderer(null, "Rubidium", hookClass);
		for (int i = 0; i < args.length; i++) {
			try (ZipFile zip = new ZipFile(args[i])) { verifyRenderer(zip, "Embeddium" + i, hookClass); }
		}
		checkLifecycle(hookClass);
		String report = "Actual renderer bytecode: " + heightCases + " corner height comparisons and " + colorCases
				+ " quad color comparisons passed (bit-identical).\n"
				+ "Native water/lava above-position lookups: " + oldHeightQueries + " -> " + newHeightQueries + ".\n"
				+ "Water color provider calls: " + oldWaterColors + " -> " + newWaterColors + ".\n"
				+ "Custom fluids, waterlogged solids, empty/stacked fluids, negative coordinates, native flat/smooth blending, "
				+ "cache-key fallbacks, color changes, reentry, exception cleanup and four parallel renderers checked.\n";
		Files.createDirectories(Paths.get("build/reports"));
		Files.write(Paths.get("build/reports/fluid-render.txt"), report.getBytes(StandardCharsets.UTF_8));
		System.out.print(report);
	}

	private static void verifyRenderer(ZipFile zip, String name, Class<?> hookClass) throws Exception {
		ClassNode renderer = read(zip, RENDERER);
		MethodNode corner = method(renderer, "getCornerHeight");
		MethodNode colors = method(renderer, "calculateQuadColors");
		int heightCalls = 0, colorCalls = 0;
		for (AbstractInsnNode instruction : corner.instructions) {
			if (instruction instanceof MethodInsnNode && isHeight((MethodInsnNode) instruction)) { heightCalls++; }
		}
		for (AbstractInsnNode instruction : colors.instructions) {
			if (instruction instanceof MethodInsnNode) {
				MethodInsnNode call = (MethodInsnNode) instruction;
				if (call.owner.equals(SODIUM + "model/quad/blender/BiomeColorBlender") && call.name.equals("getColors")) { colorCalls++; }
			}
		}
		check(heightCalls == 1 && colorCalls == 1, name + " redirect target counts changed");
		Corner original = corner(renderer, name + "Old", false);
		Corner optimized = corner(renderer, name + "New", true);
		Grid world = new Grid();
		Random random = new Random(813077);
		for (int scene = 0; scene < 3000; scene++) {
			world.x = scene % 2 == 0 ? -30000000 + scene : 29990000 - scene;
			world.y = (scene % 25) - 5;
			world.z = -30000000 + scene * 17;
			for (int i = 0; i < world.cells.length; i++) {
				Flow fluid = random.nextInt(5) == 0 ? new Flow(0) : TYPES[random.nextInt(TYPES.length)];
				world.cells[i] = new State(fluid, 1 + random.nextInt(8), random.nextBoolean());
			}
			for (Flow type : TYPES) {
				for (int c = 0; c < 4; c++) {
					int x = world.x + (c & 1), z = world.z + (c >> 1);
					world.heightCalls = 0;
					float before = original.getCornerHeight(world, x, world.y, z, type);
					int calls = world.heightCalls;
					world.heightCalls = 0;
					float after = optimized.getCornerHeight(world, x, world.y, z, type);
					check(Float.floatToIntBits(before) == Float.floatToIntBits(after), name + " fluid surface changed");
					if (type == CUSTOM) { check(calls == world.heightCalls, "custom height delegate changed"); }
					else {
						oldHeightQueries += calls; newHeightQueries += world.heightCalls;
						check(world.heightCalls == 0, "native duplicate height query remains");
					}
					heightCases++;
				}
			}
		}
		for (String kind : new String[]{"SmoothBiomeColorBlender", "FlatBiomeColorBlender"}) {
			ClassNode blenderNode = read(zip, SODIUM + "model/quad/blender/" + kind);
			Map<String, String> map = new HashMap<>(NAMES);
			map.put(blenderNode.name, GENERATED + name + kind);
			Blender blender = (Blender) LOADER.define(remap(blenderNode, map)).getConstructor().newInstance();
			Hooks hooks = (Hooks) hookClass.getConstructor().newInstance();
			CountingColor provider = new CountingColor();
			TestQuad quad = new TestQuad();
			for (int scene = 0; scene < 5000; scene++) {
				quad.flags = (scene & 1) == 0 ? 4 : 0;
				quad.tint = scene % 3;
				for (int v = 0; v < 4; v++) {
					quad.x[v] = scene % 7 == 0 ? random.nextFloat() * 5 - 2 : random.nextFloat();
					quad.z[v] = scene % 7 == 0 ? random.nextFloat() * 5 - 2 : random.nextFloat();
				}
				Pos pos = new Pos(-30000000 + scene, scene % 257 - 1, 30000000 - scene);
				State state = new State(TYPES[scene % TYPES.length], scene % 8 + 1, scene % 2 == 0);
				provider.seed = scene % 11 == 0 ? 0 : scene % 13 == 0 ? -1 : random.nextInt();
				provider.calls = 0;
				int[] expected = blender.getColors(provider, world, state, pos, quad).clone();
				int calls = provider.calls;
				provider.calls = 0;
				int[] actual = hooks.iris$reuseQuadColorSamples(blender, provider, world, state, pos, quad);
				check(Arrays.equals(expected, actual), name + " " + kind + " vertex color changed");
				if (state.fluid.type == Fluids.WATER || state.fluid.type == Fluids.FLOWING_WATER) {
					check(provider.calls <= calls, "more water color lookups");
					oldWaterColors += calls; newWaterColors += provider.calls;
				} else { check(calls == provider.calls, "custom/lava color calls changed"); }
				colorCases++;
			}
		}
		check(oldHeightQueries > 1000 && newHeightQueries == 0, "height workload never exercised allocation hotspot");
		check(newWaterColors * 2 < oldWaterColors, "color workload did not reduce duplicate lookups");
	}

	private static void checkLifecycle(Class<?> hookClass) throws Exception {
		Hooks hooks = (Hooks) hookClass.getConstructor().newInstance();
		State water = new State(Fluids.WATER, 8, false);
		Grid world = new Grid();
		Pos pos = new Pos(-17, 255, 16);
		TestQuad quad = new TestQuad();
		Blender repeat = (source, level, state, p, q) -> {
			int[] result = new int[4];
			for (int i = 0; i < 4; i++) { result[i] = source.getColor(state, level, p, q.getColorIndex()); }
			return result;
		};
		CountingColor color = new CountingColor();
		// A failed provider must not leave references/valid colors behind for the next quad.
		try {
			hooks.iris$reuseQuadColorSamples(repeat, (s, w, p, t) -> { throw new IllegalArgumentException("fixture"); }, world, water, pos, quad);
			throw new AssertionError("provider exception swallowed");
		} catch (IllegalArgumentException expected) { }
		hooks.iris$reuseQuadColorSamples(repeat, color, world, water, pos, quad);
		check(color.calls == 1, "failed blend poisoned cache");
		final int[] nestedCalls = {0};
		Color nested = (s, w, p, t) -> {
			nestedCalls[0]++;
			CountingColor inner = new CountingColor();
			hooks.iris$reuseQuadColorSamples(repeat, inner, w, s, p, quad);
			check(inner.calls == 4, "reentrant quad reused outer state");
			return 0xA1234567;
		};
		check(hooks.iris$reuseQuadColorSamples(repeat, nested, world, water, pos, quad)[3] == 0xA1234567
				&& nestedCalls[0] == 1, "outer colors changed after reentry");
		Blender unusual = (source, level, state, p, q) -> {
			int[] result = new int[4];
			for (int i = 0; i < 4; i++) {
				result[i] = source.getColor(i == 0 ? new State(CUSTOM, 8, false) : state,
						i == 1 ? new Grid() : level, i == 2 ? new Pos(p.x, p.y + 1, p.z) : p, i == 3 ? 9 : q.getColorIndex());
			}
			return result;
		};
		color.calls = 0;
		hooks.iris$reuseQuadColorSamples(unusual, color, world, water, pos, quad);
		check(color.calls == 4, "mismatched cache keys did not delegate");
		ExecutorService pool = Executors.newFixedThreadPool(4);
		try {
			List<Future<Void>> work = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				work.add(pool.submit((Callable<Void>) () -> {
					Hooks local = (Hooks) hookClass.getConstructor().newInstance();
					CountingColor source = new CountingColor();
					for (int j = 0; j < 10000; j++) {
						source.seed = j; source.calls = 0;
						int expected = source.getColor(water, world, pos, 0); source.calls = 0;
						int[] values = local.iris$reuseQuadColorSamples(repeat, source, world, water, pos, quad);
						check(values[0] == expected && values[3] == expected && source.calls == 1, "parallel/stale quad color");
					}
					return null;
				}));
			}
			for (Future<Void> result : work) { result.get(); }
		} finally { pool.shutdownNow(); }
	}

	private static Corner corner(ClassNode renderer, String name, boolean optimized) throws Exception {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V1_8; node.access = Opcodes.ACC_PUBLIC; node.name = renderer.name;
		node.superName = "java/lang/Object"; node.interfaces.add(internal(Corner.class));
		node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "scratchPos", Type.getDescriptor(MutablePos.class), null, null));
		MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
		init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		init.instructions.add(new TypeInsnNode(Opcodes.NEW, internal(MutablePos.class)));
		init.instructions.add(new InsnNode(Opcodes.DUP));
		init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, internal(MutablePos.class), "<init>", "()V", false));
		init.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.PUTFIELD, node.name, "scratchPos", Type.getDescriptor(MutablePos.class)));
		init.instructions.add(new InsnNode(Opcodes.RETURN));
		node.methods.add(init);
		MethodNode body = copy(method(renderer, "getCornerHeight")); body.access = Opcodes.ACC_PUBLIC;
		node.methods.add(body);
		if (optimized) {
			MethodNode hook = copy(method(resource(MIXIN), "iris$heightAfterAboveCheck"));
			hook.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
			for (AbstractInsnNode instruction : hook.instructions) {
				if (instruction instanceof VarInsnNode) {
					VarInsnNode variable = (VarInsnNode) instruction;
					check(variable.var > 0, "height hook depends on renderer state"); variable.var--;
				}
			}
			node.methods.add(hook);
			for (AbstractInsnNode instruction : body.instructions.toArray()) {
				if (instruction instanceof MethodInsnNode && isHeight((MethodInsnNode) instruction)) {
					body.instructions.set(instruction, new MethodInsnNode(Opcodes.INVOKESTATIC, node.name, hook.name, hook.desc, false));
				}
			}
		}
		Map<String, String> map = new HashMap<>(NAMES); map.put(node.name, GENERATED + name + "Corner");
		return (Corner) LOADER.define(remap(node, map)).getConstructor().newInstance();
	}

	private static boolean isHeight(MethodInsnNode call) { return call.name.equals("getHeight") || call.name.equals("func_215679_a"); }
	private static MethodNode copy(MethodNode source) {
		MethodNode result = new MethodNode(source.access, source.name, source.desc, source.signature, null);
		source.accept(result); return result;
	}
	private static MethodNode method(ClassNode node, String name) {
		for (MethodNode method : node.methods) { if (method.name.equals(name)) { return method; } }
		throw new AssertionError("method missing: " + name);
	}
	private static ClassNode resource(String name) throws Exception { return read(null, name); }
	private static ClassNode read(ZipFile zip, String name) throws Exception {
		try (InputStream in = zip == null ? FluidRenderCheck.class.getClassLoader().getResourceAsStream(name + ".class")
				: zip.getInputStream(zip.getEntry(name + ".class"))) {
			check(in != null, "class missing: " + name);
			ClassNode node = new ClassNode();
			new ClassReader(in).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES); return node;
		}
	}
	private static byte[] remap(ClassNode node, Map<String, String> names) {
		node.visibleAnnotations = null; node.invisibleAnnotations = null;
		Map<String, String> methods = new HashMap<>();
		String[][] pairs = {{"func_181079_c", "set"}, {"func_204610_c", "getFluidState"}, {"func_206886_c", "getType"},
				{"func_207187_a", "isSame"}, {"func_180495_p", "getBlockState"}, {"func_204520_s", "getFluidState"},
				{"func_215679_a", "getHeight"}, {"func_185904_a", "getMaterial"}, {"func_76220_a", "isSolid"},
				{"func_177958_n", "getX"}, {"func_177956_o", "getY"}, {"func_177952_p", "getZ"}};
		for (String[] pair : pairs) { methods.put(pair[0], pair[1]); }
		for (FieldNode field : node.fields) { field.visibleAnnotations = null; field.invisibleAnnotations = null; }
		for (MethodNode method : node.methods) {
			method.visibleAnnotations = null; method.invisibleAnnotations = null;
			for (AbstractInsnNode instruction : method.instructions) {
				if (instruction instanceof MethodInsnNode) {
					MethodInsnNode call = (MethodInsnNode) instruction;
					if (call.owner.startsWith("net/minecraft/")) { call.name = methods.getOrDefault(call.name, call.name); }
				}
			}
		}
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		node.accept(new ClassRemapper(writer, new SimpleRemapper(names))); return writer.toByteArray();
	}
	private static Map<String, String> names() {
		Map<String, String> names = new HashMap<>();
		String[][] pairs = {{"client/color/block/BlockColor", "client/renderer/color/IBlockColor", internal(Color.class)},
				{"core/BlockPos", "util/math/BlockPos", internal(Pos.class)},
				{"core/BlockPos$MutableBlockPos", "util/math/BlockPos$Mutable", internal(MutablePos.class)},
				{"world/level/BlockAndTintGetter", "world/IBlockDisplayReader", internal(World.class)},
				{"world/level/BlockGetter", "world/IBlockReader", internal(World.class)},
				{"world/level/block/state/BlockState", "block/BlockState", internal(State.class)},
				{"world/level/material/Fluid", "fluid/Fluid", internal(Flow.class)},
				{"world/level/material/FlowingFluid", "fluid/FlowingFluid", internal(Flow.class)},
				{"world/level/material/FluidState", "fluid/FluidState", internal(FluidState.class)},
				{"world/level/material/Fluids", "fluid/Fluids", internal(Fluids.class)},
				{"world/level/material/Material", "block/material/Material", internal(Material.class)}};
		for (String[] pair : pairs) { names.put("net/minecraft/" + pair[0], pair[2]); names.put("net/minecraft/" + pair[1], pair[2]); }
		names.put(SODIUM + "model/quad/ModelQuadView", internal(Quad.class));
		names.put(SODIUM + "model/quad/blender/BiomeColorBlender", internal(Blender.class));
		names.put(CACHE, GENERATED + "ColorCache"); names.put(MIXIN, GENERATED + "Hooks");
		return names;
	}
	private static String internal(Class<?> type) { return type.getName().replace('.', '/'); }
	private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
	private static class Loader extends ClassLoader {
		Loader() { super(FluidRenderCheck.class.getClassLoader()); }
		Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
	}

	public static class Pos {
		int x, y, z;
		public Pos() { }
		public Pos(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
		public int getX() { return x; } public int getY() { return y; } public int getZ() { return z; }
	}
	public static class MutablePos extends Pos {
		public MutablePos() { }
		public MutablePos set(int x, int y, int z) { this.x = x; this.y = y; this.z = z; return this; }
	}
	public static class Flow {
		final int family; Flow(int family) { this.family = family; }
		public boolean isSame(Flow other) { return family == other.family; }
	}
	public static class Fluids {
		public static final Flow WATER = new Flow(1), FLOWING_WATER = new Flow(1), LAVA = new Flow(2), FLOWING_LAVA = new Flow(2);
	}
	public static class FluidState {
		final Flow type; final int amount;
		FluidState(Flow type, int amount) { this.type = type; this.amount = amount; }
		public Flow getType() { return type; }
		public float getOwnHeight() { return amount / 9.0F; }
		public float getHeight(World world, Pos pos) {
			((Grid) world).heightCalls++;
			if (type == CUSTOM) { return 0.3F + amount / 19.0F + (pos.x & 1) * 0.03F; }
			return type.isSame(world.getFluidState(new Pos(pos.x, pos.y + 1, pos.z)).type) ? 1.0F : getOwnHeight();
		}
	}
	public static class State {
		final FluidState fluid; final Material material;
		State(Flow fluid, int amount, boolean solid) { this.fluid = new FluidState(fluid, amount); material = new Material(solid); }
		public FluidState getFluidState() { return fluid; } public Material getMaterial() { return material; }
	}
	public static class Material {
		final boolean solid; Material(boolean solid) { this.solid = solid; } public boolean isSolid() { return solid; }
	}
	public static class Grid implements World {
		final State[] cells = new State[18]; int x, y, z, heightCalls;
		public FluidState getFluidState(Pos pos) { return getBlockState(pos).fluid; }
		public State getBlockState(Pos pos) { return cells[(pos.x - x + 1) + (pos.z - z + 1) * 3 + (pos.y - y) * 9]; }
	}
	private static class CountingColor implements Color {
		int calls, seed;
		public int getColor(State state, World world, Pos pos, int tint) {
			calls++;
			return seed == 0 || seed == -1 ? seed : seed ^ pos.x * 389 ^ pos.y * 257 ^ pos.z * 1021 ^ tint * 31;
		}
	}
	private static class TestQuad implements Quad {
		final float[] x = new float[4], z = new float[4]; int flags, tint;
		public int getFlags() { return flags; } public int getColorIndex() { return tint; }
		public float getX(int vertex) { return x[vertex]; } public float getZ(int vertex) { return z[vertex]; }
	}
}
