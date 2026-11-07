package dev.scathiard.feedmepackages.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** One synchronous draw, clipped in tool space after all native item/gate transforms. */
final class PalletClipBuffers implements MultiBufferSource, AutoCloseable {
    private final MultiBufferSource target;
    private final float nx,ny,nz,limit;
    private final List<Cut> pending=new ArrayList<>();
    PalletClipBuffers(MultiBufferSource target,PoseStack toolPose,float rearPlane) {
        this.target=target;
        var inverse=new Matrix4f(toolPose.last().pose()).invert();
        nx=inverse.m02();ny=inverse.m12();nz=inverse.m22();limit=rearPlane-inverse.m32();
    }
    @Override public VertexConsumer getBuffer(RenderType type) {
        // A native BufferSource may end its previous batch when the render type changes.
        close();
        var output=target.getBuffer(type);
        if(type.mode()!=VertexFormat.Mode.QUADS)return output;
        var cut=new Cut(output);pending.add(cut);return cut;
    }
    @Override public void close() { pending.forEach(Cut::flush); }
    private final class Cut implements VertexConsumer {
        private final VertexConsumer output;
        private final float[][] vertices=new float[4][16];
        private int current=-1;
        Cut(VertexConsumer output) { this.output=output; }
        @Override public VertexConsumer addVertex(float x,float y,float z) {
            if(current==3)flush();
            var v=vertices[++current];Arrays.fill(v,0);v[0]=x;v[1]=y;v[2]=z;
            return this;
        }
        @Override public VertexConsumer setColor(int r,int g,int b,int a) {
            var v=vertices[current];v[3]=r;v[4]=g;v[5]=b;v[6]=a;return this;
        }
        @Override public VertexConsumer setUv(float u,float v) { vertices[current][7]=u;vertices[current][8]=v;return this; }
        @Override public VertexConsumer setUv1(int u,int v) { vertices[current][9]=u;vertices[current][10]=v;return this; }
        @Override public VertexConsumer setUv2(int u,int v) { vertices[current][11]=u;vertices[current][12]=v;return this; }
        @Override public VertexConsumer setNormal(float x,float y,float z) {
            var v=vertices[current];v[13]=x;v[14]=y;v[15]=z;return this;
        }
        private void flush() {
            if(current<0)return;
            if(current!=3) {
                // Preserve native nonstandard/incomplete output rather than inventing a face.
                for(int i=0;i<=current;i++)emit(vertices[i]);
            } else {
                int inside=0;
                for(var v:vertices)if(PalletClipping.distance(v,nx,ny,nz,limit)<=0)inside++;
                if(inside==4)for(var v:vertices)emit(v);
                else if(inside>0) {
                    var p=PalletClipping.clip(vertices,nx,ny,nz,limit);
                    if(p.size()==4)p.forEach(this::emit);
                    else for(int i=1;i<p.size()-1;i++) { emit(p.get(0));emit(p.get(i));emit(p.get(i+1));emit(p.get(i+1)); }
                }
            }
            current=-1;
        }
        private void emit(float[] v) {
            output.addVertex(v[0],v[1],v[2]).setColor(Math.round(v[3]),Math.round(v[4]),Math.round(v[5]),Math.round(v[6]))
                    .setUv(v[7],v[8]).setUv1(Math.round(v[9]),Math.round(v[10])).setUv2(Math.round(v[11]),Math.round(v[12]))
                    .setNormal(v[13],v[14],v[15]);
        }
    }
}
