package net.coderbot.iris.compat.dh;

import java.util.ArrayList;
import java.util.List;
import net.coderbot.iris.compat.illuminations.FireflyQuadOptimizer;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import static org.objectweb.asm.Opcodes.*;

/**
 * Removes only the three sampled vector temporaries in DH 3.2.0-b cloud preRender.
 * Each bridge is derived from an exact, already transformed method body. Unrecognized
 * versions/injections keep the public API fallback. No shared or cross-frame scratch state.
 */
public final class DhCloudAllocationOptimizer {
    public static final String CLOUD = "com/seibel/distanthorizons/core/render/renderer/CloudRenderHandler";
    public static final String CAMERA = "com/seibel/distanthorizons/common/wrappers/minecraft/MinecraftRenderWrapper_forge";
    public static final String GROUP = "com/seibel/distanthorizons/core/render/renderer/RenderableBoxGroup";
    public static final String VECTOR = "com/seibel/distanthorizons/core/util/math/DhVec3d";
    public static final String API_VECTOR = "com/seibel/distanthorizons/api/objects/math/DhApiVec3d";
    private static final String CAMERA_API = "com/seibel/distanthorizons/core/wrapperInterfaces/minecraft/IMinecraftRenderWrapper";
    private static final String GROUP_API = "com/seibel/distanthorizons/api/interfaces/render/IDhApiRenderableBoxGroup";
    private static final String BRIDGE = "net/coderbot/iris/compat/dh/DhCloudCoordinates";
    private static final String PRE_RENDER_DESC = "(Lcom/seibel/distanthorizons/api/methods/events/sharedParameterObjects/DhApiRenderParam;L" + CLOUD + "$CloudParams;)V";
    private static final String CAMERA_DESC = "()L" + VECTOR + ";";
    private static final String ORIGIN_DESC = "(L" + API_VECTOR + ";)V";
    // Instruction fingerprints ignore debug metadata, but include branches, operands and access.
    private static final String CLOUD_HASH = "2995ddfe583166ef12fd0b77d9d65d770ebca8627b07a20f52fd9a368ac23544";
    private static final String CAMERA_HASH = "70702ab562c7062b47856b53424918a0196e79d2f949394a845e2aac39fc3891";
    private static final String ORIGIN_HASH = "21eff01741c8f41baf5c18cfc837a06fa09173c218cee920908720b0445ed6f9";

    private DhCloudAllocationOptimizer() { }

    public static int optimize(ClassNode target) {
        if (CLOUD.equals(target.name)) { return cloud(target); }
        if (CAMERA.equals(target.name)) { return camera(target); }
        if (GROUP.equals(target.name)) { return origin(target); }
        return 0;
    }

    public static String fingerprint(MethodNode method) {
        return FireflyQuadOptimizer.fingerprint(method);
    }

    private static MethodNode supported(ClassNode target, String name, String desc, String hash) {
        for (MethodNode method : target.methods) {
            if (method.name.equals(name) && method.desc.equals(desc) && method.tryCatchBlocks.isEmpty()
                    && fingerprint(method).equals(hash)) { return method; }
        }
        return null;
    }

