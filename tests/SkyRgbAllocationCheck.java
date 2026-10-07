package net.coderbot.iris.diagnostics;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;
import net.coderbot.iris.math.SkyRgbSamplerOptimizer;
import net.coderbot.iris.math.VectorCubicSampler;
import net.coderbot.iris.mixin.compat.CompatMixinPlugin;
import net.minecraft.util.CubicSampler;
import net.minecraft.world.phys.Vec3;
import org.objectweb.asm.*;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import static org.objectweb.asm.Opcodes.*;

/** Installed sky lambda + real Mixin/plugin + differential JVM execution; no game or GL. */
public final class SkyRgbAllocationCheck {
    private static final String EXTERNAL = "com/minecraftabnormals/abnormals_core/core/mixin/client/ClientWorldMixin";
    private static final String TARGET = "net/minecraft/client/multiplayer/ClientLevel";
    private static final String VEC = "net/minecraft/world/phys/Vec3";
    private static final String BIOME = "net/minecraft/world/level/biome/Biome";
    private static final String MANAGER = "net/minecraft/world/level/biome/BiomeManager";
    private static final String SAMPLER = "net/minecraft/util/CubicSampler";
    private static final String MARKER = "net.coderbot.iris.mixin.compat.minecraft.MixinSkyRgbSampler";
    private static final Map<String, byte[]> resources = new HashMap<>();
    private static final RuntimeException ERROR = new IllegalStateException("sky query failure");
    private static ClassNode before, after;
    private static volatile Object sink;

    public interface Query { Vec3 run(Vec3 position, Manager manager); }
    public static class Manager {
        public int seed, count, colors, nullAt, failAt, failColorAt;
        public long trace;
        public boolean nested;
        public final Biome biome = new CustomBiome(this);
        public Manager(int seed) { this.seed = seed; }
        public Biome getNoiseBiomeAtQuart(int x, int y, int z) {
            count++;
            trace = ((trace * 31 + x) * 31 + y) * 31 + z;
            if (count == failAt) throw ERROR;
            biome.rgb = x * 73428767 ^ y * 912931 ^ z * 438289 ^ seed ^ count * 137;
            return count == nullAt ? null : biome;
        }
    }
    public static class Biome {
        protected final Manager manager;
        public int rgb;
        public Biome(Manager manager) { this.manager = manager; }
        public int getSkyColor() { throw new AssertionError("virtual biome override lost"); }
    }
    public static final class CustomBiome extends Biome {
        public CustomBiome(Manager manager) { super(manager); }
        @Override public int getSkyColor() {
            manager.colors++;
            if (manager.colors == manager.failColorAt) throw ERROR;
            if (manager.nested && manager.colors == 7) {
                Vec3 sample = VectorCubicSampler.sample(new Vec3(1.2D, -3.4D, 5.6D),
                        (VectorCubicSampler.RgbFetcher) (x, y, z) -> x * 413 ^ y * 717 ^ z * 977);
                return rgb ^ (int) (sample.x * 65536);
            }
            return rgb;
        }
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 2, "expected JVM mode and installed Abnormals jar");
        ClassNode external;
        try (ZipFile zip = new ZipFile(args[1]); InputStream in = zip.getInputStream(zip.getEntry(EXTERNAL + ".class"))) {
            external = read(in);
        }
        ClassNode production = remap(external, new SimpleRemapper(EXTERNAL, "net/minecraft/client/world/ClientWorld"));
        check(SkyRgbSamplerOptimizer.optimize(production) == 1, "installed production SRG handler not recognized");
        check(SkyRgbSamplerOptimizer.optimize(production) == 0, "SRG transformation must be idempotent");
        checkOuterUnchanged(external, production);

