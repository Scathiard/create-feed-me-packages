package dev.scathiard.feedmepackages.compat.fxntstorage;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * The wrapper Create: Storage's own transfer code sees instead of their plain {@link ItemStackHandler}.
 *
 * <p>Rules (one instance per transfer call, never stored anywhere):
 * <ul>
 *   <li>their occupied slot => their stack, unchanged, and writes to it go back to {@code super};</li>
 *   <li>their EMPTY item slot that we lent => a COPY of a cache stack (count clipped to the cache and to the
 *       item's stack size) - their container is never touched;</li>
 *   <li>write-back on a lent slot => how much they really took is debited from our cache, the lending ends,
 *       and nothing is written into their container;</li>
 *   <li>everything else (size, slot limit, validity, inserts) delegates to their handler.</li>
 * </ul>
 * Because the wrapper only ever exists as a local of their method and is never serialized, a virtual item
 * cannot reach their backpack, their save file or their GUI.
 */
public final class CachePresentingHandler extends ItemStackHandler {
    private final ItemStackHandler delegate;
    private final CacheSupply supply;
    private final List<ItemStack> available;
    private final String owner;
    private final int first;
    private final int last;
    private final CacheBorrow borrow = new CacheBorrow();
    private int served;

    public CachePresentingHandler(ItemStackHandler delegate, CacheSupply supply, List<ItemStack> available, int first, int last, String owner) {
        this.delegate = delegate;
        this.supply = supply;
        this.available = List.copyOf(available);
        this.first = first;
        this.last = last;
        this.owner = owner;
        plan();
    }

    /** Lend one cache stack per empty item slot; a shortage simply exposes fewer, never invents material. */
    private void plan() {
        var supplies = new ArrayList<CacheBorrow.Supply>();
        for (int key = 0; key < available.size(); key++) {
            ItemStack stack = available.get(key);
            if (stack.isEmpty()) continue;
            supplies.add(new CacheBorrow.Supply(key, stack.getCount(), stack.getMaxStackSize()));
        }
        borrow.plan(FxntCompat.emptySlots(delegate, first, last), supplies);
        if (borrow.exposed() > 0) {
            CompatLog.compat("FMP compat: exposed=" + borrow.exposed() + " cache stacks to " + owner + "; served=0 items");
        }
    }

    /** The copy we present for a lent slot: never the cache stack itself, never more than it can give. */
    private ItemStack presented(int slot) {
        var lend = borrow.lend(slot);
        if (lend == null || lend.key() < 0 || lend.key() >= available.size()) return ItemStack.EMPTY;
        return available.get(lend.key()).copyWithCount(lend.presented());
    }

    private void settle(int slot, int remaining) {
        account(borrow.settle(slot, remaining));
    }

    /** An extract keeps the lending for the rest, but debits exactly what left our hands. */
    private void use(int slot, int amount) {
        account(borrow.use(slot, amount));
    }

    private void account(CacheBorrow.Settled settled) {
        if (settled == null || settled.taken() <= 0) return;
        ItemStack source = settled.key() >= 0 && settled.key() < available.size() ? available.get(settled.key()) : ItemStack.EMPTY;
        if (!source.isEmpty()) supply.take(source, settled.taken());
        served += settled.taken();
        CompatLog.compat("FMP compat: exposed=" + borrow.exposed() + " cache stacks to " + owner + "; served=" + served + " items");
    }

    @Override public int getSlots() { return delegate.getSlots(); }

    @Override public ItemStack getStackInSlot(int slot) {
        ItemStack theirs = delegate.getStackInSlot(slot);
        if (!theirs.isEmpty()) {
            borrow.forget(slot);            // their own item wins; nothing of ours is exposed there
            return theirs;
        }
        return presented(slot);
    }

    @Override public void setStackInSlot(int slot, ItemStack stack) {
        if (borrow.lend(slot) == null) {
            delegate.setStackInSlot(slot, stack);   // not ours: hand it straight back to them
            return;
        }
        settle(slot, stack.getCount());             // ours: debit what was taken, never write their container
    }

    @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
        var lend = borrow.lend(slot);
        if (lend == null) return delegate.extractItem(slot, amount, simulate);
        int give = Math.min(amount, lend.presented());
        if (give <= 0) return ItemStack.EMPTY;
        ItemStack taken = presented(slot).copyWithCount(give);
        if (!simulate) use(slot, give);
        return taken;
    }

    @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        // While we present something in that slot their container stays untouched, so an insert is refused.
        if (borrow.lend(slot) != null) return stack;
        return delegate.insertItem(slot, stack, simulate);
    }

    @Override public int getSlotLimit(int slot) {
        return borrow.lend(slot) == null ? delegate.getSlotLimit(slot)
                : Math.min(delegate.getSlotLimit(slot), presented(slot).getMaxStackSize());
    }

    @Override public boolean isItemValid(int slot, ItemStack stack) {
        return borrow.lend(slot) == null && delegate.isItemValid(slot, stack);
    }

    /** Diagnostics only: how many slots this call lends and how many items were really served. */
    public int exposed() { return borrow.exposed(); }
    public int served() { return served; }
    public String owner() { return owner; }
}
