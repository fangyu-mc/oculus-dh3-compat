package net.coderbot.iris.compat.illuminations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.tree.*;

import static org.objectweb.asm.Opcodes.*;

/**
 * Scalarizes Illuminations 0.0.4.11's private quad temporaries after Mixin application.
 * The complete production method must match, ignoring debug metadata. Other versions or
 * earlier render injections keep their original implementation. No particle/world state,
 * vertex calls, config reads, material selection, or GL state is changed.
 */
public final class FireflyQuadOptimizer {
	public static final String TARGET = "ladysnake/illuminations/client/particle/FireflyParticle";
	private static final String VECTOR = "net/minecraft/util/math/vector/Vector3f";
	private static final String QUATERNION = "net/minecraft/util/math/vector/Quaternion";
	private static final String RENDER_DESC = "(Lcom/mojang/blaze3d/vertex/IVertexBuilder;Lnet/minecraft/client/renderer/ActiveRenderInfo;F)V";
	private static final String SUPPORTED = "c78717ca012fa8d0ae5f453195dc0c927e4b794f54d5438d305bc1aba0e96c9f";

	private FireflyQuadOptimizer() { }

	public static int optimize(ClassNode target) {
		if (!target.name.equals(TARGET)) { return 0; }
		for (MethodNode method : target.methods) {
			if (!method.name.equals("func_225606_a_") || !method.desc.equals(RENDER_DESC)
					|| !method.tryCatchBlocks.isEmpty() || !fingerprint(method).equals(SUPPORTED)) { continue; }
			List<AbstractInsnNode> code = code(method);
			int start = -1, end = -1;
			for (int i = 0; i < code.size(); i++) {
				AbstractInsnNode node = code.get(i);
				if (start < 0 && node.getOpcode() == NEW && ((TypeInsnNode) node).desc.equals(VECTOR)) { start = i; }
				if (node instanceof MethodInsnNode && ((MethodInsnNode) node).name.equals("func_217563_c")) { end = i - 1; break; }
			}
			if (start < 0 || end <= start) { return 0; }
			// All array reads must be private coordinate reads of the four corners. Verify the
			// complete replacement set before mutating anything, even with a matching fingerprint.
			List<Integer> reads = new ArrayList<>();
			String[] getters = {"func_195899_a", "func_195900_b", "func_195902_c"};
			for (int i = end; i < code.size(); i++) {
				if (!(code.get(i) instanceof VarInsnNode) || ((VarInsnNode) code.get(i)).var != 10) { continue; }
				if (code.get(i).getOpcode() != ALOAD || i + 3 >= code.size()
						|| code.get(i + 1).getOpcode() < ICONST_0 || code.get(i + 1).getOpcode() > ICONST_3
						|| code.get(i + 2).getOpcode() != AALOAD || !(code.get(i + 3) instanceof MethodInsnNode)) { return 0; }
				MethodInsnNode getter = (MethodInsnNode) code.get(i + 3);
				if (!getter.owner.equals(VECTOR) || !getter.desc.equals("()F") || axis(getter.name, getters) < 0) { return 0; }
				reads.add(i);
			}
			if (reads.size() != 24) { return 0; }
			int q = method.maxLocals, negative = q + 4, product = q + 7, corners = q + 11;
			InsnList replacement = new InsnList();
			// Preserve the original virtual size query, after choosing the roll quaternion and
			// before computing corners. Only dead math and object/array temporaries are removed.
			replacement.add(new VarInsnNode(ALOAD, 0));
			replacement.add(new VarInsnNode(FLOAD, 3));
			replacement.add(new MethodInsnNode(INVOKEVIRTUAL, TARGET, "func_217561_b", "(F)F", false));
			replacement.add(new VarInsnNode(FSTORE, 11));
			String[] components = {"func_195889_a", "func_195891_b", "func_195893_c", "func_195894_d"};
			for (int i = 0; i < 4; i++) {
				replacement.add(new VarInsnNode(ALOAD, 8));
				replacement.add(new MethodInsnNode(INVOKEVIRTUAL, QUATERNION, components[i], "()F", false));
				store(replacement, q + i);
				if (i < 3) { load(replacement, q + i); op(replacement, FNEG); store(replacement, negative + i); }
			}
			for (int corner = 0; corner < 4; corner++) {
				float x = corner < 2 ? -1.0F : 1.0F;
				float y = corner == 0 || corner == 3 ? -1.0F : 1.0F;
				// Preserve the two original Quaternion.mul operations, including their zero terms
				// and float rounding order. Do not replace this with normalized-quaternion identities.
				term(replacement, q + 3, x); term(replacement, q, 0); op(replacement, FADD);
				term(replacement, q + 1, 0); op(replacement, FADD); term(replacement, q + 2, y); op(replacement, FSUB); store(replacement, product);
				term(replacement, q + 3, y); term(replacement, q, 0); op(replacement, FSUB);
				term(replacement, q + 1, 0); op(replacement, FADD); term(replacement, q + 2, x); op(replacement, FADD); store(replacement, product + 1);
				term(replacement, q + 3, 0); term(replacement, q, y); op(replacement, FADD);
				term(replacement, q + 1, x); op(replacement, FSUB); term(replacement, q + 2, 0); op(replacement, FADD); store(replacement, product + 2);
				term(replacement, q + 3, 0); term(replacement, q, x); op(replacement, FSUB);
				term(replacement, q + 1, y); op(replacement, FSUB); term(replacement, q + 2, 0); op(replacement, FSUB); store(replacement, product + 3);
				multiply(replacement, product + 3, negative); multiply(replacement, product, q + 3); op(replacement, FADD);
				multiply(replacement, product + 1, negative + 2); op(replacement, FADD); multiply(replacement, product + 2, negative + 1); op(replacement, FSUB);
				position(replacement, 5, corners + corner * 3);
				multiply(replacement, product + 3, negative + 1); multiply(replacement, product, negative + 2); op(replacement, FSUB);
				multiply(replacement, product + 1, q + 3); op(replacement, FADD); multiply(replacement, product + 2, negative); op(replacement, FADD);
				position(replacement, 6, corners + corner * 3 + 1);
				multiply(replacement, product + 3, negative + 2); multiply(replacement, product, negative + 1); op(replacement, FADD);
				multiply(replacement, product + 1, negative); op(replacement, FSUB); multiply(replacement, product + 2, q + 3); op(replacement, FADD);
				position(replacement, 7, corners + corner * 3 + 2);
			}
			method.instructions.insertBefore(code.get(start), replacement);
			for (AbstractInsnNode node = code.get(start); node != code.get(end); ) {
				AbstractInsnNode next = node.getNext(); method.instructions.remove(node); node = next;
			}
			for (int read : reads) {
				int corner = code.get(read + 1).getOpcode() - ICONST_0;
				int component = axis(((MethodInsnNode) code.get(read + 3)).name, getters);
				method.instructions.insertBefore(code.get(read), new VarInsnNode(FLOAD, corners + corner * 3 + component));
				for (int i = 0; i < 4; i++) { method.instructions.remove(code.get(read + i)); }
			}
			method.maxLocals = corners + 12;
			if (method.localVariables != null) { method.localVariables.clear(); }
			// The Mixin writer recomputes frames after this production postApply hook.
			for (AbstractInsnNode node : method.instructions.toArray()) {
				if (node instanceof FrameNode) { method.instructions.remove(node); }
			}
			return 1;
		}
		return 0;
	}

