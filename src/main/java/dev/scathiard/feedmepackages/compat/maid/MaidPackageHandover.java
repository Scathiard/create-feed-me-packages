package dev.scathiard.feedmepackages.compat.maid;

import com.simibubi.create.content.logistics.box.PackageItem;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;

/**
 * Hands a Create package back to the maid it is addressed to <b>without ever touching her inventory</b>.
 * Packages report themselves as uncontainable, so the native verdict stays untouched; instead the package
 * is unpacked and its contents go into HER cache through the same filter-and-insert path the maid pickup
 * uses. Nothing is silently swallowed: a content stack that no filter of hers claims, or that does not fit,
 * is returned to the player. The package is consumed <b>only when at least one item reached her cache</b>;
 * otherwise it stays in the player's hand and the player is told why.
 */
public final class MaidPackageHandover {
    private MaidPackageHandover() {}

    /** True when the stack is a Create package addressed to this maid's own FMP address. */
    public static boolean addressedTo(ItemStack stack, LivingEntity maid) {
        return maid != null && stack != null && !stack.isEmpty() && PackageItem.isPackage(stack)
                && PackageItem.getAddress(stack).equals(MaidAccess.address(maid));
    }

    /**
     * Unpacks one package into the maid's cache. Returns the number of packages consumed (0 when the
     * interaction does not apply: not addressed to her, or her access is not active).
     */
    public static int deliver(ServerPlayer player, LivingEntity maid, ItemStack packageStack) {
        if (player == null || !addressedTo(packageStack, maid)) return 0;
        var access = MaidAccess.resolve(maid);
        if (!access.active()) return 0;
        var ledger = CacheLedger.get(player.getServer());
        var before = ledger.find(access.handle().cacheId());
        var contents = PackageItem.getContents(packageStack);
        var edit = before.state().edit();
        List<ItemStack> leftovers = new ArrayList<>();
        int stored = 0; // items actually deposited into her cache
        for (int slot = 0; slot < contents.getSlots(); slot++) {
            ItemStack offered = contents.getStackInSlot(slot);
            if (offered.isEmpty()) continue;
            ItemVariantKey key;
            try { key = ItemVariantKey.of(offered, player.registryAccess()); }
            catch (IllegalArgumentException unsupported) { leftovers.add(offered.copy()); continue; }
            // Only a filter she already keeps takes the stack: no filter is invented in her cache.
            int cell = before.state().find(key);
            int inserted = cell < 0 ? 0 : edit.insert(cell, key, offered.getCount());
            stored += inserted;
            if (inserted < offered.getCount())
                leftovers.add(offered.copyWithCount(offered.getCount() - inserted));
        }
        if (stored == 0) {
            // Nothing of hers takes this batch: the package is NOT opened and stays in hand.
            player.displayClientMessage(Component.translatable(
                    "message.create_feed_me_packages.maid_package_no_filter"), true);
            return 0;
        }
        ledger.replace(access.handle(), before.state().revision(), before.withState(edit.finish()));
        // At least one item landed in her cache: the package is consumed and only the remainder goes back.
        packageStack.shrink(1);
        for (ItemStack leftover : leftovers) {
            if (!player.getInventory().add(leftover)) player.drop(leftover, false);
        }
        return 1;
    }
}
