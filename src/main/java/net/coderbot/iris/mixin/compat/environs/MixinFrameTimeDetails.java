package net.coderbot.iris.mixin.compat.environs;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = {
		"org.orecruncher.environs.handlers.AreaBlockEffects",
		"org.orecruncher.environs.scanner.Scanner",
		"org.orecruncher.environs.scanner.CuboidScanner"
}, priority = 400, remap = false)
public abstract class MixinFrameTimeDetails { }
