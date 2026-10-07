package net.coderbot.iris.diagnostics;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import jdk.jfr.*;
import jdk.jfr.consumer.*;

/** Real buffer pressure in a small standalone JVM; never changes the game's JFR options. */
public final class FrameTimeJfrRetentionCheck {
    private static final int EVENTS = 250_000;
    private static volatile long sink;

    @Name("iris.check.RetentionLoad") @StackTrace(false)
    public static class Load extends Event { public long seq, a, b, c, d, e, f, g, h; }

    public static void main(String[] args) throws Exception {
        Path before = Files.createTempFile("iris-retention-control-", ".jfr");
        Path after = Files.createTempFile("iris-retention-fixed-", ".jfr");
        try {
            try (Recording control = new Recording()) {
                control.setToDisk(false); control.enable(Load.class); control.start();
                pressure(); control.dump(before); control.stop();
            }
            long[] old = loadStats(before);
            check(old[0] < EVENTS && old[1] > 0, "negative control did not reproduce rolling-buffer loss");
            FrameTimeJfr.Result result;
            long start, end;
            try (FrameTimeJfr capture = new FrameTimeJfr()) {
                Field field = FrameTimeJfr.class.getDeclaredField("recording"); field.setAccessible(true);
                ((Recording) field.get(capture)).enable(Load.class);
                start = System.nanoTime(); earlyBusy(); pressure(); lateBusy(); end = System.nanoTime();
                result = capture.finish(after);
            }
            long[] fixed = loadStats(after);
            check(fixed[0] == EVENTS && fixed[1] == 0 && fixed[2] == EVENTS - 1, "early or late events lost in production recording");
            check(result.dataLossMonitoring && result.lossEvents == 0 && result.lostBytes == 0, "JFR reported data loss under paced pressure");
            boolean early = false, late = false;
            try (RecordingFile file = new RecordingFile(after)) {
                while (file.hasMoreEvents()) {
                    RecordedEvent event = file.readEvent();
                    if (!event.getEventType().getName().equals("jdk.ExecutionSample") || event.getStackTrace() == null) { continue; }
                    for (RecordedFrame frame : event.getStackTrace().getFrames()) {
                        early |= frame.getMethod().getName().equals("earlyBusy");
                        late |= frame.getMethod().getName().equals("lateBusy");
                    }
                }
            }
            check(early && late, "early/late real execution samples missing");
            String coverage = result.coverageReport(start, end, Thread.currentThread().getId());
            check(coverage.contains("jdk.ExecutionSample") && coverage.contains("首样本_ms=") && coverage.contains("主线程样本="), "coverage report missing");
            result.lossEvents = 1; result.lostBytes = 4096;
            check(result.coverageReport(start, end, 1).contains("不能用于排除"), "data loss failed to qualify negative attribution");
            String report = "Real JFR retention pressure: in-memory control retained " + old[0] + "/" + EVENTS
                    + " events, first sequence " + old[1] + "; production retained all " + fixed[0] + ".\n"
                    + "Early and late execution samples, data-loss monitoring and coverage reporting passed.\n" + coverage + "\n";
            Files.createDirectories(Paths.get("build/reports"));
            Files.write(Paths.get("build/reports/frame-time-jfr-retention.txt"), report.getBytes(StandardCharsets.UTF_8));
            System.out.print(report);
        } finally { Files.deleteIfExists(before); Files.deleteIfExists(after); }
    }

    private static void pressure() throws InterruptedException {
        Load event = new Load();
        for (int i = 0; i < EVENTS; i++) {
            event.seq = i; event.a = event.b = event.c = event.d = event.e = event.f = event.g = event.h = Long.MAX_VALUE - i;
            event.commit();
            // Exceed retained memory, not disk throughput. The tiny buffer is only in this test JVM.
            if ((i & 511) == 511) { Thread.sleep(2); }
        }
    }
    private static void earlyBusy() { long until = System.nanoTime() + 600_000_000L, v = 7; while (System.nanoTime() < until) { for (int i = 0; i < 1000; i++) { v = v * 31 + i; } sink = v; } }
    private static void lateBusy() { long until = System.nanoTime() + 600_000_000L, v = 9; while (System.nanoTime() < until) { for (int i = 0; i < 1000; i++) { v = v * 33 + i; } sink = v; } }
    private static long[] loadStats(Path path) throws Exception {
        long count = 0, min = Long.MAX_VALUE, max = -1;
        try (RecordingFile file = new RecordingFile(path)) {
            while (file.hasMoreEvents()) {
                RecordedEvent event = file.readEvent();
                if (event.getEventType().getName().equals("iris.check.RetentionLoad")) {
                    long seq = event.getLong("seq"); count++; min = Math.min(min, seq); max = Math.max(max, seq);
                }
            }
        }
        return new long[]{count, min, max};
    }
    private static void check(boolean okay, String message) { if (!okay) { throw new AssertionError(message); } }
}
