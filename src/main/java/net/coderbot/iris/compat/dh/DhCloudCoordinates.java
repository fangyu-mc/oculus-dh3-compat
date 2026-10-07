package net.coderbot.iris.compat.dh;

import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderableBoxGroup;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3d;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;

/** Optional scalar bridges. Public DH vector APIs retain their original ownership contracts. */
public final class DhCloudCoordinates {
    private DhCloudCoordinates() { }

    public interface Camera {
        double iris$cloudCameraX();
        double iris$cloudCameraZ();
    }

    public interface Origin {
        void iris$cloudOrigin(double x, double y, double z);
    }

    public static double cameraX(IMinecraftRenderWrapper camera) {
        return camera instanceof Camera ? ((Camera) camera).iris$cloudCameraX() : camera.getCameraExactPosition().x;
    }

    public static double cameraZ(IMinecraftRenderWrapper camera) {
        return camera instanceof Camera ? ((Camera) camera).iris$cloudCameraZ() : camera.getCameraExactPosition().z;
    }

    public static void setOrigin(IDhApiRenderableBoxGroup group, double x, double y, double z) {
        if (group instanceof Origin) {
            ((Origin) group).iris$cloudOrigin(x, y, z);
        } else {
            // Custom groups may retain the parameter. Never lend them a reusable vector.
            group.setOriginBlockPos(new DhApiVec3d(x, y, z));
        }
    }
}
