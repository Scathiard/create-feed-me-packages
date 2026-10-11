package dev.scathiard.feedmepackages.client;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.consumption.PalletService;
import dev.scathiard.feedmepackages.domain.PalletLayout;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** Hold Attack to select; all drawing is functional placeholder work, not the future art treatment. */
public final class PalletScreen extends Screen {
    private static final ResourceLocation SLOTS = ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID, "textures/gui/pallet_slots.png");
    private boolean spiral;
    private final UUID cache;
    private EditBox search;
    private int first;
    private long version = -1;
    private String query = "";
    private List<ItemStack> candidates = List.of(), shown = List.of();
    public PalletScreen(boolean spiral) { super(Component.translatable("item.create_feed_me_packages.pocket_pallet")); this.spiral = spiral; cache = PalletClient.cache(); }
    @Override protected void init() {
        search = new EditBox(font, Math.max(12, width / 2 - 100), 30, Math.min(200, width - 24), 18, Component.translatable("gui.create_feed_me_packages.pallet.search"));
        search.setMaxLength(80); search.setValue(query); search.setHint(Component.translatable("gui.create_feed_me_packages.pallet.search"));
        search.setResponder(value -> { query = value; first = 0; filter(); }); addRenderableWidget(search); setInitialFocus(search);
        refresh();
    }
    private void refresh() {
        if (version != ClientMaterials.version()) { version = ClientMaterials.version(); candidates = PalletClient.candidates(); filter(); }
    }
    private void filter() { String needle = query.toLowerCase(Locale.ROOT); shown = candidates.stream().filter(stack -> stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(needle)).toList(); }
    private PalletLayout.View layout() { return PalletLayout.view(spiral, shown.size(), first, width, height); }
    private ItemStack hovered(double mouseX, double mouseY) {
        for (var tile : layout().tiles()) if (tile.contains(mouseX, mouseY)) return shown.get(tile.index()); return ItemStack.EMPTY;
    }
    @Override public void tick() {
        refresh();
        if (minecraft.player == null || !PalletService.holding(minecraft.player) || !Objects.equals(cache, PalletClient.cache())) onClose();
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        g.fill(0, 0, width, height, 0xa8000000); super.render(g, mouseX, mouseY, partial);
        g.drawCenteredString(font, title, width / 2, 12, 0xffffffff);
        var view = layout(); first = view.first();
        for (var tile : view.tiles()) {
            var item = shown.get(tile.index()); int textureX = tile.contains(mouseX, mouseY) ? 24 : PalletClient.selected(item) ? 48 : 0;
            g.blit(SLOTS, tile.x(), tile.y(), textureX, 0, 24, 24, 72, 24); g.renderItem(item, tile.x() + 4, tile.y() + 4);
        }
        var label = Component.translatable("gui.create_feed_me_packages.pallet.help", ClientBootstrap.PALLET_MODE.getTranslatedKeyMessage(),
                Component.translatable("gui.create_feed_me_packages.pallet." + (spiral ? "spiral" : "grid")), shown.size());
        g.drawCenteredString(font, label, width / 2, height - 22, 0xffeeeeee);
        var hover = hovered(mouseX, mouseY); if (!hover.isEmpty()) g.renderTooltip(font, hover, mouseX, mouseY);
        else if (shown.isEmpty()) g.drawCenteredString(font, Component.translatable("gui.create_feed_me_packages.pallet.empty"), width / 2, height / 2, 0xffcccccc);
    }
    @Override public boolean mouseReleased(double x, double y, int button) {
        if (minecraft.options.keyAttack.matchesMouse(button)) { refresh(); var item = hovered(x,y); if (!item.isEmpty()) PalletClient.choose(item); onClose(); return true; }
        return super.mouseReleased(x,y,button);
    }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (ClientBootstrap.PALLET_MODE.matchesMouse(button)) { toggle(); return true; } return super.mouseClicked(x,y,button);
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        var view = layout(); first = Math.clamp(view.first() - (int) Math.signum(vertical) * view.step(), 0, view.maximum()); return true;
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (ClientBootstrap.PALLET_MODE.matches(key, scan)) { toggle(); return true; }
        if (!search.isFocused() && minecraft.options.keyInventory.matches(key, scan)) { onClose(); return true; }
        if (!search.isFocused()) for (int i = 0; i < 9; i++) if (minecraft.options.keyHotbarSlots[i].matches(key,scan)) { minecraft.player.getInventory().selected = i; onClose(); return true; }
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public boolean keyReleased(int key, int scan, int modifiers) {
        if (minecraft.options.keyAttack.matches(key,scan)) {
            var mouse = minecraft.mouseHandler; double scale = minecraft.getWindow().getGuiScale();
            var item = hovered(mouse.xpos() / scale, mouse.ypos() / scale); if (!item.isEmpty()) PalletClient.choose(item); onClose(); return true;
        }
        return super.keyReleased(key,scan,modifiers);
    }
    private void toggle() { spiral = !spiral; PalletClient.mode(spiral); }
    @Override public void onClose() { minecraft.options.keyAttack.setDown(false); minecraft.setScreen(null); }
    @Override public boolean isPauseScreen() { return false; }
}
