package net.coderbot.iris.math;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.tree.*;

import static org.objectweb.asm.Opcodes.*;

/** Replaces only a scale-factory/multiply pair. No matrix pooling and no changes to push/pop. */
public final class ScalarMatrixScaleOptimizer {
    private static final String BRIDGE = "net/coderbot/iris/math/ScalarMatrixScale";

    private ScalarMatrixScaleOptimizer() { }

    public static int optimize(ClassNode target) {
        if (matrixSize(target.name) != 0) return matrix(target);
        if (target.name.equals("com/mojang/blaze3d/vertex/PoseStack")
                || target.name.equals("com/mojang/blaze3d/matrix/MatrixStack")) return pose(target);
        return 0;
    }

    private static int matrixSize(String owner) {
        if (owner.equals("com/mojang/math/Matrix4f") || owner.equals("net/minecraft/util/math/vector/Matrix4f")) return 4;
        if (owner.equals("com/mojang/math/Matrix3f") || owner.equals("net/minecraft/util/math/vector/Matrix3f")) return 3;
        return 0;
    }

    private static int pose(ClassNode target) {
        int changed = 0;
        for (MethodNode method : target.methods) {
            if (!(method.name.equals("scale") || method.name.equals("func_227862_a_")) || !method.desc.equals("(FFF)V")) continue;
            List<AbstractInsnNode> code = code(method);
            for (int i = 0; i + 1 < code.size(); i++) {
                if (!(code.get(i) instanceof MethodInsnNode) || !(code.get(i + 1) instanceof MethodInsnNode)) continue;
                MethodInsnNode factory = (MethodInsnNode) code.get(i), multiply = (MethodInsnNode) code.get(i + 1);
                int size = matrixSize(factory.owner);
                if (size == 0 || factory.getOpcode() != INVOKESTATIC || !isScaleFactory(factory.name, size)
                        || !factory.desc.equals("(FFF)L" + factory.owner + ";")
                        || multiply.getOpcode() != INVOKEVIRTUAL || !multiply.owner.equals(factory.owner)
                        || !isMultiply(multiply.name, size) || !multiply.desc.equals("(L" + factory.owner + ";)V")) continue;
                // Do not cross a label: another control-flow path could enter between these calls.
                boolean adjacent = true;
                for (AbstractInsnNode next = factory.getNext(); next != multiply; next = next.getNext()) {
                    if (!(next instanceof LineNumberNode)) { adjacent = false; break; }
                }
                if (!adjacent) continue;
                method.instructions.set(factory, new MethodInsnNode(INVOKESTATIC, BRIDGE, "scale" + size,
                        "(L" + factory.owner + ";FFF)V", false));
                method.instructions.remove(multiply);
                changed++;
            }
        }
        return changed;
    }

    private static boolean isScaleFactory(String name, int size) {
        return name.equals("createScaleMatrix") || name.equals(size == 4 ? "func_226593_a_" : "func_226117_b_");
    }

    private static boolean isMultiply(String name, int size) {
        return name.equals(size == 4 ? "multiply" : "mul") || name.equals(size == 4 ? "func_226595_a_" : "func_226118_b_");
    }

