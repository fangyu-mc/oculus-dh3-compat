package net.coderbot.iris.compat.sodium.mixin.render_layers;

import java.util.Iterator;
import java.util.List;
import me.jellysquid.mods.sodium.client.render.chunk.tasks.ChunkRenderRebuildTask;
import net.coderbot.iris.compat.sodium.impl.RenderLayerIterator;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = ChunkRenderRebuildTask.class, remap = false)
public class MixinChunkRenderRebuildTask {

	@Unique
	private final RenderLayerIterator<RenderType> iris$renderLayerIterator = new RenderLayerIterator<>();

	// Rubidium has one shared block/fluid loop; Embeddium has two consecutive
	// loops over its cached immutable lists. Both consume their cursor locally.
	@Redirect(method = "performBuild", at = @At(value = "INVOKE",
			target = "Ljava/util/List;iterator()Ljava/util/Iterator;"), require = 1, allow = 2)
	private Iterator<RenderType> iris$reuseRenderLayerIterator(List<RenderType> layers) {
		return iris$renderLayerIterator.acquire(layers);
	}
}
