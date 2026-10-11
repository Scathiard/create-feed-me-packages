package dev.scathiard.feedmepackages.storage;

import com.simibubi.create.content.logistics.box.PackageItem;
import dev.scathiard.feedmepackages.domain.CacheState;
import dev.scathiard.feedmepackages.domain.Cell;
import dev.scathiard.feedmepackages.domain.CacheLevel;
import dev.scathiard.feedmepackages.fluid.FluidVariantKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import java.util.*;

/** Typed part of one CacheRecord, not a second inventory or an independent revision stream. */
public record CacheFluids(CacheState<FluidVariantKey> state, Map<FluidVariantKey, ItemStack> residuals,
                          Map<FluidVariantKey, CompoundTag> suspendedResiduals) {
    public CacheFluids {
        Objects.requireNonNull(state);
        var copy = new HashMap<FluidVariantKey, ItemStack>();
        for (var entry : residuals.entrySet()) {
            var box = entry.getValue();
            // Clearing or replacing a filter cannot erase an already owned parcel. An obsolete
            // receipt stays here until ReceiveService can return the whole parcel to the holder.
            if (entry.getKey() == null || box == null
                    || box.getCount() != 1 || !PackageItem.isPackage(box))
                throw new IllegalArgumentException("Invalid fluid residual slot");
            copy.put(entry.getKey(), box.copy());
        }
        residuals = Collections.unmodifiableMap(copy);
        var suspended = new HashMap<FluidVariantKey, CompoundTag>();
        for (var entry : suspendedResiduals.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || residuals.containsKey(entry.getKey())
                    || !dev.scathiard.feedmepackages.compat.fluidlogistics.FluidPackages.unavailableResidual(entry.getValue()))
                throw new IllegalArgumentException("Invalid suspended fluid residual");
            suspended.put(entry.getKey(), entry.getValue().copy());
        }
        suspendedResiduals = Collections.unmodifiableMap(suspended);
    }
    public CacheFluids(CacheState<FluidVariantKey> state, Map<FluidVariantKey, ItemStack> residuals) {
        this(state, residuals, Map.of());
    }

    public static CacheFluids empty(CacheState<?> context) {
        return new CacheFluids(new CacheState<>(context.id(), context.level(), context.revision(),
                Collections.nCopies(context.cells().size(), Cell.<FluidVariantKey>empty()), Map.of(), context.capacity(), Map.of(), Map.of()), Map.of());
    }

    @Override public Map<FluidVariantKey, ItemStack> residuals() {
        var copy = new HashMap<FluidVariantKey, ItemStack>();
        residuals.forEach((key, box) -> copy.put(key, box.copy()));
        return Collections.unmodifiableMap(copy);
    }
    public ItemStack residual(FluidVariantKey variant) {
        var stack = residuals.get(variant); return stack == null ? ItemStack.EMPTY : stack.copy();
    }
    @Override public Map<FluidVariantKey, CompoundTag> suspendedResiduals() {
        var copy = new HashMap<FluidVariantKey, CompoundTag>();
        suspendedResiduals.forEach((key, raw) -> copy.put(key, raw.copy()));
        return Collections.unmodifiableMap(copy);
    }
    public boolean hasSuspendedResiduals() { return !suspendedResiduals.isEmpty(); }
    public boolean hasResidual(FluidVariantKey variant) { return residuals.containsKey(variant) || suspendedResiduals.containsKey(variant); }
    public boolean anyResidual() { return !residuals.isEmpty() || hasSuspendedResiduals(); }
    public boolean hasData() { return state.cells().stream().anyMatch(cell -> !cell.equals(Cell.<FluidVariantKey>empty())) || !state.orders().isEmpty() || anyResidual(); }
    public CacheFluids withState(CacheState<FluidVariantKey> next) { return new CacheFluids(next, residuals, suspendedResiduals); }
    public CacheFluids withResidual(FluidVariantKey variant, ItemStack box) {
        if (suspendedResiduals.containsKey(variant)) throw new IllegalStateException("Cannot replace a suspended parcel");
        var next = new HashMap<>(residuals);
        if (box.isEmpty()) next.remove(variant); else next.put(variant, box);
        return new CacheFluids(state, next, suspendedResiduals);
    }

    /** Item-side changes preserve all fluid columns. Capacity changes are strict unless both sides are explicit. */
    CacheFluids align(CacheState<?> context) {
        if (!context.id().equals(state.id())) throw new IllegalArgumentException("Fluid cache identity changed");
        var edit = state.edit();
        if (context.level() < state.level()) throw new IllegalArgumentException("Fluid cache level cannot decrease");
        for (int level = state.level(); level < context.level(); level++) edit.upgrade(context.capacity());
        edit.resize(context.capacity());
        var next = edit.finish();
        // An item-side extent may retain empty or read-only cells after an earlier shrink.
        if (next.cells().size() != context.cells().size()) {
            var cells = new ArrayList<Cell<FluidVariantKey>>(next.cells());
            while (cells.size() < context.cells().size()) cells.add(Cell.empty());
            next = new CacheState<>(next.id(), next.level(), next.revision(), cells, next.orders(), next.capacity(),
                    next.retainedCeilings(), next.requestedControls());
        }
        return new CacheFluids(next.withRevision(context.revision()), residuals, suspendedResiduals);
    }
}
