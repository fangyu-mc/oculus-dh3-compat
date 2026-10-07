package net.coderbot.iris.compat.sodium.impl.block_context;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Executes the compiled production handlers against lightweight delegates, without starting Minecraft. */
public final class ChunkBuildContextCheck {
	private static final String MIXIN = "net/coderbot/iris/compat/sodium/mixin/block_id/MixinChunkRenderRebuildTask";

	public static void main(String[] args) throws Exception {
		Class<?> hooks = loadHandlers();
		Object instance = hooks.getConstructor().newInstance();
		Method block = hooks.getDeclaredMethod("iris$wrapGetBlockLayer", State.class, Pos.class, Cache.class, Buffers.class);
		Method fluid = hooks.getDeclaredMethod("iris$wrapGetFluidLayer", FluidRenderer.class, World.class, Fluid.class,
				Pos.class, Models.class, Cache.class, Buffers.class);
		Method reset = hooks.getDeclaredMethod("iris$resetContext", State.class, Cache.class, Buffers.class);
		block.setAccessible(true);
		fluid.setAccessible(true);
		reset.setAccessible(true);
		TrackingBuffers[] workers = {new TrackingBuffers(), new TrackingBuffers()};
		Cache cache = new Cache();
		World world = new World();
		int cases = 0;
		int[][] origins = {{0, 0, 0}, {-16, 16, -16}, {30000000, 240, -30000000}, {-30000000, -64, 29999984}};
		for (int[] origin : origins) {
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						for (int mode = 0; mode < 4; mode++) {
							TrackingBuffers buffer = workers[cases++ & 1];
							buffer.expectedX = x;
							buffer.expectedY = y;
							buffer.expectedZ = z;
							Pos pos = new Pos(origin[0] + x, origin[1] + y, origin[2] + z);
							State solid = new State(buffer, (cases & 2) == 0);
							Fluid water = new Fluid(new State(buffer, false));
							int layers = mode == 0 ? 1 : mode == 2 ? 2 : 0;
							for (int layer = 0; layer < layers; layer++) {
								check((Long) block.invoke(instance, solid, pos, cache, buffer) == State.SEED, "changed block seed");
								check(solid.lastPos == pos, "changed world position passed to seed");
								buffer.assertContext(solid, (short) -1);
							}
							check(solid.seedCalls == layers, "seed invocation count changed");
							if (mode == 1 || mode == 2) {
								FluidRenderer renderer = new FluidRenderer(buffer, world, pos, (cases & 2) == 0);
								check((Boolean) fluid.invoke(instance, renderer, world, water, pos, new Models(), cache, buffer)
										== renderer.result, "changed fluid render result");
								check(renderer.calls == 1 && water.legacyCalls == 1, "fluid invocation count changed");
							}
							check((Boolean) reset.invoke(instance, solid, cache, buffer) == solid.blockEntity,
									"changed block-entity predicate");
							check(solid.entityCalls == 1, "block-entity predicate skipped or duplicated");
							buffer.assertReset();
						}
					}
				}
			}
		}
		// The extension interface is optional. Preserve the original plain-buffer calls as well.
		Buffers plain = new Buffers();
		Pos pos = new Pos(-1, 0, -17);
		State state = new State(null, true);
		Fluid liquid = new Fluid(state);
		FluidRenderer renderer = new FluidRenderer(null, world, pos, false);
		check((Long) block.invoke(instance, state, pos, cache, plain) == State.SEED, "plain-buffer seed");
		check(!(Boolean) fluid.invoke(instance, renderer, world, liquid, pos, new Models(), cache, plain), "plain fluid result");
		check(liquid.legacyCalls == 0, "new work was added to plain buffers");
		check((Boolean) reset.invoke(instance, state, cache, plain), "plain block entity result");
		check(state.seedCalls == 1 && state.entityCalls == 1 && renderer.calls == 1, "plain-buffer delegation");
		System.out.println("Chunk build context: " + cases + " block/fluid/waterlogged/multi-layer/non-model cases passed; "
				+ "all local coordinates, negative/far sections, separate buffers, exact delegation and callback-free bytecode checked.");
	}

	private static Class<?> loadHandlers() throws Exception {
		ClassNode node = new ClassNode();
		try (InputStream in = ChunkBuildContextCheck.class.getClassLoader().getResourceAsStream(MIXIN + ".class")) {
			check(in != null, "compiled production mixin not found");
			new ClassReader(in).accept(node, ClassReader.SKIP_DEBUG);
		}
		check(node.methods.size() == 4, "unexpected hot-path handler added");
		for (MethodNode method : node.methods) {
			check(!method.desc.contains("CallbackInfo"), "per-block callback parameter returned");
			for (AbstractInsnNode instruction : method.instructions) {
				check(instruction.getOpcode() != Opcodes.NEW && instruction.getOpcode() != Opcodes.NEWARRAY
						&& instruction.getOpcode() != Opcodes.ANEWARRAY, "new allocation in hot-path handler");
			}
			method.visibleAnnotations = null;
			method.invisibleAnnotations = null;
		}
		node.visibleAnnotations = null;
		node.invisibleAnnotations = null;
		Map<String, String> names = new HashMap<>();
		String generated = ChunkBuildContextCheck.class.getPackage().getName() + ".GeneratedChunkContextHooks";
		names.put(MIXIN, generated.replace('.', '/'));
		map(names, "net/minecraft/world/level/block/state/BlockState", State.class);
		map(names, "net/minecraft/core/BlockPos", Pos.class);
		map(names, "net/minecraft/world/level/BlockAndTintGetter", World.class);
		map(names, "net/minecraft/world/level/material/FluidState", Fluid.class);
		map(names, "me/jellysquid/mods/sodium/client/render/pipeline/FluidRenderer", FluidRenderer.class);
		map(names, "me/jellysquid/mods/sodium/client/render/pipeline/context/ChunkRenderCacheLocal", Cache.class);
		map(names, "me/jellysquid/mods/sodium/client/render/chunk/compile/ChunkBuildBuffers", Buffers.class);
		map(names, "me/jellysquid/mods/sodium/client/render/chunk/compile/buffers/ChunkModelBuffers", Models.class);
		map(names, "net/coderbot/iris/compat/sodium/impl/block_context/ChunkBuildBuffersExt", Context.class);
		ClassWriter writer = new ClassWriter(0);
		node.accept(new ClassRemapper(writer, new SimpleRemapper(names)));
		return new ClassLoader(ChunkBuildContextCheck.class.getClassLoader()) {
			Class<?> define() {
				byte[] bytes = writer.toByteArray();
				return defineClass(generated, bytes, 0, bytes.length);
			}
		}.define();
	}

	private static void map(Map<String, String> names, String original, Class<?> fixture) {
		names.put(original, fixture.getName().replace('.', '/'));
	}

	public static class Pos {
		private final int x, y, z;
		Pos(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
		public int getX() { return x; }
		public int getY() { return y; }
		public int getZ() { return z; }
	}

	public static class Buffers { }
	public static class Cache { }
	public static class Models { }
	public interface WorldView { }
	public static class World implements WorldView { }
	public interface Context {
		void iris$setLocalPos(int x, int y, int z);
		void iris$setMaterialId(State state, short renderType);
		void iris$resetBlockContext();
	}

	public static class TrackingBuffers extends Buffers implements Context {
		int x, y, z, expectedX, expectedY, expectedZ;
		State material;
		short type = -1;
		public void iris$setLocalPos(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
		public void iris$setMaterialId(State state, short renderType) { material = state; type = renderType; }
		public void iris$resetBlockContext() { x = y = z = 0; material = null; type = -1; }
		void assertContext(State expected, short expectedType) {
			check(x == expectedX && y == expectedY && z == expectedZ, "wrong position at render delegate");
			check(material == expected && type == expectedType, "wrong material/type at render delegate");
		}
		void assertReset() {
			check(x == 0 && y == 0 && z == 0 && material == null && type == -1, "stale block context");
		}
	}

	public static class State {
		static final long SEED = 0x1234567887654321L;
		final TrackingBuffers observer;
		final boolean blockEntity;
		int seedCalls, entityCalls;
		Pos lastPos;
		State(TrackingBuffers observer, boolean blockEntity) { this.observer = observer; this.blockEntity = blockEntity; }
		public long getSeed(Pos pos) {
			seedCalls++; lastPos = pos;
			if (observer != null) { observer.assertContext(this, (short) -1); }
			return SEED;
		}
		public boolean hasTileEntity() {
			entityCalls++;
			if (observer != null) { observer.assertReset(); }
			return blockEntity;
		}
	}

	public static class Fluid {
		final State state;
		int legacyCalls;
		Fluid(State state) { this.state = state; }
		public State createLegacyBlock() { legacyCalls++; return state; }
	}

	public static class FluidRenderer {
		final TrackingBuffers observer;
		final World expectedWorld;
		final Pos expectedPos;
		final boolean result;
		int calls;
		FluidRenderer(TrackingBuffers observer, World world, Pos pos, boolean result) {
			this.observer = observer; expectedWorld = world; expectedPos = pos; this.result = result;
		}
		public boolean render(World world, Fluid fluid, Pos pos, Models models) {
			calls++;
			check(world == expectedWorld && pos == expectedPos && models != null, "changed fluid render arguments");
			if (observer != null) { observer.assertContext(fluid.state, (short) 1); }
			return result;
		}
	}

	private static void check(boolean value, String message) {
		if (!value) { throw new AssertionError(message); }
	}
}
