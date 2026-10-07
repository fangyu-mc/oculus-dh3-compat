package net.coderbot.iris.diagnostics;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Pure JVM driver contract: no GL context, game, native query or window is created. */
public final class FrameTimeGpuCheck {
	public static void main(String[] args) throws Exception {
		asyncAndFrameOwnership();
		boundedRingAndUnavailableResults();
		partialStopAndPairing();
		failuresAndCleanup();
		counterValidation();
		cpuClockAndExport();
		System.out.println("Frame GPU/CPU diagnostics: delayed nonblocking readback, bounded ring, frame ownership, missing phases, stop, failures, clock wrap, CPU nesting and worker export passed (no GL context).");
	}

	private static void asyncAndFrameOwnership() throws Exception {
		Fake driver = new Fake();
		FrameTimeGpuCapture gpu = started(driver, 4);
		int calls = driver.calls;
		Thread wrongThread = new Thread(() -> {
			render(gpu);
			gpu.finishFrame(0);
			gpu.close();
		});
		wrongThread.start(); wrongThread.join();
		check(driver.calls == calls, "driver accessed from a different thread");
		render(gpu); gpu.finishFrame(0);
		check(driver.reads == 0, "result read during submission frame");
		gpu.finishFrame(1);
		check(driver.reads == 0, "result read before minimum age");
		gpu.finishFrame(2);
		check(driver.reads == 7 && gpu.resultAges[0] == 2, "result age or submitted marker count");
		check(gpu.samples[0][0] == 50 && gpu.samples[1][0] == 30 && gpu.samples[2][0] == 10
				&& gpu.samples[3][0] == 60, "timestamps attached to wrong range/frame");
		gpu.close();
		int deletes = driver.deletes;
		gpu.close(); render(gpu); gpu.finishFrame(3);
		check(driver.deletes == deletes && deletes == driver.created, "close not idempotent or query leak");
		List<Map<String, String>> rows = export(gpu, 4, null);
		check(rows.get(0).get("gpu_status").equals("ready") && rows.get(0).get("gpu_result_age_frames").equals("2"), "ready export");
		check(rows.get(1).get("gpu_status").equals("no_render") && rows.get(1).get("gpu_render_ms").isEmpty(), "missing frame became zero GPU time");
	}

	private static void boundedRingAndUnavailableResults() throws Exception {
		Fake driver = new Fake();
		FrameTimeGpuCapture gpu = started(driver, 2);
		driver.ready = false;
		render(gpu); gpu.finishFrame(0);
		render(gpu); gpu.finishFrame(1);
		int issued = driver.timestamps;
		render(gpu); gpu.finishFrame(2);
		check(driver.timestamps == issued && driver.reads == 0, "full ring waited or overwrote pending queries");
		driver.ready = true;
		// Last query being ready does not allow a read if an earlier marker is unavailable.
		driver.unavailable = 2;
		gpu.finishFrame(3);
		check(driver.reads == 0, "read before all markers became ready");
		driver.unavailable = -1;
		gpu.finishFrame(4);
		check(driver.reads == 14, "pending frames were lost");
		driver.step = 30;
		render(gpu); gpu.finishFrame(5);
		gpu.close();
		check(gpu.samples[0][0] == 50 && gpu.samples[0][1] == 50 && gpu.samples[0][5] == 150,
				"query reuse changed old samples or shifted results across frames");
		List<Map<String, String>> rows = export(gpu, 6, null);
		check(rows.get(2).get("gpu_status").equals("ring_full") && rows.get(2).get("gpu_render_ms").isEmpty(), "ring-full sample exported as zero");
	}

