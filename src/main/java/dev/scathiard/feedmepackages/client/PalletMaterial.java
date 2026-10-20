package dev.scathiard.feedmepackages.client;

import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.fluid.FluidVariantKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.fluids.FluidStack;

/** A display-only selection. Fluids never masquerade as spendable items or enter item material hints. */
public record PalletMaterial(String encoded, TemplateBrowsers.Template template) {
    public static final PalletMaterial EMPTY = new PalletMaterial("", TemplateBrowsers.Template.EMPTY);
    public static PalletMaterial of(ItemStack stack) {
        return stack.isEmpty() ? EMPTY : new PalletMaterial(ItemVariantKey.of(stack,Minecraft.getInstance().player.registryAccess()).encoded(),TemplateBrowsers.Template.of(stack));
    }
    public static PalletMaterial decode(String encoded) {
        if(encoded.isEmpty()) return EMPTY;
        var registries=Minecraft.getInstance().player.registryAccess();
        return new PalletMaterial(encoded,encoded.startsWith("fluid:")
                ? TemplateBrowsers.Template.of(FluidVariantKey.decode(encoded.substring(6),registries).stack(registries,1))
                : TemplateBrowsers.Template.of(ItemVariantKey.decode(encoded,registries).stack(registries,1)));
    }
    public boolean isEmpty() { return encoded.isEmpty(); }
    public boolean is(Item item) { return template.item().is(item); }
    public ItemStack item() { return template.item(); }
    public FluidStack fluid() { return template.fluid(); }
    public Component getHoverName() { return fluid().isEmpty() ? item().getHoverName() : fluid().getHoverName(); }
    public void render(GuiGraphics g,int x,int y) { template.render(g,x,y); }
    @Override public boolean equals(Object other) { return other instanceof PalletMaterial material && encoded.equals(material.encoded); }
    @Override public int hashCode() { return encoded.hashCode(); }
}
