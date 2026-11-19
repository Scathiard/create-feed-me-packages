package dev.scathiard.feedmepackages.logistics;

import dev.scathiard.feedmepackages.storage.CacheRecord;
import net.minecraft.world.item.ItemStack;

/** Proposed cache state, any ordinary remainder in inventory, and units actually unpacked. */
public record ParcelReceipt(CacheRecord replacement, ItemStack remainder, int moved) {}
