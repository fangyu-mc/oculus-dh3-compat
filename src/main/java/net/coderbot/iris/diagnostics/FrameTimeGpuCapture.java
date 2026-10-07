package net.coderbot.iris.diagnostics;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Capture-only timestamp ring. All driver access belongs to the creating/render thread. */
final class FrameTimeGpuCapture {
	interface Driver {
		int timestampBits();
		int create();
		void timestamp(int query);
		boolean available(int query);
		long result(int query);
		void delete(int query);
	}

	private static final int RENDER_BEGIN = 0, WORLD_BEGIN = 1, SHADOW_BEGIN = 2;
	private static final int SHADOW_END = 3, WORLD_END = 4, RENDER_END = 5, BEFORE_SWAP = 6;
	private static final int MARKERS = 7;
	private static final int[][] RANGES = {{RENDER_BEGIN, RENDER_END}, {WORLD_BEGIN, WORLD_END},
			{SHADOW_BEGIN, SHADOW_END}, {RENDER_BEGIN, BEFORE_SWAP}};
	private static final String[] STATUS_NAMES = {"no_render", "ready", "ring_full", "incomplete_phases",
			"pending_at_stop", "query_error", "unsupported", "invalid_timestamps", "pending"};
	private static final byte READY = 1, FULL = 2, INCOMPLETE = 3, TAIL = 4, ERROR = 5, UNSUPPORTED = 6, INVALID = 7, PENDING = 8;
	private static final int MIN_AGE = 2, MAX_HARVEST = 4;
	private final Thread owner = Thread.currentThread();
	private final Driver driver;
	private final Slot[] ring;
	final byte[] statuses;
	final int[] masks, resultAges;
	final long[][] samples;
	final long[] resultDelays, probeNanos;
	private int bits, head, pending, current = -1, serial;
	private long frameProbeNanos;
	private boolean attempted, enabled, closed;
	private byte unavailable = UNSUPPORTED;
	String status = "unavailable";

	FrameTimeGpuCapture(int capacity, int ringSize, Driver driver) {
		this.driver = driver;
		ring = new Slot[ringSize];
		statuses = new byte[capacity]; masks = new int[capacity]; resultAges = new int[capacity];
		samples = new long[RANGES.length][capacity];
		resultDelays = new long[capacity]; probeNanos = new long[capacity];
		try {
			bits = driver.timestampBits();
			if (bits <= 0 || bits > 64) { status = "unsupported (GL timestamp counter unavailable)"; return; }
			for (int i = 0; i < ring.length; i++) {
				Slot slot = ring[i] = new Slot();
				for (int marker = 0; marker < MARKERS; marker++) {
					int query = driver.create();
					if (query == 0) { throw new IllegalStateException("zero query name"); }
					slot.queries[marker] = query;
				}
			}
			// Prime every query before the ten-second warmup. Do not reuse them until ready.
			for (Slot slot : ring) {
				for (int marker = 0; marker < MARKERS; marker++) { driver.timestamp(slot.queries[marker]); }
				slot.mask = (1 << MARKERS) - 1;
				slot.serial = -MIN_AGE;
			}
			pending = ring.length;
			enabled = true;
			status = "available (GL_TIMESTAMP, " + bits + " bits, ring=" + ring.length + ")";
		} catch (RuntimeException | LinkageError e) { disable(e); }
	}

	void beginPhase(int phase) {
		if (!usable()) { return; }
		int marker = phase == FrameTimeRecorder.RENDER ? RENDER_BEGIN
				: phase == FrameTimeRecorder.WORLD ? WORLD_BEGIN
				: phase == FrameTimeRecorder.SHADOW ? SHADOW_BEGIN
				: phase == FrameTimeRecorder.SWAP ? BEFORE_SWAP : -1;
		if (marker < 0) { return; }
		long start = System.nanoTime();
		try {
			if (marker == RENDER_BEGIN && !attempted) {
				attempted = true;
				if (pending == ring.length) { return; }
				current = (head + pending++) % ring.length;
				ring[current].reset();
				ring[current].issuedAt = start;
			}
			mark(marker);
		} catch (RuntimeException | LinkageError e) { disable(e); }
		finally { frameProbeNanos += System.nanoTime() - start; }
	}

	void endPhase(int phase) {
		if (!usable()) { return; }
		int marker = phase == FrameTimeRecorder.RENDER ? RENDER_END
				: phase == FrameTimeRecorder.WORLD ? WORLD_END
				: phase == FrameTimeRecorder.SHADOW ? SHADOW_END : -1;
		if (marker < 0) { return; }
		long start = System.nanoTime();
		try { mark(marker); }
		catch (RuntimeException | LinkageError e) { disable(e); }
		finally { frameProbeNanos += System.nanoTime() - start; }
	}

	private boolean usable() { return enabled && !closed && Thread.currentThread() == owner; }