	private static int axis(String name, String[] getters) {
		for (int i = 0; i < getters.length; i++) { if (getters[i].equals(name)) { return i; } }
		return -1;
	}
	private static void load(InsnList out, int slot) { out.add(new VarInsnNode(FLOAD, slot)); }
	private static void store(InsnList out, int slot) { out.add(new VarInsnNode(FSTORE, slot)); }
	private static void op(InsnList out, int opcode) { out.add(new InsnNode(opcode)); }
	private static void term(InsnList out, int slot, float constant) { load(out, slot); out.add(new LdcInsnNode(constant)); op(out, FMUL); }
	private static void multiply(InsnList out, int left, int right) { load(out, left); load(out, right); op(out, FMUL); }
	private static void position(InsnList out, int offset, int result) { load(out, 11); op(out, FMUL); load(out, offset); op(out, FADD); store(out, result); }
	private static List<AbstractInsnNode> code(MethodNode method) {
		List<AbstractInsnNode> nodes = new ArrayList<>();
		for (AbstractInsnNode node : method.instructions) { if (node.getOpcode() >= 0) { nodes.add(node); } }
		return nodes;
	}

	/** Stable across line numbers, constant-pool indexes, stack-map frames and local debug names. */
	public static String fingerprint(MethodNode method) {
		Map<LabelNode, Integer> labels = new HashMap<>();
		int index = 0;
		for (AbstractInsnNode node : method.instructions) {
			if (node instanceof LabelNode) { labels.put((LabelNode) node, index); }
			if (node.getOpcode() >= 0) { index++; }
		}
		StringBuilder code = new StringBuilder(method.desc).append('|').append(method.access).append('|');
		for (AbstractInsnNode node : code(method)) {
			code.append(node.getOpcode()).append(':');
			if (node instanceof VarInsnNode) { code.append(((VarInsnNode) node).var); }
			else if (node instanceof TypeInsnNode) { code.append(((TypeInsnNode) node).desc); }
			else if (node instanceof FieldInsnNode) {
				FieldInsnNode f = (FieldInsnNode) node; code.append(f.owner).append('.').append(f.name).append(f.desc);
			} else if (node instanceof MethodInsnNode) {
				MethodInsnNode m = (MethodInsnNode) node; code.append(m.owner).append('.').append(m.name).append(m.desc).append(m.itf);
			} else if (node instanceof LdcInsnNode) {
				Object value = ((LdcInsnNode) node).cst; code.append(value.getClass().getName()).append('=').append(value);
			} else if (node instanceof IntInsnNode) { code.append(((IntInsnNode) node).operand); }
			else if (node instanceof IincInsnNode) { IincInsnNode i = (IincInsnNode) node; code.append(i.var).append(',').append(i.incr); }
			else if (node instanceof JumpInsnNode) { code.append(labels.get(((JumpInsnNode) node).label)); }
			else if (!(node instanceof InsnNode)) { return "unsupported-instruction"; }
			code.append(';');
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(code.toString().getBytes(StandardCharsets.UTF_8));
			StringBuilder result = new StringBuilder();
			for (byte b : digest) { result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255)); }
			return result.toString();
		} catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
	}
}
