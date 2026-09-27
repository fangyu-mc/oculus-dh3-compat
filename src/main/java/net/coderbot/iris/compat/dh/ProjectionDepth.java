package net.coderbot.iris.compat.dh;

import net.coderbot.iris.vendored.joml.Matrix4f;

/** Extend a conventional OpenGL perspective without discarding its screen-space transform. */
final class ProjectionDepth {
    static boolean extend(Matrix4f matrix, float near, float far) {
        if (!matrix.isFinite() || !Float.isFinite(near) || !Float.isFinite(far)
                || near <= 0 || far <= near) return false;
        // Exotic/oblique depth conventions need their own derivation; preserve the old fallback.
        if (matrix.m03()!=0 || matrix.m13()!=0 || matrix.m23()!=-1 || matrix.m33()!=0
                || matrix.m02()!=0 || matrix.m12()!=0) return false;
        double span=(double)far-near;
        float scale=(float)(-((double)far+near)/span);
        float translation=(float)(-2.0*far*near/span);
        if (!Float.isFinite(scale) || !Float.isFinite(translation)) return false;
        matrix.m22(scale);
        matrix.m32(translation);
        return true;
    }
}
