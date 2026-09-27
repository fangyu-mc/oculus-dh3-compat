package net.coderbot.iris.compat.sodium.impl;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkCameraContext;
import me.jellysquid.mods.sodium.client.render.chunk.passes.BlockRenderPass;
import net.coderbot.iris.Iris;
import net.coderbot.iris.shadows.ShadowRenderingState;
import org.lwjgl.opengl.GL11C;

/** Render-thread only. Optional center access keeps older Rubidium backends on their original path. */
public final class NearTerrainOrder {
    private static boolean eligible;
    public static long batchesReordered, regionsReordered;
    private static boolean enabled=Boolean.getBoolean("oculus.experimentalTerrainOrder")
        && !Boolean.getBoolean("oculus.legacyTerrainOrder");
    private final FrontToBackSorter<Object> sorter=new FrontToBackSorter<>();
    private Class<?> regionType;
    private MethodHandle x,y,z;
    private ChunkCameraContext camera;

    public static void begin(BlockRenderPass pass) {
        eligible=enabled && !ShadowRenderingState.areShadowsCurrentlyBeingRendered()
            && (pass==BlockRenderPass.SOLID || pass==BlockRenderPass.CUTOUT || pass==BlockRenderPass.CUTOUT_MIPPED)
            && "ComplementaryReimagined_r5.8.1.zip".equals(Iris.getCurrentPackName());
    }
    public static void end() { eligible=false; }
    public static boolean isEnabled() { return enabled; }
    public static boolean toggle() { enabled=!enabled; return enabled; }

    public void sort(List<?> regions,ChunkCameraContext camera) {
        if(!eligible || camera==null || regions.size()<2 || ShadowRenderingState.areShadowsCurrentlyBeingRendered()) return;
        // Custom blending/depth contracts can make otherwise opaque draw order significant.
        if(GL11C.glIsEnabled(GL11C.GL_BLEND) || !GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST)
            || !GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK)) return;
        int depthFunc=GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC);
        if(depthFunc!=GL11C.GL_LEQUAL && depthFunc!=GL11C.GL_LESS) return;
        Object first=regions.get(0);
        if(first==null) return;
        if(regionType!=first.getClass()) {
            regionType=first.getClass(); x=y=z=null;
            try {
                MethodHandles.Lookup lookup=MethodHandles.publicLookup();
                MethodType source=MethodType.methodType(int.class);
                MethodType generic=MethodType.methodType(int.class,Object.class);
                x=lookup.findVirtual(regionType,"getCenterBlockX",source).asType(generic);
                y=lookup.findVirtual(regionType,"getCenterBlockY",source).asType(generic);
                z=lookup.findVirtual(regionType,"getCenterBlockZ",source).asType(generic);
            } catch(ReflectiveOperationException failure) { x=y=z=null; }
        }
        if(x==null) return;
        this.camera=camera;
        try {
            @SuppressWarnings("unchecked") List<Object> batches=(List<Object>)regions;
            if(sorter.sort(batches,this::distance)) { batchesReordered++; regionsReordered+=regions.size(); }
        } finally { this.camera=null; }
    }
    private double distance(Object region) {
        if(region==null || region.getClass()!=regionType) return Double.NaN;
        try {
            int cx=(int)x.invokeExact(region), cy=(int)y.invokeExact(region), cz=(int)z.invokeExact(region);
            double dx=(double)cx-camera.blockOriginX-camera.originX;
            double dy=(double)cy-camera.blockOriginY-camera.originY;
            double dz=(double)cz-camera.blockOriginZ-camera.originZ;
            return dx*dx+dy*dy+dz*dz;
        } catch(Throwable failure) {
            if(failure instanceof Error) throw (Error)failure;
            return Double.NaN;
        }
    }
}