	private void mark(int marker) {
		if (current < 0) { return; }
		Slot slot = ring[current];
		// Repeated, reentrant or reordered phases cannot be represented by a single pair.
		if (marker <= slot.lastMarker) { slot.malformed = true; return; }
		driver.timestamp(slot.queries[marker]);
		slot.mask |= 1 << marker;
		slot.lastMarker = marker;
	}

	/** Called before the CPU frame boundary, so query polling overhead remains in that frame. */
	void finishFrame(int index) {
		if (closed || Thread.currentThread() != owner) { return; }
		long start = System.nanoTime();
		try {
			if (enabled) {
				if (current >= 0) {
					Slot slot = ring[current];
					slot.frame = index;
					slot.serial = serial;
					slot.submittedAt = start;
					if (index >= 0) { statuses[index] = PENDING; masks[index] = slot.mask; }
				} else if (index >= 0) { statuses[index] = attempted ? FULL : 0; }
				current = -1;
				collect(MAX_HARVEST, false);
			} else if (index >= 0) { statuses[index] = unavailable; }
		} catch (RuntimeException | LinkageError e) { disable(e); if (index >= 0) { statuses[index] = ERROR; } }
		finally {
			if (index >= 0) { probeNanos[index] = frameProbeNanos + System.nanoTime() - start; }
			frameProbeNanos = 0;
			attempted = false;
			current = -1;
			serial++;
		}
	}

	private void collect(int limit, boolean stopping) {
		for (int count = 0; count < limit && pending > 0; count++) {
			Slot slot = ring[head];
			if (!stopping && serial - slot.serial < MIN_AGE) { return; }
			// Check every submitted marker first. Never call RESULT on an unavailable query.
			for (int marker = MARKERS - 1; marker >= 0; marker--) {
				if ((slot.mask & (1 << marker)) != 0 && !driver.available(slot.queries[marker])) { return; }
			}
			if (slot.frame >= 0) {
				for (int marker = 0; marker < MARKERS; marker++) {
					if ((slot.mask & (1 << marker)) != 0) { slot.values[marker] = driver.result(slot.queries[marker]); }
				}
				resolve(slot);
			}
			pending--;
			head = (head + 1) % ring.length;
		}
	}

	private void resolve(Slot slot) {
		int frame = slot.frame;
		resultAges[frame] = serial - slot.serial;
		resultDelays[frame] = System.nanoTime() - slot.submittedAt;
		int required = (1 << RENDER_BEGIN) | (1 << RENDER_END) | (1 << BEFORE_SWAP);
		boolean paired = (slot.mask & required) == required;
		for (int[] range : RANGES) {
			paired &= ((slot.mask >> range[0]) & 1) == ((slot.mask >> range[1]) & 1);
		}
		if (slot.malformed || !paired) { statuses[frame] = INCOMPLETE; return; }
		// Validate monotonicity in issue order, allowing one wrap of a sub-64-bit counter.
		long total = delta(slot.values[RENDER_BEGIN], slot.values[BEFORE_SWAP]);
		long upperBound = System.nanoTime() - slot.issuedAt;
		if (total < 0 || total > upperBound + 1_000_000L
				|| bits < 63 && upperBound >= (1L << bits)) { statuses[frame] = INVALID; return; }
		long previous = 0;
		for (int marker = 0; marker < MARKERS; marker++) {
			if ((slot.mask & (1 << marker)) == 0) { continue; }
			long offset = delta(slot.values[RENDER_BEGIN], slot.values[marker]);
			if (offset < previous || offset > total || total < 0) { statuses[frame] = INVALID; return; }
			previous = offset;
		}
		for (int range = 0; range < RANGES.length; range++) {
			int[] ends = RANGES[range];
			samples[range][frame] = (slot.mask & (1 << ends[0])) == 0 ? -1 : delta(slot.values[ends[0]], slot.values[ends[1]]);
		}
		statuses[frame] = READY;
	}

	private long delta(long start, long end) {
		long duration = end - start;
		return bits == 64 ? duration : duration & ((1L << bits) - 1);
	}

	private void disable(Throwable failure) {
		enabled = false;
		unavailable = ERROR;
		status = "query_error (" + failure.getClass().getSimpleName() + ")";
		for (Slot slot : ring) {
			if (slot != null && slot.frame >= 0 && statuses[slot.frame] == PENDING) { statuses[slot.frame] = ERROR; }
		}
	}

	/** Never called by the file writer: pending queries may be deleted without waiting for results. */
	void close() {
		if (closed || Thread.currentThread() != owner) { return; }
		try {
			if (enabled) {
				// A manual stop can occur inside a partial frame; never attach it to a recorded frame.
				if (current >= 0) { ring[current].frame = -1; }
				collect(ring.length, true);
			}
		} catch (RuntimeException | LinkageError e) { disable(e); }
		finally {
			for (Slot slot : ring) {
				if (slot == null) { continue; }
				if (slot.frame >= 0 && statuses[slot.frame] == PENDING) { statuses[slot.frame] = TAIL; }
				for (int query : slot.queries) {
					if (query == 0) { continue; }
					try { driver.delete(query); }
					catch (RuntimeException | LinkageError e) { status = "cleanup_error (" + e.getClass().getSimpleName() + ")"; }
				}
			}
			closed = true;
		}
	}

