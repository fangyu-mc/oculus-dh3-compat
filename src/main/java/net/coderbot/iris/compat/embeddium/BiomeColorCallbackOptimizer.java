package net.coderbot.iris.compat.embeddium;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import static org.objectweb.asm.Opcodes.*;

/**
 * Removes the allocation around Extension's three primitive color switches after Mixin injection.
 * Both the merged handler and its complete, private callback lifetime must match. Unknown versions
 * or other transformers' changes are left alone; no Extension classes are loaded here.
 */
public final class BiomeColorCallbackOptimizer {

	private static final String EXTENSION = "com/teampotato/embeddiumextension/";
	private static final String SOURCE = EXTENSION.replace('/', '.') + "mixin.biome_colors.MixinBiomeColors";
	private static final String OPTIONS = EXTENSION + "client/gui/SodiumExtraGameOptions";
	private static final String DETAILS = OPTIONS + "$DetailSettings";
	private static final String CALLBACK = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
	private static final String HANDLER_DESC = "(L" + CALLBACK + ";)V";

	private BiomeColorCallbackOptimizer() { }

	public static int optimize(ClassNode target) {
		if (!target.name.equals("net/minecraft/client/renderer/BiomeColors")
				&& !target.name.equals("net/minecraft/world/biome/BiomeColors")) { return 0; }
		Map<String, List<AbstractInsnNode>> handlers = new HashMap<>();
		for (MethodNode method : target.methods) {
			List<AbstractInsnNode> body = handlerBody(method);
			if (body != null) { handlers.put(method.name, body); }
		}
		int changed = 0;
		for (MethodNode method : target.methods) {
			if ((method.access & ACC_STATIC) == 0 || Type.getReturnType(method.desc).getSort() != Type.INT
					|| !method.tryCatchBlocks.isEmpty()) { continue; }
			boolean rewritten = false;
			// Snapshot instructions so replacements cannot disturb this traversal.
			for (AbstractInsnNode insn : method.instructions.toArray()) {
				if (!(insn instanceof MethodInsnNode)) { continue; }
				MethodInsnNode call = (MethodInsnNode) insn;
				List<AbstractInsnNode> body = handlers.get(call.name);
				if (body == null || !invoke(call, INVOKESTATIC, target.name, call.name, HANDLER_DESC)) { continue; }
				List<AbstractInsnNode> site = callbackSite(method, call);
				if (site == null) { continue; }
				LabelNode unchanged = ((JumpInsnNode) site.get(13)).label;
				InsnList replacement = new InsnList();
				// Keep the original color on the operand stack. Read the actual options at the same point
				// as the handler, after the original provider (and earlier callbacks) has completed.
				for (int i = 0; i < 3; i++) { replacement.add(body.get(i).clone(new HashMap<>())); }
				replacement.add(new JumpInsnNode(IFNE, unchanged));
				replacement.add(new InsnNode(POP));
				replacement.add(body.get(5).clone(new HashMap<>()));
				// setReturnValue also cancelled the method: preserve the early return, including when
				// another mod has a later RETURN callback. Do not turn this into a shared final return.
				replacement.add(new InsnNode(IRETURN));
				method.instructions.insertBefore(site.get(0), replacement);
				for (AbstractInsnNode node : site) { method.instructions.remove(node); }
				int slot = ((VarInsnNode) site.get(1)).var;
				if (method.localVariables != null) { method.localVariables.removeIf(local -> local.index == slot); }
				rewritten = true;
				changed++;
			}
			if (rewritten) {
				// The final Mixin class writer computes frames. Old frames describe the removed object local.
				for (AbstractInsnNode node : method.instructions.toArray()) {
					if (node instanceof FrameNode) { method.instructions.remove(node); }
				}
			}
		}
		return changed;
	}

