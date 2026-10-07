package net.coderbot.iris.diagnostics;

import com.seibel.distanthorizons.api.objects.math.DhApiVec3d;
import com.seibel.distanthorizons.api.objects.render.DhApiRenderableBox;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import com.seibel.distanthorizons.core.util.math.DhVec3f;
import java.awt.Color;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import net.coderbot.iris.compat.dh.DhCloudAllocationOptimizer;
import net.coderbot.iris.compat.dh.DhCloudCoordinates;
import net.coderbot.iris.compat.dh.mixin.IrisDHCompatMixinPlugin;
import org.objectweb.asm.*;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import static org.objectweb.asm.Opcodes.*;

/** Installed DH method bodies, real Mixin/plugin, JVM verification and differential execution; no game or GL. */
public final class DhCloudAllocationCheck {
    private static final String CLOUD = DhCloudAllocationOptimizer.CLOUD, CAMERA = DhCloudAllocationOptimizer.CAMERA,
            GROUP = DhCloudAllocationOptimizer.GROUP, VECTOR = DhCloudAllocationOptimizer.VECTOR,
            API_VECTOR = DhCloudAllocationOptimizer.API_VECTOR, BRIDGE = Type.getInternalName(DhCloudCoordinates.class);
    private static final String ROOT = "com/seibel/distanthorizons/";
    private static final Map<String, byte[]> resources = new HashMap<>();
    private static final Map<String, ClassNode> before = new HashMap<>(), after = new HashMap<>();
    private static final RuntimeException ERROR = new IllegalStateException("cloud test callback");
    private static volatile double sink;
    private static long cases;