	private static void partialStopAndPairing() throws Exception {
		Fake driver = new Fake();
		FrameTimeGpuCapture gpu = started(driver, 4);
		gpu.beginPhase(FrameTimeRecorder.RENDER);
		gpu.endPhase(FrameTimeRecorder.RENDER);
		gpu.beginPhase(FrameTimeRecorder.SWAP);
		gpu.finishFrame(0); // A menu frame can lack world/shadow phases.
		gpu.beginPhase(FrameTimeRecorder.RENDER);
		gpu.beginPhase(FrameTimeRecorder.WORLD);
		gpu.endPhase(FrameTimeRecorder.RENDER);
		gpu.beginPhase(FrameTimeRecorder.SWAP);
		gpu.finishFrame(1);
		gpu.beginPhase(FrameTimeRecorder.RENDER);
		gpu.beginPhase(FrameTimeRecorder.RENDER); // Do not overwrite the first query.
		gpu.endPhase(FrameTimeRecorder.RENDER);
		gpu.beginPhase(FrameTimeRecorder.SWAP);
		gpu.finishFrame(2);
		gpu.beginPhase(FrameTimeRecorder.RENDER); // Manual stop in an unfinished frame.
		gpu.close();
		List<Map<String, String>> rows = export(gpu, 3, null);
		check(rows.get(0).get("gpu_status").equals("ready") && rows.get(0).get("gpu_world_ms").isEmpty(), "absent world not marked unavailable");
		check(rows.get(1).get("gpu_status").equals("incomplete_phases") && rows.get(1).get("gpu_render_ms").isEmpty(), "partial world accepted");
		check(rows.get(2).get("gpu_status").equals("incomplete_phases"), "duplicate phase accepted");
		check(driver.reads == 10 && driver.created == driver.deletes, "partial manual stop read or leaked a query");

		Fake tailDriver = new Fake();
		FrameTimeGpuCapture tail = started(tailDriver, 2);
		tailDriver.ready = false;
		render(tail); tail.finishFrame(0); tail.close();
		check(tailDriver.reads == 0 && tailDriver.created == tailDriver.deletes, "stop waited for an unfinished query");
		check(export(tail, 1, null).get(0).get("gpu_status").equals("pending_at_stop"), "pending tail reported as zero");
		FrameTimeGpuCapture empty = started(new Fake(), 2);
		empty.close();
		check(export(empty, 0, null).isEmpty(), "warmup-only stop produced a frame");
	}

	private static void failuresAndCleanup() throws Exception {
		Fake unsupported = new Fake(); unsupported.bits = 0;
		FrameTimeGpuCapture noGpu = new FrameTimeGpuCapture(32, 4, unsupported);
		render(noGpu); noGpu.finishFrame(0); noGpu.close();
		check(unsupported.created == 0 && export(noGpu, 1, null).get(0).get("gpu_status").equals("unsupported"), "unsupported driver invoked");

		Fake partial = new Fake(); partial.failCreateAt = 3;
		FrameTimeGpuCapture initFailure = new FrameTimeGpuCapture(32, 4, partial);
		initFailure.finishFrame(0); initFailure.close();
		check(partial.created == 3 && partial.deletes == 3, "partial allocation cleanup");
		check(export(initFailure, 1, null).get(0).get("gpu_status").equals("query_error"), "init error not preserved");

		for (String failure : new String[]{"timestamp", "available", "result"}) {
			Fake bad = new Fake();
			FrameTimeGpuCapture gpu = started(bad, 2);
			bad.failure = failure;
			render(gpu); gpu.finishFrame(0); gpu.finishFrame(1); gpu.finishFrame(2);
			int before = bad.calls;
			render(gpu); gpu.finishFrame(3);
			check(before == bad.calls, "failed backend retried in frame hooks: " + failure);
			gpu.close();
			Map<String, String> row = export(gpu, 4, null).get(0);
			check(row.get("gpu_status").equals("query_error") && row.get("gpu_render_ms").isEmpty(), "failure exposed a partial result");
			check(bad.created == bad.deletes, "cleanup after " + failure);
		}
		Fake badDelete = new Fake();
		FrameTimeGpuCapture gpu = started(badDelete, 2);
		badDelete.failure = "delete"; gpu.close();
		check(badDelete.deletes == badDelete.created && gpu.status.contains("cleanup_error"), "one delete failure prevented remaining cleanup");
	}

