package dev.scathiard.feedmepackages.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.GameRenderer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import org.joml.Matrix4f;

/** Shared one-pixel border channel. Corners retain native pixels; edges crop rather than stretch. */
final class CellBorders {
    private CellBorders() {}
    static final ResourceLocation ATLAS=ResourceLocation.fromNamespaceAndPath("create_feed_me_packages","textures/gui/logistics_feedback.png");
    static void texture(GuiGraphics g,int x,int y,int size,int u,int from,int to) {
        int top=y+Math.max(0,from),bottom=y+Math.min(size,to);
        if (size<2 || size>18 || top>=bottom) return;
        RenderSystem.setShaderTexture(0,ATLAS);RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
        var matrix=g.pose().last().pose();
        var mesh=Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_TEX);
        // The source frame is 18x18 with a one-pixel edge. Both target sizes (18,16) fit its middle.
        // Clip vertices locally and submit all eight pieces once; per-piece blits/scissors flush the UI repeatedly.
        quad(mesh,matrix,x,y,u,0,1,1,top,bottom);quad(mesh,matrix,x+size-1,y,u+17,0,1,1,top,bottom);
        quad(mesh,matrix,x,y+size-1,u,17,1,1,top,bottom);quad(mesh,matrix,x+size-1,y+size-1,u+17,17,1,1,top,bottom);
        quad(mesh,matrix,x+1,y,u+1,0,size-2,1,top,bottom);quad(mesh,matrix,x+1,y+size-1,u+1,17,size-2,1,top,bottom);
        quad(mesh,matrix,x,y+1,u,1,1,size-2,top,bottom);quad(mesh,matrix,x+size-1,y+1,u+17,1,1,size-2,top,bottom);
        BufferUploader.drawWithShader(mesh.buildOrThrow());RenderSystem.disableBlend();
    }
    private static void quad(BufferBuilder mesh,Matrix4f matrix,int x,int y,int u,int v,int w,int h,int top,int bottom) {
        int a=Math.max(y,top),b=Math.min(y+h,bottom);if(w<=0 || a>=b)return;
        float left=u/128f,right=(u+w)/128f,upper=(v+a-y)/64f,lower=(v+b-y)/64f;
        mesh.addVertex(matrix,x,a,180).setUv(left,upper);mesh.addVertex(matrix,x,b,180).setUv(left,lower);
        mesh.addVertex(matrix,x+w,b,180).setUv(right,lower);mesh.addVertex(matrix,x+w,a,180).setUv(right,upper);
    }
    static void outline(GuiGraphics g,int x,int y,int size,int color) {
        g.pose().pushPose();g.pose().translate(0,0,180);g.renderOutline(x,y,size,size,color);g.pose().popPose();
    }
}
