package net.coderbot.iris.diagnostics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Matrix3f;
import com.mojang.math.Matrix4f;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.zip.ZipFile;
import net.coderbot.iris.math.FullBlockBounds;
import net.coderbot.iris.math.ScalarMatrixScale;
import net.coderbot.iris.math.ScalarMatrixScaleOptimizer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.service.MixinService;

import static org.objectweb.asm.Opcodes.*;

/** Actual vanilla/Mixin bytecode comparisons and allocation counters; no game or graphics context. */
public final class RenderAllocationCheck {
    private static final String M4 = "com.mojang.math.Matrix4f", M3 = "com.mojang.math.Matrix3f";
    private static final String POSE = "com.mojang.blaze3d.vertex.PoseStack";
    private static final String SHAPE = "net.minecraft.world.phys.shapes.VoxelShape";
    private static volatile Object sink;
    private static volatile int checksum;

    public static void main(String[] args) throws Exception {
        Map<String, byte[]> original = new HashMap<>(), optimized = new HashMap<>();
        MixinBootstrap.init();
        MixinEnvironment env = MixinEnvironment.getDefaultEnvironment(); env.setSide(MixinEnvironment.Side.CLIENT);
        Mixins.addConfiguration("render-allocation-check.json");
        IMixinTransformer transformer = ((FrameTimeInstrumentationCheck.Service) MixinService.getService()).transformer();
        for (String name : new String[]{M4, M3, POSE, SHAPE}) {
            byte[] bytes = resource(name);
            original.put(name, bytes);
            byte[] modified = transformer.transformClass(env, name, bytes);
            optimized.put(name, modified);
            ClassNode node = node(modified);
            if (!name.equals(SHAPE)) {
                check(ScalarMatrixScaleOptimizer.optimize(node) == 0, "optimizer must be idempotent: " + name);
                check(countBridgeCalls(node, name.equals(POSE) ? "scale" : "iris$multiplyScale") == (name.equals(POSE) ? 2 : 0), "expected scalar bridges: " + name);
                if (!name.equals(POSE)) check(node.methods.stream().anyMatch(m -> m.name.equals("iris$multiplyScale")), "generated multiply missing: " + name);
            }
        }
        // Unsupported matrix code has no scalar entry point; patched callers must use original API.
        Map<String, byte[]> fallback = new HashMap<>(original); fallback.put(POSE, optimized.get(POSE));
        Case before = load(original), after = load(optimized), unknown = load(fallback);
        compareScales(before, after, unknown);
        compareBounds(before, after);
        checkSubclassFallback(optimized);
        testUnsupported(original);
        for (int i = 1; i < args.length; i++) productionNamespace(args[i]);
        for (Case c : new Case[]{before, after}) { c.reset(identity(4), identity(3)); c.runScales(100_000); c.runBounds(100_000); }
        long oldScale = allocated(() -> checksum = before.runScales(200_000));
        long newScale = allocated(() -> checksum = after.runScales(200_000));
        long oldBounds = allocated(() -> sink = before.runBounds(200_000));
        long newBounds = allocated(() -> sink = after.runBounds(200_000));
        System.out.println("200,000 calls, bytes allocated (" + args[0] + "): scale " + oldScale + " -> " + newScale + "; unit bounds " + oldBounds + " -> " + newBounds);
        check(newScale < 4096, "optimized scale workload allocated per-call objects");
        // Without EA, vanilla CubeVoxelShape.getCoords still creates six coordinate-list objects.
        // This change removes only the 64-byte AABB; the shape query itself is intentionally retained.
        check(oldBounds - newBounds >= 12_000_000, "unit AABB allocation was not removed");
        if (args.length > 0 && args[0].equals("no-ea")) check(oldScale >= 20_000_000 && oldBounds >= 12_000_000, "baseline allocation measurement missing");
        System.out.println("Render allocation check: 20,000 random plus boundary matrix cases, fallback/subclasses, different/special bounds and SRG transforms passed");
    }

