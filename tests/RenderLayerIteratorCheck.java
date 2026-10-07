package net.coderbot.iris.compat.sodium.impl;

import com.google.common.collect.ImmutableList;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Runs compiled production hooks and reads renderer bytecode without starting Minecraft. */
public final class RenderLayerIteratorCheck {

	private static final String MIXIN = "net/coderbot/iris/compat/sodium/mixin/render_layers/MixinChunkRenderRebuildTask";
	private static final String TARGET = "me/jellysquid/mods/sodium/client/render/chunk/tasks/ChunkRenderRebuildTask";
	private static final String HOOK = "iris$reuseRenderLayerIterator";
	private static volatile Object iteratorSink;
	private static volatile long checksumSink;

	public interface Hooks {
		Iterator<Integer> iris$reuseRenderLayerIterator(List<Integer> layers);
	}

	public static void main(String[] args) throws Exception {
		Class<?> hookClass = loadHooks();
		Hooks hooks = (Hooks) hookClass.getConstructor().newInstance();
		List<List<Integer>> cases = Arrays.asList(ImmutableList.<Integer>of(), ImmutableList.of(7),
				ImmutableList.of(3, 1, 3, 8), ImmutableList.of(5, 2, 9).reverse(),
				ImmutableList.of(0, 8, 6, 4).subList(1, 3), Collections.<Integer>emptyList(),
				Collections.singletonList(6), Arrays.asList(2, 9, 4), new LinkedList<>(Arrays.asList(8, 2)),
				new AbstractList<Integer>() {
					@Override public int size() { return 3; }
					@Override public Integer get(int index) { throw new AssertionError("custom list indexed"); }
					@Override public Iterator<Integer> iterator() { return Arrays.asList(9, 4, 1).iterator(); }
				});
		// Alternate block and fluid lists, including empty, single, multi-layer and custom lists.
		for (int repeat = 0; repeat < 100; repeat++) {
			for (List<Integer> block : cases) {
				for (List<Integer> fluid : cases) {
					assertSequence(hooks.iris$reuseRenderLayerIterator(block), block.iterator());
					assertSequence(hooks.iris$reuseRenderLayerIterator(fluid), fluid.iterator());
				}
			}
		}
		checkReentry(hooks);
		checkNativeSemantics(hooks);
		checkParallelTasks(hookClass);

		ClassLoader loader = RenderLayerIteratorCheck.class.getClassLoader();
		try (InputStream in = loader.getResourceAsStream(TARGET + ".class")) {
			checkLoops(readClass(in), 1, "Rubidium");
		}
		for (String jar : args) {
			try (ZipFile zip = new ZipFile(jar)) {
				check(zip.getEntry(TARGET + ".class") != null, "renderer class missing in " + jar);
				try (InputStream in = zip.getInputStream(zip.getEntry(TARGET + ".class"))) {
					checkLoops(readClass(in), 2, "Embeddium");
				}
			}
		}
		String report = "Compiled hook checks passed: 20,000 block/fluid sequences; immutable views, native custom/mutable "
				+ "lists, nested/abandoned iterations and four concurrent task owners.\n"
				+ "Rubidium: 1 non-escaping loop; local Embeddium jars checked: " + args.length + ".\n"
				+ checkAllocation(hooks) + "\n";
		Files.createDirectories(Paths.get("build/reports"));
		Files.write(Paths.get("build/reports/render-layer-iterator.txt"), report.getBytes(StandardCharsets.UTF_8));
		System.out.print(report);
	}

