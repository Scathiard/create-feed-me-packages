package dev.scathiard.feedmepackages.compat.fxntstorage;

import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Where the presented materials come from, and where a real removal goes. */
public interface CacheSupply {
    /** Copies of what this side can give right now; each stack's count is what we may lend. */
    List<ItemStack> available();
    /** Remove {@code count} of {@code prototype} from the real cache. Only the owning side may write. */
    void take(ItemStack prototype, int count);
    /** Short label used in the one-line evidence log. */
    String side();
}
