package dev.scathiard.feedmepackages.storage;

import dev.scathiard.feedmepackages.domain.CacheState;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import java.util.*;
import java.util.stream.Collectors;

/** owner == null is an ordinary item cache.
 *  Residuals are actual packages, not counts, and are owned per exact filter variant (one per item cell).
 *  A cell may hold at most one residual; different cells never contend for a shared slot. */
public record CacheRecord(CacheState<ItemVariantKey> state, UUID owner, Map<ItemVariantKey, ItemStack> residuals,
                          CacheFluids fluids) {
    public CacheRecord {
        Objects.requireNonNull(state);
        residuals = copyResiduals(residuals);
        Objects.requireNonNull(fluids);
        var fluid = fluids.state();
        if (!state.id().equals(fluid.id()) || state.level() != fluid.level() || state.revision() != fluid.revision() || !state.capacity().equals(fluid.capacity()) || state.cells().size() != fluid.cells().size()) throw new IllegalArgumentException("Fluid context differs from cache");
        for (int slot = 0; slot < state.cells().size(); slot++)
            if (fluid.cells().get(slot).filter() != null && state.cells().get(slot).filter() != null)
                throw new IllegalArgumentException("Item and fluid filters occupy the same cache slot: " + slot);
        if (state.orders().size() + fluid.orders().size() > CacheState.MAX_ORDERS
                || !Collections.disjoint(state.orders().keySet(), fluid.orders().keySet()))
            throw new IllegalArgumentException("Shared order limit or identity conflict");
    }
    public CacheRecord(CacheState<ItemVariantKey> state, UUID owner, Map<ItemVariantKey, ItemStack> residuals) {
        this(state, owner, residuals, CacheFluids.empty(state));
    }
    @Override public Map<ItemVariantKey, ItemStack> residuals() { return copyResiduals(residuals); }
    public ItemStack residual(ItemVariantKey variant) {
        ItemStack stack = residuals.get(variant);
        return stack == null ? ItemStack.EMPTY : stack.copy();
    }
    public boolean anyResidual() { return !residuals.isEmpty() || fluids.anyResidual(); }
    /** Missing-addon parcels remain owned, but cannot stall unrelated ordinary replenishment. */
    public boolean blocksReplenishment() { return !residuals.isEmpty() || !fluids.residuals().isEmpty(); }
    public CacheRecord withState(CacheState<ItemVariantKey> next) { return withStates(next, fluids.align(next)); }
    public CacheRecord withStates(CacheState<ItemVariantKey> next, CacheFluids nextFluids) {
        return new CacheRecord(next, owner, residuals, nextFluids);
    }
    public CacheRecord withFluids(CacheFluids next) {
        var context = next.state();
        if (!state.id().equals(context.id()) || state.level() != context.level())
            throw new IllegalArgumentException("Change shared fluid capacity through both typed states");
        return withStates(state.withRevision(context.revision()), next);
    }

    /** Replace the residual for one cell; an empty box clears it. */
    public CacheRecord withResidual(ItemVariantKey variant, ItemStack box) {
        var next = new HashMap<>(residuals);
        if (box == null || box.isEmpty()) next.remove(variant);
        else next.put(variant, box.copy());
        return new CacheRecord(state, owner, next, fluids);
    }

    private static Map<ItemVariantKey, ItemStack> copyResiduals(Map<ItemVariantKey, ItemStack> source) {
        return source.entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isEmpty())
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().copy()));
    }
}
