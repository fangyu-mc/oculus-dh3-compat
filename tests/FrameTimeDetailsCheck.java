package net.coderbot.iris.diagnostics;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public final class FrameTimeDetailsCheck {
	private static final int SCOPE = FrameTimeDetails.DH_UPLOAD;

	public static void main(String[] args) throws Exception {
		check(FrameTimeRecorder.beginDetail(SCOPE) == 0, "disabled hook starts capture");
		FrameTimeRecorder.endDetail(SCOPE, 0, true);
		check(FrameTimeRecorder.clientThreadOnly(FrameTimeDetails.CLIENT_TICK)
				&& FrameTimeRecorder.clientThreadOnly(FrameTimeDetails.PACKET_LIGHT), "tick/packet background transfers counted");
		check(!FrameTimeRecorder.clientThreadOnly(FrameTimeDetails.MAP_POST_UPLOAD)
				&& !FrameTimeRecorder.clientThreadOnly(FrameTimeDetails.MAP_PROCESS_END)
				&& !FrameTimeRecorder.clientThreadOnly(FrameTimeDetails.MAP_RELOAD), "split map scopes lost background diagnostics");
		Thread owner = Thread.currentThread(), worker = new Thread("upload,\"worker\"");
		FrameTimeDetails details = new FrameTimeDetails(6, 4, owner);
		long outer = details.begin(SCOPE, 1_100_000, owner);
		long inner = details.begin(SCOPE, 2_000_000, owner);
		details.end(SCOPE, inner, 4_000_000, owner, true);
		details.end(SCOPE, outer, 5_000_000, owner, false);
		long background = details.begin(SCOPE, 1_500_000, worker);
		details.end(SCOPE, background, 6_000_000, worker, false);
		details.finishFrame(0, 5_000_000);
		check(details.valid[0] && details.totals[SCOPE][0] == 3_900_000, "nested wall time double counted");
		check(details.calls[SCOPE][0] == 2 && details.failures[SCOPE][0] == 1 && details.maxima[SCOPE][0] == 3_900_000, "call summaries");
		check(details.totals[FrameTimeDetails.CHUNK_DRAW][0] == 0, "unrelated scope contaminated");
		// A missing exit invalidates this frame; the late exit also invalidates the next frame.
		outer = details.begin(SCOPE, 7_000_000, owner);
		details.finishFrame(1, 5_000_000);
		details.end(SCOPE, outer, 12_000_000, owner, false);
		details.finishFrame(2, 5_000_000);
		details.finishFrame(3, 5_000_000);
		check(!details.valid[1] && !details.valid[2] && details.valid[3], "unpaired/cross-frame recovery");
		outer = details.begin(SCOPE, 22_000_000, owner);
		details.end(SCOPE, outer, 28_000_000, owner, false);
		details.finishFrame(4, 5_000_000);
		check(!details.valid[4], "out-of-frame duration accepted");
		outer = details.begin(SCOPE, 29_000_000, owner);
		details.end(SCOPE, outer, 30_000_000, owner, false); // Full span buffer must not lose per-frame totals.
		details.finishFrame(5, 5_000_000);
		check(details.valid[5] && details.calls[SCOPE][5] == 1, "span capacity damaged frame totals");
		long pending = details.begin(SCOPE, 29_500_000, worker);
		details.freeze();
		details.end(SCOPE, pending, 32_000_000, worker, false);
		check(details.begin(SCOPE, 33_000_000, owner) == 0 && details.begin(SCOPE, 33_000_000, worker) == 0, "closed capture accepts work");

		FrameTimeCapture frames = new FrameTimeCapture(1, 1_000_000);
		frames.record(1_000_000, 0);
		for (int i = 1; i <= 6; i++) { frames.record(1_000_000 + i * 5_000_000L, 0); }
		Path directory = Paths.get("build/reports/frame-time-details");
		Files.createDirectories(directory);
		String name = "check-" + System.nanoTime();
		String report = details.save(directory, name, frames, 1 << SCOPE);
		List<String> csv = Files.readAllLines(directory.resolve(name + ".details.csv"), StandardCharsets.UTF_8);
		check(csv.size() == 7 && csv.get(1).startsWith("0,0.0,5.0,5.0,0,1,"), "frame alignment");
		int columns = csv.get(0).split(",").length;
		check(columns == 8 + FrameTimeDetails.COUNT * 4, "detail columns");
		for (String scope : new String[]{"map_post_upload", "map_process_end", "map_loaded_chunks", "map_config", "map_cave", "map_reload"}) {
			check(csv.get(0).contains("," + scope + "_ms,") && report.contains(scope + ": hook="), "split map column/report missing: " + scope);
		}
		check(!csv.get(0).contains("map_maintenance"), "retired aggregate would double count maintenance");
		for (String line : csv) { check(line.split(",").length == columns, "ragged CSV"); }
		List<String> spans = Files.readAllLines(directory.resolve(name + ".spans.csv"), StandardCharsets.UTF_8);
		check(spans.size() == 5, "span threshold or capacity");
		check(spans.get(3).contains("\"upload,\"\"worker\"\"\",0,0.5,5.0,4.5,0"), "background CSV quoting/time origin");
		check(report.contains("缓冲容量丢弃=1") && report.contains("后台开始=2；完成=1；截止时未完成=1"), "truncation/in-flight reporting");
		check(report.contains("dh_upload: hook=available") && report.contains("map_upload: hook=unavailable"), "hook availability");
		Files.delete(directory.resolve(name + ".details.csv")); Files.delete(directory.resolve(name + ".spans.csv"));
		checkConcurrency();
		checkTickCpu();
		checkPollCpu();
		checkWorkCpu();
		checkCallbackCpu();
		System.out.println("Frame detail checks: nested/multiple calls, exceptions, frame alignment, disabled hooks, cross-frame recovery, bounded spans, CSV escaping, background isolation and concurrent freeze passed.");
	}

	private static void checkTickCpu() throws Exception {
		Thread owner = Thread.currentThread();
		long[] cpu = {0};
		FrameTimeDetails details = new FrameTimeDetails(5, 20, owner, () -> cpu[0]);
		int scope = FrameTimeDetails.CLIENT_TICK;
		// A delayed tick may have little CPU time. Count all catch-up ticks, including short ones.
		long first = details.begin(scope, 1_000_001, owner);
		cpu[0] = 20_000_000;
		details.end(scope, first, 901_000_001, owner, false);
		for (int i = 0; i < 9; i++) {
			long start = details.begin(scope, 902_000_001L + i * 1_000_000L, owner);
			cpu[0] += 100_000;
			details.end(scope, start, start + 900_000, owner, false);
		}
		details.finishFrame(0, 920_000_000);
		check(details.valid[0] && details.calls[scope][0] == 10 && details.maxima[scope][0] == 900_000_000, "catch-up ticks merged or lost");
		check(details.tickCpuCalls[0] == 10 && details.tickCpuNanos[0] == 20_900_000, "wall time mistaken for CPU time");
		// Nested ticks use one outer CPU interval, preserving the independent wall/call counters.
		first = details.begin(scope, 922_000_001, owner);
		cpu[0] += 100;
		long inner = details.begin(scope, 923_000_001, owner);
		cpu[0] += 300;
		details.end(scope, inner, 924_000_001, owner, false);
		cpu[0] += 200;
		details.end(scope, first, 926_000_001, owner, true);
		details.finishFrame(1, 10_000_000);
		check(details.valid[1] && details.tickCpuNanos[1] == 600 && details.tickCpuCalls[1] == 1, "nested CPU time double counted");
		check(details.calls[scope][1] == 2 && details.failures[scope][1] == 1, "nested tick exception count");
		cpu[0] = -1;
		first = details.begin(scope, 932_000_001, owner);
		details.end(scope, first, 933_000_001, owner, false);
		details.finishFrame(2, 10_000_000);
		check(details.valid[2] && details.tickCpuCalls[2] == 0, "unavailable CPU clock counted as zero");
		cpu[0] = 1000;
		first = details.begin(scope, 942_000_001, owner);
		cpu[0] = 900;
		details.end(scope, first, 943_000_001, owner, false);
		details.finishFrame(3, 10_000_000);
		check(details.tickCpuCalls[3] == 0, "backward CPU clock accepted");
		details.finishFrame(4, 10_000_000);
		check(details.tickCpuCalls[4] == 0 && details.tickCpuNanos[4] == 0, "CPU totals leaked into next frame");
		details.freeze();
		FrameTimeCapture frames = new FrameTimeCapture(2, 1);
		frames.record(1, 0); frames.record(920_000_001, 0);
		for (int i = 1; i < 5; i++) { frames.record(920_000_001 + i * 10_000_000L, 0); }
		Path dir = Paths.get("build/reports/frame-time-details");
		String name = "cpu-" + System.nanoTime();
		String report = details.save(dir, name, frames, (1L << scope) | (1L << FrameTimeDetails.PACKET_LIGHT));
		List<String> csv = Files.readAllLines(dir.resolve(name + ".details.csv"), StandardCharsets.UTF_8);
		check(csv.get(1).endsWith(",20.9,10") && csv.get(3).endsWith(",,0"), "CPU CSV availability");
		List<String> spans = Files.readAllLines(dir.resolve(name + ".spans.csv"), StandardCharsets.UTF_8);
		check(spans.get(1).endsWith(",900.0,0,20.0,\"\""), "individual tick CPU span");
		check(report.contains("packet_light: hook=available") && report.contains("map_upload: hook=unavailable"), "hook mask overflow above 32");
		FrameTimeDetails.registerHook(FrameTimeDetails.PACKET_LIGHT);
		check((FrameTimeDetails.availableHooks() & (1L << FrameTimeDetails.PACKET_LIGHT)) != 0, "high hook lost");
		Files.delete(dir.resolve(name + ".details.csv")); Files.delete(dir.resolve(name + ".spans.csv"));
		FrameTimeCpuClock actual = new FrameTimeCpuClock();
		long before = actual.getAsLong(), after = actual.getAsLong();
		check(before < 0 || after < 0 || after >= before, "JVM CPU clock decreased");
		System.out.println("Tick CPU checks: ten catch-up ticks, long off-CPU interval, nesting, missing/backward clocks, reset, spans and 64-bit availability mask passed.");
	}

	private static void checkPollCpu() throws Exception {
		Thread owner = Thread.currentThread();
		long[] cpu = {0};
		int[] reads = {0};
		FrameTimeDetails details = new FrameTimeDetails(4, 20, owner, () -> { reads[0]++; return cpu[0]; });
		long outer = details.begin(FrameTimeDetails.POLL_BEFORE, 1_000_001, owner);
		cpu[0] = 100_000;
		long nested = details.begin(FrameTimeDetails.POLL_BEFORE, 2_000_001, owner);
		cpu[0] = 300_000;
		details.end(FrameTimeDetails.POLL_BEFORE, nested, 4_000_001, owner, false);
		cpu[0] = 400_000;
		long tick = details.begin(FrameTimeDetails.CLIENT_TICK, 5_000_001, owner);
		cpu[0] = 600_000;
		details.end(FrameTimeDetails.CLIENT_TICK, tick, 6_000_001, owner, false);
		cpu[0] = 2_000_000;
		details.end(FrameTimeDetails.POLL_BEFORE, outer, 31_000_001, owner, false);
		long after = details.begin(FrameTimeDetails.POLL_AFTER, 32_000_001, owner);
		cpu[0] = 3_000_000;
		details.end(FrameTimeDetails.POLL_AFTER, after, 37_000_001, owner, false);
		details.finishFrame(0, 40_000_000);
		check(details.valid[0] && details.tickCpuCalls[0] == 1 && details.tickCpuNanos[0] == 200_000,
				"poll CPU leaked into tick or overwrote a nested scope clock");
		check(details.totals[FrameTimeDetails.POLL_BEFORE][0] == 30_000_000, "poll nesting changed wall accounting");
		cpu[0] = -1;
		after = details.begin(FrameTimeDetails.POLL_AFTER, 41_000_001, owner);
		details.end(FrameTimeDetails.POLL_AFTER, after, 50_000_001, owner, false);
		details.finishFrame(1, 20_000_000);
		cpu[0] = 100;
		after = details.begin(FrameTimeDetails.POLL_AFTER, 61_000_001, owner);
		cpu[0] = 50;
		details.end(FrameTimeDetails.POLL_AFTER, after, 62_000_001, owner, false);
		int beforeReads = reads[0];
		long other = details.begin(FrameTimeDetails.CHUNK_UPDATE, 63_000_001, owner);
		details.end(FrameTimeDetails.CHUNK_UPDATE, other, 64_000_001, owner, false);
		Thread worker = new Thread("poll-cpu-test-background");
		other = details.begin(FrameTimeDetails.POLL_BEFORE, 63_000_001, worker);
		details.end(FrameTimeDetails.POLL_BEFORE, other, 65_000_001, worker, false);
		check(reads[0] == beforeReads, "unrelated/background scopes query the main-thread CPU clock");
		details.finishFrame(2, 10_000_000);
		details.finishFrame(3, 10_000_000);
		details.freeze();
		FrameTimeCapture frames = new FrameTimeCapture(1, 1);
		frames.record(1, 0); frames.record(40_000_001, 0); frames.record(60_000_001, 0);
		frames.record(70_000_001, 0); frames.record(80_000_001, 0);
		Path dir = Paths.get("build/reports/frame-time-details");
		String name = "poll-cpu-" + System.nanoTime();
		details.save(dir, name, frames, 3);
		List<String> spans = Files.readAllLines(dir.resolve(name + ".spans.csv"), StandardCharsets.UTF_8);
		check(spans.stream().anyMatch(s -> s.startsWith("poll_before,") && s.endsWith(",30.0,0,2.0,\"\"")), "poll CPU/wall span missing");
		check(spans.stream().anyMatch(s -> s.startsWith("poll_before,") && s.endsWith(",2.0,0,,\"\"")), "nested poll CPU double counted");
		check(spans.stream().anyMatch(s -> s.startsWith("poll_after,") && s.endsWith(",5.0,0,1.0,\"\"")), "after-poll clock missing");
		check(spans.stream().anyMatch(s -> s.startsWith("poll_after,") && s.endsWith(",9.0,0,,\"\"")), "unavailable poll CPU became zero");
		check(spans.stream().anyMatch(s -> s.startsWith("poll_after,") && s.endsWith(",1.0,0,,\"\"")), "backward poll CPU accepted");
		Files.delete(dir.resolve(name + ".details.csv")); Files.delete(dir.resolve(name + ".spans.csv"));
		System.out.println("Window-event CPU checks: off-CPU spans, independent before/after clocks, nesting, tick isolation, unavailable/backward clocks and no background/unrelated CPU probes passed.");
	}

	private static void checkWorkCpu() throws Exception {
		Thread owner = Thread.currentThread();
		long[] cpu = {0};
		FrameTimeDetails details = new FrameTimeDetails(1, 8, owner, () -> cpu[0]);
		Object work = new Object() {
			@Override public String toString() { throw new AssertionError("task content inspected"); }
		};
		for (int scope : new int[]{FrameTimeDetails.MAIN_TASK, FrameTimeDetails.PACKET_APPLY, FrameTimeDetails.SWAP_BUFFERS}) {
			check(FrameTimeRecorder.clientThreadOnly(scope), "dispatch/presentation included background work");
			check(FrameTimeRecorder.beginDetail(scope) == 0, "disabled work hook activated");
			FrameTimeRecorder.endWorkDetail(scope, 0, false, work);
		}
		long task = details.begin(FrameTimeDetails.MAIN_TASK, 1_000_001, owner);
		cpu[0] = 100_000;
		long packet = details.begin(FrameTimeDetails.PACKET_APPLY, 2_000_001, owner);
		cpu[0] = 400_000;
		details.end(FrameTimeDetails.PACKET_APPLY, packet, 11_000_001, owner, true, work);
		cpu[0] = 500_000;
		details.end(FrameTimeDetails.MAIN_TASK, task, 13_000_001, owner, false, work);
		long swap = details.begin(FrameTimeDetails.SWAP_BUFFERS, 14_000_001, owner);
		// A supported clock returning zero elapsed CPU must remain distinguishable from missing data.
		details.end(FrameTimeDetails.SWAP_BUFFERS, swap, 114_000_001, owner, false);
		cpu[0] = -1;
		swap = details.begin(FrameTimeDetails.SWAP_BUFFERS, 115_000_001, owner);
		details.end(FrameTimeDetails.SWAP_BUFFERS, swap, 118_000_001, owner, false);
		long shortTask = details.begin(FrameTimeDetails.MAIN_TASK, 119_000_001, owner);
		details.end(FrameTimeDetails.MAIN_TASK, shortTask, 119_500_001, owner, false, work);
		details.finishFrame(0, 120_000_000);
		check(details.valid[0] && details.calls[FrameTimeDetails.MAIN_TASK][0] == 2, "short tasks lost from totals");
		check(details.tickCpuCalls[0] == 0 && details.tickCpuNanos[0] == 0, "work CPU leaked into tick totals");
		details.freeze();
		FrameTimeCapture frames = new FrameTimeCapture(1, 1);
		frames.record(1, 0); frames.record(120_000_001, 0);
		Path dir = Paths.get("build/reports/frame-time-details");
		String name = "work-cpu-" + System.nanoTime();
		details.save(dir, name, frames, (1L << FrameTimeDetails.PACKET_APPLY));
		List<String> spans = Files.readAllLines(dir.resolve(name + ".spans.csv"), StandardCharsets.UTF_8);
		String type = ",\"" + work.getClass().getName() + "\"";
		check(spans.size() == 5 && spans.get(0).endsWith(",work_class"), "work threshold/schema");
		check(spans.get(1).endsWith(",9.0,1,0.3" + type), "packet type/cpu/failure");
		check(spans.get(2).endsWith(",12.0,0,0.5" + type), "outer task cpu/type");
		check(spans.get(3).endsWith(",100.0,0,0.0,\"\""), "swap zero CPU mistaken for unavailable");
		check(spans.get(4).endsWith(",3.0,0,,\"\""), "swap unavailable CPU mistaken for zero");
		Files.delete(dir.resolve(name + ".details.csv")); Files.delete(dir.resolve(name + ".spans.csv"));
		System.out.println("Work diagnostics: task/packet types without content, nested CPU isolation, short-task totals, swap zero/unavailable CPU and disabled hooks passed.");
	}

	private static void checkCallbackCpu() throws Exception {
		Thread owner = Thread.currentThread();
		long[] cpu = {0};
		FrameTimeDetails details = new FrameTimeDetails(1, 16, owner, () -> cpu[0]);
		for (int scope = FrameTimeDetails.WINDOW_RESIZE; scope <= FrameTimeDetails.KEYBOARD_INPUT; scope++) {
			check(FrameTimeRecorder.clientThreadOnly(scope), "callback included background work");
			check(FrameTimeRecorder.beginDetail(scope) == 0, "disabled callback hook activated");
			FrameTimeRecorder.endDetail(scope, 0, false);
		}
		long poll = details.begin(FrameTimeDetails.POLL_BEFORE, 1_000_001, owner);
		cpu[0] = 100_000;
		long mouse = details.begin(FrameTimeDetails.MOUSE_INPUT, 2_000_001, owner);
		cpu[0] = 200_000;
		long keyboard = details.begin(FrameTimeDetails.KEYBOARD_INPUT, 3_000_001, owner);
		cpu[0] = 300_000;
		long resize = details.begin(FrameTimeDetails.WINDOW_RESIZE, 4_000_001, owner);
		cpu[0] = 600_000;
		details.end(FrameTimeDetails.WINDOW_RESIZE, resize, 6_000_001, owner, false);
		cpu[0] = 700_000;
		details.end(FrameTimeDetails.KEYBOARD_INPUT, keyboard, 7_000_001, owner, true);
		cpu[0] = 800_000;
		details.end(FrameTimeDetails.MOUSE_INPUT, mouse, 8_000_001, owner, false);
		cpu[0] = 900_000;
		long focus = details.begin(FrameTimeDetails.WINDOW_FOCUS, 9_000_001, owner);
		cpu[0] = 1_000_000;
		details.end(FrameTimeDetails.WINDOW_FOCUS, focus, 11_000_001, owner, false);
		cpu[0] = 1_100_000;
		long move = details.begin(FrameTimeDetails.WINDOW_MOVE, 12_000_001, owner);
		cpu[0] = 1_200_000;
		details.end(FrameTimeDetails.WINDOW_MOVE, move, 14_000_001, owner, false);
		cpu[0] = 1_500_000;
		details.end(FrameTimeDetails.POLL_BEFORE, poll, 16_000_001, owner, false);
		details.finishFrame(0, 20_000_000);
		check(details.valid[0] && details.tickCpuCalls[0] == 0, "callbacks corrupted frame/tick accounting");
		check(details.totals[FrameTimeDetails.POLL_BEFORE][0] == 15_000_000
				&& details.totals[FrameTimeDetails.MOUSE_INPUT][0] == 6_000_000
				&& details.totals[FrameTimeDetails.KEYBOARD_INPUT][0] == 4_000_000, "callback nesting changed enclosing wall times");
		details.freeze();
		FrameTimeCapture frames = new FrameTimeCapture(1, 1);
		frames.record(1, 0); frames.record(20_000_001, 0);
		Path dir = Paths.get("build/reports/frame-time-details");
		String name = "callback-cpu-" + System.nanoTime();
		String report = details.save(dir, name, frames, 1L << FrameTimeDetails.KEYBOARD_INPUT);
		List<String> spans = Files.readAllLines(dir.resolve(name + ".spans.csv"), StandardCharsets.UTF_8);
		check(spans.size() == 7, "callback spans missing");
		String[] names = {"window_resize", "keyboard_input", "mouse_input", "window_focus", "window_move", "poll_before"};
		String[] endings = {",2.0,0,0.3,\"\"", ",4.0,1,0.5,\"\"", ",6.0,0,0.7,\"\"",
				",2.0,0,0.1,\"\"", ",2.0,0,0.1,\"\"", ",15.0,0,1.5,\"\""};
		for (int i = 0; i < names.length; i++) {
			check(spans.get(i + 1).startsWith(names[i] + ",") && spans.get(i + 1).endsWith(endings[i]), "callback CPU isolation: " + names[i]);
		}
		String header = Files.readAllLines(dir.resolve(name + ".details.csv"), StandardCharsets.UTF_8).get(0);
		check(header.contains(",keyboard_input_ms,") && report.contains("keyboard_input: hook=available")
				&& report.contains("mouse_input: hook=unavailable"), "callback CSV or high availability bit lost");
		FrameTimeDetails.registerHook(FrameTimeDetails.KEYBOARD_INPUT);
		check((FrameTimeDetails.availableHooks() & (1L << FrameTimeDetails.KEYBOARD_INPUT)) != 0, "callback registration lost bit 55");
		Files.delete(dir.resolve(name + ".details.csv")); Files.delete(dir.resolve(name + ".spans.csv"));
		System.out.println("Callback diagnostics: nested resize/input clocks, focus/move CPU, poll/tick isolation, failures, disabled hooks and bit-55 export passed.");
	}

	private static void checkConcurrency() throws Exception {
		FrameTimeDetails details = new FrameTimeDetails(1, 100, Thread.currentThread());
		CountDownLatch start = new CountDownLatch(1), allStarted = new CountDownLatch(4), finish = new CountDownLatch(1);
		AtomicReference<Throwable> error = new AtomicReference<>();
		Thread[] workers = new Thread[4];
		for (int i = 0; i < workers.length; i++) {
			workers[i] = new Thread(() -> {
				try {
					start.await();
					for (int j = 0; j < 1000; j++) {
						long token = details.begin(SCOPE, 1_000_000 + j * 1000, Thread.currentThread());
						details.end(SCOPE, token, token + 500, Thread.currentThread(), false);
					}
					long token = details.begin(SCOPE, 3_000_000, Thread.currentThread());
					allStarted.countDown(); finish.await();
					details.end(SCOPE, token, 9_000_000, Thread.currentThread(), false);
				} catch (Throwable t) { error.compareAndSet(null, t); allStarted.countDown(); }
			});
			workers[i].start();
		}
		start.countDown(); allStarted.await();
		details.finishFrame(0, 10_000_000); details.freeze(); finish.countDown();
		for (Thread worker : workers) { worker.join(); }
		check(error.get() == null, "worker failed: " + error.get());
		check(details.valid[0] && details.calls[SCOPE][0] == 0, "background work entered main-thread totals");
		FrameTimeCapture frames = new FrameTimeCapture(1, 1_000_000);
		frames.record(1_000_000, 0); frames.record(11_000_000, 0);
		Path dir = Paths.get("build/reports/frame-time-details");
		String name = "concurrent-" + System.nanoTime();
		String report = details.save(dir, name, frames, 1 << SCOPE);
		check(report.contains("后台开始=4004；完成=4000；截止时未完成=4；累计_ms=2.0"), "concurrent counters/freeze");
		Files.delete(dir.resolve(name + ".details.csv")); Files.delete(dir.resolve(name + ".spans.csv"));
	}

	private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
}
