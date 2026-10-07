package net.coderbot.iris.compat.sodium.impl;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import me.jellysquid.mods.sodium.client.gl.util.BufferSlice;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkCameraContext;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderListIterator;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.ZipFile;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;

/** Executes renderer bytecode and production hooks with CPU-only world/GPU fixtures. */
public final class ChunkDrawBatchCheck {

	private static final String S = "me/jellysquid/mods/sodium/client/";
	private static final String ROOT = "net/coderbot/iris/compat/sodium/";
	private static final String GRAPHICS = S + "render/chunk/backends/multidraw/MultidrawGraphicsState";
	private static final String MANAGER = S + "render/chunk/ChunkRenderManager";
	private static final String BACKEND = S + "render/chunk/backends/multidraw/MultidrawChunkRenderBackend";
	private static final String BATCHER = S + "render/chunk/backends/multidraw/ChunkDrawCallBatcher";
	private static final String SHADOW = ROOT + "mixin/shadow_map/MixinChunkRenderManager";
	private static long comparisons, beforeCommands, afterCommands, checkedMasks;

	public interface Manager {
		int computeVisibleFaces(Chunk chunk);
		void addChunkToRenderLists(Chunk chunk);
		void iris$onlyFacesWithGeometry(ChunkRenderList<Object> list, Object state, int faces);
	}
	public interface Backend {
		void setupDrawBatches(Commands commands, ChunkRenderListIterator<Object> list, ChunkCameraContext camera);
		void buildCommandBuffer();
	}
	public interface Batch {
		void begin(); void end(); boolean isBuilding(); boolean isEmpty();
		void addIndirectDrawCall(int first, int count, int baseInstance, int instances);
		int getCount(); int getArrayLength(); ByteBuffer getBuffer();
	}

	public static void main(String[] args) throws Exception {
		verify(null, "Rubidium");
		for (String jar : args) {
			try (ZipFile zip = new ZipFile(jar)) { verify(zip, "Embeddium"); }
		}
		check(afterCommands < beforeCommands, "workload did not exercise empty directions");
		String report = "Actual Rubidium/Embeddium bytecode checks passed: " + checkedMasks
				+ " face-mask combinations, " + comparisons + " camera/shadow comparisons.\n"
				+ "Mixed-layer fixture indirect commands: " + beforeCommands + " -> " + afterCommands
				+ "; command bytes: " + beforeCommands * 16 + " -> " + afterCommands * 16 + ".\n"
				+ "Non-empty command bytes/order, uniform indices/offsets, region order (including equal distances), "
				+ "forward/reverse lists, zero-mask entries, empty batches, tickable chunks, state replacement, "
				+ "custom backend/subclass fallback, NIO and unsafe writers checked.\n"
				+ "Compiled per-list and shadow-face hooks have no allocation or callback construction. "
				+ "GPU/game execution and live frame-rate improvement are not measured here.\n";
		Files.createDirectories(Paths.get("build/reports"));
		Files.write(Paths.get("build/reports/chunk-draw-batch.txt"), report.getBytes(StandardCharsets.UTF_8));
		System.out.print(report);
	}

	private static void verify(ZipFile zip, String label) throws Exception {
		Version old = new Version(zip, false), current = new Version(zip, true);
		checkMasks(current);
		// Reuse each backend/batcher across frames to catch missing begin/end and stale masks.
		for (boolean unsafe : new boolean[]{false, true}) {
			Scene before = new Scene(old, unsafe), after = new Scene(current, unsafe);
			for (int scene = 0; scene < 32; scene++) {
				before.fill(scene); after.fill(scene);
				for (boolean cull : new boolean[]{false, true}) {
					for (boolean shadow : new boolean[]{false, true}) {
						Shadow.active = shadow;
						for (boolean reverse : new boolean[]{false, true}) {
							before.prepare(scene, cull, reverse); after.prepare(scene, cull, reverse);
							for (int i = 0; i < before.chunks.size(); i++) {
								check(before.manager.computeVisibleFaces(before.chunks.get(i))
										== after.manager.computeVisibleFaces(after.chunks.get(i)), label + " visible faces changed");
								comparisons++;
							}
							before.populate(); after.populate();
							check(get(before.manager, "visibleChunkCount").equals(get(after.manager, "visibleChunkCount")), "chunk counter changed");
							check(before.ticking.size() == after.ticking.size(), "animated chunk count changed");
							for (int i = 0; i < before.ticking.size(); i++) {
								check(before.ticking.get(i).id == after.ticking.get(i).id, "animated chunk order changed");
							}
							for (int pass = 0; pass < 3; pass++) {
								Result a = before.draw(pass, reverse), b = after.draw(pass, reverse);
								check(a.regionIds.equals(b.regionIds), "region first-encounter/sort order changed");
								check(Arrays.equals(a.uniforms, b.uniforms), "chunk offset/index changed");
								check(a.visible.equals(b.visible), "non-empty DrawArraysIndirect stream changed");
								check(b.zeros == 0, "empty indirect command remains");
								beforeCommands += a.commands; afterCommands += b.commands;
							}
						}
					}
				}
			}
		}
		Shadow.active = false;
		System.out.println(label + ": actual graphics constructor, manager, batching loop and both writers passed");
	}

