package net.coderbot.iris.math;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import static org.objectweb.asm.Opcodes.*;

/** Keeps the live biome query, but avoids materializing its packed RGB as a temporary vector. */
public final class SkyRgbSamplerOptimizer {
    private static final String SAMPLER = "net/minecraft/util/CubicSampler";
    private static final String FETCHER = SAMPLER + "$Vec3Fetcher";
    private static final String RGB = "net/coderbot/iris/math/VectorCubicSampler$RgbFetcher";
    private static final String BOOTSTRAP = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
            + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;";

    private SkyRgbSamplerOptimizer() { }

    public static int optimize(ClassNode target) {
        boolean named = target.name.equals("net/minecraft/client/multiplayer/ClientLevel");
        if (!named && !target.name.equals("net/minecraft/client/world/ClientWorld")) return 0;
        String vec = named ? "net/minecraft/world/phys/Vec3" : "net/minecraft/util/math/vector/Vector3d";
        String biome = named ? "net/minecraft/world/level/biome/Biome" : "net/minecraft/world/biome/Biome";
        String manager = named ? "net/minecraft/world/level/biome/BiomeManager" : "net/minecraft/world/biome/BiomeManager";
        String sam = "(III)L" + vec + ";";
        int changed = 0;
        for (MethodNode method : new ArrayList<>(target.methods)) {
            for (AbstractInsnNode node : method.instructions.toArray()) {
                if (!(node instanceof InvokeDynamicInsnNode)) continue;
                InvokeDynamicInsnNode lambda = (InvokeDynamicInsnNode) node;
                if (!lambda.name.equals("fetch") || !lambda.desc.equals("(L" + manager + ";)L" + FETCHER + ";")
                        || lambda.bsm.getTag() != H_INVOKESTATIC || lambda.bsm.isInterface()
                        || !lambda.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
                        || !lambda.bsm.getName().equals("metafactory") || !lambda.bsm.getDesc().equals(BOOTSTRAP)
                        || lambda.bsmArgs.length != 3 || !Type.getMethodType(sam).equals(lambda.bsmArgs[0])
                        || !(lambda.bsmArgs[1] instanceof Handle) || !Type.getMethodType(sam).equals(lambda.bsmArgs[2])) continue;
                // The lambda is consumed only by the standard sampler. Do not change callbacks
                // stored elsewhere or passed to another mod's wrapper, and do not cross jump labels.
                AbstractInsnNode next = node.getNext();
                while (next instanceof LineNumberNode) next = next.getNext();
                if (!call(next, INVOKESTATIC, SAMPLER, named ? "gaussianSampleVec3" : "func_240807_a_",
                        "(L" + vec + ";L" + FETCHER + ";)L" + vec + ";")) continue;
                Handle handle = (Handle) lambda.bsmArgs[1];
                if (handle.getTag() != H_INVOKESTATIC || handle.isInterface() || !handle.getOwner().equals(target.name)
                        || !handle.getDesc().equals("(L" + manager + ";III)L" + vec + ";")) continue;
                MethodNode source = null;
                for (MethodNode candidate : target.methods) {
                    if (candidate.name.equals(handle.getName()) && candidate.desc.equals(handle.getDesc())) source = candidate;
                }
                if (source == null || source.access != (ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC)
                        || !source.tryCatchBlocks.isEmpty()) continue;
                List<AbstractInsnNode> code = code(source);
                // Recognize the entire private lambda. Calls to BiomeManager and Biome remain
                // virtual, so overrides, Celestial colors and other biome hooks still run each time.
                if (code.size() != 8 || !local(code.get(0), ALOAD, 0) || !local(code.get(1), ILOAD, 1)
                        || !local(code.get(2), ILOAD, 2) || !local(code.get(3), ILOAD, 3)
                        || !call(code.get(4), INVOKEVIRTUAL, manager, named ? "getNoiseBiomeAtQuart" : "func_235199_a_", "(III)L" + biome + ";")
                        || !call(code.get(5), INVOKEVIRTUAL, biome, named ? "getSkyColor" : "func_225529_c_", "()I")
                        || !call(code.get(6), INVOKESTATIC, vec, named ? "fromRGB24" : "func_237487_a_", "(I)L" + vec + ";")
                        || code.get(7).getOpcode() != ARETURN) continue;
                String helperName = "iris$sampleSkyRgb$" + changed;
                if (target.methods.stream().anyMatch(m -> m.name.equals(helperName))) continue;
                MethodNode helper = new MethodNode(source.access, helperName, "(L" + manager + ";III)I", null, null);
                // Only the six recognized operations are copied, with no callback annotations,
                // object locals or reusable mutable state. Original lambda remains available.
                for (int i = 0; i < 6; i++) helper.instructions.add(code.get(i).clone(null));
                helper.instructions.add(new InsnNode(IRETURN));
                helper.maxLocals = 4; helper.maxStack = 4;
                target.methods.add(helper);
                lambda.name = "fetchRgb";
                lambda.desc = "(L" + manager + ";)L" + RGB + ";";
                lambda.bsmArgs = new Object[]{Type.getMethodType("(III)I"),
                        new Handle(H_INVOKESTATIC, target.name, helperName, helper.desc, false), Type.getMethodType("(III)I")};
                changed++;
            }
        }
        return changed;
    }

    private static List<AbstractInsnNode> code(MethodNode method) {
        List<AbstractInsnNode> code = new ArrayList<>();
        for (AbstractInsnNode node : method.instructions) if (node.getOpcode() >= 0) code.add(node);
        return code;
    }

    private static boolean local(AbstractInsnNode node, int opcode, int slot) {
        return node instanceof VarInsnNode && node.getOpcode() == opcode && ((VarInsnNode) node).var == slot;
    }

    private static boolean call(AbstractInsnNode node, int opcode, String owner, String name, String desc) {
        if (!(node instanceof MethodInsnNode)) return false;
        MethodInsnNode call = (MethodInsnNode) node;
        return call.getOpcode() == opcode && !call.itf && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(desc);
    }
}
