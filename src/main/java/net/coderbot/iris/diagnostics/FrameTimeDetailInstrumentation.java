package net.coderbot.iris.diagnostics;

import org.apache.logging.log4j.LogManager;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import static org.objectweb.asm.Opcodes.*;

/** Wraps known methods after Mixin injection, including cancellation and exceptional exits. */
public final class FrameTimeDetailInstrumentation {
	private static final String RECORDER = "net/coderbot/iris/diagnostics/FrameTimeRecorder";
	private static final String SODIUM = "me/jellysquid/mods/sodium/client/";
	private static final String DH = "com/seibel/distanthorizons/";

	private FrameTimeDetailInstrumentation() { }

	public static void apply(ClassNode target) {
		int changed = instrument(target);
		LogManager.getLogger("Oculus").info("[FrameTime] Detail hooks: {} methods/call sites in {}", changed, target.name);
		if (changed == 0) {
			LogManager.getLogger("Oculus").warn("[FrameTime] No matching detail method; leaving class unchanged: {}", target.name);
		}
	}

	public static int instrument(ClassNode target) {
		int changed = 0;
		for (MethodNode method : target.methods) {
			int scope = scope(target.name, method.name, method.desc);
			if (scope >= 0 && wrap(method, scope)) {
				FrameTimeDetails.registerHook(scope);
				changed++;
			}
		}
		return changed + FrameTimeMapInstrumentation.instrument(target) + FrameTimeTickInstrumentation.instrument(target)
				+ FrameTimeTaskInstrumentation.instrument(target);
	}

	private static int scope(String owner, String name, String desc) {
		switch (owner) {
			case "com/mojang/blaze3d/platform/Window":
			case "net/minecraft/client/MainWindow":
				if (desc.equals("(JII)V")) {
					if (name.equals("onFramebufferResize") || name.equals("func_198102_b")
							|| name.equals("onResize") || name.equals("func_198089_c")) { return FrameTimeDetails.WINDOW_RESIZE; }
					if (name.equals("onMove") || name.equals("func_198080_a")) { return FrameTimeDetails.WINDOW_MOVE; }
				}
				if (desc.equals("(JZ)V") && (name.equals("onFocus") || name.equals("func_198095_a")
						|| name.equals("onEnter") || name.equals("func_241553_b_"))) { return FrameTimeDetails.WINDOW_FOCUS; }
				break;
			case "net/minecraft/client/MouseHandler":
			case "net/minecraft/client/MouseHelper":
				if (desc.equals("(JDD)V") && (name.equals("onMove") || name.equals("func_198022_b")
						|| name.equals("onScroll") || name.equals("func_198020_a"))) { return FrameTimeDetails.MOUSE_INPUT; }
				if (desc.equals("(JIII)V") && (name.equals("onPress") || name.equals("func_198023_a"))) { return FrameTimeDetails.MOUSE_INPUT; }
				if (desc.equals("(JLjava/util/List;)V") && (name.equals("onDrop") || name.equals("func_238228_a_"))) { return FrameTimeDetails.MOUSE_INPUT; }
				break;
			case "net/minecraft/client/KeyboardHandler":
			case "net/minecraft/client/KeyboardListener":
				if (desc.equals("(JIIII)V") && (name.equals("keyPress") || name.equals("func_197961_a"))) { return FrameTimeDetails.KEYBOARD_INPUT; }
				if (desc.equals("(JII)V") && (name.equals("charTyped") || name.equals("func_197963_a"))) { return FrameTimeDetails.KEYBOARD_INPUT; }
				break;
			case SODIUM + "render/chunk/ChunkRenderManager":
				if (name.equals("updateChunks") && desc.equals("()V")) { return FrameTimeDetails.CHUNK_UPDATE; }
				if (name.equals("update") && (desc.equals("(Lnet/minecraft/client/Camera;L" + SODIUM + "util/math/FrustumExtended;IZ)V")
						|| desc.equals("(Lnet/minecraft/client/renderer/ActiveRenderInfo;L" + SODIUM + "util/math/FrustumExtended;IZ)V"))) {
					return FrameTimeDetails.CHUNK_UPDATE;
				}
				break;
			case SODIUM + "render/chunk/backends/multidraw/MultidrawChunkRenderBackend":
				if (name.equals("upload") && desc.equals("(L" + SODIUM + "gl/device/CommandList;Ljava/util/Iterator;)V")) { return FrameTimeDetails.CHUNK_UPLOAD; }
				if (name.equals("render") && desc.equals("(L" + SODIUM + "gl/device/CommandList;L" + SODIUM + "render/chunk/lists/ChunkRenderListIterator;L" + SODIUM + "render/chunk/ChunkCameraContext;)V")) { return FrameTimeDetails.CHUNK_DRAW; }
				break;
			case SODIUM + "gl/device/GLRenderDevice$ImmediateCommandList":
				if (name.equals("uploadData") && desc.equals("(L" + SODIUM + "gl/buffer/GlMutableBuffer;Ljava/nio/ByteBuffer;)V")) { return FrameTimeDetails.CHUNK_GL_UPLOAD; }
				if (name.equals("copyBufferSubData") && desc.equals("(L" + SODIUM + "gl/buffer/GlBuffer;L" + SODIUM + "gl/buffer/GlMutableBuffer;JJJ)V")) { return FrameTimeDetails.CHUNK_GL_UPLOAD; }
				if (name.equals("allocateBuffer") && desc.equals("(L" + SODIUM + "gl/buffer/GlBufferTarget;L" + SODIUM + "gl/buffer/GlMutableBuffer;J)V")) { return FrameTimeDetails.CHUNK_GL_UPLOAD; }
				break;
			case DH + "common/render/openGl/glObject/buffer/GLBuffer_forge":
				if (name.equals("uploadBuffer") && desc.equals("(Ljava/nio/ByteBuffer;L" + DH + "api/enums/config/EDhApiGpuUploadMethod;II)V")) { return FrameTimeDetails.DH_UPLOAD; }
				break;
			case DH + "core/render/renderer/LodRenderer":
				if ((name.equals("render") || name.equals("renderDeferred")) && desc.equals("(L" + DH + "core/render/RenderParams;L" + DH + "core/wrapperInterfaces/minecraft/IProfilerWrapper;)V")) { return FrameTimeDetails.DH_RENDER; }
				break;
			case "xaero/map/MapProcessor":
				if (name.equals("onRenderProcess") && desc.equals("(Lnet/minecraft/client/Minecraft;)V")) { return FrameTimeDetails.MAP_UPDATE; }
				break;
			case "xaero/map/graphics/TextureUploader":
				if (name.equals("uploadTextures") && desc.equals("()V")) { return FrameTimeDetails.MAP_UPLOAD; }
				break;
			case "xaero/map/region/texture/RegionTexture":
				if (name.equals("writeToUnpackPBO") && desc.equals("(ILxaero/map/pool/buffer/PoolTextureDirectBufferUnit;Z)V")) { return FrameTimeDetails.MAP_PBO_WRITE; }
				if (name.equals("endPBODownload") && desc.equals("(IZZ)V")) { return FrameTimeDetails.MAP_READBACK; }
				break;
			default:
		}
		return -1;
	}

