package dev.scathiard.feedmepackages.compat.fxntstorage;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.List;

/**
 * F-6 narrow seam: their server-side placement takes the player's real {@link Inventory} as a parameter, so
 * we present a SUBCLASS of it instead of guessing who the handler belongs to. No instance identity, no dist
 * check, no thread check: {@code this.player} (the public final field of Inventory) is the owner, so
 * {@code AccessGate.resolve(player)} can no longer "not match" and silently degrade to a view that never
 * debits.
 *
 * <p>Rules: their occupied slot =&gt; their stack; an empty slot we lent =&gt; a copy of a cache cell the call's
 * materials accept; a write-back on a lent slot debits exactly that cell and never touches the real
 * inventory; everything else delegates to the player's real inventory.
 */
public final class CachePresentingInventory extends Inventory {
    private final CacheSupply supply;
    private final List<CacheSupply.Entry> entries;
    private final String owner;
    private final CacheBorrow borrow = new CacheBorrow();
    private int served;
    private int debited;

    public CachePresentingInventory(Player player, CacheSupply supply, List<CacheSupply.Entry> entries, List<Ingredient> materials, String owner) {
        super(player);
        this.supply = supply;
        this.entries = List.copyOf(entries);
        this.owner = owner;
        plan(materials);
    }

    /** The owner comes straight from the vanilla parameter - nothing to match. */
    public Player owner() { return this.player; }

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
        // Vanilla Inventory is a Container, not an IItemHandler: scan its own slots for the empty ones.
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
        return entries.get(lend.key()).stack().copyWithCount(lend.presented());
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
        if (borrow.lend(slot) == null) { player.getInventory().setItem(slot, stack); return; }
        account(borrow.settle(slot, stack.getCount()));
    }

    @Override public ItemStack removeItem(int slot, int amount) {
        var lend = borrow.lend(slot);
        if (lend == null) return player.getInventory().removeItem(slot, amount);
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