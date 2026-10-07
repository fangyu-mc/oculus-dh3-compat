package net.coderbot.iris.compat.dh.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** The plugin checks installed method bodies before enabling scalar coordinate bridges. */
@Pseudo
@Mixin(targets = {
        "com.seibel.distanthorizons.core.render.renderer.CloudRenderHandler",
        "com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftRenderWrapper_forge",
        "com.seibel.distanthorizons.core.render.renderer.RenderableBoxGroup"
}, priority = 400, remap = false)
public abstract class MixinDHCloudAllocation { }
