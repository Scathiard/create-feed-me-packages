package dev.scathiard.feedmepackages.compat.fxntstorage;

import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Where the presented materials come from, and where a real removal goes. */
public interface CacheSupply {
    /** One cache cell and what it can give; the cell index travels with the stack so a debit never re-looks it up. */
    record Entry(int cell, ItemStack stack) {}
    /** Copies of what this side can give right now, one entry per cache cell. */
    List<Entry> available();
    /** Remove up to {@code count} items from exactly this cache cell; returns how many were really removed. */
    int take(int cell, int count);
    /** Short label used in the one-line evidence log. */
    String side();
}
