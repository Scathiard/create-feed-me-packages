package dev.scathiard.feedmepackages.client;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import java.util.*;
import java.util.function.Supplier;

/** Optional browser adapters supply a hovered template; core code never loads their API classes. */
public final class TemplateBrowsers {
    private TemplateBrowsers() {}
    // EMI owns the visible index when both browsers are installed; it gets first refusal.
    public record Template(ItemStack item, FluidStack fluid) {
        public static final Template EMPTY = new Template(ItemStack.EMPTY, FluidStack.EMPTY);
        public Template {
            item = item.copyWithCount(1); fluid = fluid.copy();
            if (!item.isEmpty() && !fluid.isEmpty()) throw new IllegalArgumentException("Ambiguous browser material");
            if (!fluid.isEmpty()) fluid.setAmount(1);
        }
        @Override public ItemStack item() { return item.copy(); }
        @Override public FluidStack fluid() { return fluid.copy(); }
        public static Template of(ItemStack stack) { return stack == null || stack.isEmpty() ? EMPTY : new Template(stack, FluidStack.EMPTY); }
        public static Template of(FluidStack stack) { return stack == null || stack.isEmpty() ? EMPTY : new Template(ItemStack.EMPTY, stack); }
        public boolean isEmpty() { return item.isEmpty() && fluid.isEmpty(); }
        public void render(net.minecraft.client.gui.GuiGraphics g, int x, int y) {
            if (!item.isEmpty()) g.renderItem(item, x, y); else renderFluid(g, fluid, x, y);
        }
    }
    private static final Map<String, Supplier<Template>> BROWSERS = new java.util.concurrent.ConcurrentSkipListMap<>();
    public static void register(String key, Supplier<ItemStack> hovered) { registerMaterial(key, () -> Template.of(hovered.get())); }
    public static void registerMaterial(String key, Supplier<Template> hovered) { BROWSERS.put(key, Objects.requireNonNull(hovered)); }
    public static void remove(String key) { BROWSERS.remove(key); }
    public static boolean available() { return !BROWSERS.isEmpty(); }
    public static int cursorX() {
        var mc = net.minecraft.client.Minecraft.getInstance(); var w = mc.getWindow();
        return (int) (mc.mouseHandler.xpos() * w.getGuiScaledWidth() / w.getScreenWidth());
    }
    public static int cursorY() {
        var mc = net.minecraft.client.Minecraft.getInstance(); var w = mc.getWindow();
        return (int) (mc.mouseHandler.ypos() * w.getGuiScaledHeight() / w.getScreenHeight());
    }
    public static ItemStack hovered() { return selection().item(); }
    public static Template selection() {
        for (var browser : BROWSERS.values()) {
            var stack = browser.get(); if (stack != null && !stack.isEmpty()) return stack;
        }
        return Template.EMPTY;
    }
    public static void renderFluid(net.minecraft.client.gui.GuiGraphics g, FluidStack fluid, int x, int y) {
        if (fluid.isEmpty()) return;
        var extension = net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions.of(fluid.getFluid());
        var texture = extension.getStillTexture(fluid); if (texture == null) return;
        var sprite = net.minecraft.client.Minecraft.getInstance().getTextureAtlas(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS).apply(texture);
        int tint = extension.getTintColor(fluid);
        g.blit(x, y, 0, 16, 16, sprite, ((tint >> 16) & 255) / 255f, ((tint >> 8) & 255) / 255f, (tint & 255) / 255f, ((tint >>> 24) & 255) / 255f);
    }
}
