package dev.scathiard.feedmepackages.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.scathiard.feedmepackages.consumption.PalletService;
import dev.scathiard.feedmepackages.domain.PalletLayout;
import dev.scathiard.feedmepackages.domain.PalletSelector;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

/** A held selection binding owns this gesture; neither the attack binding nor the inventory cursor does. */
public final class PalletScreen extends Screen {
    private final UUID cache;
    private final InputConstants.Key openingKey;
    private boolean spiral, initialized;
    private EditBox search;
    private PalletSelector navigation;
    private int first;
    private long version = -1;
    private String query = "";
    // Freeze identity/order for this short gesture. Stock updates may remove icons, never shift a new item under release.
    private List<PalletMaterial> candidates = List.of(), shown = List.of(), available = List.of();
    private PalletMaterial focus = PalletMaterial.EMPTY;
    private double pointerX = Double.NaN, pointerY = Double.NaN;
    private long frameTime;

    public PalletScreen(boolean spiral) {
        super(Component.translatable("item.create_feed_me_packages.pocket_pallet"));
        this.spiral = spiral;
        cache = PalletClient.cache();
        openingKey = ClientBootstrap.PALLET_SELECT.getKey();
    }
    @Override protected void init() {
        if (!initialized) {
            candidates = PalletClient.candidates();
            initialized = true;
        }
        search = new EditBox(font, Math.max(12, width / 2 - 100), height / 2 + 22, Math.min(200, width - 24), 18,
                Component.translatable("gui.create_feed_me_packages.pallet.search"));
        search.setMaxLength(80);
        search.setBordered(false);
        search.setValue(query);
        search.setHint(Component.translatable("gui.create_feed_me_packages.pallet.search"));
        search.setResponder(value -> { query = value; first = 0; filter(); });
        addRenderableWidget(search);
        search.visible = search.active = !spiral;
        if (!spiral && openingKey.getType() == InputConstants.Type.MOUSE) setInitialFocus(search);
        filter();
        refresh();
    }
    private void refresh() {
        if (version == PalletClient.version()) return;
        version = PalletClient.version();
        available = PalletClient.candidates();
        if (!inStock(focus)) focus = PalletMaterial.EMPTY;
    }
    private boolean inStock(PalletMaterial item) {
        return !item.isEmpty() && available.stream().anyMatch(stack -> item.equals(stack));
    }
    private int indexOf(PalletMaterial item) {
        if (!item.isEmpty()) for (int i = 0; i < shown.size(); i++)
            if (item.equals(shown.get(i))) return i;
        return -1;
    }
    private void filter() {
        String needle = query.toLowerCase(Locale.ROOT);
        shown = candidates.stream().filter(stack -> stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(needle)).toList();
        navigation = new PalletSelector(shown.size(), Math.max(0, indexOf(PalletClient.selected())));
        updateFocus();
        anchorPointer();
        frameTime = System.nanoTime();
    }
    private PalletLayout.View layout() { return PalletLayout.grid(shown.size(), first, width, height); }
    private void anchorPointer() {
        pointerX=minecraft.mouseHandler.xpos()*width/minecraft.getWindow().getScreenWidth();
        pointerY=minecraft.mouseHandler.ypos()*height/minecraft.getWindow().getScreenHeight();
    }
    private PalletLayout.Arc arcLayout() { return PalletLayout.arc(width, height); }
    private PalletMaterial gridTarget(double x, double y) {
        for (var tile : layout().tiles())
            if (tile.contains(x, y) && inStock(shown.get(tile.index()))) return shown.get(tile.index());
        return PalletMaterial.EMPTY;
    }
    private void point(double x, double y) {
        double delta = Double.isNaN(pointerX) ? 0 : pointerX - x;
        pointerX = x; pointerY = y;
        if (!spiral) { focus = gridTarget(x, y); return; }
        navigation.drag(delta / arcLayout().pixelsPerCell());
        updateFocus();
    }
    private void updateFocus() {
        int selected = navigation.selected();
        focus = selected >= 0 && inStock(shown.get(selected)) ? shown.get(selected) : PalletMaterial.EMPTY;
    }
    @Override public void mouseMoved(double x, double y) { refresh(); point(x, y); super.mouseMoved(x, y); }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        point(x, y);
        return true;
    }
    @Override public void tick() {
        refresh();
        if (spiral) updateFocus();
        if (!minecraft.isWindowActive() || minecraft.player == null || !PalletService.holding(minecraft.player) || !ClientMaterials.active()
                || !Objects.equals(cache, PalletClient.cache())) onClose();
    }
    @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partial) {
        g.flush();
        renderBlurredBackground(partial);
        if(!spiral) {
            g.fill(search.getX()-4,search.getY()-4,search.getX()+search.getWidth()+4,search.getY()+14,0x40202a2e);
            g.renderOutline(search.getX()-4,search.getY()-4,search.getWidth()+8,18,search.isFocused()?PalletArtwork.BRASS:PalletArtwork.STEEL);
        }
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.client.event.ScreenEvent.BackgroundRendered(this,g));
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        if (spiral) {
            drawArc(g);
        } else {
            g.drawCenteredString(font, title, width / 2, height / 2 + 4, PalletArtwork.BRASS);
            var view = layout(); first = view.first();
            for (var tile : view.tiles()) {
                var item = shown.get(tile.index());
                if (inStock(item)) drawTile(g, item, tile.x(), tile.y());
            }
            var hovered = gridTarget(mouseX,mouseY);
            if (!hovered.isEmpty()) g.renderTooltip(font, hovered.getHoverName(), mouseX, mouseY);
        }
        long count = shown.stream().filter(this::inStock).count();
        var label = Component.translatable("gui.create_feed_me_packages.pallet.help", ClientBootstrap.PALLET_MODE.getTranslatedKeyMessage(),
                Component.translatable("gui.create_feed_me_packages.pallet." + (spiral ? "spiral" : "grid")), count);
        g.drawCenteredString(font, label, width / 2, height - 22, 0xffeeeeee);
        if(spiral) g.drawCenteredString(font, Component.translatable("gui.create_feed_me_packages.pallet.drag"),width/2,height-35,0xffd4bd82);
        if (count == 0) g.drawCenteredString(font, Component.translatable("gui.create_feed_me_packages.pallet.empty"), width / 2, height - 60, 0xffcccccc);
    }
    private void drawArc(GuiGraphics g) {
        long now=System.nanoTime(); navigation.advance((now-frameTime)/1e9); frameTime=now;
        var a=arcLayout();
        if (!shown.isEmpty()) for(long occurrence=(long)Math.floor(navigation.position())-5;
                occurrence<=(long)Math.ceil(navigation.position())+5;occurrence++) {
            double rel=occurrence-navigation.position(), angle=rel*PalletArtwork.STEP;
            double left=angle-PalletArtwork.STEP/2+PalletArtwork.GAP/2, right=angle+PalletArtwork.STEP/2-PalletArtwork.GAP/2;
            if(right < -PalletArtwork.HALF_ARC || left > PalletArtwork.HALF_ARC) continue;
            boolean current=occurrence==navigation.anchor();
            var item=shown.get((int)Math.floorMod(occurrence,(long)shown.size()));
            double alpha=Math.clamp(1-Math.abs(rel)*.145,.18,1);
            PalletArtwork.arcFrame(g,a,left,right,current,alpha);
            // Only complete icon envelopes are drawn at the viewport edges; neighbouring icons never overlap.
            if(inStock(item) && Math.abs(angle)<PalletArtwork.HALF_ARC-.045) {
                float size=(float)(50*a.unit());
                g.pose().pushPose(); g.pose().translate(a.x(a.mid(),angle)-size/2,a.y(a.mid(),angle)-size/2,0);
                g.pose().scale(size/16,size/16,1); g.setColor((float)alpha,(float)alpha,(float)alpha,1);
                item.render(g,0,0); g.setColor(1,1,1,1); g.pose().popPose();
            }
        }
        PalletArtwork.sector(g,a,a.outer()+2*a.unit(),a.outer()+4*a.unit(),-.66,.66,PalletArtwork.STEEL);
        PalletArtwork.sector(g,a,a.inner()-8*a.unit(),a.inner()-5*a.unit(),-.66,.66,PalletArtwork.BRASS);
        PalletArtwork.marker(g,a);
        PalletArtwork.drive(g,a,navigation.position());
        if(!focus.isEmpty()) {
            long amount=PalletClient.amount(focus);
            int y=(int)(a.cy()-a.inner()+54*a.unit());
            String label=font.plainSubstrByWidth(focus.getHoverName().getString(),width-80)+(focus.fluid().isEmpty()?" ×":" ")+amount+(focus.fluid().isEmpty()?"":" mB");
            int w=font.width(label)+14, x=(width-w)/2;
            g.fill(x,y-4,x+w,y+13,0xb0202a2e);g.renderOutline(x,y-4,w,17,PalletArtwork.BRASS);
            g.drawCenteredString(font,label,width/2,y,0xfff0e8d7);
        }
    }
    private void drawTile(GuiGraphics g, PalletMaterial item, int x, int y) {
        drawTile(g, item, x, y, !focus.isEmpty() && focus.equals(item));
    }
    private void drawTile(GuiGraphics g, PalletMaterial item, int x, int y, boolean current) {
        PalletArtwork.gridFrame(g,x,y,current,PalletClient.selected(item));
        item.render(g, x + 4, y + 4);
    }
    private void confirm(double x, double y) {
        if(!minecraft.isWindowActive() || minecraft.player==null || !PalletService.holding(minecraft.player)
                || !ClientMaterials.active() || !Objects.equals(cache,PalletClient.cache())) { onClose(); return; }
        refresh(); point(x, y);
        if (inStock(focus)) PalletClient.choose(focus);
        onClose();
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        if (openingKey.equals(InputConstants.Type.MOUSE.getOrCreate(button))) { confirm(x, y); return true; }
        return super.mouseReleased(x, y, button);
    }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (ClientBootstrap.PALLET_MODE.matchesMouse(button)) { toggle(); return true; }
        return super.mouseClicked(x, y, button);
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (spiral) { navigation.step(vertical<0 ? 1 : vertical>0 ? -1 : (int)Math.signum(horizontal)); updateFocus(); }
        else {
            var view = layout();
            first = Math.clamp(view.first() - (int) Math.signum(vertical) * view.step(), 0, view.maximum());
            focus = gridTarget(x, y);
        }
        return true;
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (openingKey.equals(InputConstants.getKey(key, scan))) return true;
        if (ClientBootstrap.PALLET_MODE.matches(key, scan)) { toggle(); return true; }
        if (spiral && (key==org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT || key==org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT)) {
            navigation.step(key==org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT ? 1 : -1);updateFocus();return true;
        }
        if (!search.isFocused() && minecraft.options.keyInventory.matches(key, scan)) { onClose(); return true; }
        if (!search.isFocused()) for (int i = 0; i < 9; i++)
            if (minecraft.options.keyHotbarSlots[i].matches(key, scan)) {
                minecraft.player.getInventory().selected = i; onClose(); return true;
            }
        return super.keyPressed(key, scan, modifiers);
    }
    @Override public boolean keyReleased(int key, int scan, int modifiers) {
        if (openingKey.equals(InputConstants.getKey(key, scan))) {
            var mouse = minecraft.mouseHandler;
            confirm(mouse.xpos() * width / minecraft.getWindow().getScreenWidth(), mouse.ypos() * height / minecraft.getWindow().getScreenHeight());
            return true;
        }
        return super.keyReleased(key, scan, modifiers);
    }
    private void toggle() {
        PalletMaterial previous = focus;
        spiral = !spiral;
        PalletClient.mode(spiral);
        search.visible = search.active = !spiral;
        if (spiral) {
            setFocused(null); search.setFocused(false);
            if (!query.isEmpty()) search.setValue("");
            navigation.reset(shown.size(), Math.max(0, indexOf(previous.isEmpty() ? PalletClient.selected() : previous)));
        } else {
            setFocused(search); search.setFocused(true);
            int index = indexOf(previous);
            if (index >= 0) first = index / layout().step() * layout().step();
        }
        focus = inStock(previous) ? previous : PalletMaterial.EMPTY;
        anchorPointer();
        if(spiral) updateFocus();
    }
    @Override public void removed() { PalletClient.releaseKey(openingKey); }
    @Override public void onClose() {
        minecraft.setScreen(null);
        // setScreen(null) re-grabs the mouse and may restore physical keyboard state; release our gesture after it.
        PalletClient.releaseKey(openingKey);
    }
    @Override public boolean isPauseScreen() { return false; }
}
