package net.coderbot.iris.mixin.color;

import java.util.Optional;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Biome.class)
public abstract class MixinBiome {

	@Shadow @Final private BiomeSpecialEffects specialEffects;

	@Shadow
	private int getGrassColorFromTexture() {
		throw new AssertionError("Mixin shadow");
	}

	@Shadow
	private int getFoliageColorFromTexture() {
		throw new AssertionError("Mixin shadow");
	}

	/**
	 * @author Fangyu
	 * @reason Avoid boxing the native int fallback through Optional.orElseGet on every color sample.
	 */
	@Overwrite
	public int getGrassColor(double x, double z) {
		Optional<Integer> override = specialEffects.getGrassColorOverride();
		int baseColor = override.isPresent() ? override.get() : getGrassColorFromTexture();
		// Keep the original texture lookup and coordinate-dependent/custom modifier live.
		// In particular, do not retain colors across resource reloads or biome changes.
		return specialEffects.getGrassColorModifier().modifyColor(x, z, baseColor);
	}

	/**
	 * @author Fangyu
	 * @reason Keep the texture fallback primitive while preserving explicit foliage overrides.
	 */
	@Overwrite
	public int getFoliageColor() {
		Optional<Integer> override = specialEffects.getFoliageColorOverride();
		return override.isPresent() ? override.get() : getFoliageColorFromTexture();
	}
}