    private static void compareScales(Case before, Case after, Case fallback) {
        Random random = new Random(0x4a731eeL);
        float[] a = new float[16], b = new float[9];
        float[] expected = new float[25], actual = new float[25], slow = new float[25];
        float[] special = {0.0F, -0.0F, 1.0F, -1.0F, Float.MIN_VALUE, -Float.MIN_NORMAL,
                Float.MAX_VALUE, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN};
        for (int i = 0; i < 20_000 + special.length * special.length; i++) {
            for (int j = 0; j < a.length; j++) a[j] = random.nextFloat() * 16.0F - 8.0F;
            for (int j = 0; j < b.length; j++) b[j] = random.nextFloat() * 16.0F - 8.0F;
            float x, y, z;
            if (i < 20_000) {
                x = random.nextFloat() * 4.0F - 2.0F;
                y = i % 3 == 0 ? x : random.nextFloat() * 4.0F - 2.0F;
                z = i % 3 == 0 ? x : random.nextFloat() * 4.0F - 2.0F;
            } else {
                int n = i - 20_000;
                x = special[n / special.length]; y = special[n % special.length]; z = x;
                a[n % a.length] = y; b[n % b.length] = x;
            }
            before.reset(a, b); after.reset(a, b); fallback.reset(a, b);
            // Consecutive operations also verify that copied output does not retain scratch matrices.
            for (int k = 0; k < 3; k++) {
                before.scale(x, y, z); after.scale(x, y, z); fallback.scale(x, y, z);
                before.copy(expected); after.copy(actual); fallback.copy(slow);
                for (int j = 0; j < expected.length; j++) {
                    check(Float.floatToIntBits(expected[j]) == Float.floatToIntBits(actual[j]), "matrix mismatch case " + i + " field " + j);
                    check(Float.floatToIntBits(expected[j]) == Float.floatToIntBits(slow[j]), "matrix API fallback mismatch");
                }
            }
        }
    }

    private static void compareBounds(Case before, Case after) {
        double[] special = {0.0D, -0.0D, 1.0D, -1.0D, Double.MIN_VALUE, Double.MAX_VALUE,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN};
        Random random = new Random(448317);
        for (int i = 0; i < 10_000; i++) {
            double[] values = new double[6];
            for (int j = 0; j < values.length; j++) values[j] = i < special.length ? special[(i + j) % special.length] : random.nextDouble() * 32.0D - 16.0D;
            sameBox(new AABB(values[0], values[1], values[2], values[3], values[4], values[5]), FullBlockBounds.create(values[0], values[1], values[2], values[3], values[4], values[5]));
        }
        for (int mode = 0; mode < 3; mode++) sameBox(before.box(mode), after.box(mode));
        check(before.box(0) != before.box(0) && after.box(0) == after.box(0), "only optimized unit bounds are shared");
        AABB retained = after.box(0); after.box(1); after.box(2); sameBox(retained, new AABB(0, 0, 0, 1, 1, 1));
        sameBox(retained.move(17, -32, 4), new AABB(17, -32, 4, 18, -31, 5));
        check(before.emptyFailure().equals(after.emptyFailure()), "empty shape exception changed");
    }

    private static void testUnsupported(Map<String, byte[]> original) {
        for (String target : new String[]{M4, M3}) {
            ClassNode changedFactory = node(original.get(target));
            for (MethodNode method : changedFactory.methods) if (method.name.equals("createScaleMatrix")) method.instructions.insert(new InsnNode(NOP));
            check(ScalarMatrixScaleOptimizer.optimize(changedFactory) == 0, "unknown factory must retain original code");
            ClassNode changedMultiply = node(original.get(target));
            for (MethodNode method : changedMultiply.methods) if (method.desc.equals("(L" + target.replace('.', '/') + ";)V")) method.instructions.insert(new InsnNode(NOP));
            check(ScalarMatrixScaleOptimizer.optimize(changedMultiply) == 0, "unknown multiply must retain original code");
        }
    }

