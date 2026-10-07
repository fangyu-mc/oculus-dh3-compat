package net.coderbot.iris.diagnostics;

import java.util.Arrays;

/** Preallocated frame buffer. Render thread writes; the export worker reads after capture ends. */
final class FrameTimeCapture {
	static final int NO_WORLD = 1;
	static final int UNFOCUSED = 2;
	static final int SCREEN_OPEN = 4;
	static final int PAUSED = 8;
	final long[] ends;
	final byte[] flags;
	final long durationNanos;
	final long warmupUntil;
	long start;
	long previous;
	int previousFlags;
	int count;
	boolean started;

	FrameTimeCapture(int seconds, long warmupUntil) {
		this.durationNanos = seconds * 1_000_000_000L;
		this.warmupUntil = warmupUntil;
		ends = new long[seconds * 2048];
		flags = new byte[ends.length];
	}

	boolean record(long now, int state) {
		if (now < warmupUntil) {
			return false;
		}
		if (!started) {
			started = true;
			start = previous = now;
			previousFlags = state;
			return false;
		}
		if (now <= previous) {
			return false;
		}
		if (count == ends.length) {
			return true;
		}
		ends[count] = now;
		// Exclude intervals touching either a menu/focus transition from the gameplay summary.
		// Keep every interval in the CSV, including those excluded from this summary.
		flags[count] = (byte) (state | previousFlags);
		count++;
		previous = now;
		previousFlags = state;
		return now - start >= durationNanos || count == ends.length;
	}

	Statistics statistics() {
		long[] eligible = new long[count];
		int size = 0;
		long last = start;
		for (int i = 0; i < count; i++) {
			if (flags[i] == 0) {
				eligible[size++] = ends[i] - last;
			}
			last = ends[i];
		}
		return new Statistics(Arrays.copyOf(eligible, size));
	}

	static final class Statistics {
		final int frames;
		final double averageFps;
		final double lowOnePercentFps;
		final double p99Millis;
		final double maxMillis;
		final int over50Millis;

		Statistics(long[] durations) {
			frames = durations.length;
			Arrays.sort(durations);
			long total = 0;
			int longFrames = 0;
			for (long duration : durations) {
				total += duration;
				if (duration > 50_000_000L) {
					longFrames++;
				}
			}
			over50Millis = longFrames;
			averageFps = total == 0 ? 0 : frames * 1_000_000_000.0D / total;
			int slowCount = (frames + 99) / 100;
			long slowTotal = 0;
			for (int i = frames - slowCount; i < frames; i++) {
				slowTotal += durations[i];
			}
			lowOnePercentFps = slowTotal == 0 ? 0 : slowCount * 1_000_000_000.0D / slowTotal;
			p99Millis = frames == 0 ? 0 : durations[(int) Math.ceil(frames * 0.99D) - 1] / 1_000_000.0D;
			maxMillis = frames == 0 ? 0 : durations[frames - 1] / 1_000_000.0D;
		}
	}
}