        ClassNode source = named(external);
        byte[] original = fixture(source);
        resources.put(TARGET + ".class", original);
        resources.put("sky-rgb-check.json", ("{\"required\":true,\"minVersion\":\"0.8\",\"compatibilityLevel\":\"JAVA_8\","
                + "\"package\":\"net.coderbot.iris.mixin.compat.minecraft\",\"plugin\":\"" + Plugin.class.getName()
                + "\",\"client\":[\"MixinSkyRgbSampler\"]}").getBytes(StandardCharsets.UTF_8));
        MixinBootstrap.init();
        MixinEnvironment env = MixinEnvironment.getDefaultEnvironment(); env.setSide(MixinEnvironment.Side.CLIENT);
        Mixins.addConfiguration("sky-rgb-check.json");
        byte[] transformed = ((Service) MixinService.getService()).transformer().transformClass(env, TARGET.replace('/', '.'), original);
        check(before != null && after != null, "production postApply did not execute");
        check(after.methods.size() == before.methods.size() + 1, "expected exactly one scalar helper");
        check(SkyRgbSamplerOptimizer.optimize(read(transformed)) == 0, "named transformation must be idempotent");
        Query legacy = load(original, true), optimized = load(transformed, true), vanillaFallback = load(transformed, false);
        compareQueries(legacy, optimized, vanillaFallback);
        genericApiChecks();
        unknownChecks(original);
        allocation(legacy, optimized, args[0]);
        System.out.println("Sky RGB: installed Abnormals SRG lambda, real marker Mixin/plugin, 10,000 random cases plus boundaries,"
                + " 216 ordered live queries, biome overrides, exceptions, nested/concurrent calls and vanilla sampler fallback passed.");
    }

    private static void compareQueries(Query legacy, Query optimized, Query fallback) throws Exception {
        double[] edge = {-30_000_000.0D, -1.0D, -Double.MIN_VALUE, -0.0D, 0.0D, 0.999999999D,
                1.0D, 30_000_000.0D, Double.MAX_VALUE, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NaN};
        for (double x : edge) for (double y : edge) {
            Vec3 position = new Vec3(x, y, -x);
            compare(legacy, optimized, position, 0x81234567, 0, 0);
            compare(legacy, fallback, position, 0x81234567, 0, 0);
        }
        Random random = new Random(137442);
        for (int i = 0; i < 10_000; i++) {
            Vec3 position = new Vec3(random.nextDouble() * 60_000_000 - 30_000_000,
                    random.nextDouble() * 512 - 128, random.nextDouble() * 60_000_000 - 30_000_000);
            compare(legacy, optimized, position, random.nextInt(), 0, 0);
        }
        Vec3 position = new Vec3(-12.25D, 31.5D, 256.0D);
        for (int mode = 1; mode <= 4; mode++) for (int at : new int[]{1, 2, 7, 108, 216}) {
            compare(legacy, optimized, position, -1, mode, at);
            compare(legacy, fallback, position, -1, mode, at);
        }
        Vec3 retained = optimized.run(position, new Manager(17));
        Vec3 copy = new Vec3(retained.x, retained.y, retained.z);
        optimized.run(position, new Manager(999)); same(copy, retained);
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int n = 0; n < 4; n++) {
                final int seed = n;
                futures.add(workers.submit(() -> {
                    for (int i = 0; i < 300; i++) compare(legacy, optimized, position, seed + i * 17, i % 5 == 0 ? 4 : 0, 7);
                }));
            }
            for (Future<?> future : futures) future.get();
        } finally { workers.shutdown(); }
    }

    private static void compare(Query a, Query b, Vec3 position, int seed, int mode, int at) {
        Manager left = manager(seed, mode, at), right = manager(seed, mode, at);
        Object expected = result(a, position, left), actual = result(b, position, right);
        if (expected instanceof Vec3) same((Vec3) expected, (Vec3) actual);
        else check(expected.equals(actual), "exception identity/type changed");
        check(left.count == right.count && left.colors == right.colors && left.trace == right.trace, "live query count/order changed");
        if (mode == 0 || mode == 4) check(right.count == 216 && right.colors == 216, "expected all 216 samples, including zero weights");
    }

    private static Manager manager(int seed, int mode, int at) {
        Manager manager = new Manager(seed);
        if (mode == 1) manager.failAt = at;
        if (mode == 2) manager.failColorAt = at;
        if (mode == 3) manager.nullAt = at;
        manager.nested = mode == 4;
        return manager;
    }

    private static Object result(Query query, Vec3 position, Manager manager) {
        try { return query.run(position, manager); }
        catch (NullPointerException failure) { return failure.getClass(); }
        catch (RuntimeException failure) { return failure; }
    }

    private static void genericApiChecks() {
        Random random = new Random(734981);
        Vec3 position = new Vec3(-7.25D, 19.125D, 27.9375D);
        for (int i = 0; i < 500; i++) {
            final int value = random.nextInt();
            VectorCubicSampler.RgbFetcher rgb = (x, y, z) -> value;
            same(Vec3.fromRGB24(value), rgb.fetch(17, -1, 23));
            check(rgb.fetch(0, 0, 0) != rgb.fetch(0, 0, 0), "vanilla API must still return independent vectors");
            same(CubicSampler.gaussianSampleVec3(position, rgb), VectorCubicSampler.sample(position, rgb));
        }
        for (CubicSampler.Vec3Fetcher generic : Arrays.<CubicSampler.Vec3Fetcher>asList(
                (x, y, z) -> new Vec3(x, y, z), (x, y, z) -> new Vec3(x / 255.0D, 0, 0))) {
            same(CubicSampler.gaussianSampleVec3(position, generic), VectorCubicSampler.sample(position, generic));
        }
    }

    private static void allocation(Query legacy, Query optimized, String mode) {
        Vec3 position = new Vec3(7.25D, -9.125D, 273.875D);
        Manager manager = new Manager(81791);
        // The real generic sampler is also used by fog and visibility fetchers. Warm those
        // distinct call types so the baseline is not an artificially monomorphic sky-only loop.
        CubicSampler.Vec3Fetcher fog = (x, y, z) -> new Vec3(x * 0.017, y * 0.003, z * 0.011);
        CubicSampler.Vec3Fetcher visibility = (x, y, z) -> new Vec3((x ^ y ^ z) & 255, 0, 0);
        for (int i = 0; i < 30_000; i++) {
            sink = legacy.run(position, manager); sink = optimized.run(position, manager);
            sink = VectorCubicSampler.sample(position, fog); sink = VectorCubicSampler.sample(position, visibility);
        }
        long oldBytes = allocated(() -> { for (int i = 0; i < 20_000; i++) sink = legacy.run(position, manager); });
        long newBytes = allocated(() -> { for (int i = 0; i < 20_000; i++) sink = optimized.run(position, manager); });
        check(newBytes < 2_000_000, "packed RGB path still allocates per-point vectors");
        if (mode.equals("no-ea")) check(oldBytes - newBytes >= 172_000_000, "expected 216 removed vectors per sky query");
        System.out.println("20,000 sky samples, allocation bytes (" + mode + "): " + oldBytes + " -> " + newBytes
                + "; final result vectors and capturing fetchers retained; not a game FPS measurement");
    }

    private static void unknownChecks(byte[] original) {
        for (int mode = 0; mode < 6; mode++) {
            ClassNode node = read(original);
            MethodNode lambda = node.methods.stream().filter(m -> m.name.contains("lambda$")).findFirst().get();
            MethodNode run = node.methods.stream().filter(m -> m.name.equals("run")).findFirst().get();
            InvokeDynamicInsnNode indy = null;
            for (AbstractInsnNode n : run.instructions) if (n instanceof InvokeDynamicInsnNode) indy = (InvokeDynamicInsnNode) n;
            if (mode == 0) lambda.instructions.insert(new InsnNode(NOP));
            if (mode == 1) lambda.access = ACC_PUBLIC | ACC_STATIC | ACC_SYNTHETIC;
            if (mode == 2) {
                for (AbstractInsnNode n : lambda.instructions) if (n instanceof MethodInsnNode
                        && ((MethodInsnNode) n).name.equals("getSkyColor")) ((MethodInsnNode) n).name = "otherColor";
            }
            if (mode == 3) run.instructions.insert(indy, new LabelNode());
            if (mode == 4) indy.bsm = new Handle(H_INVOKESTATIC, "fixture/OtherBootstrap", "metafactory", indy.bsm.getDesc(), false);
            if (mode == 5) indy.bsmArgs[2] = Type.getMethodType("(III)Ljava/lang/Object;");
            byte[] before = bytes(node);
            check(SkyRgbSamplerOptimizer.optimize(node) == 0, "unsupported implementation was rewritten: " + mode);
            check(Arrays.equals(before, bytes(node)), "unsupported implementation partially modified");
        }
    }

    private static void checkOuterUnchanged(ClassNode external, ClassNode production) {
        ClassNode renamed = remap(external, new SimpleRemapper(EXTERNAL, production.name));
        for (int i = 0; i < renamed.methods.size(); i++) {
            MethodNode left = renamed.methods.get(i), right = production.methods.get(i);
            List<AbstractInsnNode> a = instructions(left), b = instructions(right);
            check(a.size() == b.size(), "original method structure changed");
            // Revert only the recognized invokedynamic; everything else must remain byte-identical.
            for (int j = 0; j < a.size(); j++) if (a.get(j) instanceof InvokeDynamicInsnNode
                    && ((InvokeDynamicInsnNode) a.get(j)).name.equals("fetch")) {
                InvokeDynamicInsnNode patched = (InvokeDynamicInsnNode) b.get(j);
                check(patched.name.equals("fetchRgb"), "expected packed sky fetcher");
                InvokeDynamicInsnNode old = (InvokeDynamicInsnNode) a.get(j);
                patched.name = old.name; patched.desc = old.desc; patched.bsmArgs = old.bsmArgs.clone();
            }
        }
        production.methods.remove(production.methods.size() - 1);
        check(Arrays.equals(bytes(renamed), bytes(production)), "weather, time, dimension conditions or original lambda changed");
    }

    private static ClassNode named(ClassNode external) {
        Map<String, String> map = new HashMap<>();
        map.put(EXTERNAL, TARGET);
        map.put("net/minecraft/util/math/vector/Vector3d", VEC);
        map.put("net/minecraft/world/biome/Biome", BIOME);
        map.put("net/minecraft/world/biome/BiomeManager", MANAGER);
        return remap(external, new Remapper() {
            @Override public String map(String name) { return map.getOrDefault(name, name); }
            @Override public String mapMethodName(String owner, String name, String desc) {
                switch (name) {
                    case "func_235199_a_": return "getNoiseBiomeAtQuart";
                    case "func_225529_c_": return "getSkyColor";
                    case "func_237487_a_": return "fromRGB24";
                    case "func_240807_a_": return "gaussianSampleVec3";
                    default: return name;
                }
            }
        });
    }

    private static byte[] fixture(ClassNode source) {
        ClassNode target = new ClassNode(); target.visit(V1_8, ACC_PUBLIC, TARGET, null, "java/lang/Object", null);
        MethodNode constructor = new MethodNode(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.instructions.add(new VarInsnNode(ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        constructor.instructions.add(new InsnNode(RETURN)); target.methods.add(constructor);
        MethodNode original = source.methods.stream().filter(m -> m.name.equals("lambda$getSkyColor$0")).findFirst().get();
        target.methods.add(original);
        InvokeDynamicInsnNode indy = null; MethodInsnNode sampler = null;
        for (MethodNode m : source.methods) if (m.name.equals("getSkyColor")) for (AbstractInsnNode n : m.instructions) {
            if (n instanceof InvokeDynamicInsnNode && ((InvokeDynamicInsnNode) n).name.equals("fetch")) indy = (InvokeDynamicInsnNode) n;
            if (n instanceof MethodInsnNode && ((MethodInsnNode) n).owner.equals(SAMPLER)) sampler = (MethodInsnNode) n;
        }
        check(indy != null && sampler != null, "installed sky sampling site missing");
        MethodNode run = new MethodNode(ACC_PUBLIC, "run", "(L" + VEC + ";L" + MANAGER + ";)L" + VEC + ";", null, null);
        run.instructions.add(new VarInsnNode(ALOAD, 1)); run.instructions.add(new VarInsnNode(ALOAD, 2));
        run.instructions.add(indy.clone(null)); run.instructions.add(sampler.clone(null)); run.instructions.add(new InsnNode(ARETURN));
        target.methods.add(run); return bytes(target);
    }

    private static Query load(byte[] data, boolean scalarSampler) throws Exception {
        Map<String, String> names = new HashMap<>(); names.put(MANAGER, Type.getInternalName(Manager.class)); names.put(BIOME, Type.getInternalName(Biome.class));
        if (scalarSampler) names.put(SAMPLER, Type.getInternalName(VectorCubicSampler.class));
        ClassNode node = remap(read(data), new Remapper() {
            @Override public String map(String name) { return names.getOrDefault(name, name); }
            @Override public String mapMethodName(String owner, String name, String desc) {
                return scalarSampler && owner.equals(SAMPLER) && name.equals("gaussianSampleVec3") ? "sample" : name;
            }
        });
        node.interfaces.add(Type.getInternalName(Query.class));
        return (Query) new Loader().define(bytes(node)).getDeclaredConstructor().newInstance();
    }

    public static class Plugin extends CompatMixinPlugin {
        @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {
            check(mixin.equals(MARKER), "unexpected test mixin");
            before = read(bytes(node)); super.postApply(target, node, mixin, info); after = read(bytes(node));
        }
    }
    public static class Service extends FrameTimeInstrumentationCheck.Service {
        @Override public InputStream getResourceAsStream(String name) {
            byte[] data = resources.get(name); return data == null ? super.getResourceAsStream(name) : new ByteArrayInputStream(data);
        }
    }
    private static class Loader extends ClassLoader {
        Loader() { super(SkyRgbAllocationCheck.class.getClassLoader()); }
        Class<?> define(byte[] data) { return defineClass(null, data, 0, data.length); }
    }
    private static long allocated(Runnable action) {
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().getId(), start = bean.getThreadAllocatedBytes(id); action.run(); return bean.getThreadAllocatedBytes(id) - start;
    }
    private static List<AbstractInsnNode> instructions(MethodNode method) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode node : method.instructions) if (node.getOpcode() >= 0) result.add(node);
        return result;
    }
    private static ClassNode remap(ClassNode source, Remapper remapper) {
        ClassNode target = new ClassNode(); source.accept(new ClassRemapper(target, remapper)); return target;
    }
    private static ClassNode read(InputStream input) throws IOException { ClassNode node = new ClassNode(); new ClassReader(input).accept(node, 0); return node; }
    private static ClassNode read(byte[] data) { ClassNode node = new ClassNode(); new ClassReader(data).accept(node, 0); return node; }
    private static byte[] bytes(ClassNode node) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
    }
    private static void same(Vec3 expected, Vec3 actual) {
        check(Double.doubleToLongBits(expected.x) == Double.doubleToLongBits(actual.x)
                && Double.doubleToLongBits(expected.y) == Double.doubleToLongBits(actual.y)
                && Double.doubleToLongBits(expected.z) == Double.doubleToLongBits(actual.z), "RGB result differs from original");
    }
    private static void check(boolean okay, String message) { if (!okay) throw new AssertionError(message); }
}