    private static void checkSubclassFallback(Map<String, byte[]> optimized) throws Exception {
        // Vanilla matrices are final. Simulate an access transformer allowing a custom subclass.
        Map<String, byte[]> fixtures = new HashMap<>(optimized);
        for (String target : new String[]{M4, M3}) {
            ClassNode parent = node(optimized.get(target)); parent.access &= ~ACC_FINAL;
            fixtures.put(target, bytes(parent));
            String name = "fixture/Custom" + target.substring(target.lastIndexOf('.') + 1);
            ClassNode child = new ClassNode(); child.version = V1_8; child.access = ACC_PUBLIC;
            child.name = name; child.superName = target.replace('.', '/');
            child.fields.add(new FieldNode(ACC_PUBLIC, "calls", "I", null, null));
            child.fields.add(new FieldNode(ACC_PUBLIC, "argument", "L" + child.superName + ";", null, null));
            MethodNode init = new MethodNode(ACC_PUBLIC, "<init>", "()V", null, null);
            init.instructions.add(new VarInsnNode(ALOAD, 0));
            init.instructions.add(new MethodInsnNode(INVOKESPECIAL, child.superName, "<init>", "()V", false)); init.instructions.add(new InsnNode(RETURN)); child.methods.add(init);
            MethodNode override = new MethodNode(ACC_PUBLIC, target.equals(M4) ? "multiply" : "mul", "(L" + child.superName + ";)V", null, null);
            override.instructions.add(new VarInsnNode(ALOAD, 0)); override.instructions.add(new InsnNode(DUP));
            override.instructions.add(new FieldInsnNode(GETFIELD, name, "calls", "I")); override.instructions.add(new InsnNode(ICONST_1)); override.instructions.add(new InsnNode(IADD));
            override.instructions.add(new FieldInsnNode(PUTFIELD, name, "calls", "I"));
            override.instructions.add(new VarInsnNode(ALOAD, 0)); override.instructions.add(new VarInsnNode(ALOAD, 1));
            override.instructions.add(new FieldInsnNode(PUTFIELD, name, "argument", "L" + child.superName + ";"));
            override.instructions.add(new InsnNode(RETURN)); child.methods.add(override);
            fixtures.put(name.replace('/', '.'), bytes(child));
        }
        Loader loader = new Loader(fixtures);
        for (String target : new String[]{M4, M3}) {
            Class<?> matrix = loader.loadClass(target);
            Class<?> custom = loader.loadClass("fixture.Custom" + target.substring(target.lastIndexOf('.') + 1));
            Object receiver = custom.newInstance();
            java.lang.reflect.Method scale = loader.loadClass(ScalarMatrixScale.class.getName()).getMethod(target.equals(M4) ? "scale4" : "scale3", matrix, float.class, float.class, float.class);
            scale.invoke(null, receiver, 2F, 3F, 4F); Object retained = custom.getField("argument").get(receiver);
            scale.invoke(null, receiver, 5F, 6F, 7F);
            check(retained != custom.getField("argument").get(receiver) && custom.getField("calls").getInt(receiver) == 2, "subclass override or argument lifetime changed");
            Object expected = matrix.getMethod("createScaleMatrix", float.class, float.class, float.class).invoke(null, 2F, 3F, 4F);
            check(retained.equals(expected), "retained subclass argument was mutated");
        }
    }

    private static void productionNamespace(String path) throws Exception {
        try (ZipFile jar = new ZipFile(path)) {
            for (String name : new String[]{"net/minecraft/util/math/vector/Matrix4f", "net/minecraft/util/math/vector/Matrix3f", "com/mojang/blaze3d/matrix/MatrixStack"}) {
                ClassNode node = new ClassNode();
                try (InputStream in = jar.getInputStream(jar.getEntry(name + ".class"))) { new ClassReader(in).accept(node, 0); }
                check(ScalarMatrixScaleOptimizer.optimize(node) == (name.endsWith("MatrixStack") ? 2 : 1), "production SRG transform missed: " + name);
                check(ScalarMatrixScaleOptimizer.optimize(node) == 0, "production duplicate transform");
            }
        }
    }

    private static long allocated(Runnable action) throws Exception {
        Object bean = ManagementFactory.getThreadMXBean();
        java.lang.reflect.Method query = Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", long.class);
        long id = Thread.currentThread().getId(), start = (Long) query.invoke(bean, id);
        action.run();
        return (Long) query.invoke(bean, id) - start;
    }

    private static int countBridgeCalls(ClassNode node, String name) {
        int count = 0;
        for (MethodNode method : node.methods) if (method.name.equals(name)) for (AbstractInsnNode insn : method.instructions)
            if (insn instanceof MethodInsnNode && ((MethodInsnNode) insn).owner.equals("net/coderbot/iris/math/ScalarMatrixScale")) count++;
        return count;
    }