	private static void counterValidation() throws Exception {
		for (int bits : new int[]{32, 64}) {
			Fake driver = new Fake(); driver.bits = bits;
			FrameTimeGpuCapture gpu = started(driver, 2);
			driver.time = bits == 64 ? -40L : (1L << bits) - 40;
			render(gpu); gpu.finishFrame(0); gpu.close();
			check(gpu.samples[3][0] == 60 && export(gpu, 1, null).get(0).get("gpu_status").equals("ready"), "counter wrap rejected: " + bits);
		}
		Fake invalid = new Fake();
		FrameTimeGpuCapture gpu = started(invalid, 2);
		invalid.step = -10;
		render(gpu); gpu.finishFrame(0); gpu.close();
		Map<String, String> row = export(gpu, 1, null).get(0);
		check(row.get("gpu_status").equals("invalid_timestamps") && row.get("gpu_render_ms").isEmpty(), "negative timestamp interval exported");
	}

	private static void cpuClockAndExport() throws Exception {
		AtomicLong clock = new AtomicLong(100);
		FrameTimePhases phases = new FrameTimePhases(32, clock::get);
		phases.begin(FrameTimeRecorder.RENDER, 0);
		clock.set(110); phases.begin(FrameTimeRecorder.RENDER, 1); // Reentrant render, counted once.
		clock.set(120); phases.begin(FrameTimeRecorder.WORLD, 2);
		clock.set(130); phases.begin(FrameTimeRecorder.SHADOW, 3);
		clock.set(140); phases.end(FrameTimeRecorder.SHADOW, 4);
		clock.set(160); phases.end(FrameTimeRecorder.WORLD, 5);
		clock.set(170); phases.end(FrameTimeRecorder.RENDER, 6);
		clock.set(180); phases.end(FrameTimeRecorder.RENDER, 7);
		phases.finishFrame(0, 10);
		check(phases.valid[0] && phases.cpuSamples[2][0] == 80 && phases.cpuSamples[3][0] == 40
				&& phases.cpuSamples[4][0] == 10, "nested CPU timing double counted, or rounded CPU greater than wall rejected");
		phases.begin(FrameTimeRecorder.RENDER, 10); clock.set(-1); phases.end(FrameTimeRecorder.RENDER, 11);
		clock.set(200); phases.begin(FrameTimeRecorder.RENDER, 12); clock.set(220); phases.end(FrameTimeRecorder.RENDER, 13);
		phases.finishFrame(1, 10);
		check(phases.cpuSamples[2][1] == -1, "partly unavailable CPU time exported as a complete sum");
		phases.begin(FrameTimeRecorder.WORLD, 20);
		phases.finishFrame(2, 10);
		check(!phases.valid[2] && phases.cpuSamples[3][2] == -1, "unpaired CPU phase not rejected");
		phases.finishFrame(3, 10);
		check(phases.valid[3] && phases.cpuSamples[2][3] == -1, "missing CPU phase became zero");

		Fake driver = new Fake(); driver.bits = 0;
		FrameTimeGpuCapture gpu = new FrameTimeGpuCapture(32, 2, driver);
		for (int i = 0; i < 4; i++) { gpu.finishFrame(i); }
		gpu.close();
		List<Map<String, String>> rows = export(gpu, 4, phases);
		check(rows.get(0).get("render_cpu_ms").equals("8.0E-5") && rows.get(1).get("render_cpu_ms").isEmpty(), "CPU fallback export");
		check(rows.get(2).get("world_cpu_ms").isEmpty() && rows.get(3).get("render_cpu_ms").isEmpty(), "invalid/missing CPU output");
	}

	private static FrameTimeGpuCapture started(Fake driver, int size) {
		FrameTimeGpuCapture gpu = new FrameTimeGpuCapture(32, size, driver);
		gpu.finishFrame(-1); // Retire prewarmed queries; no result reads for discarded warmup data.
		check(driver.reads == 0, "warmup data read unnecessarily");
		return gpu;
	}