    private static int cloud(ClassNode target) {
        MethodNode method = supported(target, "preRender", PRE_RENDER_DESC, CLOUD_HASH);
        if (method == null) { return 0; }
        List<AbstractInsnNode> code = code(method);
        List<MethodInsnNode> cameras = new ArrayList<>();
        TypeInsnNode allocation = null;
        MethodInsnNode constructor = null, setter = null;
        for (AbstractInsnNode insn : code) {
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) insn;
                if (call.owner.equals(CAMERA_API) && call.name.equals("getCameraExactPosition")) { cameras.add(call); }
                if (call.owner.equals(API_VECTOR) && call.name.equals("<init>")) { constructor = call; }
                if (call.owner.equals(GROUP_API) && call.name.equals("setOriginBlockPos")) { setter = call; }
            } else if (insn.getOpcode() == NEW && ((TypeInsnNode) insn).desc.equals(API_VECTOR)) {
                allocation = (TypeInsnNode) insn;
            }
        }
        if (cameras.size() != 2 || allocation == null || constructor == null || setter == null) { return 0; }
        int start = code.indexOf(allocation);
        if (code.get(start + 1).getOpcode() != DUP || code.indexOf(constructor) != start + 8
                || code.indexOf(setter) != start + 9) { return 0; }
        for (int i = 0; i < 2; i++) {
            AbstractInsnNode next = code.get(code.indexOf(cameras.get(i)) + 1);
            if (!(next instanceof FieldInsnNode) || next.getOpcode() != GETFIELD
                    || !((FieldInsnNode) next).owner.equals(VECTOR)
                    || !((FieldInsnNode) next).name.equals(i == 0 ? "x" : "z")) { return 0; }
        }
        for (int i = 0; i < 2; i++) {
            MethodInsnNode call = cameras.get(i);
            method.instructions.remove(code.get(code.indexOf(call) + 1));
            method.instructions.set(call, new MethodInsnNode(INVOKESTATIC, BRIDGE, i == 0 ? "cameraX" : "cameraZ",
                    "(L" + CAMERA_API + ";)D", false));
        }
        method.instructions.remove(allocation);
        method.instructions.remove(code.get(start + 1));
        method.instructions.remove(constructor);
        method.instructions.set(setter, new MethodInsnNode(INVOKESTATIC, BRIDGE, "setOrigin", "(L" + GROUP_API + ";DDD)V", false));
        clean(method);
        return 1;
    }

    private static int camera(ClassNode target) {
        if (target.interfaces.contains(BRIDGE + "$Camera") || hasBridge(target)) { return 0; }
        MethodNode original = supported(target, "getCameraExactPosition", CAMERA_DESC, CAMERA_HASH);
        if (original == null) { return 0; }
        List<AbstractInsnNode> source = code(original);
        int allocation = -1;
        for (int i = 0; i < source.size(); i++) {
            if (source.get(i).getOpcode() == NEW && ((TypeInsnNode) source.get(i)).desc.equals(VECTOR)) { allocation = i; }
        }
        if (allocation < 0 || source.size() != allocation + 10 || source.get(allocation + 1).getOpcode() != DUP
                || source.get(allocation + 8).getOpcode() != INVOKESPECIAL || source.get(allocation + 9).getOpcode() != ARETURN) { return 0; }
        // Copy the original portal/thread checks and camera reads for each access. Never cache a camera across calls.
        for (int axis = 0; axis < 2; axis++) {
            MethodNode method = copy(original);
            method.name = axis == 0 ? "iris$cloudCameraX" : "iris$cloudCameraZ";
            method.desc = "()D";
            method.signature = null;
            List<AbstractInsnNode> code = code(method);
            method.instructions.remove(code.get(allocation));
            method.instructions.remove(code.get(allocation + 1));
            InsnList select = new InsnList();
            if (axis == 0) {
                select.add(new InsnNode(POP2)); select.add(new InsnNode(POP2));
            } else {
                select.add(new VarInsnNode(DSTORE, method.maxLocals));
                select.add(new InsnNode(POP2)); select.add(new InsnNode(POP2));
                select.add(new VarInsnNode(DLOAD, method.maxLocals)); method.maxLocals += 2;
            }
            method.instructions.insertBefore(code.get(allocation + 8), select);
            method.instructions.remove(code.get(allocation + 8));
            for (AbstractInsnNode insn : code) {
                if (insn.getOpcode() != ARETURN) { continue; }
                if (insn != code.get(allocation + 9)) {
                    method.instructions.insertBefore(insn, new FieldInsnNode(GETFIELD, VECTOR, axis == 0 ? "x" : "z", "D"));
                }
                method.instructions.set(insn, new InsnNode(DRETURN));
            }
            InsnList guard = exactClassGuard(CAMERA);
            LabelNode fast = ((JumpInsnNode) guard.getLast()).label;
            guard.add(new VarInsnNode(ALOAD, 0));
            guard.add(new MethodInsnNode(INVOKEVIRTUAL, CAMERA, original.name, original.desc, false));
            guard.add(new FieldInsnNode(GETFIELD, VECTOR, axis == 0 ? "x" : "z", "D"));
            guard.add(new InsnNode(DRETURN)); guard.add(fast);
            method.instructions.insert(guard);
            clean(method); target.methods.add(method);
        }
        target.interfaces.add(BRIDGE + "$Camera");
        return 2;
    }

    private static int origin(ClassNode target) {
        if (target.interfaces.contains(BRIDGE + "$Origin") || hasBridge(target)) { return 0; }
        MethodNode original = supported(target, "setOriginBlockPos", ORIGIN_DESC, ORIGIN_HASH);
        if (original == null) { return 0; }
        MethodNode method = copy(original);
        method.name = "iris$cloudOrigin"; method.desc = "(DDD)V"; method.signature = null;
        List<AbstractInsnNode> code = code(method);
        for (AbstractInsnNode insn : code) {
            if (!(insn instanceof FieldInsnNode) || insn.getOpcode() != GETFIELD) { continue; }
            FieldInsnNode field = (FieldInsnNode) insn;
            if (!field.owner.equals(API_VECTOR)) { continue; }
            int axis = field.name.equals("x") ? 0 : field.name.equals("y") ? 1 : 2;
            method.instructions.remove(code.get(code.indexOf(insn) - 1));
            method.instructions.set(insn, new VarInsnNode(DLOAD, 1 + axis * 2));
        }
        InsnList guard = exactClassGuard(GROUP);
        LabelNode fast = ((JumpInsnNode) guard.getLast()).label;
        guard.add(new VarInsnNode(ALOAD, 0));
        guard.add(new TypeInsnNode(NEW, API_VECTOR)); guard.add(new InsnNode(DUP));
        guard.add(new VarInsnNode(DLOAD, 1)); guard.add(new VarInsnNode(DLOAD, 3)); guard.add(new VarInsnNode(DLOAD, 5));
        guard.add(new MethodInsnNode(INVOKESPECIAL, API_VECTOR, "<init>", "(DDD)V", false));
        guard.add(new MethodInsnNode(INVOKEVIRTUAL, GROUP, original.name, original.desc, false));
        guard.add(new InsnNode(RETURN)); guard.add(fast);
        method.instructions.insert(guard); method.maxLocals = 7;
        clean(method); target.methods.add(method); target.interfaces.add(BRIDGE + "$Origin");
        return 1;
    }

    /** Subclasses must still dispatch their overrides, including setters retaining the supplied object. */
    private static InsnList exactClassGuard(String target) {
        InsnList guard = new InsnList();
        guard.add(new VarInsnNode(ALOAD, 0));
        guard.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false));
        guard.add(new LdcInsnNode(Type.getObjectType(target)));
        guard.add(new JumpInsnNode(IF_ACMPEQ, new LabelNode()));
        return guard;
    }

    private static boolean hasBridge(ClassNode target) {
        for (MethodNode method : target.methods) { if (method.name.startsWith("iris$cloud")) { return true; } }
        return false;
    }

    private static MethodNode copy(MethodNode original) {
        MethodNode method = new MethodNode(original.access, original.name, original.desc, original.signature,
                original.exceptions.toArray(new String[0]));
        original.accept(method); return method;
    }

    private static List<AbstractInsnNode> code(MethodNode method) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) { if (insn.getOpcode() >= 0) { result.add(insn); } }
        return result;
    }

    private static void clean(MethodNode method) {
        if (method.localVariables != null) { method.localVariables.clear(); }
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof FrameNode) { method.instructions.remove(insn); }
        }
    }
}
