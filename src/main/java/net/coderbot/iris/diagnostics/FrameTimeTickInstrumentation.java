package net.coderbot.iris.diagnostics;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.tree.*;

import static org.objectweb.asm.Opcodes.*;

/** Exact call-site timers for the client tick. No scheduling, tick or packet limits are changed. */
final class FrameTimeTickInstrumentation {
	private static final String MC = "net/minecraft/client/Minecraft";
	private static final String PREFIX = "iris$frameDetail$tick$";

	private FrameTimeTickInstrumentation() { }

	static int instrument(ClassNode target) {
		int changed = 0;
		List<MethodNode> helpers = new ArrayList<>();
		for (MethodNode method : target.methods) {
			if (target.name.equals(MC) && is(method.name, "tick", "func_71407_l") && method.desc.equals("()V")) {
				for (AbstractInsnNode insn : method.instructions.toArray()) {
					if (!(insn instanceof MethodInsnNode)) { continue; }
					MethodInsnNode call = (MethodInsnNode) insn;
					int scope = tickScope(call);
					if (scope < 0) { continue; }
					MethodNode helper = FrameTimeMapInstrumentation.helper(PREFIX + helpers.size(), call, scope);
					helpers.add(helper);
					method.instructions.set(call, new MethodInsnNode(INVOKESTATIC, target.name, helper.name, helper.desc, false));
					FrameTimeDetails.registerHook(scope);
					changed++;
				}
				changed += wrap(method, FrameTimeDetails.CLIENT_TICK);
			} else {
				int scope = methodScope(target.name, method.name, method.desc);
				if (scope >= 0) { changed += wrap(method, scope); }
			}
		}
		target.methods.addAll(helpers);
		return changed;
	}

	private static int wrap(MethodNode method, int scope) {
		if (!FrameTimeDetailInstrumentation.wrap(method, scope)) { return 0; }
		FrameTimeDetails.registerHook(scope);
		return 1;
	}

	private static int tickScope(MethodInsnNode call) {
		if (call.getOpcode() != INVOKESTATIC && call.getOpcode() != INVOKEVIRTUAL) { return -1; }
		String owner = call.owner, name = call.name, desc = call.desc;
		if (owner.equals("net/minecraftforge/fml/hooks/BasicEventHooks") && desc.equals("()V")) {
			if (name.equals("onPreClientTick")) { return FrameTimeDetails.TICK_PRE; }
			if (name.equals("onPostClientTick")) { return FrameTimeDetails.TICK_POST; }
		}
		if (is(owner, "net/minecraft/client/multiplayer/MultiPlayerGameMode", "net/minecraft/client/multiplayer/PlayerController")
				&& is(name, "tick", "func_78765_e") && desc.equals("()V")) { return FrameTimeDetails.TICK_GAME_MODE; }
		if (is(owner, "net/minecraft/client/multiplayer/ClientLevel", "net/minecraft/client/world/ClientWorld")) {
			if (is(name, "tickEntities", "func_217419_d") && desc.equals("()V")) { return FrameTimeDetails.TICK_ENTITIES; }
			if (is(name, "tick", "func_72835_b") && desc.equals("(Ljava/util/function/BooleanSupplier;)V")) { return FrameTimeDetails.TICK_LEVEL; }
			if (is(name, "animateTick", "func_73029_E") && desc.equals("(III)V")) { return FrameTimeDetails.TICK_ANIMATE; }
		}
		if (is(owner, "net/minecraft/client/particle/ParticleEngine", "net/minecraft/client/particle/ParticleManager")
				&& is(name, "tick", "func_78868_a") && desc.equals("()V")) { return FrameTimeDetails.TICK_PARTICLES; }
		if (is(owner, "net/minecraft/client/sounds/SoundManager", "net/minecraft/client/audio/SoundHandler")
				&& is(name, "tick", "func_215290_a") && desc.equals("(Z)V")) { return FrameTimeDetails.TICK_SOUND; }
		if (is(owner, "net/minecraft/client/sounds/MusicManager", "net/minecraft/client/audio/MusicTicker")
				&& is(name, "tick", "func_73660_a") && desc.equals("()V")) { return FrameTimeDetails.TICK_SOUND; }
		if (owner.equals("net/minecraft/client/renderer/texture/TextureManager")
				&& is(name, "tick", "func_110550_d") && desc.equals("()V")) { return FrameTimeDetails.TICK_TEXTURES; }
		if (is(owner, "net/minecraft/client/gui/Gui", "net/minecraft/client/gui/IngameGui")
				&& is(name, "tick", "func_73831_a") && desc.equals("()V")) { return FrameTimeDetails.TICK_GUI; }
		if (owner.equals("net/minecraft/client/renderer/GameRenderer")
				&& (is(name, "tick", "func_78464_a") && desc.equals("()V")
					|| is(name, "pick", "func_78473_a") && desc.equals("(F)V"))) { return FrameTimeDetails.TICK_RENDERER; }
		if (is(owner, "net/minecraft/client/renderer/LevelRenderer", "net/minecraft/client/renderer/WorldRenderer")
				&& is(name, "tick", "func_72734_e") && desc.equals("()V")) { return FrameTimeDetails.TICK_RENDERER; }
		return -1;
	}

