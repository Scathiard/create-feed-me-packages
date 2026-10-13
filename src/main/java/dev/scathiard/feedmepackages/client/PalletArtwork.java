package dev.scathiard.feedmepackages.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;

/** Shared thin steel / brass frame for arc and grid. No opaque screen-wide dimmer. */
final class PalletArtwork {
    static final int BRASS = 0xffdfc17d, STEEL = 0xff88918b, SHADOW = 0xff51482f;
    static final double STEP = .18, GAP = .016, HALF_ARC = .66;
    record Arc(double unit, double outer, double inner, double cx, double cy) {
        double x(double r, double a) { return cx + Math.sin(a) * r; }
        double y(double r, double a) { return cy - Math.cos(a) * r; }
        double mid() { return (inner + outer) / 2; }
        double pixelsPerCell() { return 85 * unit; }
    }
    static Arc arc(int width, int height) {
        double u = Math.min(width / 560.0, height / 260.0);
        double outer = (width - 44 * u) / (2 * Math.sin(HALF_ARC));
        return new Arc(u, outer, outer - 114 * u, width / 2.0, outer + 24 * u);
    }
    static int fade(int argb, double alpha) { return (argb & 0xffffff) | ((int) ((argb >>> 24) * alpha) << 24); }
    static void sector(GuiGraphics g, Arc a, double inner, double outer, double from, double to, int color) {
        from = Math.max(-HALF_ARC, from); to = Math.min(HALF_ARC, to);
        if (from >= to || outer <= inner) return;
        int steps = Math.max(1, (int) Math.ceil((to - from) * outer / 3));
        g.flush(); RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        var b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        var pose = g.pose().last().pose();
        for (int i = 0; i < steps; i++) {
            double l = from + (to - from) * i / steps, r = from + (to - from) * (i + 1) / steps;
            b.addVertex(pose, (float) a.x(outer,l), (float) a.y(outer,l), 0).setColor(color);
            b.addVertex(pose, (float) a.x(inner,l), (float) a.y(inner,l), 0).setColor(color);
            b.addVertex(pose, (float) a.x(inner,r), (float) a.y(inner,r), 0).setColor(color);
            b.addVertex(pose, (float) a.x(outer,r), (float) a.y(outer,r), 0).setColor(color);
        }
        BufferUploader.drawWithShader(b.buildOrThrow()); RenderSystem.disableBlend();
    }
    static void arcFrame(GuiGraphics g, Arc a, double from, double to, boolean selected, double alpha) {
        double line = (selected ? 3 : 1.8) * a.unit, edge = line / a.mid();
        int color = fade(selected ? BRASS : STEEL, alpha);
        sector(g,a,a.inner,a.outer,from,to,fade(selected ? 0x28b9944a : 0x2812191c,alpha));
        sector(g,a,a.outer-line,a.outer,from,to,color);
        sector(g,a,a.inner,a.inner+line,from,to,color);
        sector(g,a,a.inner,a.outer,from,from+edge,color);
        sector(g,a,a.inner,a.outer,to-edge,to,color);
        if (selected) {
            sector(g,a,a.outer-6*a.unit,a.outer-5*a.unit,from+.006,to-.006,SHADOW);
            sector(g,a,a.inner+5*a.unit,a.inner+6*a.unit,from+.006,to-.006,SHADOW);
        }
    }
    static void gridFrame(GuiGraphics g, int x, int y, boolean selected, boolean previous) {
        int color = selected ? BRASS : previous ? 0xffb3975d : STEEL;
        g.fill(x,y,x+24,y+24,selected ? 0x28b9944a : 0x2812191c);
        g.renderOutline(x,y,24,24,color);
        if (selected) g.renderOutline(x+2,y+2,20,20,SHADOW);
        for (int dx : new int[]{0,22}) for (int dy : new int[]{0,22}) g.fill(x+dx,y+dy,x+dx+2,y+dy+2,color);
    }
    static void marker(GuiGraphics g, Arc a) {
        int cx=(int)a.cx, top=(int)(a.cy-a.outer-7*a.unit), bottom=(int)(a.cy-a.inner+8*a.unit);
        int h=Math.max(4,(int)(12*a.unit));
        for(int i=0;i<h;i++) {
            int w=Math.max(1,(int)((h-i)*.65));
            g.fill(cx-w,top+i,cx+w+1,top+i+1,BRASS);
            g.fill(cx-w,bottom-i,cx+w+1,bottom-i+1,BRASS);
        }
    }
    static void drive(GuiGraphics g, Arc a, double position) {
        double pitchRadius=16*a.unit*.86,rack=a.inner-9*a.unit;
        double pitch=2*Math.PI*pitchRadius/12/rack,phase=position*STEP;
        long first=(long)Math.floor((phase-HALF_ARC)/pitch)-1,last=(long)Math.ceil((phase+HALF_ARC)/pitch)+1;
        for(long tooth=first;tooth<=last;tooth++) {
            double angle=tooth*pitch-phase;
            sector(g,a,a.inner-13*a.unit,a.inner-6*a.unit,angle-pitch*.2,angle+pitch*.2,BRASS);
        }
        g.pose().pushPose();g.pose().translate(a.cx,a.cy-a.inner+9*a.unit+pitchRadius,0);
        g.pose().scale((float)a.unit,(float)a.unit,1);
        g.pose().mulPose(com.mojang.math.Axis.ZP.rotation((float)(Math.PI/12-phase*rack/pitchRadius)));
        for(int i=0;i<12;i++) {
            g.pose().pushPose();g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(i*30));
            g.fill(-4,-16,4,-9,BRASS);g.pose().popPose();
        }
        g.fill(-9,-9,9,9,SHADOW);g.fill(-4,-4,4,4,STEEL);g.pose().popPose();
    }
    private PalletArtwork() {}
}
