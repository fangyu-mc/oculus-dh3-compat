package net.coderbot.iris.diagnostics;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Optional JFR bridge using only public APIs, accessed reflectively to retain Java 8 loading.
 * Never called from a per-frame hook. No RuntimeMXBean/GcInfo epoch conversion is used:
 * those clocks can have different origins even within the same HotSpot JVM.
 */
final class FrameTimeJfr implements AutoCloseable {
	private static final String CLOCK_EVENT = "iris.FrameTimeClock";
	private static final String OLD_OBJECT_EVENT = "jdk.OldObjectSample";
	private static final long SAMPLE_PERIOD_MILLIS = 10;
	private static final long WAIT_THRESHOLD_MILLIS = 5;
	private final Api api = new Api();
	private Object recording;
	private Object factory;
	private boolean oldObjectsEnabled;
	final boolean allocationSampling;
	final boolean oldObjectSampling;
	private final boolean dataLossMonitoring;
	final String profilingDetails;

	FrameTimeJfr() throws ReflectiveOperationException {
		boolean allocations = false;
		boolean oldObjects = false;
		boolean lossMonitoring = false;
		List<String> sampled = new ArrayList<>();
		List<String> waits = new ArrayList<>();
		List<String> safepoints = new ArrayList<>();
		List<String> gcDetails = new ArrayList<>();
		List<String> gcConfiguration = new ArrayList<>();
		List<String> unavailable = new ArrayList<>();
		try {
			if (!((Boolean) api.flightRecorder.getMethod("isAvailable").invoke(null))) {
				throw new IllegalStateException("JFR unavailable");
			}
			Object recorder = api.flightRecorder.getMethod("getFlightRecorder").invoke(null);
			Set<String> available = new HashSet<>();
			Object oldObjectType = null;
			for (Object type : (List<?>) api.flightRecorder.getMethod("getEventTypes").invoke(recorder)) {
				String name = (String) api.eventType.getMethod("getName").invoke(type);
				available.add(name);
				if (OLD_OBJECT_EVENT.equals(name)) { oldObjectType = type; }
			}
			allocations = available.contains("jdk.ObjectAllocationSample");
			if (!available.contains("jdk.GCPhasePause")) {
				throw new IllegalStateException("JFR GCPhasePause event unavailable");
			}
			Object name = api.annotation.getConstructor(Class.class, Object.class)
					.newInstance(Class.forName("jdk.jfr.Name"), CLOCK_EVENT);
			Constructor<?> field = api.descriptor.getConstructor(Class.class, String.class);
			factory = api.factory.getMethod("create", List.class, List.class).invoke(null,
					Collections.singletonList(name), Arrays.asList(field.newInstance(long.class, "beforeNanos"),
							field.newInstance(long.class, "afterNanos"), field.newInstance(int.class, "phase")));
			recording = api.recording.getConstructor().newInstance();
			api.recording.getMethod("setName", String.class).invoke(recording, "Iris frame diagnostics");
			// An in-memory recording is a bounded rolling buffer. Under a busy 120-second
			// capture it can silently retain only the last part of the execution samples.
			// Let JFR retain chunks in its own repository until this recording is closed.
			// No frame hook writes files; only explicit diagnostic captures use the repository.
			api.recording.getMethod("setToDisk", boolean.class).invoke(recording, true);
			configure(CLOCK_EVENT, false);
			configure("jdk.GCPhasePause", false);
			configure("jdk.GarbageCollection", false);
			if (available.contains("jdk.DataLoss")) {
				configure("jdk.DataLoss", false);
				lossMonitoring = true;
			} else {
				unavailable.add("jdk.DataLoss");
			}
			// These are VM-produced events, not heap walks or per-frame probes. Keep nested
			// phases in JFR only: summing them into Result.pauses would double-count stop time.
			for (String event : Arrays.asList("jdk.GCPhasePauseLevel1", "jdk.GCPhasePauseLevel2",
					"jdk.GCPhasePauseLevel3", "jdk.GCPhasePauseLevel4", "jdk.GCPhaseConcurrent",
					"jdk.GCPhaseConcurrentLevel1", "jdk.GCReferenceStatistics", "jdk.GCHeapSummary",
					"jdk.G1HeapSummary", "jdk.G1GarbageCollection", "jdk.GCPhaseParallel",
					"jdk.EvacuationInformation", "jdk.G1EvacuationYoungStatistics", "jdk.G1EvacuationOldStatistics")) {
				if (available.contains(event)) {
					configure(event, false);
					gcDetails.add(event);
				} else {
					unavailable.add(event);
				}
			}
			for (String event : Arrays.asList("jdk.GCConfiguration", "jdk.GCHeapConfiguration")) {
				if (available.contains(event)) {
					Object settings = configure(event, false);
					api.settings.getMethod("with", String.class, String.class).invoke(settings, "period", "beginChunk");
					gcConfiguration.add(event);
				} else {
					unavailable.add(event);
				}
			}
			if (allocations) {
				Object settings = configure("jdk.ObjectAllocationSample", true);
				api.settings.getMethod("with", String.class, String.class).invoke(settings, "throttle", "100/s");
			}
			oldObjects = configureOldObjects(oldObjectType);
			if (!oldObjects) { unavailable.add(OLD_OBJECT_EVENT + " (requires cutoff setting)"); }
			// The JVM captures these asynchronously. Do not walk stacks or allocate events in frame hooks.
			for (String event : Arrays.asList("jdk.ExecutionSample", "jdk.NativeMethodSample")) {
				if (available.contains(event)) {
					Object settings = configure(event, true);
					api.settings.getMethod("withPeriod", Duration.class)
							.invoke(settings, Duration.ofMillis(SAMPLE_PERIOD_MILLIS));
					sampled.add(event);
				} else {
					unavailable.add(event);
				}
			}
			for (String event : Arrays.asList("jdk.JavaMonitorEnter", "jdk.JavaMonitorWait", "jdk.ThreadPark",
					"jdk.ThreadSleep", "jdk.FileRead", "jdk.FileWrite", "jdk.SocketRead", "jdk.SocketWrite")) {
				if (available.contains(event)) {
					Object settings = configure(event, true);
					api.settings.getMethod("withThreshold", Duration.class)
							.invoke(settings, Duration.ofMillis(WAIT_THRESHOLD_MILLIS));
					waits.add(event);
				} else {
					unavailable.add(event);
				}
			}
			// GC does not account for every safepoint. Keep their boundaries without extra stack walks.
			for (String event : Arrays.asList("jdk.SafepointBegin", "jdk.SafepointStateSynchronization",
					"jdk.SafepointCleanup", "jdk.SafepointCleanupTask", "jdk.SafepointEnd", "jdk.ExecuteVMOperation")) {
				if (available.contains(event)) {
					configure(event, false);
					safepoints.add(event);
				} else {
					unavailable.add(event);
				}
			}
			api.recording.getMethod("start").invoke(recording);
			markClock(0);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			close();
			throw e;
		}
		allocationSampling = allocations;
		oldObjectSampling = oldObjects;
		dataLossMonitoring = lossMonitoring;
		profilingDetails = "JFR 使用磁盘仓库保留本次记录，关闭后由 JFR 释放临时块；仅采样期间有后台磁盘写入。"
				+ "\n执行/本地调用栈采样目标周期=" + SAMPLE_PERIOD_MILLIS + " ms；已启用=" + sampled
				+ "\n锁等待、线程等待及 I/O 事件阈值=" + WAIT_THRESHOLD_MILLIS + " ms；已启用=" + waits
				+ "\nSafepoint 边界/VM 操作事件=" + safepoints
				+ "\nGC 子阶段、引用统计及堆摘要事件=" + gcDetails
				+ "\nGC 配置事件（每个 JFR 数据块开始时）=" + gcConfiguration
				+ "\nGC 子阶段及堆摘要按 gcId 关联；子阶段可能嵌套，不能相加；并发阶段/完整回收周期不等同于停顿。"
				+ "主 CSV 与长帧重合统计仍只使用顶层 GCPhasePause；引用统计是数量，不是耗时。"
				+ "\nGCPhaseParallel 是各回收工作线程的重叠阶段，不得相加当作暂停；EvacuationInformation 记录搬迁字节与回收区域。"
				+ "G1EvacuationYoung/OldStatistics 没有 gcId 时按事件时间关联所属暂停，不表示新的暂停。"
				+ "\n上述 GC 细分由 JVM 记录，无额外调用栈、堆遍历或强制 GC；未产生某事件不等于其配置未启用。"
				+ "\n存活对象抽样=" + oldObjects + "；本记录请求 OldObjectSample 创建栈及 cutoff=0 ns，不请求 GC 根路径遍历或强制 GC。"
				+ "该抽样有运行开销，初始化/导出仍可能涉及 VM 停顿；JFR 启动在预热前，停止和导出在帧数据冻结后。"
				+ "\nOldObjectSample 的事件时间是样本导出时间；来源须看 allocationTime、objectAge 与创建栈。"
				+ "它是有限抽样，不能外推为全堆占比、泄漏证据或某次 GC 的精确搬迁量，也不能排除录制前创建的对象。"
				+ "\n当前 JVM 不支持的事件=" + unavailable;
	}

