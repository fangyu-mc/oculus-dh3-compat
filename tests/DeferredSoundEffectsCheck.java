package net.coderbot.iris.diagnostics;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;
import net.coderbot.iris.compat.illuminations.FireflyQuadOptimizer;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.service.MixinService;
import static org.objectweb.asm.Opcodes.*;

/** Installed Sound Control bytecode + real Mixin; no game, world or audio device. */
public final class DeferredSoundEffectsCheck {
    private static final String ROOT = "org/orecruncher/sndctrl/";
    private static final String CTX = ROOT + "audio/handlers/SourceContext";
    private static final String FX = ROOT + "audio/handlers/SoundFXProcessor";
    private static final String DATA = ROOT + "misc/IMixinSoundContext";
    private static final String SOUND = "net/minecraft/client/audio/ISound";
    private static final String SOURCE = "net/minecraft/client/audio/SoundSource";
    private static final String RANDOM = "org/orecruncher/lib/random/LCGRandom";
    private static ZipFile installed;
    private static final Map<String, byte[]> resources = new HashMap<>();

    public interface Context extends Callable<Void> { void exec(); boolean shouldExecute(); }
    public static final class Sound { public double x; Sound(double x) { this.x = x; } }
    public static class Probe {
        public int captures, calculations;
        public boolean enabled;
        public Sound sound;
        public double capturedX, resultX;
        public String calculationThread;
        public volatile CountDownLatch entered, release;
        public void attachSound(Sound s) { sound = s; captureState(); }
        public void enable() { enabled = true; }
        public void captureState() { captures++; capturedX = sound == null ? 0 : sound.x; }
        public void updateImpl() {
            calculations++; calculationThread = Thread.currentThread().getName();
            if (entered != null) {
                entered.countDown();
                try { check(release.await(5, TimeUnit.SECONDS), "worker release timeout"); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            }
            resultX = capturedX;
        }
    }
    public static class RandomProbe {
        private final Random random = new Random(90117);
        public int nextInt(int bound) { return random.nextInt(bound); }
    }
    public static class PoolBox {
        public static final ExecutorService pool = Executors.newFixedThreadPool(2, r -> new Thread(r, "sound-effects-check-worker"));
        public Object get() { return pool; }
    }
    public static class Tasks extends ArrayList<Object> { public Tasks(int capacity) { super(capacity); } }
    public static class Limits { public static int getMaxSounds() { return 4; } }
    public interface Log { void error(Throwable t, String message, Object... args); }
    public static class CheckedLog implements Log {
        public void error(Throwable t, String message, Object... args) { throw new AssertionError(message, t); }
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 1, "expected installed Dynamic Surroundings jar");
        try (ZipFile zip = new ZipFile(args[0])) {
            installed = zip;
            ClassNode original = read(zip.getInputStream(zip.getEntry(CTX + ".class")));
            ClassNode processor = read(zip.getInputStream(zip.getEntry(FX + ".class")));
            resources.put("sound-effects-check.json", ("{\"required\":true,\"minVersion\":\"0.8\",\"compatibilityLevel\":\"JAVA_8\","
                    + "\"package\":\"net.coderbot.iris.mixin.compat.sndctrl\",\"client\":[\"MixinDeferredInitialSoundEffects\"]}").getBytes(StandardCharsets.UTF_8));
            MixinBootstrap.init();
            MixinEnvironment env = MixinEnvironment.getDefaultEnvironment(); env.setSide(MixinEnvironment.Side.CLIENT);
            Mixins.addConfiguration("sound-effects-check.json");
            byte[] transformed = ((Service) MixinService.getService()).transformer().transformClass(env, CTX.replace('/', '.'), bytes(original));
            ClassNode patched = read(new ByteArrayInputStream(transformed));
            int changed = 0;
            for (MethodNode m : original.methods) {
                MethodNode p = method(patched, m.name, m.desc);
                if (!FireflyQuadOptimizer.fingerprint(m).equals(FireflyQuadOptimizer.fingerprint(p))) {
                    check(m.name.equals("exec") || m.name.equals("shouldExecute"), "unexpected method change: " + m.name);
                    changed++;
                }
            }
            check(changed == 2, "expected only initial execution and scheduling to change");
            check(patched.fields.stream().anyMatch(f -> f.name.contains("initialSoundEffectsPending") && (f.access & ACC_VOLATILE) != 0), "handoff flag must be volatile");
            check(countCalls(processor, CTX, "exec") == 1, "installed exec has unexpected callers");
            check(countCalls(processor, CTX, "shouldExecute") == 1, "installed background scheduling changed");
            Harness old = harness(original, processor), now = harness(patched, processor);
            Field oldCount = old.context.getDeclaredField("updateCount"), newCount = now.context.getDeclaredField("updateCount");
            oldCount.setAccessible(true); newCount.setAccessible(true);
            for (int n = 0; n < 2000; n++) {
                Context a = old.newContext(), b = now.newContext();
                int counter = n % 8 == 0 ? 0 : n + 1;
                oldCount.setInt(a, counter); newCount.setInt(b, counter);
                a.exec(); b.exec();
                check(((Probe) a).calculations == 1 && ((Probe) b).calculations == 0, "initial work was not deferred");
                a.shouldExecute(); check(b.shouldExecute(), "first worker pass was not eligible");
                check(oldCount.getInt(a) == newCount.getInt(b), "periodic counter changed");
                b.call(); check(((Probe) b).calculations == 1, "original calculation path lost");
                for (int pass = 0; pass < 25; pass++) {
                    check(a.shouldExecute() == b.shouldExecute(), "periodic scheduling drift after first pass");
                }
            }
            lifecycle(now);
            String report = "Installed Sound Control 4.0.5.0 + actual Mixin: passed.\n"
                    + "Only exec/shouldExecute changed. Original call/captureState/updateImpl/tick code preserved.\n"
                    + "2000 counter/random-state cases; first background pass forced; 50000 later scheduling decisions unchanged.\n"
                    + "Installed sound-start, source-stop and processSounds bytecode exercised with an isolated two-worker pool.\n"
                    + "Sound thread does no initial calculation; active source calculated on existing worker; moving source state refreshed.\n"
                    + "Invalid source, stop-before-first-pass, slot reuse, stop-during-calculation and registry reset passed.\n"
                    + "No game, OpenAL device, world or server started. Initial filter timing and in-game low FPS need player validation.\n";
            Files.createDirectories(Paths.get("build/reports"));
            Files.write(Paths.get("build/reports/deferred-sound-effects.txt"), report.getBytes(StandardCharsets.UTF_8));
            System.out.print(report);
        } finally { PoolBox.pool.shutdownNow(); }
    }