	private static List<AbstractInsnNode> handlerBody(MethodNode method) {
		if (!HANDLER_DESC.equals(method.desc) || (method.access & (ACC_PRIVATE | ACC_STATIC | ACC_SYNCHRONIZED))
				!= (ACC_PRIVATE | ACC_STATIC) || !method.tryCatchBlocks.isEmpty() || !fromExtension(method)) { return null; }
		List<AbstractInsnNode> b = code(method);
		if (b.size() != 9
				|| !invoke(b.get(0), INVOKESTATIC, EXTENSION + "client/SodiumExtraClientMod", "options", "()L" + OPTIONS + ";")
				|| !field(b.get(1), OPTIONS, "detailSettings", "L" + DETAILS + ";")
				|| !field(b.get(2), DETAILS, "biomeColors", "Z")
				|| b.get(3).getOpcode() != IFNE || nextCode(((JumpInsnNode) b.get(3)).label) != b.get(8)
				|| !local(b.get(4), ALOAD, 0) || !(b.get(5) instanceof LdcInsnNode)
				|| !(((LdcInsnNode) b.get(5)).cst instanceof Integer)
				|| !invoke(b.get(6), INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
				|| !invoke(b.get(7), INVOKEVIRTUAL, CALLBACK, "setReturnValue", "(Ljava/lang/Object;)V")
				|| b.get(8).getOpcode() != RETURN) { return null; }
		return b;
	}

	private static boolean fromExtension(MethodNode method) {
		if (method.visibleAnnotations == null) { return false; }
		for (AnnotationNode annotation : method.visibleAnnotations) {
			if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;")
					|| annotation.values == null) { continue; }
			for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
				if (annotation.values.get(i).equals("mixin") && SOURCE.equals(annotation.values.get(i + 1))) { return true; }
			}
		}
		return false;
	}

	private static List<AbstractInsnNode> callbackSite(MethodNode method, MethodInsnNode call) {
		List<AbstractInsnNode> all = code(method);
		int at = all.indexOf(call), start = at - 10;
		if (start < 0 || at + 6 >= all.size()) { return null; }
		List<AbstractInsnNode> s = new ArrayList<>(all.subList(start, at + 7));
		if (s.get(0).getOpcode() != DUP || s.get(1).getOpcode() != ISTORE
				|| !(s.get(2) instanceof TypeInsnNode) || s.get(2).getOpcode() != NEW
				|| !((TypeInsnNode) s.get(2)).desc.equals(CALLBACK) || s.get(3).getOpcode() != DUP
				|| !(s.get(4) instanceof LdcInsnNode) || !(((LdcInsnNode) s.get(4)).cst instanceof String)
				|| s.get(5).getOpcode() != ICONST_1
				|| !invoke(s.get(7), INVOKESPECIAL, CALLBACK, "<init>", "(Ljava/lang/String;ZI)V")
				|| !invoke(s.get(12), INVOKEVIRTUAL, CALLBACK, "isCancelled", "()Z")
				|| s.get(13).getOpcode() != IFEQ
				|| !invoke(s.get(15), INVOKEVIRTUAL, CALLBACK, "getReturnValueI", "()I")
				|| s.get(16).getOpcode() != IRETURN) { return null; }
		int slot = ((VarInsnNode) s.get(1)).var;
		int argumentSlots = 0;
		for (Type argument : Type.getArgumentTypes(method.desc)) { argumentSlots += argument.getSize(); }
		if (slot < argumentSlots || !local(s.get(6), ILOAD, slot) || !local(s.get(8), ASTORE, slot)
				|| !local(s.get(9), ALOAD, slot) || !local(s.get(11), ALOAD, slot)
				|| !local(s.get(14), ALOAD, slot)) { return null; }
		LabelNode resume = ((JumpInsnNode) s.get(13)).label;
		Set<LabelNode> interiorLabels = new HashSet<>();
		boolean foundResume = false;
		for (AbstractInsnNode n = s.get(16).getNext(); n != null && n.getOpcode() < 0; n = n.getNext()) {
			if (n == resume) { foundResume = true; break; }
		}
		if (!foundResume) { return null; }
		for (AbstractInsnNode n = s.get(0); n != s.get(16); n = n.getNext()) {
			if (n instanceof LabelNode) { interiorLabels.add((LabelNode) n); }
		}
		for (AbstractInsnNode n : method.instructions.toArray()) {
			// Do not remove a callback local which another injection observes or shares.
			if (!s.contains(n) && ((n instanceof VarInsnNode && ((VarInsnNode) n).var == slot)
					|| (n instanceof IincInsnNode && ((IincInsnNode) n).var == slot))) { return null; }
			if (n instanceof JumpInsnNode && interiorLabels.contains(((JumpInsnNode) n).label)) { return null; }
			if (n instanceof TableSwitchInsnNode) {
				TableSwitchInsnNode jump = (TableSwitchInsnNode) n;
				if (interiorLabels.contains(jump.dflt) || jump.labels.stream().anyMatch(interiorLabels::contains)) { return null; }
			}
			if (n instanceof LookupSwitchInsnNode) {
				LookupSwitchInsnNode jump = (LookupSwitchInsnNode) n;
				if (interiorLabels.contains(jump.dflt) || jump.labels.stream().anyMatch(interiorLabels::contains)) { return null; }
			}
		}
		return s;
	}

	private static List<AbstractInsnNode> code(MethodNode method) {
		List<AbstractInsnNode> result = new ArrayList<>();
		for (AbstractInsnNode node : method.instructions.toArray()) {
			if (node.getOpcode() >= 0) { result.add(node); }
		}
		return result;
	}

	private static AbstractInsnNode nextCode(AbstractInsnNode node) {
		do { node = node.getNext(); } while (node != null && node.getOpcode() < 0);
		return node;
	}

	private static boolean local(AbstractInsnNode node, int opcode, int slot) {
		return node instanceof VarInsnNode && node.getOpcode() == opcode && ((VarInsnNode) node).var == slot;
	}

	private static boolean field(AbstractInsnNode node, String owner, String name, String descriptor) {
		if (!(node instanceof FieldInsnNode) || node.getOpcode() != GETFIELD) { return false; }
		FieldInsnNode field = (FieldInsnNode) node;
		return field.owner.equals(owner) && field.name.equals(name) && field.desc.equals(descriptor);
	}

	private static boolean invoke(AbstractInsnNode node, int opcode, String owner, String name, String descriptor) {
		if (!(node instanceof MethodInsnNode) || node.getOpcode() != opcode) { return false; }
		MethodInsnNode method = (MethodInsnNode) node;
		return !method.itf && method.owner.equals(owner) && method.name.equals(name) && method.desc.equals(descriptor);
	}
}
