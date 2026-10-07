package net.coderbot.iris.diagnostics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.locks.LockSupport;

/** Real JVM GC, CPU work and blocking, verified from the exported recording. Never runs Minecraft. */
public final class FrameTimeJfrCheck {
	private static volatile Object allocated;
	private static volatile long cpuResult;

	public static void main(String[] args) throws Exception {
		boolean concurrent = args.length > 0 && args[0].equals("concurrent");
		Path file = Files.createTempFile("iris-gc-clock-check-", ".jfr");
		List<long[]> calls = new ArrayList<>();
		List<ExpectedTrace> expected = new ArrayList<>();
		FrameTimeJfr.Result result;
		try (FrameTimeJfr jfr = new FrameTimeJfr()) {
			for (int i = 0; i < 4; i++) {
				for (int j = 0; j < 2048; j++) {
					allocated = new byte[16 * 1024];
				}
				long before = System.nanoTime();
				System.gc();
				long after = System.nanoTime();
				calls.add(new long[]{before, after});
				Thread.sleep(25);
			}
			long owner = Thread.currentThread().getId();
			long before = System.nanoTime();
			busyTick();
			expected.add(new ExpectedTrace("jdk.ExecutionSample", "busyTick", owner, before, System.nanoTime()));
			before = System.nanoTime();
			parkingTick();
			expected.add(new ExpectedTrace("jdk.ThreadPark", "parkingTick", owner, before, System.nanoTime()));
			before = System.nanoTime();
			waitingTick();
			expected.add(new ExpectedTrace("jdk.JavaMonitorWait", "waitingTick", owner, before, System.nanoTime()));
			before = System.nanoTime();
			sleepingTick();
			expected.add(new ExpectedTrace("jdk.ThreadSleep", "sleepingTick", owner, before, System.nanoTime()));
			Object monitor = new Object();
			long[] blocked = new long[2];
			Thread contender = new Thread(() -> blockedTick(monitor, blocked), "Iris diagnostic lock contender");
			contender.setDaemon(true);
			synchronized (monitor) {
				contender.start();
				long deadline = System.nanoTime() + 3_000_000_000L;
				while (contender.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
					Thread.yield();
				}
				check(contender.getState() == Thread.State.BLOCKED, "test thread did not contend for monitor");
				Thread.sleep(80);
			}
			contender.join(3000);
			check(!contender.isAlive(), "test thread did not acquire released monitor");
			expected.add(new ExpectedTrace("jdk.JavaMonitorEnter", "blockedTick", contender.getId(), blocked[0], blocked[1]));
			result = jfr.finish(file);
		}
		check(result.aligned && result.uncertaintyMillis < 2, "JFR calibration failed");
		long tolerance = (long) Math.ceil(result.uncertaintyMillis * 1_000_000);
		for (long[] call : calls) {
			boolean contained = false;
			for (FrameTimeJfr.Pause pause : result.pauses) {
				contained |= pause.start >= call[0] - tolerance && pause.end <= call[1] + tolerance
						&& pause.end > call[0] && pause.start < call[1];
			}
			check(contained, "A real GC pause was not aligned with its System.nanoTime interval");
		}
		verifyTraces(file, result.anchor, tolerance, expected);
		String gcReport = verifyGcDetails(file, result, tolerance, concurrent);
		// A second capture must remain usable after the first dynamic event factory was unregistered.
		try (FrameTimeJfr again = new FrameTimeJfr()) {
			System.gc();
			check(again.finish(file).aligned, "repeat capture lost clock markers");
		}
		Files.delete(file);
		Path report = Paths.get("build/reports/frame-time-gc-" + (concurrent ? "concurrent" : "default") + ".txt");
		Files.createDirectories(report.getParent());
		Files.write(report, gcReport.getBytes(StandardCharsets.UTF_8));
		System.out.print(gcReport);
		System.out.printf("JFR GC clock: %d real pauses; all 4 forced-GC intervals match; uncertainty %.6f ms; drift %.6f ms; repeat capture passed.%n",
				result.pauses.size(), result.uncertaintyMillis, result.driftMillis);
		System.out.println("JFR stalls: real execution samples, monitor contention, Object.wait, park and sleep all retained their thread, call stack and aligned interval.");
	}

