package net.coderbot.iris.diagnostics;

public final class FrameTimeCaptureCheck {
	public static void main(String[] args) throws Exception {
		// The disabled frame hook must return without requesting a Minecraft instance.
		FrameTimeRecorder.onFrame();
		FrameTimeRecorder.beginPhase(FrameTimeRecorder.RENDER);
		FrameTimeRecorder.endPhase(FrameTimeRecorder.RENDER);
		FrameTimeCapture capture = new FrameTimeCapture(120, 1_000_000_000L);
		check(!capture.record(0, 0) && !capture.started, "warmup recorded");
		long now = 1_000_000_000L;
		check(!capture.record(now, 0) && capture.count == 0, "first boundary became a frame");
		for (int i = 0; i < 1000; i++) {
			now += i % 100 == 0 ? 100_000_000L : 10_000_000L;
			check(!capture.record(now, 0), "premature timeout");
		}
		FrameTimeCapture.Statistics stats = capture.statistics();
		check(stats.frames == 1000 && stats.over50Millis == 10, "frame counts");
		close(stats.averageFps, 1000.0D / 10.9D);
		close(stats.lowOnePercentFps, 10.0D);
		close(stats.p99Millis, 10.0D);
		close(stats.maxMillis, 100.0D);
		capture.record(now, 0);
		check(capture.count == 1000, "duplicate timestamp added");

		FrameTimeCapture transitions = new FrameTimeCapture(1, 0);
		transitions.record(0, 0);
		transitions.record(10_000_000L, 0);
		transitions.record(20_000_000L, FrameTimeCapture.UNFOCUSED);
		transitions.record(30_000_000L, 0);
		transitions.record(40_000_000L, 0);
		check(transitions.count == 4 && transitions.statistics().frames == 2, "focus transitions not excluded");
		close(transitions.statistics().averageFps, 100.0D);
		check(transitions.record(1_000_000_000L, FrameTimeCapture.NO_WORLD | FrameTimeCapture.SCREEN_OPEN), "timeout missing");
		check(transitions.statistics().frames == 2, "menu/unload interval entered summary");

		FrameTimeCapture full = new FrameTimeCapture(1, 0);
		full.record(0, 0);
		for (int i = 1; i <= full.ends.length; i++) {
			check(full.record(i, 0) == (i == full.ends.length), "buffer cap");
		}
		check(full.record(full.ends.length + 1, 0), "full buffer did not stop");
		check(new FrameTimeCapture(1, 0).statistics().frames == 0, "empty capture");
		close(new FrameTimeCapture.Statistics(new long[]{20_000_000L}).lowOnePercentFps, 50.0D);

		FrameTimePhases phases = new FrameTimePhases(10);
		phases.begin(FrameTimeRecorder.RENDER, 0);
		phases.finishFrame(-1, 5); // A command may start inside an existing frame/phase.
		phases.begin(FrameTimeRecorder.TICK, 10);
		phases.end(FrameTimeRecorder.TICK, 12);
		phases.begin(FrameTimeRecorder.TICK, 12);
		phases.end(FrameTimeRecorder.TICK, 14);
		phases.begin(FrameTimeRecorder.RENDER, 15);
		phases.begin(FrameTimeRecorder.WORLD, 17);
		phases.begin(FrameTimeRecorder.SHADOW, 19);
		phases.end(FrameTimeRecorder.SHADOW, 22);
		phases.end(FrameTimeRecorder.WORLD, 24);
		phases.end(FrameTimeRecorder.RENDER, 25);
		phases.begin(FrameTimeRecorder.DISPLAY, 25);
		phases.begin(FrameTimeRecorder.SWAP, 26);
		phases.end(FrameTimeRecorder.SWAP, 28);
		phases.end(FrameTimeRecorder.DISPLAY, 29);
		phases.finishFrame(0, 20);
		check(phases.valid[0], "nested inclusive phases incorrectly summed together");
		check(phases.samples[FrameTimeRecorder.TICK][0] == 4, "multiple client ticks not accumulated");
		check(phases.samples[FrameTimeRecorder.RENDER][0] == 10
				&& phases.samples[FrameTimeRecorder.WORLD][0] == 7
				&& phases.samples[FrameTimeRecorder.SHADOW][0] == 3, "phase boundaries");
		phases.begin(FrameTimeRecorder.RENDER, 30);
		phases.begin(FrameTimeRecorder.RENDER, 31);
		phases.end(FrameTimeRecorder.RENDER, 32);
		phases.end(FrameTimeRecorder.RENDER, 34);
		phases.finishFrame(1, 10);
		check(phases.valid[1] && phases.samples[FrameTimeRecorder.RENDER][1] == 4, "reentrant phase double-counted");
		phases.end(FrameTimeRecorder.TICK, 42);
		phases.finishFrame(2, 10);
		check(!phases.valid[2], "unmatched phase end not rejected");
		phases.begin(FrameTimeRecorder.RENDER, 50);
		phases.finishFrame(3, 10);
		check(!phases.valid[3], "open phase not rejected");
		phases.finishFrame(4, 10);
		check(phases.valid[4] && phases.samples[FrameTimeRecorder.RENDER][4] == 0, "phase state leaked across frames");
		System.out.println("Frame-time capture: statistics, transitions, warmup, capacity, disabled hooks, nested/multiple phases and unmatched-phase recovery passed.");
	}

	private static void close(double actual, double expected) {
		check(Math.abs(actual - expected) < 1e-8D, actual + " != " + expected);
	}

	private static void check(boolean value, String message) {
		if (!value) {
			throw new AssertionError(message);
		}
	}
}
