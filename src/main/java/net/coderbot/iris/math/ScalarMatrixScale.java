package net.coderbot.iris.math;

import com.mojang.math.Matrix3f;
import com.mojang.math.Matrix4f;

/** Bridges to multiplication derived from the actual matrix bytecode, with public-API fallback. */
public final class ScalarMatrixScale {
    private ScalarMatrixScale() { }

    public interface Access {
        void iris$multiplyScale(float x, float y, float z);
    }

    public static void scale4(Matrix4f matrix, float x, float y, float z) {
        // Subclasses may override multiply or retain its argument. Keep their fresh matrix.
        if (matrix != null && matrix.getClass() == Matrix4f.class && (Object) matrix instanceof Access) {
            ((Access) (Object) matrix).iris$multiplyScale(x, y, z);
        } else {
            matrix.multiply(Matrix4f.createScaleMatrix(x, y, z));
        }
    }

    public static void scale3(Matrix3f matrix, float x, float y, float z) {
        if (matrix != null && matrix.getClass() == Matrix3f.class && (Object) matrix instanceof Access) {
            ((Access) (Object) matrix).iris$multiplyScale(x, y, z);
        } else {
            matrix.mul(Matrix3f.createScaleMatrix(x, y, z));
        }
    }
}
