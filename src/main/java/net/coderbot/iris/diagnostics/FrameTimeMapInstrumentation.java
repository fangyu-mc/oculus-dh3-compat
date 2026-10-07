package net.coderbot.iris.diagnostics;

import java.util.*;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import static org.objectweb.asm.Opcodes.*;

/** Timers around an explicit call whitelist; no additional rendering, uploads or GL queries. */
public final class FrameTimeMapInstrumentation {
	private static final String PROCESSOR = "xaero/map/MapProcessor";
	private static final String TEXTURE = "xaero/map/region/texture/RegionTexture";
	private static final String REGION = "xaero/map/region/LeveledRegion";
	private static final String PREFIX = "iris$frameDetail$map$";

	private FrameTimeMapInstrumentation() { }

	public static int instrument(ClassNode target) {
		if (!target.name.equals(PROCESSOR)) { return 0; }
		List<MethodNode> helpers = new ArrayList<>();
		Map<String, MethodNode> reused = new HashMap<>();
		Set<String> names = new HashSet<>();
		for (MethodNode m : target.methods) { names.add(m.name); }
		int changed = 0;
		for (MethodNode method : target.methods) {
			if (!eligible(method)) { continue; }
			for (AbstractInsnNode insn : method.instructions.toArray()) {
				if (!(insn instanceof MethodInsnNode)) { continue; }
				MethodInsnNode call = (MethodInsnNode) insn;
				int scope = scope(call);
				if (scope < 0) { continue; }
				String key = call.getOpcode() + ":" + call.owner + "." + call.name + call.desc;
				MethodNode helper = reused.get(key);
				if (helper == null) {
					int suffix = helpers.size();
					String name;
					do { name = PREFIX + suffix++; } while (!names.add(name));
					helper = helper(name, call, scope);
					helpers.add(helper); reused.put(key, helper);
					FrameTimeDetails.registerHook(scope);
				}
				// The receiver and arguments are already evaluated, in the original order.
				method.instructions.set(call, new MethodInsnNode(INVOKESTATIC, target.name, helper.name, helper.desc, false));
				changed++;
			}
		}
		target.methods.addAll(helpers);
		return changed;
	}