	private static void render(FrameTimeGpuCapture gpu) {
		gpu.beginPhase(FrameTimeRecorder.RENDER);
		gpu.beginPhase(FrameTimeRecorder.WORLD);
		gpu.beginPhase(FrameTimeRecorder.SHADOW);
		gpu.endPhase(FrameTimeRecorder.SHADOW);
		gpu.endPhase(FrameTimeRecorder.WORLD);
		gpu.endPhase(FrameTimeRecorder.RENDER);
		gpu.beginPhase(FrameTimeRecorder.SWAP);
	}

	private static List<Map<String, String>> export(FrameTimeGpuCapture gpu, int count, FrameTimePhases supplied) throws Exception {
		FrameTimeCapture frames = new FrameTimeCapture(1, 0);
		FrameTimePhases phases = supplied == null ? new FrameTimePhases(32) : supplied;
		frames.record(0, 0);
		for (int i = 0; i < count; i++) {
			frames.record((i + 1) * 10_000_000L, i == 1 ? FrameTimeCapture.UNFOCUSED : 0);
			if (supplied == null) { phases.finishFrame(i, 10_000_000L); }
		}
		Path directory = Files.createTempDirectory("iris-gpu-check-");
		AtomicReference<Throwable> error = new AtomicReference<>();
		Thread writer = new Thread(() -> {
			try {
				String report = gpu.save(directory, "test", frames, phases);
				check(report.contains("不是屏幕实际呈现时间") && report.contains("上界"), "GPU interpretation constraints absent");
			} catch (Throwable e) { error.set(e); }
		});
		writer.start(); writer.join();
		if (error.get() != null) { throw new AssertionError("worker export failed", error.get()); }
		try {
			List<String> lines = Files.readAllLines(directory.resolve("test.render.csv"), StandardCharsets.UTF_8);
			check(lines.size() == count + 1, "row/frame alignment");
			String[] header = lines.get(0).split(",", -1);
			List<Map<String, String>> rows = new ArrayList<>();
			for (int i = 1; i < lines.size(); i++) {
				String[] fields = lines.get(i).split(",", -1);
				check(fields.length == header.length, "CSV column alignment");
				Map<String, String> row = new HashMap<>();
				for (int j = 0; j < fields.length; j++) { row.put(header[j], fields[j]); }
				check(row.get("frame").equals(Integer.toString(i - 1)) && row.get("flags").equals(Byte.toString(frames.flags[i - 1])), "frame index/flags changed");
				rows.add(row);
			}
			return rows;
		} finally {
			Files.deleteIfExists(directory.resolve("test.render.csv")); Files.delete(directory);
		}
	}

	private static final class Fake implements FrameTimeGpuCapture.Driver {
		final Thread owner = Thread.currentThread();
		final Map<Integer, Long> values = new HashMap<>();
		int bits = 64, created, deletes, reads, timestamps, calls, unavailable = -1, failCreateAt = -1;
		long time = 1000, step = 10;
		boolean ready = true;
		String failure = "";
		void access(String operation) {
			check(Thread.currentThread() == owner, "driver called from writer or wrong thread"); calls++;
			if (failure.equals(operation)) { throw new IllegalStateException(operation); }
		}
		@Override public int timestampBits() { access("bits"); return bits; }
		@Override public int create() {
			access("create");
			if (created == failCreateAt) { throw new IllegalStateException("create"); }
			return ++created;
		}
		@Override public void timestamp(int query) {
			access("timestamp"); timestamps++;
			time += step;
			values.put(query, bits == 64 ? time : time & ((1L << bits) - 1));
		}
		@Override public boolean available(int query) { access("available"); return ready && query != unavailable; }
		@Override public long result(int query) {
			access("result"); check(ready && query != unavailable, "blocking query result read"); reads++;
			return values.get(query);
		}
		@Override public void delete(int query) { deletes++; access("delete"); }
	}

	private static void check(boolean value, String message) { if (!value) { throw new AssertionError(message); } }
}
