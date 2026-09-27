package net.coderbot.iris.pipeline;

import java.util.function.IntConsumer;
import net.minecraft.client.renderer.RenderType;
import net.coderbot.iris.shadows.ShadowRenderingState;

/** Optional diagnostics bridge. Null outside a capture; no dependency on the diagnostics mod. */
public final class RenderStageObserver {
    private static IntConsumer begin, end;
    public static void install(IntConsumer onBegin, IntConsumer onEnd) {
        begin=onBegin; end=onEnd;
    }
    public static void begin(int stage) {
        if(begin!=null) try { begin.accept(stage); } catch(RuntimeException failure) { disable(failure); }
    }
    public static void end(int stage) {
        if(end!=null) try { end.accept(stage); } catch(RuntimeException failure) { disable(failure); }
    }
    private static void disable(RuntimeException failure) {
        begin=end=null;
        net.coderbot.iris.Iris.logger.warn("Render-stage diagnostics disabled after callback failure",failure);
    }
    public static void beginTerrain(RenderType type) { if(begin!=null) begin(terrainStage(type)); }
    public static void endTerrain(RenderType type) { if(end!=null) end(terrainStage(type)); }
    private static int terrainStage(RenderType type) {
        int layer=type==RenderType.solid()?0:type==RenderType.cutout()?1:
            type==RenderType.cutoutMipped()?2:type==RenderType.translucent()?3:4;
        return 1+layer+(ShadowRenderingState.areShadowsCurrentlyBeingRendered()?5:0);
    }
}
