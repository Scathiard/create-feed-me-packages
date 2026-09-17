package dev.scathiard.feedmepackages.compat.fxntstorage;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * The wrapper Create: Storage's own transfer code sees instead of their plain handler.
 *
 * <p>Rules (one instance per call, never stored anywhere):
 * <ul>
 *   <li>their occupied slot =&gt; their stack, unchanged, and writes to it go back to them;</li>
 *   <li>their EMPTY item slot that we lent =&gt; a COPY of a cache stack, and only for a cell this call's
 *       recipe materials accept (the material filter comes from {@link FxntContext});</li>
 *   <li>write-back on a lent slot =&gt; the amount really taken is debited from EXACTLY that cache cell, and
 *       nothing is written into their container;</li>
 *   <li>everything else delegates to their handler.</li>
 * </ul>
 * The summary is ONE line per wrapper (and only when something was really taken), so a transfer cannot flood
 * the log. The wrapper never escapes the call and is never serialized, so virtual items cannot reach their
 * backpack, their save file or their GUI.
 */
public final class CachePresentingHandler extends ItemStackHandler {
    private final IItemHandler delegate;
    private final CacheSupply supply;
    private final List<CacheSupply.Entry> entries;
    private final String owner;
    private final int first;
    private final int last;
    private final CacheBorrow borrow = new CacheBorrow();
    private int served;
    private int debited;
    private boolean summarised;

    public CachePresentingHandler(IItemHandler delegate, CacheSupply supply, List<CacheSupply.Entry> entries, List<Ingredient> materials, int first, int last, String owner) {
        this.delegate = delegate;
        this.supply = supply;
        this.entries = List.copyOf(entries);
        this.first = first;
        this.last = last;
        this.owner = owner;
        plan(materials);
    }

    private IItemHandlerModifiable writable() {
        return delegate instanceof IItemHandlerModifiable modifiable ? modifiable : null;
    }

    /** Lend only cells this call's materials accept; an empty material set lends nothing (their behaviour). */
    private void plan(List<Ingredient> materials) {
        if (materials == null || materials.isEmpty()) {
            CompatLog.once("no-materials", "FMP compat: degraded (no recipe materials for this call)");
            return;
        }
        var supplies = new ArrayList<CacheBorrow.Supply>();
        for (int key = 0; key < entries.size(); key++) {
            ItemStack stack = entries.get(key).stack();
            if (stack.isEmpty()) continue;
            if (!matches(materials, stack)) continue;
            supplies.add(new CacheBorrow.Supply(key, entries.get(key).cell(), stack.getCount(), stack.getMaxStackSize()));
        }
        if (supplies.isEmpty()) return;
        borrow.plan(FxntCompat.emptySlots(delegate, first, last), supplies, key -> true);
    }

    private static boolean matches(List<Ingredient> materials, ItemStack stack) {
        for (Ingredient ingredient : materials) if (ingredient != null && ingredient.test(stack)) return true;
        return false;
    }

    private ItemStack presented(int slot) {
        var lend = borrow.lend(slot);
        if (lend == null || lend.key() < 0 || lend.key() >= entries.size()) return ItemStack.EMPTY;
        return entries.get(lend.key()).stack().copyWithCount(lend.presented());
    }

    private void settle(int slot, int remaining) { account(borrow.settle(slot, remaining)); }
    private void use(int slot, int amount) { account(borrow.use(slot, amount)); }

    /** One item really left our cache: count it, debit exactly that cell, and say it once per wrapper. */
    private void account(CacheBorrow.Settled settled) {
        if (settled == null || settled.taken() <= 0) return;
        int removed = supply.take(settled.cell(), settled.taken());
        served += settled.taken();
        debited += removed;
        if (!summarised) {
            summarised = true;
            CompatLog.compat("FMP compat: borrow exposed=" + borrow.exposed() + " served=" + served + " debited=" + debited + " from " + owner);
        }
    }

    @Override public int getSlots() { return delegate.getSlots(); }

    @Override public ItemStack getStackInSlot(int slot) {
        ItemStack theirs = delegate.getStackInSlot(slot);
        if (!theirs.isEmpty()) { borrow.forget(slot); return theirs; }
        return presented(slot);
    }

    @Override public void setStackInSlot(int slot, ItemStack stack) {
        if (borrow.lend(slot) == null) {
            var writable = writable();
            if (writable != null) writable.setStackInSlot(slot, stack);
            return;
        }
        settle(slot, stack.getCount());
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

    public int exposed() { return borrow.exposed(); }
    public int served() { return served; }
    public int debited() { return debited; }
    public String owner() { return owner; }
}