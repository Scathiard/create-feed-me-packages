package dev.scathiard.feedmepackages.compat.emi;

import dev.scathiard.feedmepackages.FmpConfig;
import dev.scathiard.feedmepackages.client.ClientCompatibility;
import dev.scathiard.feedmepackages.client.ClientMaterials;
import dev.scathiard.feedmepackages.consumption.CraftingService;
import net.minecraft.client.Minecraft;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Consumer-local material query: the handler list, original slots and its verdicts stay untouched. */
public final class EmiSourcePreview {
    private EmiSourcePreview() {}
    private static final Set<String> NATIVE = Set.of("dev.emi.emi.handler.InventoryRecipeHandler", "dev.emi.emi.handler.CraftingRecipeHandler",
            "dev.emi.emi.handler.StonecuttingRecipeHandler");
    private static final Set<String> FXNT = Set.of("net.fxnt.fxntstorage.compat.emi.EMIInventoryRecipeHandler",
            "net.fxnt.fxntstorage.compat.emi.EMICraftingRecipeHandler", "net.fxnt.fxntstorage.compat.emi.EMIStonecuttingRecipeHandler",
            "net.fxnt.fxntstorage.compat.emi.EMIBackpackCraftingRecipeHandler");
    public static List<Slot> append(Object handler, AbstractContainerMenu menu, List<Slot> original) {
        if (!ClientMaterials.active()) return original;
        String name = handler.getClass().getName();
        if (!NATIVE.contains(name) && !FXNT.contains(name)) return original;
        var result = new ArrayList<>(physicalSlots(handler, menu, original));
        // Turning off cache supply does not turn an existing reservation alias into physical stock.
        if (!FmpConfig.emiCraftingEnabled() || !ClientCompatibility.emiSourcesAvailable()
                || FXNT.contains(name) && (!ClientCompatibility.fxntSourcesAvailable()
                    || !dev.scathiard.feedmepackages.mixin.OptionalMixins.fxntEmiOriginCompatible())) return result;
        for (var stack : ClientMaterials.craftingStacks()) {
            int remaining = Math.min(stack.getCount(), Math.min(64, stack.getMaxStackSize()) * 9);
            while (remaining > 0) {
                int count = Math.min(remaining, Math.min(64, stack.getMaxStackSize())); result.add(view(stack.copyWithCount(count))); remaining -= count;
            }
        }
        return result;
    }
    /** Query-only view of our aliases; never used as the real destination or network slot list. */
    public static List<Slot> physicalSlots(Object handler, AbstractContainerMenu menu, List<Slot> original) {
        if (!ClientMaterials.active() || (!NATIVE.contains(handler.getClass().getName()) && !FXNT.contains(handler.getClass().getName()))
                || !CraftingService.supported(menu)) return original;
        var result = new ArrayList<>(original);
        if (CraftingService.supported(menu)) {
            var grid = CraftingService.grid(menu); var physical = ClientMaterials.realGrid(Minecraft.getInstance().player, grid);
            for (int i = 0; i < result.size(); i++) {
                var slot = result.get(i);
                if (slot != null && slot.container == grid && slot.getContainerSlot() >= 0 && slot.getContainerSlot() < physical.size()) {
                    var real = physical.get(slot.getContainerSlot());
                    if (!ItemStack.matches(real, slot.getItem())) {
                        var query = new Slot(new SimpleContainer(real.copy()), 0, slot.x, slot.y) {
                            @Override public int getMaxStackSize() { return slot.getMaxStackSize(); }
                            @Override public int getMaxStackSize(ItemStack stack) { return slot.getMaxStackSize(stack); }
                            @Override public boolean mayPlace(ItemStack stack) { return slot.mayPlace(stack); }
                        };
                        query.index = slot.index; result.set(i, query);
                    }
                }
            }
        }
        return result;
    }
    private static Slot view(ItemStack stack) { var slot = new Slot(new SimpleContainer(stack.copy()), 0, 0, 0); slot.index = -1; return slot; }
}
