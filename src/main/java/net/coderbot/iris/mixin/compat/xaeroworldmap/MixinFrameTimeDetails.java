package net.coderbot.iris.mixin.compat.xaeroworldmap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = {
		"xaero.map.MapProcessor",
		"xaero.map.graphics.TextureUploader",
		"xaero.map.region.texture.RegionTexture"
}, priority = 400, remap = false)
public abstract class MixinFrameTimeDetails { }