	private static boolean eligible(MethodNode method) {
		if (method.name.equals("onRenderProcess") && method.desc.equals("(Lnet/minecraft/client/Minecraft;)V")) { return true; }
		// DH-Xaero redirects uploadBuffer into its budget handler. Time only the actual admitted
		// upload inside that handler; do not bypass the handler or turn a deferred upload into work.
		if (method.name.startsWith(PREFIX) || method.visibleAnnotations == null) { return false; }
		for (AnnotationNode annotation : method.visibleAnnotations) {
			if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;") || annotation.values == null) { continue; }
			for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
				if (annotation.values.get(i).equals("mixin") && annotation.values.get(i + 1).equals("com.fangyu.dhxaero.mixin.MapProcessorBudgetMixin")) { return true; }
			}
		}
		return false;
	}

	static MethodNode helper(String name, MethodInsnNode original, int scope) {
		Type[] args = Type.getArgumentTypes(original.desc);
		if (original.getOpcode() != INVOKESTATIC) {
			Type[] withReceiver = new Type[args.length + 1];
			withReceiver[0] = Type.getObjectType(original.owner);
			System.arraycopy(args, 0, withReceiver, 1, args.length); args = withReceiver;
		}
		Type result = Type.getReturnType(original.desc);
		MethodNode helper = new MethodNode(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, name, Type.getMethodDescriptor(result, args), null, null);
		int slot = 0;
		for (Type type : args) {
			helper.instructions.add(new VarInsnNode(type.getOpcode(ILOAD), slot)); slot += type.getSize();
		}
		helper.instructions.add(new MethodInsnNode(original.getOpcode(), original.owner, original.name, original.desc, original.itf));
		helper.instructions.add(new InsnNode(result.getOpcode(IRETURN)));
		helper.maxLocals = slot; helper.maxStack = Math.max(slot, result.getSize());
		FrameTimeDetailInstrumentation.wrap(helper, scope);
		return helper;
	}

	private static int scope(MethodInsnNode call) {
		if (call.getOpcode() != INVOKESTATIC && call.getOpcode() != INVOKEVIRTUAL && call.getOpcode() != INVOKEINTERFACE) { return -1; }
		String owner = call.owner, name = call.name, desc = call.desc;
		if (owner.equals("xaero/map/MapWriter") && name.equals("onRender")
				&& desc.equals("(Lxaero/map/biome/BiomeColorCalculator;Lxaero/map/region/OverlayManager;)V")) { return FrameTimeDetails.MAP_WRITE; }
		if (owner.equals(TEXTURE)) {
			if (name.equals("preUpload") && desc.equals("(Lxaero/map/MapProcessor;Lxaero/map/biome/BlockTintProvider;Lxaero/map/region/OverlayManager;L" + REGION + ";ZLxaero/map/cache/BlockStateShortShapeCache;Lxaero/map/region/MapUpdateFastConfig;)V")) { return FrameTimeDetails.MAP_PREPARE; }
			if (name.equals("uploadBuffer") && desc.equals("(Lxaero/map/highlight/DimensionHighlighterHandler;Lxaero/map/graphics/TextureUploader;L" + REGION + ";Lxaero/map/region/texture/BranchTextureRenderer;II)J")) { return FrameTimeDetails.MAP_BUFFER; }
			if (name.equals("postUpload") && desc.equals("(Lxaero/map/MapProcessor;L" + REGION + ";Z)V")) { return FrameTimeDetails.MAP_POST_UPLOAD; }
		}
		if (owner.equals(REGION)) {
			if (name.equals("getTexture") && desc.equals("(II)L" + TEXTURE + ";")) { return FrameTimeDetails.MAP_TEXTURE_LOOKUP; }
			if (name.equals("deleteGLBuffers") && desc.equals("()V")) { return FrameTimeDetails.MAP_CLEANUP; }
			if (name.equals("onProcessingEnd") && desc.equals("()V")) { return FrameTimeDetails.MAP_PROCESS_END; }
			if (name.equals("processWhenLoadedChunksExist") && desc.equals("(I)V")) { return FrameTimeDetails.MAP_LOADED_CHUNKS; }
		}
		if (owner.equals("xaero/map/MapLimiter") && name.equals("updateAvailableVRAM") && desc.equals("()V")) { return FrameTimeDetails.MAP_VRAM; }
		if ((owner.equals("net/minecraft/client/renderer/IRenderTypeBuffer$Impl") && name.equals("func_228461_a_")
				|| owner.equals("net/minecraft/client/renderer/MultiBufferSource$BufferSource") && name.equals("endBatch"))
				&& desc.equals("()V")) { return FrameTimeDetails.MAP_FLUSH; }
		if (owner.equals("org/lwjgl/opengl/GL11") && name.equals("glGetError") && desc.equals("()I")
				|| owner.equals("xaero/map/exception/OpenGLException") && name.equals("checkGLError") && desc.equals("()V")) { return FrameTimeDetails.MAP_GL_CHECK; }
		if (owner.equals("org/lwjgl/opengl/GL11") && name.equals("glClearColor") && desc.equals("(FFFF)V")
				|| owner.equals("com/mojang/blaze3d/systems/RenderSystem") && name.equals("pixelStore") && desc.equals("(II)V")
				|| owner.equals("com/mojang/blaze3d/platform/GlStateManager") && desc.equals("(FFFF)V")
						&& (name.equals("func_227673_b_") || name.equals("_clearColor") || name.equals("func_227702_d_") || name.equals("_color4f"))) { return FrameTimeDetails.MAP_GL_STATE; }
		if (owner.equals("xaero/map/file/MapSaveLoad") && name.equals("requestCache") && desc.equals("(L" + REGION + ";)V")) { return FrameTimeDetails.MAP_CACHE; }
		if (owner.equals(PROCESSOR) && desc.equals("()V")) {
			if (name.equals("updateConfigValuesForMultipleThreads")) { return FrameTimeDetails.MAP_CONFIG; }
			if (name.equals("updateCaveStart")) { return FrameTimeDetails.MAP_CAVE; }
		}
		if (owner.equals("xaero/map/MapFullReloader") && name.equals("onRenderProcess") && desc.equals("()V")) { return FrameTimeDetails.MAP_RELOAD; }
		return -1;
	}
}