	private boolean configureOldObjects(Object type) throws ReflectiveOperationException {
		// Event availability alone is insufficient on an unfamiliar JVM: only request
		// samples when the public metadata exposes a cutoff that we can set explicitly.
		if (!hasCutoff(type)) { return false; }
		try {
			Object settings = configure(OLD_OBJECT_EVENT, true);
			api.settings.getMethod("with", String.class, String.class).invoke(settings, "cutoff", "0 ns");
			oldObjectsEnabled = true;
			return true;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
			// Never leave the event enabled with an unknown cutoff after a partial failure.
			api.recording.getMethod("disable", String.class).invoke(recording, OLD_OBJECT_EVENT);
			return false;
		}
	}

	private boolean hasCutoff(Object type) {
		if (type == null) { return false; }
		try {
			Method name = Class.forName("jdk.jfr.SettingDescriptor").getMethod("getName");
			for (Object setting : (List<?>) api.eventType.getMethod("getSettingDescriptors").invoke(type)) {
				if ("cutoff".equals(name.invoke(setting))) { return true; }
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
			// Older/other JVMs retain the existing GC and frame capture.
		}
		return false;
	}

	private Object configure(String name, boolean stack) throws ReflectiveOperationException {
		Object settings = api.recording.getMethod("enable", String.class).invoke(recording, name);
		api.settings.getMethod("withThreshold", Duration.class).invoke(settings, Duration.ZERO);
		api.settings.getMethod(stack ? "withStackTrace" : "withoutStackTrace").invoke(settings);
		return settings;
	}

	private void markClock(int phase) throws ReflectiveOperationException {
		Method create = api.factory.getMethod("newEvent");
		Method begin = api.event.getMethod("begin");
		Method set = api.event.getMethod("set", int.class, Object.class);
		Method end = api.event.getMethod("end");
		Method commit = api.event.getMethod("commit");
		for (int i = 0; i < 8; i++) {
			Object event = create.invoke(factory);
			long before = System.nanoTime();
			begin.invoke(event);
			long after = System.nanoTime();
			set.invoke(event, 0, before);
			set.invoke(event, 1, after);
			set.invoke(event, 2, phase);
			end.invoke(event);
			commit.invoke(event);
		}
	}

	Result finish(Path file) throws ReflectiveOperationException {
		try {
			markClock(1);
			// The caller freezes frame data before handing this to its background writer.
			// Keep the recording running for dump: on the tested JDK, stop-then-dump loses
			// OldObjectSample allocation stacks. Disable only our old-object event after
			// the snapshot, so stop does not emit those same samples a second time.
			api.recording.getMethod("dump", Path.class).invoke(recording, file);
			disableOldObjects();
			api.recording.getMethod("stop").invoke(recording);
		} finally {
			close();
		}
		return read(file, api, dataLossMonitoring, oldObjectSampling);
	}

	@Override
	public void close() {
		if (recording != null) {
			try {
				disableOldObjects();
			} catch (ReflectiveOperationException | RuntimeException ignored) {
			}
			try {
				api.recording.getMethod("close").invoke(recording);
			} catch (ReflectiveOperationException | RuntimeException ignored) {
			}
			recording = null;
		}
		if (factory != null) {
			try {
				api.factory.getMethod("unregister").invoke(factory);
			} catch (ReflectiveOperationException | RuntimeException ignored) {
			}
			factory = null;
		}
	}

	private void disableOldObjects() throws ReflectiveOperationException {
		if (oldObjectsEnabled) {
			api.recording.getMethod("disable", String.class).invoke(recording, OLD_OBJECT_EVENT);
			oldObjectsEnabled = false;
		}
	}

	private static Result read(Path path, Api api, boolean dataLossMonitoring, boolean oldObjectSampling) throws ReflectiveOperationException {
		List<RawPause> pauses = new ArrayList<>();
		List<RawSample> samples = new ArrayList<>();
		long lostBytes = 0;
		int lossEvents = 0;
		int oldObjectSamples = 0;
		Anchor[] anchors = new Anchor[2];
		Class<?> fileType = Class.forName("jdk.jfr.consumer.RecordingFile");
		Class<?> recordedEvent = Class.forName("jdk.jfr.consumer.RecordedEvent");
		Class<?> recordedObject = Class.forName("jdk.jfr.consumer.RecordedObject");
		Method more = fileType.getMethod("hasMoreEvents");
		Method next = fileType.getMethod("readEvent");
		Method type = recordedEvent.getMethod("getEventType");
		Method name = api.eventType.getMethod("getName");
		Method start = recordedEvent.getMethod("getStartTime");
		Method end = recordedEvent.getMethod("getEndTime");
		Method number = recordedObject.getMethod("getLong", String.class);
		Method integer = recordedObject.getMethod("getInt", String.class);
		Method string = recordedObject.getMethod("getString", String.class);
		Method sampledThread = recordedObject.getMethod("getThread", String.class);
		Method threadId = Class.forName("jdk.jfr.consumer.RecordedThread").getMethod("getJavaThreadId");
		Object file = fileType.getConstructor(Path.class).newInstance(path);
		try {
			while ((Boolean) more.invoke(file)) {
				Object event = next.invoke(file);
				String eventName = (String) name.invoke(type.invoke(event));
				if (CLOCK_EVENT.equals(eventName)) {
					long before = (Long) number.invoke(event, "beforeNanos");
					long after = (Long) number.invoke(event, "afterNanos");
					int phase = (Integer) integer.invoke(event, "phase");
					if (phase >= 0 && phase < anchors.length && after >= before) {
						Anchor anchor = new Anchor((Instant) start.invoke(event), before, after);
						if (anchors[phase] == null || anchor.span < anchors[phase].span) {
							anchors[phase] = anchor;
						}
					}
				} else if ("jdk.GCPhasePause".equals(eventName)) {
					pauses.add(new RawPause((Instant) start.invoke(event), (Instant) end.invoke(event),
							(Integer) integer.invoke(event, "gcId"), (String) string.invoke(event, "name")));
				} else if ("jdk.ExecutionSample".equals(eventName) || "jdk.NativeMethodSample".equals(eventName)) {
					Object thread = sampledThread.invoke(event, "sampledThread");
					samples.add(new RawSample(eventName.equals("jdk.ExecutionSample") ? 0 : 1,
							(Instant) start.invoke(event), thread == null ? -1 : (Long) threadId.invoke(thread)));
				} else if ("jdk.DataLoss".equals(eventName)) {
					lossEvents++;
					lostBytes += (Long) number.invoke(event, "amount");
				} else if (OLD_OBJECT_EVENT.equals(eventName)) {
					// These are emitted on export, outside the measured frame interval. Do not
					// filter them by event time or count them as per-frame allocation samples.
					oldObjectSamples++;
				}
			}
		} finally {
			fileType.getMethod("close").invoke(file);
		}
		if (anchors[0] == null || anchors[1] == null) {
			throw new IllegalStateException("Missing JFR clock markers; GC alignment unavailable");
		}
		Anchor first = anchors[0];
		Anchor last = anchors[1];
		long drift = (last.midpoint - first.midpoint) - Duration.between(first.time, last.time).toNanos();
		double uncertainty = (Math.max(first.span, last.span) / 2.0 + Math.abs(drift) + 1000.0) / 1_000_000.0;
		Result result = new Result(first, uncertainty, drift / 1_000_000.0);
		result.samples.addAll(samples);
		result.dataLossMonitoring = dataLossMonitoring;
		result.lossEvents = lossEvents;
		result.lostBytes = lostBytes;
		result.oldObjectSampling = oldObjectSampling;
		result.oldObjectSamples = oldObjectSamples;
		// Reject a failed calibration instead of silently claiming that no GC overlapped a long frame.
		if (result.aligned) {
			for (RawPause pause : pauses) {
				result.pauses.add(new Pause(first.toNanos(pause.start), first.toNanos(pause.end), pause.id, pause.name));
			}
			result.pauses.sort((a, b) -> Long.compare(a.start, b.start));
		}
		return result;
	}

	static final class Result {
		final List<Pause> pauses = new ArrayList<>();
		private final List<RawSample> samples = new ArrayList<>();
		boolean dataLossMonitoring;
		int lossEvents;
		long lostBytes;
		boolean oldObjectSampling;
		int oldObjectSamples;
		final Anchor anchor;
		final double uncertaintyMillis;
		final double driftMillis;
		final boolean aligned;

		Result(Anchor anchor, double uncertaintyMillis, double driftMillis) {
			this.anchor = anchor;
			this.uncertaintyMillis = uncertaintyMillis;
			this.driftMillis = driftMillis;
			aligned = uncertaintyMillis <= 2.0;
		}

		String coverageReport(long start, long end, long owner) {
			StringBuilder out = new StringBuilder("JFR 数据丢失监测=")
					.append(dataLossMonitoring ? "available" : "unavailable")
					.append("；整份记录丢失事件=").append(lossEvents).append("；丢失字节=").append(lostBytes);
			if (lossEvents > 0 || !dataLossMonitoring) {
				out.append("\nJFR 数据可能不完整；未记录到 GC 或等待不能用于排除这些原因。");
			}
			out.append("\n存活对象抽样启用=").append(oldObjectSampling)
					.append("；整份记录 OldObjectSample 事件数=").append(oldObjectSamples)
					.append("；类型、年龄与创建栈保存在 JFR 中，样本数不代表对象总数；没有样本也不能排除存活对象负担。");
			if (!aligned || end <= start) { return out.toString(); }
			for (int kind = 0; kind < 2; kind++) {
				List<Long> times = new ArrayList<>();
				int main = 0;
				for (RawSample sample : samples) {
					if (sample.kind != kind) { continue; }
					long time = anchor.toNanos(sample.time);
					if (time < start || time >= end) { continue; }
					times.add(time);
					if (sample.thread == owner) { main++; }
				}
				Collections.sort(times);
				out.append("\n区间调用栈覆盖=").append(kind == 0 ? "jdk.ExecutionSample" : "jdk.NativeMethodSample")
						.append("；全部线程样本=").append(times.size()).append("；主线程样本=").append(main);
				if (times.isEmpty()) { out.append("；无样本，不能据此排除该类工作"); continue; }
				long previous = start, gap = 0;
				for (long time : times) { gap = Math.max(gap, time - previous); previous = time; }
				gap = Math.max(gap, end - previous);
				out.append("；首样本_ms=").append((times.get(0) - start) / 1_000_000.0)
						.append("；末样本_ms=").append((previous - start) / 1_000_000.0)
						.append("；最大无样本间隔_ms=").append(gap / 1_000_000.0);
			}
			return out.append("\n覆盖统计仅说明保留了哪些样本；无丢失事件不等于每次停顿都有调用栈。无样本间隔也可能来自线程状态或 JVM 采样机制。")
					.toString();
		}
	}

	private static final class RawSample {
		final int kind;
		final Instant time;
		final long thread;
		RawSample(int kind, Instant time, long thread) { this.kind = kind; this.time = time; this.thread = thread; }
	}

	static final class Pause {
		final long start, end;
		final int id;
		final String name;

		Pause(long start, long end, int id, String name) {
			this.start = start;
			this.end = end;
			this.id = id;
			this.name = name;
		}
	}

	static final class Anchor {
		final Instant time;
		final long midpoint, span;

		Anchor(Instant time, long before, long after) {
			this.time = time;
			span = after - before;
			midpoint = before + span / 2;
		}

		long toNanos(Instant value) {
			return midpoint + Duration.between(time, value).toNanos();
		}

		Instant toInstant(long nanos) {
			return time.plusNanos(nanos - midpoint);
		}
	}

	private static final class RawPause {
		final Instant start, end;
		final int id;
		final String name;

		RawPause(Instant start, Instant end, int id, String name) {
			this.start = start;
			this.end = end;
			this.id = id;
			this.name = name;
		}
	}

	private static final class Api {
		final Class<?> flightRecorder = Class.forName("jdk.jfr.FlightRecorder");
		final Class<?> recording = Class.forName("jdk.jfr.Recording");
		final Class<?> factory = Class.forName("jdk.jfr.EventFactory");
		final Class<?> event = Class.forName("jdk.jfr.Event");
		final Class<?> eventType = Class.forName("jdk.jfr.EventType");
		final Class<?> settings = Class.forName("jdk.jfr.EventSettings");
		final Class<?> annotation = Class.forName("jdk.jfr.AnnotationElement");
		final Class<?> descriptor = Class.forName("jdk.jfr.ValueDescriptor");

		Api() throws ClassNotFoundException {
		}
	}
}