    public interface CameraApi { DhVec3d getCameraExactPosition(); DhVec3f getLookAtVector(); }
    public interface GroupApi extends Iterable<DhApiRenderableBox> {
        void setOriginBlockPos(DhApiVec3d value); DhApiVec3d getOriginBlockPos();
        void setActive(boolean value); void triggerBoxChange();
    }
    public interface Render { void run(Param param, Params params); }
    public interface HeightLevel { int getMaxHeight(); }
    public interface ClientLevel extends HeightLevel { Color getCloudColor(float partial); }
    public interface DhLevel { ClientLevel getClientLevelWrapper(); HeightLevel getLevelWrapper(); }
    public interface Portals { DhVec3d getActualCameraPos(); }
    public static class Param { public float partialTicks; }
    public static class Params {
        public int instanceOffsetX, instanceOffsetY, instanceOffsetZ, widthInBlocks, halfWidthInBlocks, heightOffset;
        public float heightSpeedOffset, deltaOffsetX, deltaOffsetZ;
        public long lastFrameTime;
        public Color previousColor = Color.WHITE;
        Params copy() {
            Params p = new Params();
            for (Field f : Params.class.getFields()) { try { f.set(p, f.get(this)); } catch (Exception e) { throw new AssertionError(e); } }
            return p;
        }
    }
    public static class Entry {
        public Object value;
        Entry(Object value) { this.value = value; }
        public Object get() { return value; }
    }
    public static class Config {
        public static Entry enableCloudRendering = new Entry(true), enableMultiLayerClouds = new Entry(true);
    }
    public static class Quality { public static Entry lodChunkRenderDistanceRadius = new Entry(128); }
    public static class Clock { public static long time; public static long currentTimeMillis() { return time; } }
    public static class Delayed { public static Portals IMMERSIVE_PORTALS; }
    public static class ThreadState {
        public static final ThreadState INSTANCE = new ThreadState();
        public static boolean current;
        public boolean isCurrentThread() { Probe.threadReads++; return current; }
    }
    public static class MC { public Renderer field_71460_t = new Renderer(); }
    public static class Renderer { public View func_215316_n() { Probe.rendererReads++; return Probe.view; } }
    public static class Position { public double field_72450_a, field_72448_b, field_72449_c; }
    public static class View {
        public Position func_216785_c() {
            if (++Probe.cameraReads == Probe.failCamera) { throw ERROR; }
            Probe.position.field_72450_a = Probe.x + (Probe.changing ? Probe.cameraReads * 17 : 0);
            Probe.position.field_72448_b = Probe.y;
            Probe.position.field_72449_c = Probe.z - (Probe.changing ? Probe.cameraReads * 11 : 0);
            return Probe.position;
        }
    }
    public static class Level implements DhLevel, ClientLevel {
        public ClientLevel getClientLevelWrapper() { Probe.levelReads++; return Probe.noLevel ? null : this; }
        public HeightLevel getLevelWrapper() { return this; }
        public int getMaxHeight() { return Probe.height; }
        public Color getCloudColor(float partial) { Probe.colorReads++; if (Probe.failColor) { throw ERROR; } return Probe.color; }
    }
    public static abstract class BaseGroup extends ArrayList<DhApiRenderableBox> implements GroupApi {
        public boolean active;
        public int activeCalls, dirtyCalls;
        public DhApiVec3d last, previous;
        public void setActive(boolean value) { active = value; activeCalls++; }
        public void triggerBoxChange() { dirtyCalls++; }
        public void retain(DhApiVec3d value) { previous = last; last = value; }
    }
    public static class RetainingGroup extends BaseGroup {
        public void setOriginBlockPos(DhApiVec3d value) { retain(value); }
        public DhApiVec3d getOriginBlockPos() { return last == null ? new DhApiVec3d() : last; }
    }
    public static class CustomCamera implements CameraApi {
        public DhVec3d getCameraExactPosition() { return Probe.customCamera(); }
        public DhVec3f getLookAtVector() { return Probe.look(); }
    }
    public static class Probe {
        public static double x, y, z;
        public static int height, cameraReads, rendererReads, portalReads, threadReads, customReads, colorReads, levelReads, lookReads, failCamera;
        public static boolean changing, noLevel, failColor;
        public static Color color;
        public static final Position position = new Position();
        public static final View view = new View();
        public static DhVec3d portal;
        public static void reset() { cameraReads = rendererReads = portalReads = threadReads = customReads = colorReads = levelReads = lookReads = 0; }
        public static DhVec3d portal() { portalReads++; return portal; }
        public static DhVec3d customCamera() { customReads++; return new DhVec3d(x + customReads * 3, y, z - customReads * 2); }
        public static DhVec3f look() { lookReads++; return new DhVec3f(0.1f, 0.2f, -0.9f); }
        public static int[] calls() { return new int[]{cameraReads, rendererReads, portalReads, threadReads, customReads, colorReads, levelReads, lookReads}; }
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 2, "expected allocation mode and installed DH jar");
        Map<String, ClassNode> installed = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(args[1])) {
            for (String name : Arrays.asList(CAMERA, GROUP, CLOUD)) {
                ClassNode original = read(zip.getInputStream(zip.getEntry(name + ".class")));
                installed.put(name, original);
                ClassNode changed = copy(original);
                check(DhCloudAllocationOptimizer.optimize(changed) == (name.equals(CAMERA) ? 2 : 1), "installed body unsupported: " + name);
                checkOriginalMethods(original, changed);
                checkFallback(original, name.equals(CAMERA) ? "getCameraExactPosition" : name.equals(GROUP) ? "setOriginBlockPos" : "preRender");
                resources.put(name + ".class", bytes(fixture(original)));
            }
        }
        String config = "{\"required\":true,\"minVersion\":\"0.8\",\"compatibilityLevel\":\"JAVA_8\",\"package\":\"net.coderbot.iris.compat.dh.mixin\","
                + "\"plugin\":\"" + Plugin.class.getName() + "\",\"client\":[\"MixinDHCloudAllocation\"]}";
        resources.put("dh-cloud-check.json", config.getBytes(StandardCharsets.UTF_8));
        MixinBootstrap.init(); MixinEnvironment env = MixinEnvironment.getDefaultEnvironment(); env.setSide(MixinEnvironment.Side.CLIENT);
        Mixins.addConfiguration("dh-cloud-check.json");
        Map<String, byte[]> transformed = new LinkedHashMap<>();
        org.spongepowered.asm.mixin.transformer.IMixinTransformer transformer = ((Service) MixinService.getService()).transformer();
        for (String name : installed.keySet()) {
            byte[] result = transformer.transformClass(env, name.replace('/', '.'), resources.get(name + ".class"));
            check(before.containsKey(name) && after.containsKey(name), "production plugin did not execute for " + name);
            check(DhCloudAllocationOptimizer.optimize(after.get(name)) == 0, "not idempotent: " + name);
            transformed.put(name, result);
        }
        Map<String, byte[]> originals = new LinkedHashMap<>();
        for (String name : installed.keySet()) { originals.put(name, bytes(before.get(name))); }
        Harness old = harness(originals), now = harness(transformed);
        Random random = new Random(0xdac10adL);
        for (int i = 0; i < 12000; i++) { semantics(old, now, random, i); }
        ownership(old, now);
        String allocation = allocations(old, now, args[0]);
        String report = "Installed DH 3.2.0-b method bodies + production Mixin/plugin + -Xverify:all passed.\n"
                + cases + " differential cases: cloud motion, layer/config switches, null level, culling, color, changing camera, portal/thread paths, exceptions and custom/subclass fallbacks.\n"
                + "Public camera/setter/getter and complete culling method bytecode retained. Fresh-vector API ownership preserved.\n"
                + "Unknown method signatures/injections and repeated application leave original bytecode untouched.\n"
                + allocation + "\nNo game, world, GL context or server was started. In-game low FPS requires player verification.\n";
        Files.createDirectories(Paths.get("build/reports"));
        Files.write(Paths.get("build/reports/dh-cloud-allocation-" + args[0] + ".txt"), report.getBytes(StandardCharsets.UTF_8));
        System.out.print(report);
    }

    private static void semantics(Harness old, Harness now, Random random, int i) throws Exception {
        Config.enableCloudRendering.value = i % 9 != 0; Config.enableMultiLayerClouds.value = i % 5 != 0;
        Quality.lodChunkRenderDistanceRadius.value = 16 + random.nextInt(1000);
        ThreadState.current = (i / 3) % 3 == 0; Delayed.IMMERSIVE_PORTALS = i % 4 == 0 ? null : Probe::portal;
        Probe.portal = i % 3 == 1 ? null : new DhVec3d(random.nextDouble() * 60000000 - 30000000, 256, random.nextDouble() * 60000000 - 30000000);
        Probe.x = random.nextDouble() * 60000000 - 30000000; Probe.y = random.nextDouble() * 1000; Probe.z = -Probe.x * 0.7;
        double[] edges = {0, -0.0, -1, -4096, 4096, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        if (i % 13 == 0) { Probe.x = edges[i % edges.length]; Probe.z = edges[(i + 3) % edges.length]; }
        Probe.height = 128 + random.nextInt(1024); Probe.changing = i % 2 == 0;
        Probe.noLevel = i % 31 == 0; Probe.failCamera = i % 37 == 0 ? 2 : 0; Probe.failColor = i % 41 == 0;
        Probe.color = i % 2 == 0 ? Color.WHITE : Color.BLUE; Clock.time = 1000000 + i * 17;
        Params pa = new Params(); pa.widthInBlocks = (1 + random.nextInt(256)) * 128; pa.halfWidthInBlocks = pa.widthInBlocks / 2;
        pa.instanceOffsetY = i % 3; pa.instanceOffsetX = random.nextInt(11) - 5; pa.instanceOffsetZ = random.nextInt(11) - 5;
        pa.heightOffset = pa.instanceOffsetY * 512; pa.heightSpeedOffset = pa.instanceOffsetY * 10;
        pa.deltaOffsetX = random.nextFloat() * pa.widthInBlocks; pa.deltaOffsetZ = random.nextFloat() * pa.widthInBlocks;
        pa.lastFrameTime = Clock.time - random.nextInt(30000);
        Params pb = pa.copy(); Param param = new Param(); param.partialTicks = random.nextFloat();
        BaseGroup ga = old.group((i / 11) % 3), gb = now.group((i / 11) % 3);
        for (BaseGroup group : Arrays.asList(ga, gb)) {
            group.add(new DhApiRenderableBox(new DhApiVec3d(), new DhApiVec3d(1, 1, 1), Color.WHITE,
                    com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial.values()[0]));
        }
        old.setup(pa, ga, (i / 7) % 3); now.setup(pb, gb, (i / 7) % 3);
        Probe.reset(); Throwable a = draw(old.render, param, pa); int[] calls = Probe.calls();
        Probe.reset(); Throwable b = draw(now.render, param, pb);
        check(a == b && Arrays.equals(calls, Probe.calls()), "callback/exception mismatch " + i);
        compare(ga, gb, pa, pb); cases++;
    }

    private static void compare(BaseGroup a, BaseGroup b, Params pa, Params pb) {
        check(a.active == b.active && a.activeCalls == b.activeCalls && a.dirtyCalls == b.dirtyCalls, "group state changed");
        for (int i = 0; i < a.size(); i++) { check(a.get(i).color.equals(b.get(i).color), "box color changed"); }
        DhApiVec3d av = a.getOriginBlockPos(), bv = b.getOriginBlockPos();
        check(bits(av.x) == bits(bv.x) && bits(av.y) == bits(bv.y) && bits(av.z) == bits(bv.z), "cloud coordinates differ");
        check(Float.floatToIntBits(pa.deltaOffsetX) == Float.floatToIntBits(pb.deltaOffsetX)
                && pa.lastFrameTime == pb.lastFrameTime && pa.previousColor.equals(pb.previousColor), "cloud movement/color state differs");
    }

    private static void ownership(Harness old, Harness now) throws Exception {
        for (Harness h : Arrays.asList(old, now)) {
            CameraApi camera = h.camera(0); Probe.x = 1; Probe.y = 2; Probe.z = 3; Probe.changing = false;
            Probe.failCamera = 0; Delayed.IMMERSIVE_PORTALS = null;
            DhVec3d first = camera.getCameraExactPosition(), second = camera.getCameraExactPosition();
            check(first != second && first.x == second.x, "public camera vector ownership changed");
            for (int mode = 0; mode < 3; mode++) {
                BaseGroup group = h.group(mode);
                h.scalarSetter.invoke(null, group, 11.0, 22.0, 33.0);
                DhApiVec3d got = group.getOriginBlockPos();
                h.scalarSetter.invoke(null, group, -44.0, -55.0, -66.0);
                check(got.x == 11 && got.y == 22 && got.z == 33, "previous getter/retained vector mutated");
                if (mode > 0) { check(group.last != group.previous && group.previous.x == got.x, "custom setter did not receive a fresh vector"); }
            }
        }
    }

    private static String allocations(Harness old, Harness now, String mode) throws Exception {
        Config.enableCloudRendering.value = Config.enableMultiLayerClouds.value = true;
        Probe.noLevel = Probe.failColor = Probe.changing = false; Probe.failCamera = 0; Probe.color = Color.WHITE;
        Probe.x = 13; Probe.y = 200; Probe.z = -20; Probe.height = 256; Delayed.IMMERSIVE_PORTALS = null;
        Params pa = new Params(); pa.widthInBlocks = 32768; pa.halfWidthInBlocks = 16384; pa.lastFrameTime = Clock.time;
        Params pb = pa.copy(); Param param = new Param();
        BaseGroup ga = old.group(0), gb = now.group(0); old.setup(pa, ga, 0); now.setup(pb, gb, 0);
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        check(bean.isThreadAllocatedMemorySupported(), "allocation counter unavailable"); bean.setThreadAllocatedMemoryEnabled(true);
        work(old.render, param, pa, 150000); work(now.render, param, pb, 150000);
        long tid = Thread.currentThread().getId(), at = bean.getThreadAllocatedBytes(tid);
        work(old.render, param, pa, 200000); long oldBytes = bean.getThreadAllocatedBytes(tid) - at;
        at = bean.getThreadAllocatedBytes(tid); work(now.render, param, pb, 200000); long newBytes = bean.getThreadAllocatedBytes(tid) - at;
        compare(ga, gb, pa, pb);
        check(newBytes < 200000, "optimized three-coordinate path still allocates >=1 byte per call");
        if (mode.equals("no-ea")) { check(oldBytes >= 24000000 && newBytes < oldBytes / 100, "three temporary vectors were not removed"); }
        return "200000 cloud-group updates (" + mode + "): allocated bytes " + oldBytes + " -> " + newBytes + ". This is an isolated allocation check, not a game FPS measurement.";
    }
    private static void work(Render render, Param param, Params params, int count) {
        for (int i = 0; i < count; i++) { render.run(param, params); }
        sink = params.deltaOffsetX;
    }
    private static Throwable draw(Render render, Param param, Params params) {
        try { render.run(param, params); return null; } catch (RuntimeException e) { return e; }
    }
    private static long bits(double x) { return Double.doubleToLongBits(x); }

    public static class Plugin extends IrisDHCompatMixinPlugin {
        @Override public void onLoad(String pkg) { }
        @Override public boolean shouldApplyMixin(String target, String mixin) { return true; }
        @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {
            before.put(node.name, copy(node)); super.postApply(target, node, mixin, info); after.put(node.name, copy(node));
        }
    }
    public static class Service extends FrameTimeInstrumentationCheck.Service {
        @Override public InputStream getResourceAsStream(String name) {
            byte[] data = resources.get(name); return data == null ? super.getResourceAsStream(name) : new ByteArrayInputStream(data);
        }
    }

    private static ClassNode fixture(ClassNode original) {
        ClassNode result = new ClassNode(); result.version = V1_8; result.access = ACC_PUBLIC; result.name = original.name;
        result.superName = original.name.equals(GROUP) ? Type.getInternalName(BaseGroup.class) : "java/lang/Object";
        Set<String> methods = original.name.equals(CAMERA) ? Collections.singleton("getCameraExactPosition")
                : original.name.equals(GROUP) ? new HashSet<>(Arrays.asList("setOriginBlockPos", "getOriginBlockPos"))
                : new HashSet<>(Arrays.asList("preRender", "shouldCloudBeCulled"));
        Set<String> fields = original.name.equals(CAMERA) ? Collections.singleton("MC")
                : original.name.equals(GROUP) ? Collections.singleton("originBlockPos")
                : new HashSet<>(Arrays.asList("MC_RENDER", "boxGroupByOffset", "level", "cullingCorners"));
        for (MethodNode m : original.methods) { if (methods.contains(m.name)) { result.methods.add(copy(m)); } }
        for (FieldNode f : original.fields) {
            if (fields.contains(f.name)) { result.fields.add(new FieldNode(ACC_PUBLIC | (f.access & ACC_STATIC), f.name, f.desc, null, null)); }
        }
        MethodNode ctor = new MethodNode(ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.instructions.add(new VarInsnNode(ALOAD, 0)); ctor.instructions.add(new MethodInsnNode(INVOKESPECIAL, result.superName, "<init>", "()V", false));
        if (original.name.equals(GROUP)) {
            ctor.instructions.add(new VarInsnNode(ALOAD, 0)); ctor.instructions.add(new TypeInsnNode(NEW, API_VECTOR)); ctor.instructions.add(new InsnNode(DUP));
            ctor.instructions.add(new MethodInsnNode(INVOKESPECIAL, API_VECTOR, "<init>", "()V", false));
            ctor.instructions.add(new FieldInsnNode(PUTFIELD, GROUP, "originBlockPos", "L" + API_VECTOR + ";"));
        }
        ctor.instructions.add(new InsnNode(RETURN)); result.methods.add(ctor);
        return result;
    }

    private static class Harness {
        final Class<?> cloudClass, cameraClass, cameraSubclass, groupClass, groupSubclass;
        final Render render;
        final Method scalarSetter;
        Harness(Loader loader) throws Exception {
            cameraClass = loader.loadClass("fixture.Camera"); groupClass = loader.loadClass("fixture.Group"); cloudClass = loader.loadClass("fixture.Cloud");
            cameraSubclass = loader.loadClass("fixture.CameraSubclass"); groupSubclass = loader.loadClass("fixture.GroupSubclass");
            cameraClass.getField("MC").set(null, new MC()); render = (Render) cloudClass.getConstructor().newInstance();
            scalarSetter = loader.loadClass("fixture.Coordinates").getMethod("setOrigin", GroupApi.class, double.class, double.class, double.class);
            cloudClass.getField("level").set(render, new Level());
            cloudClass.getField("cullingCorners").set(render, new DhVec3d[]{new DhVec3d(), new DhVec3d(), new DhVec3d(), new DhVec3d()});
        }
        CameraApi camera(int mode) throws Exception { return mode == 2 ? new CustomCamera() : (CameraApi) (mode == 0 ? cameraClass : cameraSubclass).getConstructor().newInstance(); }
        BaseGroup group(int mode) throws Exception { return mode == 2 ? new RetainingGroup() : (BaseGroup) (mode == 0 ? groupClass : groupSubclass).getConstructor().newInstance(); }
        void setup(Params params, BaseGroup group, int mode) throws Exception {
            GroupApi[][][] groups = new GroupApi[3][11][11]; groups[params.instanceOffsetY][params.instanceOffsetX + 5][params.instanceOffsetZ + 5] = group;
            cloudClass.getField("boxGroupByOffset").set(render, groups); cloudClass.getField("MC_RENDER").set(null, camera(mode));
        }
    }
    private static Harness harness(Map<String, byte[]> targets) throws Exception {
        Map<String, String> names = names(); Loader loader = new Loader();
        for (Map.Entry<String, byte[]> entry : targets.entrySet()) {
            ClassNode node = new ClassNode(); new ClassReader(entry.getValue()).accept(new ClassRemapper(node, new SimpleRemapper(names)), 0);
            if (entry.getKey().equals(CAMERA)) {
                node.interfaces.add(Type.getInternalName(CameraApi.class));
                MethodNode look = new MethodNode(ACC_PUBLIC, "getLookAtVector", "()L" + Type.getInternalName(DhVec3f.class) + ";", null, null);
                look.instructions.add(new MethodInsnNode(INVOKESTATIC, Type.getInternalName(Probe.class), "look", look.desc, false)); look.instructions.add(new InsnNode(ARETURN)); node.methods.add(look);
            } else if (entry.getKey().equals(CLOUD)) {
                node.interfaces.add(Type.getInternalName(Render.class));
                MethodNode pre = method(node, "preRender"); MethodNode run = new MethodNode(ACC_PUBLIC, "run", pre.desc, null, null);
                run.instructions.add(new VarInsnNode(ALOAD, 0)); run.instructions.add(new VarInsnNode(ALOAD, 1)); run.instructions.add(new VarInsnNode(ALOAD, 2));
                run.instructions.add(new MethodInsnNode(INVOKESPECIAL, node.name, "preRender", pre.desc, false)); run.instructions.add(new InsnNode(RETURN)); node.methods.add(run);
            }
            loader.classes.put(node.name.replace('/', '.'), bytes(node));
        }
        ClassNode helper = read(DhCloudAllocationCheck.class.getClassLoader().getResourceAsStream(BRIDGE + ".class"));
        ClassNode remapped = new ClassNode(); helper.accept(new ClassRemapper(remapped, new SimpleRemapper(names)));
        loader.classes.put("fixture.Coordinates", bytes(remapped));
        loader.classes.put("fixture.CameraSubclass", subclass("fixture/Camera", true));
        loader.classes.put("fixture.GroupSubclass", subclass("fixture/Group", false));
        return new Harness(loader);
    }
    private static byte[] subclass(String base, boolean camera) {
        ClassNode node = new ClassNode(); node.version = V1_8; node.access = ACC_PUBLIC; node.name = base + "Subclass"; node.superName = base;
        MethodNode ctor = new MethodNode(ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.instructions.add(new VarInsnNode(ALOAD, 0)); ctor.instructions.add(new MethodInsnNode(INVOKESPECIAL, base, "<init>", "()V", false)); ctor.instructions.add(new InsnNode(RETURN)); node.methods.add(ctor);
        MethodNode method = new MethodNode(ACC_PUBLIC, camera ? "getCameraExactPosition" : "setOriginBlockPos", camera ? "()L" + VECTOR + ";" : "(L" + API_VECTOR + ";)V", null, null);
        if (camera) {
            method.instructions.add(new MethodInsnNode(INVOKESTATIC, Type.getInternalName(Probe.class), "customCamera", method.desc, false)); method.instructions.add(new InsnNode(ARETURN));
        } else {
            method.instructions.add(new VarInsnNode(ALOAD, 0)); method.instructions.add(new VarInsnNode(ALOAD, 1));
            method.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, Type.getInternalName(BaseGroup.class), "retain", method.desc, false));
            method.instructions.add(new VarInsnNode(ALOAD, 0)); method.instructions.add(new VarInsnNode(ALOAD, 1));
            method.instructions.add(new MethodInsnNode(INVOKESPECIAL, base, "setOriginBlockPos", method.desc, false)); method.instructions.add(new InsnNode(RETURN));
        }
        node.methods.add(method); return bytes(node);
    }

    private static Map<String, String> names() {
        Map<String, String> map = new HashMap<>(); map.put(CLOUD, "fixture/Cloud"); map.put(CAMERA, "fixture/Camera"); map.put(GROUP, "fixture/Group"); map.put(BRIDGE, "fixture/Coordinates");
        map.put(CLOUD + "$CloudParams", Type.getInternalName(Params.class));
        bind(map, "api/methods/events/sharedParameterObjects/DhApiRenderParam", Param.class);
        bind(map, "core/wrapperInterfaces/minecraft/IMinecraftRenderWrapper", CameraApi.class);
        bind(map, "api/interfaces/render/IDhApiRenderableBoxGroup", GroupApi.class);
        bind(map, "core/level/IDhClientLevel", DhLevel.class);
        bind(map, "core/wrapperInterfaces/world/IClientLevelWrapper", ClientLevel.class);
        bind(map, "core/wrapperInterfaces/world/ILevelWrapper", HeightLevel.class);
        bind(map, "core/config/types/ConfigEntry", Entry.class);
        bind(map, "core/config/Config$Client$Advanced$Graphics$GenericRendering", Config.class);
        bind(map, "core/config/Config$Client$Advanced$Graphics$Quality", Quality.class);
        bind(map, "common/wrappers/minecraft/MinecraftRenderWrapper$DelayedAccessors_forge", Delayed.class);
        bind(map, "core/render/RenderThreadTaskHandler", ThreadState.class);
        bind(map, "core/wrapperInterfaces/modAccessor/IImmersivePortalsAccessor", Portals.class);
        map.put("net/minecraft/client/Minecraft", Type.getInternalName(MC.class));
        map.put("net/minecraft/client/renderer/GameRenderer", Type.getInternalName(Renderer.class));
        map.put("net/minecraft/client/renderer/ActiveRenderInfo", Type.getInternalName(View.class));
        map.put("net/minecraft/util/math/vector/Vector3d", Type.getInternalName(Position.class));
        map.put("java/lang/System", Type.getInternalName(Clock.class)); return map;
    }
    private static void bind(Map<String, String> map, String name, Class<?> type) { map.put(ROOT + name, Type.getInternalName(type)); }
    private static void checkOriginalMethods(ClassNode original, ClassNode changed) {
        for (MethodNode m : original.methods) {
            if (original.name.equals(CLOUD) && m.name.equals("preRender")) { continue; }
            check(methodHash(m).equals(methodHash(method(changed, m.name, m.desc))), "unrelated/public method changed: " + original.name + "." + m.name);
        }
    }
    private static void checkFallback(ClassNode original, String name) {
        ClassNode unknown = copy(original); method(unknown, name).instructions.insert(new InsnNode(NOP)); byte[] bytes = bytesRaw(unknown);
        check(DhCloudAllocationOptimizer.optimize(unknown) == 0 && Arrays.equals(bytes, bytesRaw(unknown)), "unknown injected body changed");
        unknown = copy(original); unknown.name += "Other";
        check(DhCloudAllocationOptimizer.optimize(unknown) == 0, "unknown class changed");
    }
    private static String methodHash(MethodNode method) {
        ClassNode node = new ClassNode(); node.version = V1_8; node.access = ACC_PUBLIC; node.name = "Hash"; node.superName = "java/lang/Object"; node.methods.add(method);
        return Base64.getEncoder().encodeToString(bytesRaw(node));
    }
    private static MethodNode method(ClassNode node, String name) { for (MethodNode m : node.methods) { if (m.name.equals(name)) { return m; } } throw new AssertionError(name); }
    private static MethodNode method(ClassNode node, String name, String desc) { for (MethodNode m : node.methods) { if (m.name.equals(name) && m.desc.equals(desc)) { return m; } } throw new AssertionError(name); }
    private static MethodNode copy(MethodNode original) { MethodNode result = new MethodNode(original.access, original.name, original.desc, original.signature, original.exceptions.toArray(new String[0])); original.accept(result); return result; }
    private static ClassNode copy(ClassNode original) { ClassNode result = new ClassNode(); original.accept(result); return result; }
    private static ClassNode read(InputStream in) throws IOException { try (InputStream input = in) { ClassNode result = new ClassNode(); new ClassReader(input).accept(result, 0); return result; } }
    private static byte[] bytesRaw(ClassNode node) { ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray(); }
    private static byte[] bytes(ClassNode node) { ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray(); }
    private static class Loader extends ClassLoader {
        final Map<String, byte[]> classes = new HashMap<>();
        @Override protected Class<?> findClass(String name) throws ClassNotFoundException { byte[] data = classes.get(name); if (data == null) { throw new ClassNotFoundException(name); } return defineClass(name, data, 0, data.length); }
    }
    private static void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } }
}