	private static void checkMasks(Version version) throws Exception {
		Manager manager = version.manager();
		Region region = new Region(0, version.batch(false));
		Chunk chunk = new Chunk(0, 0, 0, 0);
		for (int mask = 0; mask < 128; mask++) {
			Mesh mesh = mesh(mask, 19);
			BaseState state = version.state(chunk, region, mesh);
			check(((NonEmptyModelParts) state).iris$getNonEmptyFaces() == mask, "cached upload mask changed");
			for (int visible = 0; visible < 128; visible++) {
				ChunkRenderList<Object> list = new ChunkRenderList<>();
				manager.iris$onlyFacesWithGeometry(list, state, visible);
				ChunkRenderListIterator<Object> it = list.iterator(false);
				check(it.hasNext() && it.getGraphicsState() == state, "zero-mask list entry lost");
				check(it.getVisibleFaces() == (visible & mask), "face mask intersection changed");
				it.advance(); check(!it.hasNext(), "list entry duplicated"); checkedMasks++;
			}
		}
		BaseState custom = (BaseState) version.subclass.getConstructor(Chunk.class, Region.class, Segment.class, Mesh.class, Format.class)
				.newInstance(chunk, region, new Segment(0), mesh(0, 1), new Format());
		for (Object state : new Object[]{new Object(), custom, null}) {
			ChunkRenderList<Object> list = new ChunkRenderList<>();
			manager.iris$onlyFacesWithGeometry(list, state, 127);
			check(list.iterator(false).getVisibleFaces() == 127, "custom backend/subclass filtering changed");
		}
	}

	private static final class Scene {
		final Version version;
		final Manager manager;
		final Backend backend;
		final List<Chunk> chunks = new ArrayList<>();
		final ObjectArrayList<Chunk> ticking = new ObjectArrayList<>();
		final ObjectArrayList<Region> pending = new ObjectArrayList<>();
		final Region[] regions = new Region[8];
		final ChunkRenderList<Object>[] lists;
		final Params params = new Params();
		ChunkCameraContext camera;