	private static void checkReentry(Hooks hooks) {
		Iterator<Integer> outer = hooks.iris$reuseRenderLayerIterator(ImmutableList.of(4, 5, 6));
		check(outer.next() == 4, "outer first layer");
		Iterator<Integer> inner = hooks.iris$reuseRenderLayerIterator(ImmutableList.of(9, 8));
		check(inner != outer, "nested loop reused an active cursor");
		assertSequence(inner, ImmutableList.of(9, 8).iterator());
		// A broken/throwing loop must remain pinned instead of corrupting a later traversal.
		for (int i = 0; i < 10; i++) {
			assertSequence(hooks.iris$reuseRenderLayerIterator(ImmutableList.of(1, 2)), ImmutableList.of(1, 2).iterator());
		}
		assertSequence(outer, ImmutableList.of(5, 6).iterator());
		Iterator<Integer> last = hooks.iris$reuseRenderLayerIterator(ImmutableList.of(7));
		check(last == outer && last.next() == 7, "cursor not reused after completion");
		inner = hooks.iris$reuseRenderLayerIterator(ImmutableList.of(2));
		check(inner != last, "cursor released before rendering the last layer");
		assertSequence(inner, ImmutableList.of(2).iterator());
		check(!last.hasNext(), "nested call changed exhausted outer loop");
	}

	private static void checkNativeSemantics(Hooks hooks) {
		List<Integer> mutable = new ArrayList<>(Arrays.asList(2, 4, 6));
		Iterator<Integer> iterator = hooks.iris$reuseRenderLayerIterator(mutable);
		check(iterator.next() == 2, "mutable list order");
		iterator.remove();
		check(mutable.equals(Arrays.asList(4, 6)), "mutable iterator remove lost");
		mutable.add(8);
		try { iterator.next(); throw new AssertionError("fail-fast behavior lost"); }
		catch (ConcurrentModificationException expected) { }
		iterator = hooks.iris$reuseRenderLayerIterator(ImmutableList.of(1));
		iterator.next();
		try { iterator.remove(); throw new AssertionError("immutable iterator allowed remove"); }
		catch (UnsupportedOperationException expected) { }
		check(!iterator.hasNext(), "immutable remove advanced cursor");
		try { iterator.next(); throw new AssertionError("exhausted iterator returned an element"); }
		catch (NoSuchElementException expected) { }
	}