    private static int matrix(ClassNode target) {
        int size = matrixSize(target.name);
        if (target.interfaces.contains(BRIDGE + "$Access")) return 0;
        MethodNode factory = null, multiply = null, constructor = null;
        for (MethodNode method : target.methods) {
            if (isScaleFactory(method.name, size) && method.desc.equals("(FFF)L" + target.name + ";")) factory = method;
            if (isMultiply(method.name, size) && method.desc.equals("(L" + target.name + ";)V")) multiply = method;
            if (method.name.equals("<init>") && method.desc.equals("()V")) constructor = method;
            if (method.name.equals("iris$multiplyScale")) return 0;
        }
        if (factory == null || multiply == null || constructor == null
                || (multiply.access & (ACC_STATIC | ACC_SYNCHRONIZED | ACC_ABSTRACT | ACC_NATIVE)) != 0) return 0;
        // A modified constructor could initialize off-diagonal fields to something other than zero.
        List<AbstractInsnNode> init = code(constructor);
        if (init.size() != 3 || !var(init.get(0), ALOAD, 0) || init.get(1).getOpcode() != INVOKESPECIAL
                || !(init.get(1) instanceof MethodInsnNode) || !((MethodInsnNode) init.get(1)).owner.equals("java/lang/Object")
                || !((MethodInsnNode) init.get(1)).name.equals("<init>") || !((MethodInsnNode) init.get(1)).desc.equals("()V")
                || init.get(2).getOpcode() != RETURN) return 0;

        Map<String, Integer> values = scaleFields(target.name, factory, size);
        if (values == null || !multiply.tryCatchBlocks.isEmpty()) return 0;
        List<AbstractInsnNode> original = code(multiply);
        Set<String> fields = new HashSet<>();
        int parameterReads = 0, stores = 0;
        for (int i = 0; i < original.size(); i++) {
            AbstractInsnNode insn = original.get(i);
            int op = insn.getOpcode();
            if (insn instanceof VarInsnNode) {
                VarInsnNode v = (VarInsnNode) insn;
                if (op == ALOAD && v.var == 0) continue;
                if (op == ALOAD && v.var == 1 && i + 1 < original.size() && original.get(i + 1).getOpcode() == GETFIELD) { parameterReads++; continue; }
                if ((op == FLOAD || op == FSTORE) && v.var >= 2) continue;
                return 0;
            }
            if (insn instanceof FieldInsnNode) {
                FieldInsnNode field = (FieldInsnNode) insn;
                if (!field.owner.equals(target.name) || !field.desc.equals("F") || (op != GETFIELD && op != PUTFIELD)) return 0;
                fields.add(field.name);
                if (op == PUTFIELD) stores++;
            } else if (op != FMUL && op != FADD && op != RETURN) return 0;
        }
        if (fields.size() != size * size || !fields.containsAll(values.keySet())
                || parameterReads != size * size * size || stores != size * size) return 0;

        MethodNode scalar = new MethodNode(ASM9, ACC_PUBLIC | ACC_SYNTHETIC, "iris$multiplyScale", "(FFF)V", null, null);
        for (int i = 0; i < original.size(); i++) {
            AbstractInsnNode insn = original.get(i);
            if (var(insn, ALOAD, 1)) {
                FieldInsnNode field = (FieldInsnNode) original.get(++i);
                Integer value = values.get(field.name);
                scalar.instructions.add(value == null ? new InsnNode(FCONST_0)
                        : value == 3 ? new InsnNode(FCONST_1) : new VarInsnNode(FLOAD, value + 1));
            } else if (insn instanceof VarInsnNode) {
                VarInsnNode v = (VarInsnNode) insn;
                scalar.instructions.add(new VarInsnNode(v.getOpcode(), v.var >= 2 ? v.var + 2 : v.var));
            } else {
                scalar.instructions.add(insn.clone(new HashMap<LabelNode, LabelNode>()));
            }
        }
        scalar.maxLocals = multiply.maxLocals + 2;
        scalar.maxStack = multiply.maxStack;
        target.methods.add(scalar);
        target.interfaces.add(BRIDGE + "$Access");
        return 1;
    }

    private static Map<String, Integer> scaleFields(String owner, MethodNode method, int size) {
        if (!method.tryCatchBlocks.isEmpty() || (method.access & ACC_STATIC) == 0) return null;
        List<AbstractInsnNode> code = code(method);
        if (code.size() != 6 + size * 3 || code.get(0).getOpcode() != NEW || !((TypeInsnNode) code.get(0)).desc.equals(owner)
                || code.get(1).getOpcode() != DUP || !(code.get(2) instanceof MethodInsnNode)
                || !var(code.get(3), ASTORE, 3) || !var(code.get(code.size() - 2), ALOAD, 3)
                || code.get(code.size() - 1).getOpcode() != ARETURN) return null;
        MethodInsnNode init = (MethodInsnNode) code.get(2);
        if (init.getOpcode() != INVOKESPECIAL || !init.owner.equals(owner) || !init.name.equals("<init>") || !init.desc.equals("()V")) return null;
        Map<String, Integer> values = new HashMap<>();
        for (int axis = 0; axis < size; axis++) {
            int start = 4 + axis * 3;
            if (!var(code.get(start), ALOAD, 3) || !(code.get(start + 2) instanceof FieldInsnNode)) return null;
            if (axis < 3 ? !var(code.get(start + 1), FLOAD, axis) : code.get(start + 1).getOpcode() != FCONST_1) return null;
            FieldInsnNode field = (FieldInsnNode) code.get(start + 2);
            if (field.getOpcode() != PUTFIELD || !field.owner.equals(owner) || !field.desc.equals("F") || values.put(field.name, axis) != null) return null;
        }
        return values;
    }

    private static boolean var(AbstractInsnNode insn, int opcode, int index) {
        return insn instanceof VarInsnNode && insn.getOpcode() == opcode && ((VarInsnNode) insn).var == index;
    }

    private static List<AbstractInsnNode> code(MethodNode method) {
        List<AbstractInsnNode> code = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) if (insn.getOpcode() >= 0) code.add(insn);
        return code;
    }
}
