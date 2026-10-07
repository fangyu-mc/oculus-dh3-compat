package net.coderbot.iris.math;

import net.minecraft.world.phys.AABB;

/** A single immutable unit box; all other bounds retain vanilla allocation and normalization. */
public final class FullBlockBounds {
    private static final AABB UNIT = new AABB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);

    private FullBlockBounds() { }

    public static AABB create(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        // Positive zero checks preserve signed zero as well as NaN/infinity behavior for all other boxes.
        if (Double.doubleToRawLongBits(minX) == 0L && Double.doubleToRawLongBits(minY) == 0L
                && Double.doubleToRawLongBits(minZ) == 0L && maxX == 1.0D && maxY == 1.0D && maxZ == 1.0D) return UNIT;
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