    private static void lifecycle(Harness h) throws Exception {
        Object invalid = h.source(0); h.start(new Sound(1), invalid);
        check(h.data(invalid) == null, "invalid source was registered");
        Object source = h.source(1); Sound sound = new Sound(7); h.start(sound, source);
        Probe p = (Probe) h.data(source);
        check(p.enabled && p.calculations == 0 && p.captures == 1, "sound setup/order changed");
        sound.x = 11; h.process();
        check(p.calculations == 1 && p.resultX == 11 && p.calculationThread.equals("sound-effects-check-worker"), "initial calculation was not a worker refresh");
        h.stop(source); int calls = p.calculations;
        for (int i = 0; i < 12; i++) { h.process(); }
        check(p.calculations == calls, "stopped source was rescheduled");

        h.start(new Sound(20), source); Probe stopped = (Probe) h.data(source); h.stop(source); h.process();
        check(stopped.calculations == 0, "stopped-before-first-pass source retained work");
        h.start(new Sound(30), source); Probe previous = (Probe) h.data(source);
        previous.entered = new CountDownLatch(1); previous.release = new CountDownLatch(1);
        FutureTask<Void> pass = new FutureTask<>(() -> { h.process(); return null; });
        Thread scheduler = new Thread(pass, "sound-effects-check-scheduler"); scheduler.start();
        try {
            check(previous.entered.await(5, TimeUnit.SECONDS), "worker did not start");
            h.stop(source); h.start(new Sound(40), source); Probe replacement = (Probe) h.data(source);
            check(replacement != previous && replacement.calculations == 0, "slot reuse lost isolation");
            previous.release.countDown(); pass.get(5, TimeUnit.SECONDS);
            check(previous.calculations == 1 && replacement.calculations == 0, "old result applied to replacement");
            h.process(); check(replacement.calculations == 1 && replacement.resultX == 40, "replacement did not initialize");
            h.start(new Sound(50), source); Probe beforeReset = (Probe) h.data(source);
            h.reset(); h.process(); check(beforeReset.calculations == 0, "registry reset retained deferred work");
        } finally { previous.release.countDown(); scheduler.join(5000); }
    }

