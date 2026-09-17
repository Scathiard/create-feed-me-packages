package dev.scathiard.feedmepackages.compat.fxntstorage;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.List;

/**
 * F-6 narrow seam: presented through the vanilla {@link Inventory} the call already carries, so the owner is
 * the parameter itself (its public final {@code player}) and nothing has to be matched. Server side it debits
 * the real cache; the client side is the same rule with {@code readOnly} set, where any write is refused out
 * loud and nothing is written anywhere.
 */
public final class CachePresentingInventory extends Inventory {
    private final CacheSupply supply;
    private final List<CacheSupply.Entry> entries;
    private final String owner;
    private final boolean readOnly;
    private final CacheBorrow borrow = new CacheBorrow();
    private int served;
    private int debited;

    public CachePresentingInventory(Player player, CacheSupply supply, List<CacheSupply.Entry> entries, List<Ingredient> materials, String owner, boolean readOnly) {
        super(player);
        this.supply = supply;
        this.entries = List.copyOf(entries);
        this.owner = owner;
        this.readOnly = readOnly;
        plan(materials);
    }

    public Player owner() { return this.player; }
    public boolean readOnly() { return readOnly; }

    private void plan(List<Ingredient> materials) {
        if (materials == null || materials.isEmpty()) {
            CompatLog.once("no-materials", "FMP compat: degraded (no recipe materials for this call)");
            return;
        }
        var supplies = new ArrayList<CacheBorrow.Supply>();
        for (int key = 0; key < entries.size(); key++) {
            ItemStack stack = entries.get(key).stack();
            if (stack.isEmpty() || !matches(materials, stack)) continue;
            supplies.add(new CacheBorrow.Supply(key, entries.get(key).cell(), stack.getCount(), stack.getMaxStackSize()));
        }
        if (supplies.isEmpty()) return;
        var empty = new ArrayList<Integer>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) empty.add(slot);
        }
        borrow.plan(empty, supplies, key -> true);
    }

    private static boolean matches(List<Ingredient> materials, ItemStack stack) {
        for (Ingredient ingredient : materials) if (ingredient != null && ingredient.test(stack)) return true;
        return false;
    }

    private ItemStack presented(int slot) {
        var lend = borrow.lend(slot);
        if (lend == null || lend.key() < 0 || lend.key() >= entries.size()) return ItemStack.EMPTY;
        int shown = Math.min(lend.presented(), entries.get(lend.key()).stack().getMaxStackSize());
        return entries.get(lend.key()).stack().copyWithCount(shown);
    }

    private void account(CacheBorrow.Settled settled) {
        if (settled == null || settled.taken() <= 0) return;
        int removed = supply.take(settled.cell(), settled.taken());
        served += settled.taken();
        debited += removed;
        String item = settled.key() >= 0 && settled.key() < entries.size() ? entries.get(settled.key()).stack().getItem().toString() : "?";
        CompatLog.compat("FMP compat: borrow side=" + supply.side() + " cell=" + settled.cell() + " item=" + item
                + " amount=" + settled.taken() + " exposed=" + borrow.exposed() + " served=" + served + " debited=" + debited + " from " + owner);
    }

    @Override public int getContainerSize() { return player.getInventory().getContainerSize(); }

    @Override public ItemStack getItem(int slot) {
        ItemStack real = player.getInventory().getItem(slot);
        if (!real.isEmpty()) { borrow.forget(slot); return real; }
        return presented(slot);
    }

    @Override public void setItem(int slot, ItemStack stack) {
        if (borrow.lend(slot) == null) {
            if (readOnly) { CompatLog.once("debit-refused:readonly", "FMP compat: debit refused (read-only client presenter)"); return; }
            player.getInventory().setItem(slot, stack);
            return;
        }
        account(borrow.settle(slot, stack.getCount()));
    }

    @Override public ItemStack removeItem(int slot, int amount) {
        var lend = borrow.lend(slot);
        if (lend == null) {
            if (readOnly) { CompatLog.once("debit-refused:readonly", "FMP compat: debit refused (read-only client presenter)"); return ItemStack.EMPTY; }
            return player.getInventory().removeItem(slot, amount);
        }
        int give = Math.min(amount, lend.presented());
        if (give <= 0) return ItemStack.EMPTY;
        ItemStack taken = presented(slot).copyWithCount(give);
        account(borrow.use(slot, give));
        return taken;
    }

    public int served() { return served; }
    public int debited() { return debited; }
    public int exposed() { return borrow.exposed(); }
}