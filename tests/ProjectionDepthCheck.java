package net.coderbot.iris.compat.dh;

import net.coderbot.iris.vendored.joml.Matrix4f;
import java.util.Random;

public final class ProjectionDepthCheck {
    public static void main(String[] args) {
        Random random=new Random(8129);
        for(int i=0;i<10000;i++) {
            Matrix4f matrix=new Matrix4f().setPerspective(0.6f+random.nextFloat(),1.7f,0.05f,512);
            // Off-center lens, horizontal/vertical scaling and screen-space translation.
            matrix.m20(random.nextFloat()*.2f).m21(random.nextFloat()*.2f);
            matrix.m00(matrix.m00()*1.3f).m30(.07f).m31(-.03f);
            float[] before=matrix.get(new float[16]);
            check(ProjectionDepth.extend(matrix,4,8192),"perspective accepted");
            float[] after=matrix.get(new float[16]);
            for(int n=0;n<16;n++) if(n!=10 && n!=14) check(before[n]==after[n],"preserve all non-depth entries");
            for(float z : new float[]{-4,-8192}) {
                double clipZ=(matrix.m22()*(double)z+matrix.m32())/-z;
                check(Math.abs(clipZ-(z==-4?-1:1))<1e-6,"near/far depth mapping");
            }
        }
        Matrix4f identity=new Matrix4f();
        check(!ProjectionDepth.extend(identity,4,8192),"orthographic fallback");
        Matrix4f oblique=new Matrix4f().setPerspective(1,1,1,100).m02(.1f);
        check(!ProjectionDepth.extend(oblique,4,8192),"oblique fallback");
        check(!ProjectionDepth.extend(oblique,Float.NaN,8192),"invalid range");
        System.out.println("Projection depth checks passed: 10000 off-center/scaled projections, clip boundaries and fallbacks");
    }
    private static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
}
