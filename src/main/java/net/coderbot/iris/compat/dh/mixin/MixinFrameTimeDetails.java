package net.coderbot.iris.compat.dh.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = {
		"com.seibel.distanthorizons.common.render.openGl.glObject.buffer.GLBuffer_forge",
		"com.seibel.distanthorizons.core.render.renderer.LodRenderer"
}, priority = 400, remap = false)
public abstract class MixinFrameTimeDetails { }
