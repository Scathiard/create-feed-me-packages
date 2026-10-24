package dev.scathiard.feedmepackages.client;

import java.util.*;

/** Clips this tool's baked BLOCK-format faces at the outlet, preserving UVs instead of squashing them. */
public final class PalletClipping {
    private PalletClipping() {}
    public static List<int[]> clip(int[] data,float plane) {
        if(data.length!=32) throw new IllegalArgumentException("Expected four BLOCK-format vertices");
        var output=new ArrayList<int[]>(5);
        int[] previous=Arrays.copyOfRange(data,24,32);
        boolean before=z(previous)<=plane;
        for(int i=0;i<4;i++) {
            int[] current=Arrays.copyOfRange(data,i*8,i*8+8);
            boolean inside=z(current)<=plane;
            if(inside!=before) output.add(intersection(previous,current,plane));
            if(inside) output.add(current);
            previous=current;before=inside;
        }
        return output;
    }
    private static float z(int[] v) { return Float.intBitsToFloat(v[2]); }
    private static int[] intersection(int[] a,int[] b,float plane) {
        float t=(plane-z(a))/(z(b)-z(a));int[] v=a.clone();
        for(int k:new int[]{0,1,2,4,5}) v[k]=Float.floatToRawIntBits(mix(Float.intBitsToFloat(a[k]),Float.intBitsToFloat(b[k]),t));
        v[2]=Float.floatToRawIntBits(plane);
        v[3]=packed(a[3],b[3],t,8,false);
        v[6]=packed(a[6],b[6],t,16,false);
        v[7]=packed(a[7],b[7],t,8,true);
        return v;
    }
    private static int packed(int a,int b,float t,int bits,boolean signed) {
        int mask=(1<<bits)-1,result=0;
        for(int shift=0;shift<32;shift+=bits) {
            int x=(a>>>shift)&mask,y=(b>>>shift)&mask;
            if(signed) { x=(byte)x;y=(byte)y; }
            result|=(Math.round(mix(x,y,t))&mask)<<shift;
        }
        return result;
    }
    private static float mix(float a,float b,float t) { return a+(b-a)*t; }
}
