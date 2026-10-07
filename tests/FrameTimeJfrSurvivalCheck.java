package net.coderbot.iris.diagnostics;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import jdk.jfr.EventFactory;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordingFile;

/** Real surviving allocations and export cleanup, without explicit GC or a game process. */
public final class FrameTimeJfrSurvivalCheck {
    private static final List<byte[]> held = new ArrayList<>();
    private static volatile Object sink;

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("iris-jfr-survival-");
        Path captureFile = directory.resolve("capture.jfr");
        Path observerFile = directory.resolve("observer.jfr");
        Path repeatFile = directory.resolve("repeat.jfr");
        FrameTimeJfr.Result result;
        long beforeStart, afterFinish, start, end, startCost, finishCost;
        String details;
        try {
            // A separate recording observes start/stop VM operations even when the target
            // recording is not yet running or has already stopped. It does not enable roots.
            try (Recording observer = new Recording()) {
                observer.enable("jdk.ExecuteVMOperation").withThreshold(Duration.ZERO);
                observer.start();
                beforeStart = System.nanoTime();
                try (FrameTimeJfr capture = new FrameTimeJfr()) {
                    startCost = System.nanoTime() - beforeStart;
                    Recording raw = recording(capture);
                    long factoryId = factoryId(capture);
                    check(capture.oldObjectSampling, "OldObjectSample not enabled on verification JVM");
                    check("0 ns".equals(raw.getSettings().get("jdk.OldObjectSample#cutoff")), "cutoff must be explicit zero");
                    check("true".equals(raw.getSettings().get("jdk.OldObjectSample#stackTrace")), "allocation stacks disabled");
                    check(!raw.getSettings().containsKey("jdk.ObjectCountAfterGC#enabled"), "heap census must not be enabled");
                    verifyMetadataFallback(capture);
                    details = capture.profilingDetails;
                    start = System.nanoTime();
                    retain();
                    churn();
                    Thread.sleep(100);
                    end = System.nanoTime();
                    long finishBefore = System.nanoTime();
                    result = capture.finish(captureFile);
                    finishCost = System.nanoTime() - finishBefore;
                    afterFinish = System.nanoTime();
                    verifyClosed(raw, factoryId);
                }
                observer.stop();
                observer.dump(observerFile);
            }

            int oldSamples = 0, retainedSamples = 0, gc = 0;
            long oldestNanos = 0;
            try (RecordingFile file = new RecordingFile(captureFile)) {
                while (file.hasMoreEvents()) {
                    RecordedEvent event = file.readEvent();
                    if (event.getEventType().getName().equals("jdk.GarbageCollection")) {
                        gc++;
                        check(!event.getString("cause").contains("System.gc"), "unexpected explicit GC");
                    }
                    if (!event.getEventType().getName().equals("jdk.OldObjectSample")) { continue; }
                    oldSamples++;
                    check(event.getValue("root") == null, "unexpected GC root path");
                    if (event.getStackTrace() == null || event.getStackTrace().getFrames().stream().noneMatch(
                            f -> f.getMethod().getType().getName().equals(FrameTimeJfrSurvivalCheck.class.getName())
                                    && f.getMethod().getName().equals("retain"))) { continue; }
                    retainedSamples++;
                    RecordedObject object = event.getValue("object");
                    check(object != null && object.getClass("type").getName().equals("[B"), "retained array type lost");
                    int length = event.getInt("arrayElements");
                    check(length >= 96 * 1024 && length < 96 * 1024 + 80, "retained array length lost");
                    Instant allocation = event.getInstant("allocationTime");
                    long allocationNanos = result.anchor.toNanos(allocation);
                    long tolerance = (long) Math.ceil(result.uncertaintyMillis * 1_000_000.0);
                    check(allocationNanos >= start - tolerance && allocationNanos < end + tolerance, "allocation time not in workload");
                    check(result.anchor.toNanos(event.getStartTime()) >= end - tolerance, "sample export must be after measured work");
                    long age = event.getDuration("objectAge").toNanos();
                    check(age > 0 && allocation.isBefore(event.getStartTime()), "object age missing");
                    oldestNanos = Math.max(oldestNanos, age);
                }
            }
            check(gc > 0 && retainedSamples > 0, "natural GC failed to yield surviving allocations: gc="
                    + gc + ", samples=" + oldSamples + ", retained=" + retainedSamples);
            check(result.oldObjectSampling && result.oldObjectSamples == oldSamples, "export-time samples lost from result");
            check(result.aligned && result.lossEvents == 0, "clock calibration or recording coverage failed");
            String coverage = result.coverageReport(start, end, Thread.currentThread().getId());
            check(coverage.contains("OldObjectSample 事件数=" + oldSamples), "sample summary missing");
            check(details.contains("cutoff=0 ns") && details.contains("allocationTime"), "configuration or timing guidance missing");

            StringBuilder overhead = new StringBuilder();
            int observedOldSamples = 0;
            try (RecordingFile file = new RecordingFile(observerFile)) {
                while (file.hasMoreEvents()) {
                    RecordedEvent event = file.readEvent();
                    if (event.getEventType().getName().equals("jdk.OldObjectSample")) { observedOldSamples++; }
                    if (!event.getEventType().getName().equals("jdk.ExecuteVMOperation")
                            || !event.getString("operation").equals("JFROldObject")) { continue; }
                    long time = result.anchor.toNanos(event.getStartTime());
                    if (time < beforeStart || time > afterFinish) { continue; }
                    String phase = time < start ? "startup" : time < end ? "workload" : "finish";
                    overhead.append("JFROldObject phase=").append(phase)
                            .append(" duration_ms=").append(event.getDuration().toNanos() / 1_000_000.0)
                            .append(" safepoint=").append(event.getBoolean("safepoint")).append('\n');
                }
            }
            check(observedOldSamples == oldSamples, "stop emitted duplicate old-object samples after export");

            // Invalid destination must still close the recording and unregister the clock event.
            try (FrameTimeJfr failed = new FrameTimeJfr()) {
                Recording raw = recording(failed);
                long factoryId = factoryId(failed);
                boolean rejected = false;
                try { failed.finish(directory); }
                catch (InvocationTargetException expected) {
                    check(expected.getCause() instanceof java.io.IOException, "unexpected export failure");
                    rejected = true;
                }
                check(rejected, "directory unexpectedly accepted as export file");
                verifyClosed(raw, factoryId);
            }
            try (FrameTimeJfr repeat = new FrameTimeJfr()) {
                check(repeat.finish(repeatFile).aligned, "capture unusable after failed export");
            }
            String report = "Natural GC events=" + gc + "; OldObjectSample=" + oldSamples
                    + "; retained arrays with allocation stacks=" + retainedSamples
                    + "; maximum sampled age_ms=" + oldestNanos / 1_000_000.0 + "\n"
                    + "All sampled roots absent; explicit cutoff, creation/export time distinction, metadata fallback, success/failure cleanup and repeat capture passed.\n"
                    + "Standalone observed startup_ms=" + startCost / 1_000_000.0
                    + "; finish including export/read_ms=" + finishCost / 1_000_000.0 + "\n"
                    + overhead + "These observations are not a game-overhead bound or a low-FPS improvement measurement.\n"
                    + coverage + "\n";
            Files.createDirectories(Paths.get("build/reports"));
            Files.write(Paths.get("build/reports/frame-time-jfr-survival.txt"), report.getBytes(StandardCharsets.UTF_8));
            System.out.print(report);
        } catch (Exception | AssertionError failure) {
            Path evidence = Paths.get("build/reports/frame-time-jfr-survival-failed");
            Files.createDirectories(evidence);
            for (Path file : new Path[]{captureFile, observerFile}) {
                if (Files.isRegularFile(file)) {
                    Files.copy(file, evidence.resolve(file.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            throw failure;
        } finally {
            held.clear();
            sink = null;
            Files.deleteIfExists(captureFile);
            Files.deleteIfExists(observerFile);
            Files.deleteIfExists(repeatFile);
            Files.deleteIfExists(directory);
        }
    }

    private static void retain() { for (int i = 0; i < 80; i++) { held.add(new byte[96 * 1024 + i]); } }
    private static void churn() { for (int i = 0; i < 8000; i++) { sink = new byte[8192 + (i & 1023)]; } }
    private static Recording recording(FrameTimeJfr capture) throws Exception {
        Field field = FrameTimeJfr.class.getDeclaredField("recording"); field.setAccessible(true);
        return (Recording) field.get(capture);
    }
    private static long factoryId(FrameTimeJfr capture) throws Exception {
        Field field = FrameTimeJfr.class.getDeclaredField("factory"); field.setAccessible(true);
        return ((EventFactory) field.get(capture)).getEventType().getId();
    }
    private static void verifyClosed(Recording recording, long factoryId) {
        check(recording.getState() == RecordingState.CLOSED, "recording not closed");
        check(FlightRecorder.getFlightRecorder().getRecordings().stream().noneMatch(r -> r.getId() == recording.getId()), "recording leaked");
        check(FlightRecorder.getFlightRecorder().getEventTypes().stream().noneMatch(t -> t.getId() == factoryId), "clock factory leaked");
    }
    private static void verifyMetadataFallback(FrameTimeJfr capture) throws Exception {
        Method hasCutoff = FrameTimeJfr.class.getDeclaredMethod("hasCutoff", Object.class); hasCutoff.setAccessible(true);
        EventType ordinary = FlightRecorder.getFlightRecorder().getEventTypes().stream()
                .filter(t -> t.getName().equals("jdk.ExecutionSample")).findFirst().orElseThrow(AssertionError::new);
        check(!(Boolean) hasCutoff.invoke(capture, new Object[]{null}), "missing event must be rejected");
        check(!(Boolean) hasCutoff.invoke(capture, ordinary), "event without cutoff must be rejected");
    }
    private static void check(boolean okay, String message) { if (!okay) { throw new AssertionError(message); } }
}
