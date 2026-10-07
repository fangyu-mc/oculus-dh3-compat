package net.coderbot.iris.mixin.compat.minecraft;

import net.coderbot.iris.math.FullBlockBounds;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(VoxelShape.class)
public abstract class MixinFullBlockBounds {
    @Redirect(method = "bounds", at = @At(value = "NEW", target = "net/minecraft/world/phys/AABB"))
    private AABB iris$fullBlockBounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return FullBlockBounds.create(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
