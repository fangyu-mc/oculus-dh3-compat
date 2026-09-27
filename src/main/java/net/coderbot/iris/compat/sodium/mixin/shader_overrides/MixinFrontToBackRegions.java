package net.coderbot.iris.compat.sodium.mixin.shader_overrides;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkCameraContext;
import me.jellysquid.mods.sodium.client.render.chunk.backends.multidraw.MultidrawChunkRenderBackend;
import me.jellysquid.mods.sodium.client.render.chunk.backends.multidraw.MultidrawGraphicsState;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderListIterator;
import me.jellysquid.mods.sodium.client.render.chunk.region.ChunkRegion;
import net.coderbot.iris.compat.sodium.impl.NearTerrainOrder;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=MultidrawChunkRenderBackend.class,remap=false)
public abstract class MixinFrontToBackRegions {
    @Shadow @Final private ObjectArrayList<ChunkRegion<MultidrawGraphicsState>> pendingBatches;
    @Unique private final NearTerrainOrder iris$ordering=new NearTerrainOrder();
    @Unique private ChunkCameraContext iris$orderCamera;
    @Inject(method="render",at=@At("HEAD"))
    private void iris$captureCamera(CommandList commands,ChunkRenderListIterator<MultidrawGraphicsState> list,
                                   ChunkCameraContext camera,CallbackInfo ci) { iris$orderCamera=camera; }
    @Inject(method="buildCommandBuffer",at=@At("HEAD"))
    private void iris$orderRegions(CallbackInfo ci) { iris$ordering.sort(pendingBatches,iris$orderCamera); }
    @Inject(method="render",at=@At("RETURN"))
    private void iris$releaseCamera(CallbackInfo ci) { iris$orderCamera=null; }
}