	private static String verifyGcDetails(Path path, FrameTimeJfr.Result result, long tolerance,
			boolean concurrent) throws Exception {
		Class<?> fileType = Class.forName("jdk.jfr.consumer.RecordingFile");
		Class<?> eventType = Class.forName("jdk.jfr.consumer.RecordedEvent");
		Class<?> objectType = Class.forName("jdk.jfr.consumer.RecordedObject");
		Map<String, Integer> counts = new TreeMap<>();
		Set<String> phaseNames = new TreeSet<>();
		Set<Integer> beforeHeap = new HashSet<>(), afterHeap = new HashSet<>(), references = new HashSet<>(), evacuations = new HashSet<>();
		List<FrameTimeJfr.Pause> tops = new ArrayList<>(), children = new ArrayList<>(), parallel = new ArrayList<>();
		boolean remark = false, remarkChildren = false;
		Object recording = fileType.getConstructor(Path.class).newInstance(path);
		try {
			while ((Boolean) fileType.getMethod("hasMoreEvents").invoke(recording)) {
				Object event = fileType.getMethod("readEvent").invoke(recording);
				Object type = eventType.getMethod("getEventType").invoke(event);
				String name = (String) Class.forName("jdk.jfr.EventType").getMethod("getName").invoke(type);
				if (!name.startsWith("jdk.GC") && !name.startsWith("jdk.G1") && !name.equals("jdk.ExecuteVMOperation")
						&& !name.equals("jdk.EvacuationInformation")) {
					continue;
				}
				counts.put(name, counts.getOrDefault(name, 0) + 1);
				check(eventType.getMethod("getStackTrace").invoke(event) == null, "GC diagnostics requested extra stack traces");
				if (name.equals("jdk.GCPhasePause") || name.startsWith("jdk.GCPhasePauseLevel") || name.equals("jdk.GCPhaseParallel")) {
					long start = result.anchor.toNanos((Instant) eventType.getMethod("getStartTime").invoke(event));
					long end = result.anchor.toNanos((Instant) eventType.getMethod("getEndTime").invoke(event));
					int id = (Integer) objectType.getMethod("getInt", String.class).invoke(event, "gcId");
					String phase = (String) objectType.getMethod("getString", String.class).invoke(event, "name");
					FrameTimeJfr.Pause pause = new FrameTimeJfr.Pause(start, end, id, phase);
					if (name.equals("jdk.GCPhasePause")) {
						tops.add(pause);
						remark |= phase.equals("Pause Remark");
					} else if (name.equals("jdk.GCPhaseParallel")) {
						parallel.add(pause);
						check((Integer) objectType.getMethod("getInt", String.class).invoke(event, "gcWorkerId") >= 0, "parallel worker id missing");
					} else {
						children.add(pause);
						phaseNames.add(phase);
					}
				} else if (name.equals("jdk.GCHeapSummary")) {
					int id = (Integer) objectType.getMethod("getInt", String.class).invoke(event, "gcId");
					String when = (String) objectType.getMethod("getString", String.class).invoke(event, "when");
					long bytes = (Long) objectType.getMethod("getLong", String.class).invoke(event, "heapUsed");
					check(bytes >= 0, "heap summary lacks used bytes");
					if (when.equals("Before GC")) { beforeHeap.add(id); }
					if (when.equals("After GC")) { afterHeap.add(id); }
				} else if (name.equals("jdk.GCReferenceStatistics")) {
					references.add((Integer) objectType.getMethod("getInt", String.class).invoke(event, "gcId"));
					check((Long) objectType.getMethod("getLong", String.class).invoke(event, "count") >= 0,
							"reference count missing");
				} else if (name.equals("jdk.EvacuationInformation")) {
					evacuations.add((Integer) objectType.getMethod("getInt", String.class).invoke(event, "gcId"));
					check((Long) objectType.getMethod("getLong", String.class).invoke(event, "bytesCopied") >= 0, "evacuation bytes missing");
					check((Integer) objectType.getMethod("getInt", String.class).invoke(event, "cSetRegions") >= 0, "collection set region count missing");
				}
			}
		} finally {
			fileType.getMethod("close").invoke(recording);
		}
		for (String required : new String[]{"jdk.GCPhasePauseLevel1", "jdk.GCPhasePauseLevel2",
				"jdk.GCReferenceStatistics", "jdk.GCHeapSummary", "jdk.G1HeapSummary",
				"jdk.G1GarbageCollection", "jdk.GCConfiguration", "jdk.GCHeapConfiguration", "jdk.ExecuteVMOperation",
				"jdk.GCPhaseParallel", "jdk.EvacuationInformation", "jdk.G1EvacuationYoungStatistics", "jdk.G1EvacuationOldStatistics"}) {
			check(counts.getOrDefault(required, 0) > 0, "Missing real diagnostic event " + required);
		}
		check(tops.size() == result.pauses.size(), "Nested/concurrent phases leaked into pause totals");
		for (FrameTimeJfr.Pause pause : tops) {
			boolean retained = false;
			for (FrameTimeJfr.Pause actual : result.pauses) {
				retained |= actual.id == pause.id && actual.start == pause.start && actual.end == pause.end
						&& actual.name.equals(pause.name);
			}
			check(retained, "Top-level pause changed when GC detail events were enabled");
		}
		for (FrameTimeJfr.Pause child : children) {
			boolean contained = false;
			for (FrameTimeJfr.Pause parent : tops) {
				boolean matches = child.id == parent.id && child.start >= parent.start - tolerance
						&& child.end <= parent.end + tolerance;
				contained |= matches;
				remarkChildren |= matches && parent.name.equals("Pause Remark");
			}
			check(contained, "GC child phase has no matching parent interval: " + child.name);
		}
		beforeHeap.retainAll(afterHeap);
		beforeHeap.retainAll(references);
		check(!beforeHeap.isEmpty(), "Heap before/after and reference counts cannot be joined by gcId");
		check(!evacuations.isEmpty(), "No evacuation summary captured");
		for (Integer id : evacuations) {
			check(tops.stream().anyMatch(p -> p.id == id), "evacuation summary has no parent GC");
		}
		for (FrameTimeJfr.Pause worker : parallel) {
			check(tops.stream().anyMatch(p -> p.id == worker.id && worker.start >= p.start - tolerance
					&& worker.end <= p.end + tolerance), "parallel phase has no matching top-level GC: " + worker.name);
		}
		if (concurrent) {
			check(remark && remarkChildren && counts.getOrDefault("jdk.GCPhaseConcurrent", 0) > 0,
					"Concurrent G1 test did not capture real Remark, nested phases and concurrent work");
		}
		return "Real G1 diagnostics (" + (concurrent ? "concurrent" : "default") + "): " + counts + "\n"
				+ "Nested phases: " + phaseNames + "\n"
				+ "Pause totals contain only " + tops.size() + " top-level events; " + children.size()
				+ " children retain matching gcId/time intervals. Heap/reference joins: " + beforeHeap.size() + ".\n"
				+ "Parallel worker phases=" + parallel.size() + "; evacuation joins=" + evacuations.size()
				+ "; worker/evacuation events do not enter pause totals.\n"
				+ "Remark captured=" + remark + "; Remark children=" + remarkChildren + "; extra GC stacks=0.\n";
	}

