package net.coderbot.iris.diagnostics;

import java.util.Arrays;
import java.util.function.LongSupplier;

/** Inclusive wall times. WORLD/SHADOW are inside RENDER, and SWAP is inside DISPLAY. */
final class FrameTimePhases {
	static final int COUNT = 8;
	static final String CSV_COLUMNS = "tasks_ms,tick_ms,render_ms,world_ms,shadow_ms,display_ms,swap_ms,limiter_ms";
	final long[][] samples;
	final long[][] cpuSamples = new long[COUNT][];
	final boolean[] valid;
	private final LongSupplier cpuClock;
	private final long[] cpuStarts = new long[COUNT];
	private final long[] cpuTotals = new long[COUNT];
	private final boolean[] called = new boolean[COUNT];
	private final long[] starts = new long[COUNT];
	private final long[] totals = new long[COUNT];
	private final int[] depth = new int[COUNT];
	private boolean balanced = true;

	FrameTimePhases(int capacity) {
		this(capacity, () -> -1);
	}

	FrameTimePhases(int capacity, LongSupplier cpuClock) {
		samples = new long[COUNT][capacity];
		valid = new boolean[capacity];
		this.cpuClock = cpuClock;
		for (int phase = FrameTimeRecorder.RENDER; phase <= FrameTimeRecorder.SHADOW; phase++) {
			cpuSamples[phase] = new long[capacity];
		}
	}

	void begin(int phase, long now) {
		if (depth[phase]++ == 0) {
			starts[phase] = now;
			called[phase] = true;
			if (cpuSamples[phase] != null) { cpuStarts[phase] = cpuClock.getAsLong(); }
		}
	}

	void end(int phase, long now) {
		if (depth[phase] == 0) {
			balanced = false;
		} else if (--depth[phase] == 0) {
			if (cpuSamples[phase] != null) {
				long end = cpuClock.getAsLong();
				if (cpuStarts[phase] < 0 || end < cpuStarts[phase]) { cpuTotals[phase] = -1; }
				else if (cpuTotals[phase] >= 0) { cpuTotals[phase] += end - cpuStarts[phase]; }
			}
			if (now < starts[phase]) {
				balanced = false;
			} else {
				totals[phase] += now - starts[phase];
			}
		}
	}

	/** A negative index discards warmup/first-boundary data, including partial phases. */
	void finishFrame(int index, long duration) {
		if (index >= 0) {
			for (int phase = 0; phase < COUNT; phase++) {
				samples[phase][index] = totals[phase];
				if (cpuSamples[phase] != null) {
					cpuSamples[phase][index] = called[phase] && depth[phase] == 0 ? cpuTotals[phase] : -1;
				}
				balanced &= depth[phase] == 0 && totals[phase] <= duration;
			}
			long topLevel = totals[0] + totals[1] + totals[2] + totals[5] + totals[7];
			valid[index] = balanced && topLevel <= duration;
		}
		Arrays.fill(totals, 0);
		Arrays.fill(depth, 0);
		Arrays.fill(cpuTotals, 0);
		Arrays.fill(called, false);
		balanced = true;
	}
}