	private static int methodScope(String owner, String name, String desc) {
		if (owner.equals("org/orecruncher/environs/handlers/AreaBlockEffects") && name.equals("process")
				&& is(desc, "(Lnet/minecraft/world/entity/player/Player;)V", "(Lnet/minecraft/entity/player/PlayerEntity;)V")) { return FrameTimeDetails.DS_AREA; }
		if (owner.equals("org/orecruncher/environs/scanner/Scanner") && name.equals("tick") && desc.equals("()V")) { return FrameTimeDetails.DS_SCAN; }
		if (owner.equals("org/orecruncher/environs/scanner/CuboidScanner")) {
			if (name.equals("updateScan") && desc.equals("(Lorg/orecruncher/environs/scanner/Cuboid;Lorg/orecruncher/environs/scanner/Cuboid;Lorg/orecruncher/environs/scanner/Cuboid;)V")) { return FrameTimeDetails.DS_DELTA; }
			if (name.equals("onBlockUpdate") && is(desc, "(Lnet/minecraft/core/BlockPos;)V", "(Lnet/minecraft/util/math/BlockPos;)V")) { return FrameTimeDetails.DS_BLOCK_UPDATE; }
		}
		if (is(owner, "net/minecraft/client/multiplayer/ClientPacketListener", "net/minecraft/client/network/play/ClientPlayNetHandler")) {
			if (is(name, "handleLevelChunk", "func_147263_a") && is(desc, "(Lnet/minecraft/network/protocol/game/ClientboundLevelChunkPacket;)V", "(Lnet/minecraft/network/play/server/SChunkDataPacket;)V")) { return FrameTimeDetails.PACKET_CHUNK; }
			if (is(name, "handleBlockUpdate", "func_147234_a") && is(desc, "(Lnet/minecraft/network/protocol/game/ClientboundBlockUpdatePacket;)V", "(Lnet/minecraft/network/play/server/SChangeBlockPacket;)V")) { return FrameTimeDetails.PACKET_BLOCK; }
			if (is(name, "handleChunkBlocksUpdate", "func_147287_a") && is(desc, "(Lnet/minecraft/network/protocol/game/ClientboundSectionBlocksUpdatePacket;)V", "(Lnet/minecraft/network/play/server/SMultiBlockChangePacket;)V")) { return FrameTimeDetails.PACKET_BLOCK; }
			if (is(name, "handleLightUpdatePacked", "func_217269_a") && is(desc, "(Lnet/minecraft/network/protocol/game/ClientboundLightUpdatePacket;)V", "(Lnet/minecraft/network/play/server/SUpdateLightPacket;)V")) { return FrameTimeDetails.PACKET_LIGHT; }
		}
		return -1;
	}

	private static boolean is(String actual, String named, String srg) { return actual.equals(named) || actual.equals(srg); }
}
