package dev.scathiard.feedmepackages.storage;

import dev.scathiard.feedmepackages.domain.CacheState;
import dev.scathiard.feedmepackages.domain.SupplyOrder;
import dev.scathiard.feedmepackages.fluid.FluidVariantKey;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.world.item.ItemStack;
import java.util.*;
import dev.scathiard.feedmepackages.domain.Cell;
import dev.scathiard.feedmepackages.domain.CacheLevel;

/** Additive typed column. Context belongs to the cache row, quantities here are always mB. */
final class FluidStorageCodec {
    private FluidStorageCodec() {}

    static CompoundTag write(CacheFluids fluids, HolderLookup.Provider registries) {
        var tag = new CompoundTag(); tag.putInt("version", 1);
        tag.put("cells", writeCells(fluids.state()));
        var orders = new ListTag();
        for (var order : fluids.state().orders().values()) {
            var row = new CompoundTag(); row.putUUID("id", order.id()); row.putUUID("player", order.player());
            row.putString("variant", order.variant().encoded()); row.putLong("revision", order.filterRevision());
            row.putInt("remaining", order.remaining()); row.putBoolean("uncertain", order.uncertain()); orders.add(row);
        }
        tag.put("orders", orders);
        var residuals = new ListTag();
        var packages = new TreeMap<String, Tag>();
        fluids.residuals().forEach((variant, box) -> packages.put(variant.encoded(), box.save(registries)));
        fluids.suspendedResiduals().forEach((variant, raw) -> packages.put(variant.encoded(), raw));
        packages.forEach((variant, raw) -> {
            var row = new CompoundTag(); row.putString("variant", variant);
            row.put("package", raw); residuals.add(row);
        });
        tag.put("residuals", residuals); return tag;
    }

    static CacheFluids read(CompoundTag tag, CacheState<?> context, HolderLookup.Provider registries) {
        fields(tag, Set.of("version", "cells", "orders", "residuals")); require(tag, "version", Tag.TAG_INT);
        if (tag.getInt("version") != 1) throw new IllegalArgumentException("Unsupported fluid storage version");
        require(tag, "cells", Tag.TAG_COMPOUND); var cells = tag.getCompound("cells");
        fields(cells, Set.of("extent", "entries"));
        var orders = new HashMap<UUID, SupplyOrder<FluidVariantKey>>();
        var orderRows = compounds(tag, "orders");
        if (orderRows.size() + context.orders().size() > CacheState.MAX_ORDERS)
            throw new IllegalArgumentException("Too many shared orders");
        for (var entry : orderRows) {
            var row = (CompoundTag) entry;
            fields(row, Set.of("id", "player", "variant", "revision", "remaining", "uncertain"));
            require(row, "id", Tag.TAG_INT_ARRAY); require(row, "player", Tag.TAG_INT_ARRAY);
            require(row, "variant", Tag.TAG_STRING); require(row, "revision", Tag.TAG_LONG);
            require(row, "remaining", Tag.TAG_INT); require(row, "uncertain", Tag.TAG_BYTE);
            if (row.getByte("uncertain") != 0 && row.getByte("uncertain") != 1)
                throw new IllegalArgumentException("Invalid fluid order flag");
            var id = row.getUUID("id");
            var order = new SupplyOrder<>(id, row.getUUID("player"), FluidVariantKey.decode(row.getString("variant"), registries),
                    row.getLong("revision"), row.getInt("remaining"), row.getBoolean("uncertain"));
            if (orders.put(id, order) != null) throw new IllegalArgumentException("Duplicate fluid order");
        }
        var state = readCells(cells, context, orders, registries);
        var residuals = new HashMap<FluidVariantKey, ItemStack>();
        var suspended = new HashMap<FluidVariantKey, CompoundTag>();
        var seen = new HashSet<FluidVariantKey>();
        for (var entry : compounds(tag, "residuals")) {
            var row = (CompoundTag) entry; fields(row, Set.of("variant", "package"));
            require(row, "variant", Tag.TAG_STRING); require(row, "package", Tag.TAG_COMPOUND);
            var key = FluidVariantKey.decode(row.getString("variant"), registries);
            if (!seen.add(key)) throw new IllegalArgumentException("Duplicate fluid residual");
            var raw = row.getCompound("package");
            fields(raw, Set.of("id", "count", "components"));
            if (dev.scathiard.feedmepackages.compat.fluidlogistics.FluidPackages.unavailableResidual(raw)) {
                require(raw, "components", Tag.TAG_COMPOUND);
                suspended.put(key, raw); continue;
            }
            var box = ItemStack.parse(registries, row.getCompound("package")).orElseThrow();
            if (!box.save(registries).equals(row.getCompound("package")))
                throw new IllegalArgumentException("Fluid residual cannot round-trip without alteration");
            if (residuals.put(key, box) != null) throw new IllegalArgumentException("Duplicate fluid residual");
        }
        return new CacheFluids(state, residuals, suspended);
    }

