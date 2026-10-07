package net.coderbot.iris.mixin.compat.illuminations;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** The plugin checks the complete installed method before removing private quad temporaries. */
@Pseudo
@Mixin(targets = "ladysnake.illuminations.client.particle.FireflyParticle", priority = 400, remap = false)
public abstract class MixinFireflyParticle { }
