package dev.scathiard.feedmepackages.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.consumption.PalletService;
import dev.scathiard.feedmepackages.domain.PalletLayout;
import dev.scathiard.feedmepackages.domain.PalletSpiral;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** A held selection binding owns this gesture; neither the attack binding nor the inventory cursor does. */
public final class PalletScreen extends Screen {
    private static final ResourceLocation SLOTS = ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID, "textures/gui/pallet_slots.png");
    private final UUID cache;
    private final InputConstants.Key openingKey;
    private boolean spiral, initialized;
    private EditBox search;
    private PalletSpiral navigation;
    private int first;
    private long version = -1;
    private String query = "";
    // Freeze identity/order for this short gesture. Stock updates may remove icons, never shift a new item under release.
    private List<ItemStack> candidates = List.of(), shown = List.of(), available = List.of();
    private ItemStack focus = ItemStack.EMPTY;
    private double pointerX = Double.NaN, pointerY = Double.NaN;

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
        search = new EditBox(font, Math.max(12, width / 2 - 100), 30, Math.min(200, width - 24), 18,
                Component.translatable("gui.create_feed_me_packages.pallet.search"));
        search.setMaxLength(80);
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
        if (version == ClientMaterials.version()) return;
        version = ClientMaterials.version();
        available = PalletClient.candidates();
        if (!inStock(focus)) focus = ItemStack.EMPTY;
    }
    private boolean inStock(ItemStack item) {
        return !item.isEmpty() && available.stream().anyMatch(stack -> ItemStack.isSameItemSameComponents(item, stack));
    }
    private int indexOf(ItemStack item) {
        if (!item.isEmpty()) for (int i = 0; i < shown.size(); i++)
            if (ItemStack.isSameItemSameComponents(item, shown.get(i))) return i;
        return -1;
    }
    private void filter() {
        String needle = query.toLowerCase(Locale.ROOT);
        shown = candidates.stream().filter(stack -> stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(needle)).toList();
        navigation = new PalletSpiral(shown.size(), Math.max(0, indexOf(PalletClient.selected())));
        focus = ItemStack.EMPTY;
        pointerX = pointerY = Double.NaN;
    }
    private PalletLayout.View layout() { return PalletLayout.grid(shown.size(), first, width, height); }
    private PalletLayout.SpiralView spiralLayout() {
        return PalletLayout.spiral(shown.size(), navigation.position(), navigation.phase(), width, height);
    }
    private ItemStack gridTarget(double x, double y) {
        for (var tile : layout().tiles())
            if (tile.contains(x, y) && inStock(shown.get(tile.index()))) return shown.get(tile.index());
        return ItemStack.EMPTY;
    }
    private void point(double x, double y) {
        if (Double.compare(pointerX, x) == 0 && Double.compare(pointerY, y) == 0) return;
        pointerX = x; pointerY = y;
        if (!spiral) { focus = gridTarget(x, y); return; }
        var view = spiralLayout();
        navigation.move(x - view.centerX(), y - view.centerY());
        int selected = navigation.selected();
        focus = selected >= 0 && inStock(shown.get(selected)) ? shown.get(selected) : ItemStack.EMPTY;
    }
    @Override public void mouseMoved(double x, double y) { refresh(); point(x, y); super.mouseMoved(x, y); }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        point(x, y);
        return true;
    }
    @Override public void tick() {
        refresh();
        if (minecraft.player == null || !PalletService.holding(minecraft.player) || !ClientMaterials.active()
                || !Objects.equals(cache, PalletClient.cache())) onClose();
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        g.flush();
        renderBlurredBackground(partial);
        super.render(g, mouseX, mouseY, partial);
        g.drawCenteredString(font, title, width / 2, 12, 0xffffffff);
        if (spiral) {
            var view = spiralLayout();
            PalletLayout.SpiralTile highlighted = null;
            for (var tile : view.tiles()) {
                var item = shown.get(tile.index());
                if (!inStock(item)) continue;
                if (tile.current() && !focus.isEmpty() && ItemStack.isSameItemSameComponents(focus, item)) highlighted = tile;
                else drawSpiralTile(g, tile, item, false);
            }
            // Draw the full-bright current occurrence last, above the surrounding spiral.
            if (highlighted != null) drawSpiralTile(g, highlighted, focus, true);
            g.fill((int) view.centerX() - 1, (int) view.centerY() - 1, (int) view.centerX() + 1, (int) view.centerY() + 1, 0xffb6a58b);
            if (!focus.isEmpty()) g.drawCenteredString(font, focus.getHoverName(), width / 2, height - 42, 0xffffffff);
        } else {
            var view = layout(); first = view.first();
            for (var tile : view.tiles()) {
                var item = shown.get(tile.index());
                if (inStock(item)) drawTile(g, item, tile.x(), tile.y());
            }
            if (!focus.isEmpty()) g.renderTooltip(font, focus, mouseX, mouseY);
        }
        long count = shown.stream().filter(this::inStock).count();
        var label = Component.translatable("gui.create_feed_me_packages.pallet.help", ClientBootstrap.PALLET_MODE.getTranslatedKeyMessage(),
                Component.translatable("gui.create_feed_me_packages.pallet." + (spiral ? "spiral" : "grid")), count);
        g.drawCenteredString(font, label, width / 2, height - 22, 0xffeeeeee);
        if (count == 0) g.drawCenteredString(font, Component.translatable("gui.create_feed_me_packages.pallet.empty"), width / 2, height / 2 + 12, 0xffcccccc);
    }
    private void drawSpiralTile(GuiGraphics g, PalletLayout.SpiralTile tile, ItemStack item, boolean current) {
        g.pose().pushPose();
        g.pose().translate(tile.x() - tile.size() / 2, tile.y() - tile.size() / 2, current ? 200 : 0);
        float scale = (float) (tile.size() / 24);
        g.pose().scale(scale, scale, 1);
        float brightness = current ? 1 : tile.opacity() * .6f;
        g.setColor(brightness, brightness, brightness, current ? 1 : tile.opacity());
        drawTile(g, item, 0, 0, current);
        g.setColor(1, 1, 1, 1);
        g.pose().popPose();
    }
    private void drawTile(GuiGraphics g, ItemStack item, int x, int y) {
        drawTile(g, item, x, y, !focus.isEmpty() && ItemStack.isSameItemSameComponents(focus, item));
    }
    private void drawTile(GuiGraphics g, ItemStack item, int x, int y, boolean current) {
        int textureX = current ? 24 : PalletClient.selected(item) ? 48 : 0;
        g.blit(SLOTS, x, y, textureX, 0, 24, 24, 72, 24);
        g.renderItem(item, x + 4, y + 4);
        if (current) {
            g.renderOutline(x - 2, y - 2, 28, 28, 0xff352618);
            g.renderOutline(x - 1, y - 1, 26, 26, 0xffffce55);
            g.renderOutline(x, y, 24, 24, 0xfffff0b0);
        }
    }
    private void confirm(double x, double y) {
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
        if (!spiral) {
            var view = layout();
            first = Math.clamp(view.first() - (int) Math.signum(vertical) * view.step(), 0, view.maximum());
            focus = gridTarget(x, y);
        }
        return true;
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (openingKey.equals(InputConstants.getKey(key, scan))) return true;
        if (ClientBootstrap.PALLET_MODE.matches(key, scan)) { toggle(); return true; }
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
        ItemStack previous = focus;
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
        focus = inStock(previous) ? previous : ItemStack.EMPTY;
    }
    @Override public void removed() { PalletClient.releaseKey(openingKey); }
    @Override public void onClose() {
        minecraft.setScreen(null);
        // setScreen(null) re-grabs the mouse and may restore physical keyboard state; release our gesture after it.
        PalletClient.releaseKey(openingKey);
    }
    @Override public boolean isPauseScreen() { return false; }
}