    private static Harness harness(ClassNode context, ClassNode processor) throws Exception {
        Map<String, String> names = new HashMap<>();
        names.put(SOUND, Type.getInternalName(Sound.class));
        names.put(RANDOM, Type.getInternalName(RandomProbe.class));
        names.put("org/orecruncher/lib/Singleton", Type.getInternalName(PoolBox.class));
        names.put("org/orecruncher/lib/collections/ObjectArray", Type.getInternalName(Tasks.class));
        names.put(ROOT + "audio/SoundUtils", Type.getInternalName(Limits.class));
        names.put("org/orecruncher/lib/logging/IModLog", Type.getInternalName(Log.class));
        Loader loader = new Loader();
        ClassNode c = shell(CTX, Type.getInternalName(Probe.class)); c.interfaces.add(Type.getInternalName(Context.class));
        for (FieldNode f : context.fields) {
            if (f.name.equals("RANDOM") || f.name.equals("updateCount") || f.name.contains("initialSoundEffectsPending")) { c.fields.add(f); }
        }
        for (MethodNode m : context.methods) {
            if (m.name.equals("exec") || m.name.equals("call") || m.name.equals("shouldExecute") || m.name.contains("iris$")) { c.methods.add(m); }
        }
        for (String name : Arrays.asList("captureState", "updateImpl")) {
            MethodNode m = new MethodNode(ACC_PRIVATE, name, "()V", null, null);
            m.instructions.add(new VarInsnNode(ALOAD, 0)); m.instructions.add(new MethodInsnNode(INVOKESPECIAL, c.superName, name, "()V", false)); m.instructions.add(new InsnNode(RETURN)); c.methods.add(m);
        }
        constructor(c);
        MethodNode init = new MethodNode(ACC_STATIC, "<clinit>", "()V", null, null);
        init.instructions.add(new TypeInsnNode(NEW, RANDOM)); init.instructions.add(new InsnNode(DUP));
        init.instructions.add(new MethodInsnNode(INVOKESPECIAL, RANDOM, "<init>", "()V", false));
        init.instructions.add(new FieldInsnNode(PUTSTATIC, CTX, "RANDOM", "L" + RANDOM + ";")); init.instructions.add(new InsnNode(RETURN)); c.methods.add(init);
        Class<?> contextClass = loader.define(remap(c, names));
        loader.define(installed.getInputStream(installed.getEntry(DATA + ".class")).readAllBytes());
        ClassNode s = shell(SOURCE, "java/lang/Object"); s.interfaces.add(DATA); constructor(s);
        s.fields.add(new FieldNode(ACC_PUBLIC, "field_216441_b", "I", null, null)); s.fields.add(new FieldNode(ACC_PUBLIC, "data", "L" + CTX + ";", null, null));
        MethodNode get = new MethodNode(ACC_PUBLIC, "getData", "()L" + CTX + ";", null, null);
        get.instructions.add(new VarInsnNode(ALOAD, 0)); get.instructions.add(new FieldInsnNode(GETFIELD, SOURCE, "data", "L" + CTX + ";")); get.instructions.add(new InsnNode(ARETURN)); s.methods.add(get);
        MethodNode set = new MethodNode(ACC_PUBLIC, "setData", "(L" + CTX + ";)V", null, null);
        set.instructions.add(new VarInsnNode(ALOAD, 0)); set.instructions.add(new VarInsnNode(ALOAD, 1)); set.instructions.add(new FieldInsnNode(PUTFIELD, SOURCE, "data", "L" + CTX + ";")); set.instructions.add(new InsnNode(RETURN)); s.methods.add(set);
        Class<?> sourceClass = loader.define(bytes(s));
        ClassNode f = shell(FX, "java/lang/Object");
        for (FieldNode field : processor.fields) {
            if (Arrays.asList("sources", "threadPool", "LOGGER", "$assertionsDisabled").contains(field.name)) {
                f.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC, field.name, field.desc, null, null));
            }
        }
        for (MethodNode m : processor.methods) {
            if (m.name.equals("lambda$onSoundPlay$2") || m.name.equals("processSounds") || m.name.startsWith("lambda$processSounds$") || m.name.equals("stopSoundPlay")) { f.methods.add(m); }
        }
        Class<?> processorClass = loader.define(remap(f, names));
        processorClass.getField("threadPool").set(null, new PoolBox()); processorClass.getField("LOGGER").set(null, new CheckedLog());
        processorClass.getField("$assertionsDisabled").setBoolean(null, true);
        return new Harness(contextClass, sourceClass, processorClass);
    }

    private static class Harness {
        final Class<?> context, source, processor;
        final Method start, stop, process;
        Harness(Class<?> context, Class<?> source, Class<?> processor) throws Exception {
            this.context = context; this.source = source; this.processor = processor;
            start = processor.getDeclaredMethod("lambda$onSoundPlay$2", Sound.class, source); start.setAccessible(true);
            stop = processor.getDeclaredMethod("stopSoundPlay", source); stop.setAccessible(true);
            process = processor.getDeclaredMethod("processSounds"); process.setAccessible(true); reset();
        }
        Context newContext() throws Exception { return (Context) context.getConstructor().newInstance(); }
        Object source(int id) throws Exception { Object s = source.getConstructor().newInstance(); source.getField("field_216441_b").setInt(s, id); return s; }
        void start(Sound sound, Object s) throws Exception { start.invoke(null, sound, s); }
        void stop(Object s) throws Exception { stop.invoke(null, s); }
        void process() throws Exception { process.invoke(null); }
        Object data(Object s) throws Exception { return source.getField("data").get(s); }
        void reset() throws Exception { processor.getField("sources").set(null, java.lang.reflect.Array.newInstance(context, 4)); }
    }
    public static class Service extends FrameTimeInstrumentationCheck.Service {
        @Override public InputStream getResourceAsStream(String name) {
            byte[] data = resources.get(name); if (data != null) { return new ByteArrayInputStream(data); }
            if (installed != null && installed.getEntry(name) != null) {
                try { return installed.getInputStream(installed.getEntry(name)); } catch (IOException e) { throw new UncheckedIOException(e); }
            }
            return super.getResourceAsStream(name);
        }
    }
    private static ClassNode shell(String name, String parent) { ClassNode c = new ClassNode(); c.version = V1_8; c.access = ACC_PUBLIC; c.name = name; c.superName = parent; return c; }
    private static void constructor(ClassNode c) {
        MethodNode m = new MethodNode(ACC_PUBLIC, "<init>", "()V", null, null);
        m.instructions.add(new VarInsnNode(ALOAD, 0)); m.instructions.add(new MethodInsnNode(INVOKESPECIAL, c.superName, "<init>", "()V", false)); m.instructions.add(new InsnNode(RETURN)); c.methods.add(m);
    }
    private static int countCalls(ClassNode c, String owner, String name) {
        int n = 0; for (MethodNode m : c.methods) { for (AbstractInsnNode i : m.instructions) {
            if (i instanceof MethodInsnNode && ((MethodInsnNode) i).owner.equals(owner) && ((MethodInsnNode) i).name.equals(name)) { n++; }
        } } return n;
    }
    private static MethodNode method(ClassNode c, String name, String desc) { for (MethodNode m : c.methods) { if (m.name.equals(name) && m.desc.equals(desc)) { return m; } } throw new AssertionError(name); }
    private static byte[] remap(ClassNode c, Map<String, String> names) { ClassNode out = new ClassNode(); c.accept(new ClassRemapper(out, new SimpleRemapper(names))); return bytes(out); }
    private static ClassNode read(InputStream in) throws IOException { try (InputStream input = in) { ClassNode c = new ClassNode(); new ClassReader(input).accept(c, 0); return c; } }
    private static byte[] bytes(ClassNode c) { ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS); c.accept(w); return w.toByteArray(); }
    private static class Loader extends ClassLoader { Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); } }
    private static void check(boolean okay, String message) { if (!okay) { throw new AssertionError(message); } }
}
