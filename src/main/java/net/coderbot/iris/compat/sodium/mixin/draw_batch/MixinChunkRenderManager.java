package net.coderbot.iris.compat.sodium.mixin.draw_batch;

import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderManager;
import me.jellysquid.mods.sodium.client.render.chunk.backends.multidraw.MultidrawGraphicsState;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.coderbot.iris.compat.sodium.impl.NonEmptyModelParts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = ChunkRenderManager.class, remap = false)
public abstract class MixinChunkRenderManager {

	@Redirect(method = "addChunkToRenderLists", at = @At(value = "INVOKE",
			target = "Lme/jellysquid/mods/sodium/client/render/chunk/lists/ChunkRenderList;add(Ljava/lang/Object;I)V"))
	private <T> void iris$onlyFacesWithGeometry(ChunkRenderList<T> list, T state, int faces) {
		// Other backends and subclasses may compute model parts dynamically.
		if (state instanceof NonEmptyModelParts && state.getClass() == MultidrawGraphicsState.class) {
			faces &= ((NonEmptyModelParts) state).iris$getNonEmptyFaces();
		}
		// Retain even zero-mask entries: their uniform index and first region encounter
		// must stay unchanged, including equal-distance translucent region ordering.
		list.add(state, faces);
	}
}
