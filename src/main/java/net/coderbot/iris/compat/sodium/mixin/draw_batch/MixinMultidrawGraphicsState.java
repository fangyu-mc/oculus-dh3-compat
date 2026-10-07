package net.coderbot.iris.compat.sodium.mixin.draw_batch;

import me.jellysquid.mods.sodium.client.gl.util.BufferSlice;
import me.jellysquid.mods.sodium.client.render.chunk.backends.multidraw.MultidrawGraphicsState;
import net.coderbot.iris.compat.sodium.impl.NonEmptyModelParts;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MultidrawGraphicsState.class, remap = false)
public abstract class MixinMultidrawGraphicsState implements NonEmptyModelParts {

	@Shadow @Final private long[] parts;

	@Unique private int iris$nonEmptyFaces;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void iris$cacheNonEmptyFaces(CallbackInfo ci) {
		// The parts array is fixed for the lifetime of this uploaded graphics state.
		// A rebuild or translucent re-sort uploads a new state and recomputes the mask.
		for (int face = 0; face < parts.length; face++) {
			if (BufferSlice.unpackLength(parts[face]) != 0) {
				iris$nonEmptyFaces |= 1 << face;
			}
		}
	}

	@Override
	public int iris$getNonEmptyFaces() {
		return iris$nonEmptyFaces;
	}
}
