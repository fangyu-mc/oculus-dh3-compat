package net.coderbot.iris.diagnostics;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.tree.*;

import static org.objectweb.asm.Opcodes.*;

/** Time actual dispatch, inside the original queue/connection guards and exception handlers. */
final class FrameTimeTaskInstrumentation {
	private static final String RECORDER = "net/coderbot/iris/diagnostics/FrameTimeRecorder";
	private static final String PREFIX = "iris$frameDetail$work$";

	static int instrument(ClassNode target) {
		boolean task = is(target.name, "net/minecraft/util/thread/BlockableEventLoop", "net/minecraft/util/concurrent/ThreadTaskExecutor");
		boolean packet = is(target.name, "net/minecraft/network/protocol/PacketUtils", "net/minecraft/network/PacketThreadUtil");
		if (!task && !packet) { return 0; }
		List<MethodNode> helpers = new ArrayList<>();
		for (MethodNode method : target.methods) {
			if (method.name.startsWith(PREFIX)) { continue; }
			if (task && (!is(method.name, "doRunTask", "func_213166_h") || !method.desc.equals("(Ljava/lang/Runnable;)V"))) { continue; }
			for (AbstractInsnNode insn : method.instructions.toArray()) {
				if (!(insn instanceof MethodInsnNode)) { continue; }
				MethodInsnNode call = (MethodInsnNode) insn;
				if (call.getOpcode() != INVOKEINTERFACE) { continue; }
				boolean match = task ? call.owner.equals("java/lang/Runnable") && call.name.equals("run") && call.desc.equals("()V")
						: is(call.owner, "net/minecraft/network/protocol/Packet", "net/minecraft/network/IPacket")
						&& is(call.name, "handle", "func_148833_a")
						&& is(call.desc, "(Lnet/minecraft/network/PacketListener;)V", "(Lnet/minecraft/network/INetHandler;)V");
				if (!match) { continue; }
				int scope = task ? FrameTimeDetails.MAIN_TASK : FrameTimeDetails.PACKET_APPLY;
				MethodNode helper = FrameTimeMapInstrumentation.helper(PREFIX + helpers.size(), call, scope);
				for (AbstractInsnNode exit : helper.instructions.toArray()) {
					if (!(exit instanceof MethodInsnNode)) { continue; }
					MethodInsnNode end = (MethodInsnNode) exit;
					if (end.owner.equals(RECORDER) && end.name.equals("endDetail")) {
						helper.instructions.insertBefore(end, new VarInsnNode(ALOAD, 0));
						end.name = "endWorkDetail";
						end.desc = "(IJZLjava/lang/Object;)V";
					}
				}
				helper.maxStack++;
				helpers.add(helper);
				method.instructions.set(call, new MethodInsnNode(INVOKESTATIC, target.name, helper.name, helper.desc, false));
				FrameTimeDetails.registerHook(scope);
			}
		}
		target.methods.addAll(helpers);
		return helpers.size();
	}

	private static boolean is(String value, String named, String srg) { return value.equals(named) || value.equals(srg); }
}
