package dev.scathiard.feedmepackages.client;

import dev.scathiard.feedmepackages.domain.CellSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import static dev.scathiard.feedmepackages.client.CellControlGeometry.*;

/** Approved 144 x 57 local popup; resource references are reusable, with no new embedded third-party art. */
public final class CellControlArtwork {
    private CellControlArtwork() {}
    private static final ResourceLocation GAUGE = ResourceLocation.fromNamespaceAndPath("create", "textures/gui/factory_gauge.png");
    private static final ResourceLocation TRACK = ResourceLocation.fromNamespaceAndPath("create_feed_me_packages", "textures/gui/panel.png");
    public static void render(GuiGraphics g, PanelLayout.Rect r, CellSettings s, int capacity, int pending, ItemStack parcel) { render(g,r,s,capacity,pending,parcel,false); }
    public static String fluidQuantity(int amount) {
        return amount < 1000 ? amount + "mB" : amount % 1000 == 0 ? amount / 1000 + "B"
                : String.format(java.util.Locale.ROOT,"%.2fB",amount / 1000.0);
    }
    public static void render(GuiGraphics g, PanelLayout.Rect r, CellSettings s, int capacity, int pending, ItemStack parcel, boolean fluid) {
        int x=r.x(), y=r.y();
        g.pose().pushPose(); g.pose().translate(0,0,250);
        g.fill(x,y,x+144,y+57,0xff000000); g.fill(x+1,y+1,x+143,y+56,0xffc6c6c6);
        g.fill(x+1,y+1,x+143,y+3,0xffffffff); g.fill(x+1,y+1,x+3,y+56,0xffffffff);
        g.fill(x+3,y+54,x+143,y+56,0xff555555); g.fill(x+141,y+3,x+143,y+56,0xff555555);
        well(g,field(r,0),true,false); well(g,field(r,1),false,false);
        well(g,field(r,2),false,true); well(g,field(r,3),false,true);
        var box=field(r,4); g.blit(GAUGE,box.x(),box.y(),99,215,18,18,256,256);
        g.renderItem(parcel,box.x()+1,box.y()+1);
        number(g,fluid ? fluidQuantity(pending) : compactCount(pending),box.x()+1,box.y()+11,18);
        int color=s.pendingLimit()>=0 && s.pendingLimit()<s.batch()?0xffff7777:0xffeeeeee;
        number(g,fluid ? "—" : Integer.toString(s.batch()),x+16,y+14,38,color);
        number(g,fluid ? "—" : s.pendingLimit()<0 ? "/" : Integer.toString(s.pendingLimit()),x+96,y+14,38,color);
        number(g,s.lower()<0 ? "/" : fluid ? fluidQuantity(s.lower()) : Integer.toString(s.lower()),x+11,y+37,19);
        number(g,s.upper()<0 ? "/" : fluid ? fluidQuantity(s.upper()) : Integer.toString(s.upper()),x+115,y+37,19);
        int tx=x+TRACK_X,ty=y+TRACK_Y;
        g.blit(TRACK,tx,ty,0,1,4,5,256,256);
        for(int px=4;px<68;px+=3) g.blit(TRACK,tx+px,ty,17,1,Math.min(3,68-px),5,256,256);
        g.blit(TRACK,tx+68,ty,36,1,4,5,256,256);
        g.blit(TRACK,thumb(r,Math.max(0,s.lower()),capacity)-2,y+41,0,15,5,5,256,256);
        g.blit(TRACK,thumb(r,s.upper(),capacity)-2,y+33,7,11,5,5,256,256);
        g.pose().popPose();
    }
    private static void well(GuiGraphics g, PanelLayout.Rect r, boolean reversed, boolean flat) {
        // Reverse source columns instead of scaling by -1, which reverses face winding and can be culled.
        for(int dx=0;dx<r.width();dx++) {
            int x=reversed?r.width()-1-dx:dx;
            int u=x>=r.width()-2?155+x-(r.width()-2):flat?(x<2?156-x:129):(x<8?121+x:129);
            g.blit(GAUGE,r.x()+dx,r.y(),u,215,1,18,256,256);
        }
    }
    private static void number(GuiGraphics g,String text,int x,int y,int width) {
        number(g,text,x,y,width,0xffeeeeee);
    }
    private static String compactCount(int value) {
        if(value<1000)return Integer.toString(value);
        int scale=value>=1_000_000_000?1_000_000_000:value>=1_000_000?1_000_000:1000;
        String unit=scale==1000?"k":scale==1_000_000?"M":"B";
        return (value/scale>=10?Integer.toString(value/scale):String.format(java.util.Locale.ROOT,"%.1f",(double)value/scale))+unit;
    }
    private static void number(GuiGraphics g,String text,int x,int y,int width,int color) {
        var font=Minecraft.getInstance().font; float scale=Math.min(1f,(float)width/Math.max(1,font.width(text)));
        g.pose().pushPose();g.pose().translate(x,y,1);g.pose().scale(scale,scale,1);g.drawString(font,text,0,0,color,true);g.pose().popPose();
    }
}