	private static void busyTick() {
		long end = System.nanoTime() + 1_000_000_000L;
		long value = 123456789;
		do {
			for (int i = 0; i < 4096; i++) {
				value ^= value << 13;
				value ^= value >>> 7;
				value ^= value << 17;
			}
			cpuResult = value;
		} while (System.nanoTime() < end);
	}

	private static void parkingTick() {
		long end = System.nanoTime() + 80_000_000L;
		long remaining;
		while ((remaining = end - System.nanoTime()) > 0) {
			LockSupport.parkNanos(remaining);
		}
	}

	private static void waitingTick() throws InterruptedException {
		Object monitor = new Object();
		synchronized (monitor) {
			monitor.wait(80);
		}
	}

	private static void sleepingTick() throws InterruptedException {
		Thread.sleep(80);
	}

	private static void blockedTick(Object monitor, long[] interval) {
		interval[0] = System.nanoTime();
		synchronized (monitor) {
			allocated = monitor;
		}
		interval[1] = System.nanoTime();
	}

	// Reflective consumer keeps the same Java 8 bytecode boundary as the production bridge.
	private static void verifyTraces(Path path, FrameTimeJfr.Anchor anchor, long tolerance,
			List<ExpectedTrace> expected) throws Exception {
		Class<?> fileType = Class.forName("jdk.jfr.consumer.RecordingFile");
		Class<?> eventType = Class.forName("jdk.jfr.consumer.RecordedEvent");
		Class<?> objectType = Class.forName("jdk.jfr.consumer.RecordedObject");
		Class<?> threadType = Class.forName("jdk.jfr.consumer.RecordedThread");
		Class<?> stackType = Class.forName("jdk.jfr.consumer.RecordedStackTrace");
		Class<?> frameType = Class.forName("jdk.jfr.consumer.RecordedFrame");
		Class<?> methodType = Class.forName("jdk.jfr.consumer.RecordedMethod");
		Class<?> classType = Class.forName("jdk.jfr.consumer.RecordedClass");
		Object recording = fileType.getConstructor(Path.class).newInstance(path);
		try {
			while ((Boolean) fileType.getMethod("hasMoreEvents").invoke(recording)) {
				Object event = fileType.getMethod("readEvent").invoke(recording);
				Object type = eventType.getMethod("getEventType").invoke(event);
				String name = (String) Class.forName("jdk.jfr.EventType").getMethod("getName").invoke(type);
				for (ExpectedTrace target : expected) {
					if (!name.equals(target.type)) {
						continue;
					}
					Object thread = objectType.getMethod("getThread", String.class).invoke(event,
							name.equals("jdk.ExecutionSample") ? "sampledThread" : "eventThread");
					long id = (Long) threadType.getMethod("getJavaThreadId").invoke(thread);
					long start = anchor.toNanos((Instant) eventType.getMethod("getStartTime").invoke(event));
					long end = anchor.toNanos((Instant) eventType.getMethod("getEndTime").invoke(event));
					if (id != target.thread || start < target.start - tolerance || end > target.end + tolerance
							|| (!name.equals("jdk.ExecutionSample") && end - start < 5_000_000L)) {
						continue;
					}
					Object stack = eventType.getMethod("getStackTrace").invoke(event);
					if (stack != null) {
						for (Object frame : (List<?>) stackType.getMethod("getFrames").invoke(stack)) {
							Object method = frameType.getMethod("getMethod").invoke(frame);
							Object clazz = methodType.getMethod("getType").invoke(method);
							target.found |= target.method.equals(methodType.getMethod("getName").invoke(method))
									&& FrameTimeJfrCheck.class.getName().equals(classType.getMethod("getName").invoke(clazz));
						}
					}
				}
			}
		} finally {
			fileType.getMethod("close").invoke(recording);
		}
		for (ExpectedTrace target : expected) {
			check(target.found, "Missing real " + target.type + " with " + target.method + " stack/interval in " + path);
		}
	}

	private static final class ExpectedTrace {
		final String type, method;
		final long thread, start, end;
		boolean found;

		ExpectedTrace(String type, String method, long thread, long start, long end) {
			this.type = type;
			this.method = method;
			this.thread = thread;
			this.start = start;
			this.end = end;
		}
	}

	private static void check(boolean value, String message) {
		if (!value) {
			throw new AssertionError(message);
		}
	}
}