		@SuppressWarnings("unchecked")
		Scene(Version version, boolean unsafe) throws Exception {
			this.version = version; manager = version.manager(); backend = version.backend();
			lists = new ChunkRenderList[]{new ChunkRenderList<>(), new ChunkRenderList<>(), new ChunkRenderList<>()};
			for (int i = 0; i < regions.length; i++) { regions[i] = new Region(i, version.batch(unsafe)); }
			set(manager, "chunkRenderLists", lists); set(manager, "tickableChunks", ticking);
			set(backend, "pendingBatches", pending); set(backend, "uniformBufferBuilder", params);
			set(backend, "uniformBuffer", new Buffer()); set(backend, "commandClientBufferBuilder", new CommandVector());
			if (version.embeddium) {
				set(backend, "REGION_REVERSER", Comparator.<Region>comparingDouble(r -> r.camDistance).reversed());
			}
		}
		void fill(int seed) throws Exception {
			chunks.clear();
			Random random = new Random(seed + 76019);
			for (int i = 0; i < 48; i++) {
				// Large/negative coordinates, water-only and foliage layers, all-empty batches,
				// fully populated geometry, and a new upload replacing each previous state.
				int origin = seed % 3 == 0 ? -30000000 : seed % 3 == 1 ? 30000000 : 0;
				Chunk chunk = new Chunk(i, origin + (i % 8) * 16, (i % 16) * 16, origin - i * 16);
				for (int pass = 0; pass < 3; pass++) {
					int mask = pass == 0 ? 127 : pass == 1 ? 64 : random.nextInt(128);
					if (seed == 0) { mask = 0; } // a union mask can outlive empty slices on special/custom meshes
					chunk.faces |= mask;
					if ((i + seed + pass) % 11 != 0) {
						chunk.states[pass] = version.state(chunk, regions[i % regions.length], mesh(mask, i + seed));
					}
				}
				if (seed == 0) { chunk.faces = 127; }
				chunks.add(chunk);
			}
		}
		void prepare(int scene, boolean cull, boolean reverse) throws Exception {
			for (ChunkRenderList<Object> list : lists) { list.reset(); }
			ticking.clear(); set(manager, "visibleChunkCount", 0);
			set(manager, "useBlockFaceCulling", cull);
			Chunk first = chunks.get(0);
			float x = first.x + (scene - 16) * 16.25f, z = first.z + (scene - 16) * 15.75f;
			set(manager, "cameraX", x); set(manager, "cameraY", (float) (scene * 13)); set(manager, "cameraZ", z);
			camera = new ChunkCameraContext(x - .75, scene * 13 + .125, z + .5);
			if (version.embeddium) { set(manager, "translucencySorting", reverse); }
		}
		void populate() { for (Chunk chunk : chunks) { manager.addChunkToRenderLists(chunk); } }
		Result draw(int pass, boolean reverse) throws Exception {
			pending.clear();
			if (version.embeddium) { set(backend, "reverseRegions", reverse && pass == 2); }
			backend.setupDrawBatches((buffer, data) -> {}, lists[pass].iterator(reverse), camera);
			backend.buildCommandBuffer();
			Result result = new Result(); result.uniforms = params.values();
			for (Region region : pending) {
				result.regionIds.add(region.id);
				Batch batch = region.batch;
				check(!batch.isBuilding(), "region left building");
				check(batch.getArrayLength() == 16 * batch.getCount(), "indirect buffer stride/count incorrect");
				check(batch.getBuffer().remaining() == batch.getArrayLength(), "indirect buffer position/limit incorrect");
				for (int i = 0; i < batch.getCount(); i++) {
					ByteBuffer bytes = batch.getBuffer(); int offset = 16 * i;
					result.commands++;
					if (bytes.getInt(offset) == 0) { result.zeros++; continue; }
					result.visible.add(region.id + ":" + bytes.getInt(offset) + ":" + bytes.getInt(offset + 4)
							+ ":" + bytes.getInt(offset + 8) + ":" + bytes.getInt(offset + 12));
				}
			}
			return result;
		}
	}

	private static final class Version {
		final Loader loader = new Loader();
		final Map<String, String> names = names();
		final boolean embeddium;
		final Class<?> graphics, manager, backend, nio, unsafe, subclass;