	String save(Path directory, String name, FrameTimeCapture frames, FrameTimePhases phases) throws IOException {
		if (!closed) { throw new IllegalStateException("GPU capture is not sealed"); }
		int[] counts = new int[STATUS_NAMES.length];
		try (BufferedWriter out = Files.newBufferedWriter(directory.resolve(name + ".render.csv"), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
			out.write("frame,start_ms,end_ms,flags,phases_valid,render_cpu_ms,world_cpu_ms,shadow_cpu_ms,gpu_status,gpu_marker_mask,"
					+ "gpu_render_ms,gpu_world_ms,gpu_shadow_ms,gpu_render_to_swap_ms,gpu_result_age_frames,gpu_result_observed_after_ms,gpu_probe_ms\n");
			long previous = frames.start;
			for (int i = 0; i < frames.count; i++) {
				out.write(i + "," + millis(previous - frames.start) + "," + millis(frames.ends[i] - frames.start)
						+ "," + frames.flags[i] + "," + (phases.valid[i] ? 1 : 0));
				for (int phase = FrameTimeRecorder.RENDER; phase <= FrameTimeRecorder.SHADOW; phase++) {
					out.write("," + (phases.valid[i] && phases.cpuSamples[phase][i] >= 0 ? millis(phases.cpuSamples[phase][i]) : ""));
				}
				out.write("," + STATUS_NAMES[statuses[i]] + "," + masks[i]);
				for (long[] range : samples) { out.write("," + (statuses[i] == READY && range[i] >= 0 ? millis(range[i]) : "")); }
				boolean resolved = statuses[i] == READY || statuses[i] == INCOMPLETE || statuses[i] == INVALID;
				out.write("," + (resolved ? resultAges[i] : "") + "," + (resolved ? millis(resultDelays[i]) : "")
						+ "," + millis(probeNanos[i]) + "\n");
				counts[statuses[i]]++;
				previous = frames.ends[i];
			}
		}
		StringBuilder report = new StringBuilder("渲染 CPU/GPU 诊断=" + name + ".render.csv；GPU 查询=" + status + "\n");
		report.append("frame 从 0 对齐主 CSV 的全部 frame 行（不包含 gc 行），flags 过滤方式不变；本文件不会改变 low 帧统计定义。\n")
				.append("render/world/shadow_cpu_ms 为主线程 CPU 时间，阶段嵌套不可相加；CPU 分辨率取决于 JVM/系统，短区间可能为零或超过墙钟时间，不能单独据此认定等待。未执行、未配对或时钟不可用时留空。\n")
				.append("GPU 为 GL_TIMESTAMP 标记间隔：render=游戏渲染，world=世界渲染，shadow=阴影，render_to_swap=开始渲染至调用交换缓冲前（含最终画面拷贝和队列）。区间嵌套不可相加。\n")
				.append("GPU 时间戳可能包含 GPU 等待 CPU 提交的空档，不等于纯 GPU 忙碌时间；不含交换缓冲后的显示过程，不是屏幕实际呈现时间，不能把 swap 等待直接归因为 GPU 算力或驱动。\n")
				.append("每帧最多回收 4 组，通常延后至少 2 帧且全部 query RESULT_AVAILABLE 才读结果；ring 满则跳样，无 glFinish/glFlush、fence 等待或 TIME_ELAPSED target。仅采集时启用，与其他计时 query 共存。\n")
				.append("gpu_result_age_frames/observed_after_ms 是首次观察到结果就绪时距离提交结束的帧数/时间，包含刻意延后和轮询，属于上界，不能当作精确 GPU 延迟。\n")
				.append("gpu_probe_ms 为该帧时间戳提交与回收的 CPU 墙钟开销（也可能含 GC/调度暂停），已包含在原始帧时间里；查询仍可能影响驱动调度，不应将采样开销误判为游戏逻辑。\n")
				.append("gpu_marker_mask 的位 0..6 对应 render_begin,world_begin,shadow_begin,shadow_end,world_end,render_end,before_swap。GPU 不支持、队列满、阶段缺失/重复、异常或结束时未就绪都留空，不写成 0；未执行 world/shadow 也留空。\n");
		for (int i = 0; i < counts.length; i++) { report.append(STATUS_NAMES[i]).append('=').append(counts[i]).append(' '); }
		return report.append('\n').toString();
	}

	private static double millis(long value) { return value / 1_000_000.0D; }

	private static final class Slot {
		final int[] queries = new int[MARKERS];
		final long[] values = new long[MARKERS];
		int mask, lastMarker = -1, frame = -1, serial;
		long issuedAt, submittedAt;
		boolean malformed;
		void reset() { mask = 0; lastMarker = -1; frame = -1; malformed = false; }
	}
}