	private static void checkParallelTasks(final Class<?> hookClass) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(4);
		CyclicBarrier start = new CyclicBarrier(4);
		try {
			List<Future<Void>> work = new ArrayList<>();
			for (int worker = 0; worker < 4; worker++) {
				final int value = worker;
				work.add(executor.submit((Callable<Void>) () -> {
					Hooks hooks = (Hooks) hookClass.getConstructor().newInstance();
					ImmutableList<Integer> layers = ImmutableList.of(value, value + 10, value + 20);
					start.await();
					for (int block = 0; block < 16384; block++) {
						assertSequence(hooks.iris$reuseRenderLayerIterator(layers), layers.iterator());
						if ((block & 31) == 0) { checkReentry(hooks); }
					}
					return null;
				}));
			}
			for (Future<Void> result : work) { result.get(); }
		} finally {
			executor.shutdownNow();
		}
	}

	private static String checkAllocation(Hooks current) throws Exception {
		Class<?> api = Class.forName("com.sun.management.ThreadMXBean");
		Object bean = ManagementFactory.getThreadMXBean();
		if (!api.isInstance(bean) || !(Boolean) api.getMethod("isThreadAllocatedMemorySupported").invoke(bean)) {
			return "Allocation counter unavailable on this JVM (behavior checks still ran).";
		}
		api.getMethod("setThreadAllocatedMemoryEnabled", boolean.class).invoke(bean, true);
		Method bytes = api.getMethod("getThreadAllocatedBytes", long.class);
		long thread = Thread.currentThread().getId();
		List<List<Integer>> lists = Arrays.asList(ImmutableList.<Integer>of(), ImmutableList.of(1),
				ImmutableList.of(2, 3, 4), ImmutableList.of(5));
		Hooks original = List::iterator;
		run(original, lists, 20000);
		run(current, lists, 20000);
		long before = (Long) bytes.invoke(bean, thread);
		long expected = run(original, lists, 200000);
		long originalBytes = (Long) bytes.invoke(bean, thread) - before;
		before = (Long) bytes.invoke(bean, thread);
		long actual = run(current, lists, 200000);
		long currentBytes = (Long) bytes.invoke(bean, thread) - before;
		check(expected == actual, "allocation workload output changed");
		check(originalBytes > 1000000 && currentBytes < 8192, "per-block allocations remain: " + currentBytes);
		return "800,000 warmed traversals: legacy=" + originalBytes + " B, reused=" + currentBytes
				+ " B (includes counter overhead; allocation check, not a game FPS measurement).";
	}

	private static long run(Hooks hooks, List<List<Integer>> lists, int repeats) {
		long checksum = 0;
		for (int i = 0; i < repeats; i++) {
			for (int j = 0; j < lists.size(); j++) {
				Iterator<Integer> iterator = hooks.iris$reuseRenderLayerIterator(lists.get(j));
				// Make both cursors observable: escape analysis must not erase the legacy allocation.
				iteratorSink = iterator;
				while (iterator.hasNext()) { checksum += iterator.next(); }
			}
		}
		checksumSink = checksum;
		return checksum;
	}

	private static void assertSequence(Iterator<Integer> actual, Iterator<Integer> expected) {
		while (expected.hasNext()) {
			check(actual.hasNext(), "missing render layer");
			check(actual.hasNext(), "hasNext advanced iterator");
			check(expected.next().equals(actual.next()), "layer order/value changed");
		}
		check(!actual.hasNext() && !actual.hasNext(), "extra render layer");
	}

	private static Class<?> loadHooks() throws Exception {
		ClassNode node;
		try (InputStream in = RenderLayerIteratorCheck.class.getClassLoader().getResourceAsStream(MIXIN + ".class")) {
			node = readClass(in);
		}
		check(node.version == Opcodes.V1_8, "mixin lost Java 8 compatibility");
		check(node.methods.size() == 2 && node.fields.size() == 1, "unexpected task state/hooks");
		for (FieldNode field : node.fields) {
			check((field.access & Opcodes.ACC_STATIC) == 0, "cursor shared between build tasks");
			field.visibleAnnotations = null;
			field.invisibleAnnotations = null;
		}
		for (MethodNode method : node.methods) {
			if (method.name.equals(HOOK)) {
				AnnotationNode redirect = method.visibleAnnotations.get(0);
				check(redirect.desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;"), "not a redirect");
				check(Integer.valueOf(1).equals(value(redirect, "require"))
						&& Integer.valueOf(2).equals(value(redirect, "allow")), "renderer call count guard changed");
				@SuppressWarnings("unchecked")
				List<String> methods = (List<String>) value(redirect, "method");
				check(methods.equals(Collections.singletonList("performBuild")), "wrong target method");
				AnnotationNode at = (AnnotationNode) value(redirect, "at");
				check("Ljava/util/List;iterator()Ljava/util/Iterator;".equals(value(at, "target")), "wrong redirect target");
				method.access = Opcodes.ACC_PUBLIC;
			}
			method.visibleAnnotations = null;
			method.invisibleAnnotations = null;
		}
		node.visibleAnnotations = null;
		node.invisibleAnnotations = null;
		node.interfaces.add(Hooks.class.getName().replace('.', '/'));
		String generated = RenderLayerIteratorCheck.class.getPackage().getName() + ".GeneratedRenderLayerHooks";
		Map<String, String> names = new HashMap<>();
		names.put(MIXIN, generated.replace('.', '/'));
		names.put("net/minecraft/client/renderer/RenderType", "java/lang/Integer");
		ClassWriter writer = new ClassWriter(0);
		node.accept(new ClassRemapper(writer, new SimpleRemapper(names)));
		return new ClassLoader(RenderLayerIteratorCheck.class.getClassLoader()) {
			Class<?> define() {
				byte[] bytes = writer.toByteArray();
				return defineClass(generated, bytes, 0, bytes.length);
			}
		}.define();
	}

	private static void checkLoops(ClassNode node, int expected, String renderer) {
		int loops = 0;
		for (MethodNode method : node.methods) {
			if (!method.name.equals("performBuild")) { continue; }
			for (AbstractInsnNode instruction : method.instructions) {
				if (!(instruction instanceof MethodInsnNode)) { continue; }
				MethodInsnNode call = (MethodInsnNode) instruction;
				if (!call.owner.equals("java/util/List") || !call.name.equals("iterator")) { continue; }
				loops++;
				AbstractInsnNode previous = instruction.getPrevious();
				while (previous.getOpcode() < 0) { previous = previous.getPrevious(); }
				check(previous instanceof MethodInsnNode, "layer list producer changed");
				MethodInsnNode producer = (MethodInsnNode) previous;
				check(renderer.equals("Embeddium")
						? producer.owner.equals("org/embeddedt/embeddium/render/EmbeddiumRenderLayerCache") && producer.name.equals("forState")
						: producer.owner.equals("net/minecraft/client/renderer/RenderType") && producer.name.equals("chunkBufferLayers"),
						"redirect would intercept a different list");
				VarInsnNode store = (VarInsnNode) nextInstruction(call);
				check(store.getOpcode() == Opcodes.ASTORE, "iterator no longer stored locally");
				AbstractInsnNode head = nextInstruction(store);
				check(head.getOpcode() == Opcodes.ALOAD && ((VarInsnNode) head).var == store.var, "loop entry changed");
				MethodInsnNode hasNext = (MethodInsnNode) nextInstruction(head);
				check(hasNext.name.equals("hasNext"), "loop entry no longer tests exhaustion");
				JumpInsnNode exit = (JumpInsnNode) nextInstruction(hasNext);
				check(exit.getOpcode() == Opcodes.IFEQ, "loop exit changed");
				int end = method.instructions.indexOf(exit.label);
				int reads = 0;
				for (AbstractInsnNode part = head; part != exit.label; part = part.getNext()) {
					check(part != null, "invalid loop boundary");
					if (part instanceof VarInsnNode && ((VarInsnNode) part).var == store.var) {
						check(part.getOpcode() == Opcodes.ALOAD, "cursor local overwritten");
						MethodInsnNode use = (MethodInsnNode) nextInstruction(part);
						check(use.owner.equals("java/util/Iterator") && (use.name.equals("hasNext") || use.name.equals("next")),
								"iterator escapes the loop");
						reads++;
					}
					check(part.getOpcode() != Opcodes.ARETURN && part.getOpcode() != Opcodes.RETURN, "loop can return early");
					if (part instanceof JumpInsnNode) {
						int target = method.instructions.indexOf(((JumpInsnNode) part).label);
						check(target > method.instructions.indexOf(store) && target <= end, "jump escapes iterator lifetime");
					}
				}
				check(reads == 2, "unexpected iterator use count");
			}
		}
		check(loops == expected, renderer + " iterator call count: " + loops);
	}

	private static AbstractInsnNode nextInstruction(AbstractInsnNode instruction) {
		do { instruction = instruction.getNext(); } while (instruction != null && instruction.getOpcode() < 0);
		check(instruction != null, "unexpected bytecode end");
		return instruction;
	}

	private static Object value(AnnotationNode annotation, String key) {
		for (int i = 0; i < annotation.values.size(); i += 2) {
			if (annotation.values.get(i).equals(key)) { return annotation.values.get(i + 1); }
		}
		return null;
	}

	private static ClassNode readClass(InputStream input) throws Exception {
		check(input != null, "class file missing");
		ClassNode node = new ClassNode();
		new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG);
		return node;
	}

	private static void check(boolean condition, String message) {
		if (!condition) { throw new AssertionError(message); }
	}
}
