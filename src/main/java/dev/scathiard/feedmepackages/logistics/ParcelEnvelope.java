package dev.scathiard.feedmepackages.logistics;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.logistics.box.PackageItem;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import java.util.Set;

/** Recognize a complete envelope before projecting it onto Create's nine item slots.
 * Unknown components can carry additional goods; a valid route does not authorize destroying them. */
public final class ParcelEnvelope {
    private ParcelEnvelope() {}
    private static final Set<DataComponentType<?>> SAFE = Set.of(
            AllDataComponents.PACKAGE_CONTENTS, AllDataComponents.PACKAGE_ADDRESS,
            AllDataComponents.PACKAGE_ORDER_DATA, AllDataComponents.PACKAGE_ORDER_CONTEXT,
            DataComponents.MAX_STACK_SIZE, DataComponents.RARITY, DataComponents.ENCHANTMENTS,
            DataComponents.ATTRIBUTE_MODIFIERS, DataComponents.REPAIR_COST,
            DataComponents.CUSTOM_NAME, DataComponents.ITEM_NAME, DataComponents.LORE,
            DataComponents.CUSTOM_MODEL_DATA, DataComponents.ENCHANTMENT_GLINT_OVERRIDE,
            DataComponents.HIDE_ADDITIONAL_TOOLTIP, DataComponents.HIDE_TOOLTIP);

    public static boolean recognized(ItemStack box) {
        if (box.getCount() != 1 || !PackageItem.isPackage(box)
                || box.getOrDefault(AllDataComponents.PACKAGE_CONTENTS, ItemContainerContents.EMPTY).getSlots() > 9)
            return false;
        for (var type : box.getComponents().keySet()) {
            if (!SAFE.contains(type) && type != FmpRegistries.PARCEL_SEAL.get()
                    && type != FmpRegistries.FLUID_PARCEL_SEAL.get()) return false;
        }
        return true;
    }

    public static boolean items(ItemStack box) {
        // Subclasses may expose only a partial item view while owning fluids, energy or other payloads.
        return recognized(box) && box.getItem().getClass() == PackageItem.class;
    }
}
