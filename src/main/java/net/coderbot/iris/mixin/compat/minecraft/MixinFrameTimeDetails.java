package net.coderbot.iris.mixin.compat.minecraft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.KeyboardHandler;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;

/** Marker for post-Mixin instrumentation; class literals are remapped with the production jar. */
@Mixin(value = {Minecraft.class, ClientPacketListener.class, BlockableEventLoop.class, PacketUtils.class,
		Window.class, MouseHandler.class, KeyboardHandler.class}, priority = 400)
public abstract class MixinFrameTimeDetails { }