		Version(ZipFile zip, boolean optimized) throws Exception {
			ClassNode gm = read(null, ROOT + "mixin/draw_batch/MixinMultidrawGraphicsState");
			ClassNode mm = read(null, ROOT + "mixin/draw_batch/MixinChunkRenderManager");
			ClassNode sm = read(null, SHADOW);
			ClassNode state = read(zip, GRAPHICS);
			check(state.version == Opcodes.V1_8, "renderer bytecode version changed");
			for (MethodNode m : state.methods) {
				for (AbstractInsnNode n : m.instructions) {
					check(n.getOpcode() != Opcodes.LASTORE || m.name.equals("<init>"), "model parts mutate after upload");
				}
			}
			names.put(GRAPHICS, ROOT + "impl/check/Graphics");
			names.put(gm.name, names.get(GRAPHICS)); names.put(mm.name, ROOT + "impl/check/Manager");
			names.put(SHADOW, ROOT + "impl/check/Manager"); names.put(MANAGER, ROOT + "impl/check/Manager");
			names.put(BACKEND, ROOT + "impl/check/Backend");
			if (optimized) {
				state.interfaces.add(Type.getInternalName(NonEmptyModelParts.class));
				state.fields.add(gm.fields.stream().filter(f -> f.name.equals("iris$nonEmptyFaces")).findFirst().get());
				state.methods.add(method(gm, "iris$cacheNonEmptyFaces")); state.methods.add(method(gm, "iris$getNonEmptyFaces"));
				MethodNode ctor = method(state, "<init>");
				for (AbstractInsnNode n : ctor.instructions.toArray()) {
					if (n.getOpcode() == Opcodes.RETURN) {
						InsnList call = new InsnList(); call.add(new VarInsnNode(Opcodes.ALOAD, 0)); call.add(new InsnNode(Opcodes.ACONST_NULL));
						call.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, gm.name, "iris$cacheNonEmptyFaces",
								"(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", false));
						ctor.instructions.insertBefore(n, call);
					}
				}
			}
			graphics = loader.define(remap(state, names));
			ClassNode sub = shell(ROOT + "impl/check/CustomGraphics", names.get(GRAPHICS));
			MethodVisitor c = sub.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
					"(" + desc(Chunk.class) + desc(Region.class) + desc(Segment.class) + desc(Mesh.class) + desc(Format.class) + ")V", null, null);
			for (int i = 0; i <= 5; i++) { c.visitVarInsn(Opcodes.ALOAD, i); }
			c.visitMethodInsn(Opcodes.INVOKESPECIAL, sub.superName, "<init>", sub.methods.get(0).desc, false);
			c.visitInsn(Opcodes.RETURN); c.visitMaxs(0, 0); c.visitEnd(); subclass = loader.define(sub);

			ClassNode sourceManager = read(zip, MANAGER);
			embeddium = sourceManager.fields.stream().anyMatch(f -> f.name.equals("translucencySorting"));
			ClassNode mgr = select(sourceManager, "computeVisibleFaces", "addChunkToRenderLists");
			mgr.interfaces.add(Type.getInternalName(Manager.class));
			MethodNode face = method(mgr, "computeVisibleFaces");
			int reads = 0, adds = 0;
			MethodNode add = method(mgr, "addChunkToRenderLists");
			for (AbstractInsnNode n : add.instructions.toArray()) {
				if (n instanceof MethodInsnNode && ((MethodInsnNode) n).owner.equals(S + "render/chunk/lists/ChunkRenderList")
						&& ((MethodInsnNode) n).name.equals("add")) {
					adds++;
					if (optimized) {
						int slot = add.maxLocals; add.maxLocals += 3;
						InsnList call = new InsnList(); call.add(new VarInsnNode(Opcodes.ISTORE, slot + 2));
						call.add(new VarInsnNode(Opcodes.ASTORE, slot + 1)); call.add(new VarInsnNode(Opcodes.ASTORE, slot));
						call.add(new VarInsnNode(Opcodes.ALOAD, 0)); call.add(new VarInsnNode(Opcodes.ALOAD, slot));
						call.add(new VarInsnNode(Opcodes.ALOAD, slot + 1)); call.add(new VarInsnNode(Opcodes.ILOAD, slot + 2));
						MethodNode hook = method(mm, "iris$onlyFacesWithGeometry");
						call.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, mm.name, hook.name, hook.desc, false));
						add.instructions.insertBefore(n, call); add.instructions.remove(n);
					}
				}
			}
			for (AbstractInsnNode n : face.instructions.toArray()) {
				if (n instanceof FieldInsnNode && ((FieldInsnNode) n).name.equals("useBlockFaceCulling")) {
					check(n.getOpcode() == Opcodes.GETFIELD, "culling flag unexpectedly written"); reads++;
					if (optimized) {
						face.instructions.insertBefore(n, new InsnNode(Opcodes.DUP));
						MethodNode hook = method(sm, "iris$useBlockFaceCulling");
						face.instructions.set(n, new MethodInsnNode(Opcodes.INVOKESPECIAL, sm.name, hook.name, hook.desc, false));
					}
				}
			}
			check(reads == 1 && adds == 1, "renderer injection target count changed");
			MethodNode filter = method(mm, "iris$onlyFacesWithGeometry");
			MethodNode culling = method(sm, "iris$useBlockFaceCulling");
			noAllocation(filter); noAllocation(culling);
			mgr.methods.add(filter); mgr.methods.add(culling);
			if (!optimized) {
				// The previous Oculus HEAD callback returned ALL during the shadow pass.
				InsnList head = new InsnList(); LabelNode proceed = new LabelNode();
				head.add(new FieldInsnNode(Opcodes.GETSTATIC, Type.getInternalName(Shadow.class), "active", "Z"));
				head.add(new JumpInsnNode(Opcodes.IFEQ, proceed)); head.add(new IntInsnNode(Opcodes.BIPUSH, 127));
				head.add(new InsnNode(Opcodes.IRETURN)); head.add(proceed); face.instructions.insert(head);
			}
			publicMethods(mgr); manager = loader.define(remap(mgr, names));

			// Run the actual NIO and Unsafe writers. Native memory is replaced with a bounds-checked byte buffer.
			Map<String, String> writerNames = new HashMap<>(names);
			writerNames.put(BATCHER, ROOT + "impl/check/Batcher");
			writerNames.put(BATCHER + "$NioChunkDrawCallBatcher", ROOT + "impl/check/Nio");
			writerNames.put(BATCHER + "$UnsafeChunkDrawCallBatcher", ROOT + "impl/check/Unsafe");
			ClassNode batch = read(zip, BATCHER); batch.methods.removeIf(m -> m.name.equals("create"));
			batch.interfaces.add(Type.getInternalName(Batch.class)); loader.define(remap(batch, writerNames));
			nio = loader.define(remap(read(zip, BATCHER + "$NioChunkDrawCallBatcher"), writerNames));
			unsafe = loader.define(remap(read(zip, BATCHER + "$UnsafeChunkDrawCallBatcher"), writerNames));
			ClassNode back = select(read(zip, BACKEND), "setupDrawBatches", "buildCommandBuffer");
			back.interfaces.add(Type.getInternalName(Backend.class));
			for (MethodNode m : back.methods) {
				for (AbstractInsnNode n : m.instructions) {
					if (n instanceof MethodInsnNode && ((MethodInsnNode) n).owner.equals(BATCHER)) {
						((MethodInsnNode) n).setOpcode(Opcodes.INVOKEINTERFACE); ((MethodInsnNode) n).itf = true;
					}
				}
			}
			publicMethods(back); backend = loader.define(remap(back, names));
		}
		Manager manager() throws Exception { return (Manager) manager.getConstructor().newInstance(); }
		Backend backend() throws Exception { return (Backend) backend.getConstructor().newInstance(); }
		Batch batch(boolean direct) throws Exception { return (Batch) (direct ? unsafe : nio).getConstructor(int.class).newInstance(1024); }
		BaseState state(Chunk chunk, Region region, Mesh mesh) throws Exception {
			return (BaseState) graphics.getConstructor(Chunk.class, Region.class, Segment.class, Mesh.class, Format.class)
					.newInstance(chunk, region, new Segment(96 * (chunk.id + 1)), mesh, new Format());
		}
	}

	private static Mesh mesh(int mask, int seed) {
		Mesh mesh = new Mesh(); int start = 0;
		for (int f = 0; f < 7; f++) {
			int len = (mask & (1 << f)) == 0 ? 0 : (1 + (seed + f) % 7) * 4 * 32;
			// Include explicit zero-length slices with a nonzero start as well as absent slices.
			if (len != 0 || (f & 1) == 0) { mesh.parts.put(Facing.values()[f], new BufferSlice(start, len)); }
			start += len;
		}
		return mesh;
	}
	public static class BaseState {
		final Chunk chunk;
		public BaseState(Chunk chunk) { this.chunk = chunk; }
		public int getX() { return chunk.x; } public int getY() { return chunk.y; } public int getZ() { return chunk.z; }
	}
	public static class Chunk {
		final int id, x, y, z; int faces; final BaseState[] states = new BaseState[3]; final Bounds bounds = new Bounds();
		Chunk(int id, int x, int y, int z) {
			this.id = id; this.x = x; this.y = y; this.z = z;
			bounds.x1 = x; bounds.x2 = x + 16; bounds.y1 = y; bounds.y2 = y + 16; bounds.z1 = z; bounds.z2 = z + 16;
		}
		public int getFacesWithData() { return faces; } public BaseState[] getGraphicsStates() { return states; }
		public Bounds getBounds() { return bounds; } public boolean isTickable() { return (id & 1) == 0; }
	}
	public static class Bounds { public float x1, y1, z1, x2, y2, z2; }
	public static class Segment { final int start; Segment(int start) { this.start = start; } public int getStart() { return start; } public void delete() {} }
	public static class Format { public int getStride() { return 32; } }
	public enum Facing { A, B, C, D, E, F, G; public static final int COUNT = 7; }
	public static class Flags { public static int ALL = 127, UP = 1, DOWN = 2, EAST = 4, WEST = 8, SOUTH = 16, NORTH = 32, UNASSIGNED = 64; }
	public static class Mesh {
		final EnumMap<Facing, BufferSlice> parts = new EnumMap<>(Facing.class);
		public Iterable<? extends Map.Entry<Facing, BufferSlice>> getSlices() { return parts.entrySet(); }
	}
	public static class Shadow { public static boolean active; public static boolean areShadowsCurrentlyBeingRendered() { return active; } }
	public enum Pass { SOLID, CUTOUT, TRANSLUCENT; public static final Pass[] VALUES = values(); public boolean isTranslucent() { return this == TRANSLUCENT; } }
	public static class Region {
		final int id; final Batch batch; public float camDistance;
		Region(int id, Batch batch) { this.id = id; this.batch = batch; }
		public Batch getDrawBatcher() { return batch; }
		// Pairs intentionally share a center to exercise stable equal-distance ordering.
		public int getCenterBlockX() { return (id / 2) * 32; } public int getCenterBlockY() { return 64; } public int getCenterBlockZ() { return -32; }
	}
	public static class Buffer {}
	public interface Commands { void uploadData(Buffer buffer, ByteBuffer data); }
	public static class Params {
		final ByteBuffer bytes = ByteBuffer.allocate(8192).order(ByteOrder.nativeOrder());
		public void reset() { bytes.clear(); }
		public void pushChunkDrawParams(float x, float y, float z) { bytes.putFloat(x).putFloat(y).putFloat(z).putInt(0); }
		public ByteBuffer getBuffer() { ByteBuffer copy = bytes.duplicate(); copy.flip(); return copy; }
		byte[] values() { byte[] out = new byte[bytes.position()]; getBuffer().get(out); return out; }
	}
	public static class CommandVector {
		public void begin() {} public void end() {}
		public void pushCommandBuffer(Batch batch) { check(batch.getBuffer().remaining() == batch.getArrayLength(), "command upload length"); }
	}
	public static class Struct {
		protected final int stride; protected final ByteBuffer buffer;
		public Struct(int capacity, int stride) { this.stride = stride; buffer = ByteBuffer.allocate(capacity * stride).order(ByteOrder.nativeOrder()); }
		public ByteBuffer getBuffer() { return buffer; }
	}
	public static class Maths {
		public static int smallestEncompassingPowerOfTwo(int x) { return Integer.highestOneBit(x - 1) << 1; }
		public static int func_151236_b(int x) { return smallestEncompassingPowerOfTwo(x); }
	}
	public static class Memory {
		private static final List<ByteBuffer> buffers = new ArrayList<>();
		public static long memAddress(ByteBuffer buffer) { buffers.add(buffer); return ((long) buffers.size()) << 32; }
		public static void memPutInt(long address, int value) { buffers.get((int) (address >>> 32) - 1).putInt((int) address, value); }
	}
	private static class Result {
		final List<Integer> regionIds = new ArrayList<>(); final List<String> visible = new ArrayList<>(); byte[] uniforms; int zeros, commands;
	}

	private static Map<String, String> names() {
		Map<String, String> map = new HashMap<>();
		map.put(S + "render/chunk/ChunkGraphicsState", internal(BaseState.class));
		map.put(S + "render/chunk/ChunkRenderContainer", internal(Chunk.class));
		map.put(S + "render/chunk/data/ChunkRenderBounds", internal(Bounds.class));
		map.put(S + "render/chunk/data/ChunkMeshData", internal(Mesh.class));
		map.put(S + "gl/arena/GlBufferSegment", internal(Segment.class));
		map.put(S + "gl/attribute/GlVertexFormat", internal(Format.class));
		map.put(S + "model/quad/properties/ModelQuadFacing", internal(Facing.class));
		map.put(S + "render/chunk/cull/ChunkFaceFlags", internal(Flags.class));
		map.put(S + "render/chunk/passes/BlockRenderPass", internal(Pass.class));
		map.put(S + "render/chunk/region/ChunkRegion", internal(Region.class));
		map.put(S + "gl/device/CommandList", internal(Commands.class));
		map.put(S + "gl/buffer/GlMutableBuffer", internal(Buffer.class));
		map.put(S + "render/chunk/backends/multidraw/ChunkDrawParamsVector", internal(Params.class));
		map.put(S + "render/chunk/backends/multidraw/IndirectCommandBufferVector", internal(CommandVector.class));
		map.put(S + "render/chunk/backends/multidraw/StructBuffer", internal(Struct.class));
		map.put(BATCHER, internal(Batch.class));
		map.put("net/coderbot/iris/shadows/ShadowRenderingState", internal(Shadow.class));
		map.put("org/lwjgl/system/MemoryUtil", internal(Memory.class));
		map.put("net/minecraft/util/math/MathHelper", internal(Maths.class));
		map.put("net/minecraft/util/Mth", internal(Maths.class));
		return map;
	}
	private static ClassNode select(ClassNode source, String... keep) {
		ClassNode node = shell(source.name, "java/lang/Object");
		Set<String> fields = new HashSet<>();
		for (String name : keep) {
			MethodNode m = method(source, name); node.methods.add(m);
			for (AbstractInsnNode n : m.instructions) {
				if (n instanceof FieldInsnNode && ((FieldInsnNode) n).owner.equals(source.name)) { fields.add(((FieldInsnNode) n).name); }
			}
		}
		for (FieldNode f : source.fields) {
			if (fields.contains(f.name)) { f.access = Opcodes.ACC_PUBLIC | (f.access & Opcodes.ACC_STATIC); node.fields.add(f); }
		}
		MethodVisitor ctor = node.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		ctor.visitVarInsn(Opcodes.ALOAD, 0); ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		ctor.visitInsn(Opcodes.RETURN); ctor.visitMaxs(0, 0); ctor.visitEnd(); return node;
	}
	private static ClassNode shell(String name, String parent) {
		ClassNode node = new ClassNode(); node.version = Opcodes.V1_8; node.access = Opcodes.ACC_PUBLIC; node.name = name; node.superName = parent; return node;
	}
	private static void publicMethods(ClassNode node) { for (MethodNode m : node.methods) { m.access = Opcodes.ACC_PUBLIC | (m.access & Opcodes.ACC_STATIC); } }
	private static void noAllocation(MethodNode m) {
		check(!m.desc.contains("CallbackInfo"), "callback parameter remains in hot hook");
		for (AbstractInsnNode n : m.instructions) {
			check(n.getOpcode() != Opcodes.NEW && n.getOpcode() != Opcodes.NEWARRAY && n.getOpcode() != Opcodes.ANEWARRAY, "allocation in hot hook");
		}
	}
	private static MethodNode method(ClassNode node, String name) { return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError(name)); }
	private static ClassNode read(ZipFile zip, String name) throws Exception {
		try (InputStream in = zip == null ? ChunkDrawBatchCheck.class.getClassLoader().getResourceAsStream(name + ".class") : zip.getInputStream(zip.getEntry(name + ".class"))) {
			check(in != null, "missing class " + name); ClassNode node = new ClassNode(); new ClassReader(in).accept(node, 0); return node;
		}
	}
	private static ClassNode remap(ClassNode node, Map<String, String> names) { ClassNode out = new ClassNode(); node.accept(new ClassRemapper(out, new SimpleRemapper(names))); return out; }
	private static String internal(Class<?> type) { return Type.getInternalName(type); }
	private static String desc(Class<?> type) { return Type.getDescriptor(type); }
	private static Object get(Object target, String name) throws Exception { return target.getClass().getField(name).get(target); }
	private static void set(Object target, String name, Object value) throws Exception { target.getClass().getField(name).set(target, value); }
	private static void check(boolean okay, String message) { if (!okay) { throw new AssertionError(message); } }
	private static class Loader extends ClassLoader {
		Loader() { super(ChunkDrawBatchCheck.class.getClassLoader()); }
		Class<?> define(ClassNode node) {
			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
				@Override protected String getCommonSuperClass(String a, String b) { return "java/lang/Object"; }
			};
			node.accept(writer); byte[] bytes = writer.toByteArray(); return defineClass(null, bytes, 0, bytes.length);
		}
	}
}