    private static void sameBox(AABB a, AABB b) {
        double[] left = {a.minX, a.minY, a.minZ, a.maxX, a.maxY, a.maxZ}, right = {b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ};
        for (int i = 0; i < 6; i++) check(Double.doubleToLongBits(left[i]) == Double.doubleToLongBits(right[i]), "bounding coordinate changed");
    }

    private static float[] identity(int size) { float[] values = new float[size * size]; for (int i = 0; i < size; i++) values[i * size + i] = 1.0F; return values; }
    private static ClassNode node(byte[] bytes) { ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node; }
    private static byte[] bytes(ClassNode node) { ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray(); }
    private static byte[] resource(String name) throws Exception {
        try (InputStream in = RenderAllocationCheck.class.getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
            check(in != null, "missing class " + name); ClassWriter writer = new ClassWriter(0); new ClassReader(in).accept(writer, 0); return writer.toByteArray();
        }
    }
    private static Case load(Map<String, byte[]> classes) throws Exception { return (Case) new Loader(classes).loadClass(Workload.class.getName()).newInstance(); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    public interface Case {
        void reset(float[] pose, float[] normal);
        void scale(float x, float y, float z);
        void copy(float[] result);
        AABB box(int mode);
        String emptyFailure();
        int runScales(int count);
        Object runBounds(int count);
    }

    public static class Workload implements Case {
        private static final java.lang.reflect.Field[] NORMAL_FIELDS = normalFields();
        private PoseStack stack = new PoseStack();
        private final VoxelShape[] shapes = {Shapes.block(), Shapes.box(0, 0, 0, .5D, 1, 1), Shapes.box(-2, .25D, -3, 4, 1, 5)};
        private static volatile Object boundsSink;
        public void reset(float[] pose, float[] normal) {
            stack = new PoseStack(); stack.last().pose().set(new Matrix4f(pose));
            for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) stack.last().normal().set(r, c, normal[r * 3 + c]);
        }
        public void scale(float x, float y, float z) { stack.scale(x, y, z); }
        public void copy(float[] result) {
            FloatBuffer matrix = FloatBuffer.allocate(16); stack.last().pose().store(matrix); matrix.get(result, 0, 16);
            try {
                for (int i = 0; i < 9; i++) result[16 + i] = NORMAL_FIELDS[i].getFloat(stack.last().normal());
            } catch (IllegalAccessException e) { throw new AssertionError(e); }
        }
        private static java.lang.reflect.Field[] normalFields() {
            java.lang.reflect.Field[] fields = new java.lang.reflect.Field[9];
            try {
                for (int i = 0; i < 9; i++) { fields[i] = Matrix3f.class.getDeclaredField("m" + i / 3 + i % 3); fields[i].setAccessible(true); }
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            return fields;
        }
        public AABB box(int mode) { return shapes[mode].bounds(); }
        public String emptyFailure() {
            try { Shapes.empty().bounds(); return "no error"; }
            catch (UnsupportedOperationException expected) { return expected.getMessage(); }
        }
        public int runScales(int count) {
            int result = 0;
            for (int i = 0; i < count; i++) {
                stack.last().pose().setIdentity(); stack.last().normal().setIdentity();
                stack.scale((i & 1) == 0 ? 1.25F : -.75F, .5F, 1.75F);
                result += stack.last().pose().hashCode() + stack.last().normal().hashCode();
            }
            return result;
        }
        public Object runBounds(int count) { for (int i = 0; i < count; i++) boundsSink = shapes[0].bounds(); return boundsSink; }
    }

    private static final class Loader extends ClassLoader {
        private final Map<String, byte[]> classes;
        Loader(Map<String, byte[]> classes) { super(RenderAllocationCheck.class.getClassLoader()); this.classes = classes; }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            boolean isolated = classes.containsKey(name) || name.startsWith(POSE + "$") || name.startsWith("net.minecraft.world.phys.shapes.")
                    || name.equals(Workload.class.getName())
                    || name.equals(ScalarMatrixScale.class.getName());
            if (!isolated) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    try {
                        byte[] bytes = classes.containsKey(name) ? classes.get(name) : resource(name);
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    } catch (Exception e) { throw new ClassNotFoundException(name, e); }
                }
                if (resolve) resolveClass(loaded); return loaded;
            }
        }
    }
}
