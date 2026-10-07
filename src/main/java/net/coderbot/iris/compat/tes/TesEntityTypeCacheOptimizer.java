package net.coderbot.iris.compat.tes;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import static org.objectweb.asm.Opcodes.*;

/** Removes the capturing Function at TES's entity-type cache lookup, retaining its classifier. */
public final class TesEntityTypeCacheOptimizer {
	public static final String TARGET = "net/tslat/tes/api/util/TESUtil";
	private static final String RESULT = "net/tslat/tes/api/TESEntityType";
	private static final String MAP = "Ljava/util/Map;";
	private static final String BOOTSTRAP = "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
			+ "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;";

	private TesEntityTypeCacheOptimizer() { }

	public static int optimize(ClassNode target) {
		if (!TARGET.equals(target.name)) { return 0; }
		for (MethodNode method : target.methods) {
			if (!method.name.equals("getEntityType") || method.access != (ACC_PUBLIC | ACC_STATIC)
					|| !method.tryCatchBlocks.isEmpty()) { continue; }
			Type[] args = Type.getArgumentTypes(method.desc);
			if (args.length != 1 || args[0].getSort() != Type.OBJECT
					|| !(args[0].getInternalName().equals("net/minecraft/entity/LivingEntity")
					|| args[0].getInternalName().equals("net/minecraft/world/entity/LivingEntity"))
					|| !Type.getReturnType(method.desc).equals(Type.getObjectType(RESULT))) { continue; }
			List<AbstractInsnNode> code = new ArrayList<>();
			for (AbstractInsnNode insn : method.instructions) { if (insn.getOpcode() >= 0) { code.add(insn); } }
			// Validate the whole original method before changing anything. Other versions or
			// injected handlers keep their implementation instead of receiving a partial rewrite.
			if (code.size() != 8 || !(code.get(0) instanceof FieldInsnNode)
					|| !loadEntity(code.get(1)) || !call(code.get(2), INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;")
					|| !loadEntity(code.get(3)) || !(code.get(4) instanceof InvokeDynamicInsnNode)
					|| !call(code.get(5), INVOKEINTERFACE, "java/util/Map", "computeIfAbsent", "(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;")
					|| !(code.get(6) instanceof TypeInsnNode) || code.get(6).getOpcode() != CHECKCAST
					|| !((TypeInsnNode) code.get(6)).desc.equals(RESULT) || code.get(7).getOpcode() != ARETURN) { continue; }
			FieldInsnNode cache = (FieldInsnNode) code.get(0);
			if (cache.getOpcode() != GETSTATIC || !cache.owner.equals(TARGET) || !cache.name.equals("ENTITY_TYPE_MAP")
					|| !cache.desc.equals(MAP) || !hasCache(target, cache.name)) { continue; }
			InvokeDynamicInsnNode lambda = (InvokeDynamicInsnNode) code.get(4);
			if (!lambda.name.equals("apply") || !lambda.desc.equals("(" + args[0].getDescriptor() + ")Ljava/util/function/Function;")
					|| lambda.bsm.getTag() != H_INVOKESTATIC || lambda.bsm.isInterface()
					|| !lambda.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory") || !lambda.bsm.getName().equals("metafactory")
					|| !lambda.bsm.getDesc().equals(BOOTSTRAP) || lambda.bsmArgs.length != 3
					|| !Type.getMethodType("(Ljava/lang/Object;)Ljava/lang/Object;").equals(lambda.bsmArgs[0])
					|| !(lambda.bsmArgs[1] instanceof Handle)
					|| !Type.getMethodType("(Ljava/lang/Class;)L" + RESULT + ";").equals(lambda.bsmArgs[2])) { continue; }
			Handle classifier = (Handle) lambda.bsmArgs[1];
			if (classifier.getTag() != H_INVOKESTATIC || classifier.isInterface() || !classifier.getOwner().equals(TARGET)
					|| !classifier.getDesc().equals("(" + args[0].getDescriptor() + "Ljava/lang/Class;)L" + RESULT + ";")
					|| !hasClassifier(target, classifier)) { continue; }

			InsnList out = new InsnList();
			LabelNode done = new LabelNode();
			// Capture the original map and key in the original evaluation order. The cache
			// remains owned by TES, so existing entries, class keys and lifetime are unchanged.
			out.add(new FieldInsnNode(GETSTATIC, TARGET, cache.name, MAP)); out.add(new VarInsnNode(ASTORE, 1));
			out.add(new VarInsnNode(ALOAD, 0));
			out.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false));
			out.add(new VarInsnNode(ASTORE, 2));
			out.add(new VarInsnNode(ALOAD, 1)); out.add(new VarInsnNode(ALOAD, 2));
			out.add(new MethodInsnNode(INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
			out.add(new TypeInsnNode(CHECKCAST, RESULT)); out.add(new VarInsnNode(ASTORE, 3));
			out.add(new VarInsnNode(ALOAD, 3)); out.add(new JumpInsnNode(IFNONNULL, done));
			// Call the very same private classifier that the former lambda called. No copy
			// of TES's classification rules and no entity instance retained by a new cache.
			out.add(new VarInsnNode(ALOAD, 0)); out.add(new VarInsnNode(ALOAD, 2));
			out.add(new MethodInsnNode(INVOKESTATIC, TARGET, classifier.getName(), classifier.getDesc(), false));
			out.add(new VarInsnNode(ASTORE, 3));
			out.add(new VarInsnNode(ALOAD, 3)); out.add(new JumpInsnNode(IFNULL, done));
			out.add(new VarInsnNode(ALOAD, 1)); out.add(new VarInsnNode(ALOAD, 2)); out.add(new VarInsnNode(ALOAD, 3));
			out.add(new MethodInsnNode(INVOKEINTERFACE, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true));
			out.add(new InsnNode(POP));
			out.add(done); out.add(new VarInsnNode(ALOAD, 3)); out.add(new InsnNode(ARETURN));
			method.instructions = out;
			method.maxLocals = 4; method.maxStack = 3;
			if (method.localVariables != null) { method.localVariables.clear(); }
			method.visibleLocalVariableAnnotations = null; method.invisibleLocalVariableAnnotations = null;
			return 1;
		}
		return 0;
	}

	private static boolean loadEntity(AbstractInsnNode insn) {
		return insn instanceof VarInsnNode && insn.getOpcode() == ALOAD && ((VarInsnNode) insn).var == 0;
	}
	private static boolean call(AbstractInsnNode insn, int opcode, String owner, String name, String desc) {
		if (!(insn instanceof MethodInsnNode)) { return false; }
		MethodInsnNode call = (MethodInsnNode) insn;
		return call.getOpcode() == opcode && call.owner.equals(owner) && call.name.equals(name)
				&& call.desc.equals(desc) && call.itf == (opcode == INVOKEINTERFACE);
	}
	private static boolean hasCache(ClassNode target, String name) {
		for (FieldNode field : target.fields) {
			if (field.name.equals(name) && field.desc.equals(MAP) && field.access == (ACC_PRIVATE | ACC_STATIC | ACC_FINAL)) { return true; }
		}
		return false;
	}
	private static boolean hasClassifier(ClassNode target, Handle classifier) {
		for (MethodNode method : target.methods) {
			if (method.name.equals(classifier.getName()) && method.desc.equals(classifier.getDesc())
					&& method.access == (ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC)) { return true; }
		}
		return false;
	}
}
