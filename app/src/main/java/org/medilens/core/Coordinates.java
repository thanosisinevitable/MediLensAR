package org.medilens.core;
/** OCR outputs upright-image coordinates; ARCore mapping expects unrotated CPU-image pixels. */
public final class Coordinates {
    private Coordinates() {}
    public static float[] unrotate(float x,float y,int w,int h,int degrees){
        switch(degrees){case 0:return new float[]{x,y};case 90:return new float[]{y,h-x};case 180:return new float[]{w-x,h-y};case 270:return new float[]{w-y,x};default:throw new IllegalArgumentException("Rotation must be a right angle");}
    }
    public static float[] view(float x,float y,int w,int h,float[] affine){
        return new float[]{affine[0]+x/w*(affine[2]-affine[0])+y/h*(affine[4]-affine[0]),affine[1]+x/w*(affine[3]-affine[1])+y/h*(affine[5]-affine[1])};
    }
}