    private static ListTag compounds(CompoundTag tag, String key) {
        require(tag, key, Tag.TAG_LIST); var rows = (ListTag) tag.get(key);
        if (!rows.isEmpty() && rows.getElementType() != Tag.TAG_COMPOUND)
            throw new IllegalArgumentException("Invalid fluid list: " + key);
        return rows;
    }

    private static CompoundTag writeCells(CacheState<FluidVariantKey> state) {
        var result = new CompoundTag(); result.putInt("extent", state.cells().size()); var entries = new ListTag();
        for (int slot = 0; slot < state.cells().size(); slot++) {
            var cell = state.cells().get(slot);
            if (cell.equals(Cell.<FluidVariantKey>empty())) continue;
            var row = new CompoundTag(); row.putInt("slot", slot);
            if (cell.filter() != null) row.putString("filter", cell.filter().encoded());
            row.putLong("amount", cell.amount()); row.putLong("min", cell.minimum());
            row.putLong("max", cell.maximum()); row.putLong("revision", cell.filterRevision()); entries.add(row);
        }
        result.put("entries", entries); return result;
    }
    private static CacheState<FluidVariantKey> readCells(CompoundTag tag, CacheState<?> context,
            Map<UUID, SupplyOrder<FluidVariantKey>> orders, HolderLookup.Provider registries) {
        require(tag, "extent", Tag.TAG_INT);
        if (tag.getInt("extent") != CacheLevel.of(context.level()).slots()) throw new IllegalArgumentException("Fluid cell count differs from cache");
        var cells = new ArrayList<Cell<FluidVariantKey>>(Collections.nCopies(context.cells().size(), Cell.empty()));
        var used = new HashSet<Integer>();
        for (var entry : compounds(tag, "entries")) {
            var row = (CompoundTag) entry; fields(row, Set.of("slot", "filter", "amount", "min", "max", "revision"));
            require(row, "slot", Tag.TAG_INT);
            for (String key : List.of("amount", "min", "max", "revision")) require(row, key, Tag.TAG_LONG);
            int slot = row.getInt("slot");
            if (slot < 0 || slot >= cells.size() || !used.add(slot)) throw new IllegalArgumentException("Invalid or duplicate fluid slot");
            if (row.contains("filter")) require(row, "filter", Tag.TAG_STRING);
            var key = row.contains("filter") ? FluidVariantKey.decode(row.getString("filter"), registries) : null;
            cells.set(slot, new Cell<>(key, Math.toIntExact(row.getLong("amount")), Math.toIntExact(row.getLong("min")),
                    Math.toIntExact(row.getLong("max")), row.getLong("revision")));
        }
        return new CacheState<>(context.id(), context.level(), context.revision(), cells, orders);
    }
    private static void require(CompoundTag tag, String key, int type) {
        if (!tag.contains(key, type)) throw new IllegalArgumentException("Missing or mistyped fluid field: " + key);
    }
    private static void fields(CompoundTag tag, Set<String> allowed) {
        if (!allowed.containsAll(tag.getAllKeys())) throw new IllegalArgumentException("Unknown fluid storage fields");
    }
}