	static boolean wrap(MethodNode method, int scope) {
		if ((method.access & (ACC_ABSTRACT | ACC_NATIVE)) != 0 || method.instructions.size() == 0) { return false; }
		for (AbstractInsnNode insn : method.instructions) {
			if (insn.getOpcode() == JSR || insn.getOpcode() == RET) { return false; }
			if (insn instanceof MethodInsnNode && ((MethodInsnNode) insn).owner.equals(RECORDER)
					&& ((MethodInsnNode) insn).name.equals("beginDetail")) { return false; }
		}
		Type resultType = Type.getReturnType(method.desc);
		int token = method.maxLocals, result = token + 2, exception = result + resultType.getSize();
		method.maxLocals = exception + 1;
		LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode();
		InsnList exits = new InsnList();
		// Each RETURN gets its own exit outside the catch-all. An INVOKE/FIELD Mixin
		// cancellation can return with live operands, which RETURN legally discards.
		// Merging that path with an ordinary empty-stack return breaks frame computation.
		for (AbstractInsnNode insn : method.instructions.toArray()) {
			if (insn.getOpcode() >= IRETURN && insn.getOpcode() <= RETURN) {
				LabelNode exit = new LabelNode();
				if (resultType.getSort() != Type.VOID) {
					method.instructions.insertBefore(insn, new VarInsnNode(resultType.getOpcode(ISTORE), result));
				}
				method.instructions.set(insn, new JumpInsnNode(GOTO, exit));
				exits.add(exit);
				endCall(exits, scope, token, false);
				if (resultType.getSort() != Type.VOID) {
					exits.add(new VarInsnNode(resultType.getOpcode(ILOAD), result));
				}
				exits.add(new InsnNode(resultType.getOpcode(IRETURN)));
			}
			else if (insn instanceof FrameNode) { method.instructions.remove(insn); }
		}
		InsnList entry = new InsnList();
		entry.add(new LdcInsnNode(scope));
		entry.add(new MethodInsnNode(INVOKESTATIC, RECORDER, "beginDetail", "(I)J", false));
		entry.add(new VarInsnNode(LSTORE, token));
		entry.add(start);
		method.instructions.insert(entry);
		method.instructions.add(end);
		method.instructions.add(exits);
		method.instructions.add(handler);
		method.instructions.add(new VarInsnNode(ASTORE, exception));
		endCall(method.instructions, scope, token, true);
		method.instructions.add(new VarInsnNode(ALOAD, exception));
		method.instructions.add(new InsnNode(ATHROW));
		// Original catches/finally run first. The original throwable is propagated unchanged.
		method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
		// Exit hooks push four slots above any operands discarded by the original return.
		method.maxStack += 4;
		return true;
	}

	private static void endCall(InsnList body, int scope, int token, boolean failed) {
		body.add(new LdcInsnNode(scope));
		body.add(new VarInsnNode(LLOAD, token));
		body.add(new InsnNode(failed ? ICONST_1 : ICONST_0));
		body.add(new MethodInsnNode(INVOKESTATIC, RECORDER, "endDetail", "(IJZ)V", false));
	}
}
