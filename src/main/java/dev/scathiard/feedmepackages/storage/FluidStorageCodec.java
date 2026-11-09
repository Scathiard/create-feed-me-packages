package dev.scathiard.feedmepackages.storage;

import dev.scathiard.feedmepackages.compat.fluidlogistics.FluidPackages;
import dev.scathiard.feedmepackages.domain.CacheState;
import dev.scathiard.feedmepackages.domain.Cell;
import dev.scathiard.feedmepackages.domain.CellSettings;
import dev.scathiard.feedmepackages.domain.SupplyOrder;
import dev.scathiard.feedmepackages.fluid.FluidVariantKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * Serializes and deserializes fluid cells, fluid supply orders, and fluid package residuals
 * ({@code astra_fluids}, versions 1–3) for a cache record.
 *
 * <p>All fluid quantities are stored in millibuckets ({@code mB}, with 8000 mB per group).
 * If {@code fluidlogistics} is temporarily uninstalled, any saved fluid package residual is held in
 * {@code suspendedResiduals} so the cache remains operational without data loss.
 */
final class FluidStorageCodec {
    private FluidStorageCodec() {}

    static CompoundTag write(CacheFluids fluids, HolderLookup.Provider registries) {
        var tag = new CompoundTag();
        tag.putInt("version", 3);
        tag.put("cells", writeCells(fluids.state()));
        tag.put(CapacityCodec.KEY, CapacityCodec.write(fluids.state()));

        var orders = new ListTag();
        for (var order : fluids.state().orders().values()) {
            var row = new CompoundTag();
            row.putUUID("id", order.id());
            row.putUUID("player", order.player());
            row.putString("variant", order.variant().encoded());
            row.putLong("revision", order.filterRevision());
            row.putInt("remaining", order.remaining());
            row.putBoolean("uncertain", order.uncertain());
            orders.add(row);
        }
        tag.put("orders", orders);

        var residuals = new ListTag();
        var packages = new TreeMap<String, Tag>();
        fluids.residuals().forEach((variant, box) -> packages.put(variant.encoded(), box.save(registries)));
        fluids.suspendedResiduals().forEach((variant, raw) -> packages.put(variant.encoded(), raw));
        packages.forEach((variant, raw) -> {
            var row = new CompoundTag();
            row.putString("variant", variant);
            row.put("package", raw);
            residuals.add(row);
        });
        tag.put("residuals", residuals);
        return tag;
    }

    static CacheFluids read(CompoundTag tag, CacheState<?> context, HolderLookup.Provider registries) {
        fields(tag, Set.of("version", "cells", "orders", "residuals", CapacityCodec.KEY));
        require(tag, "version", Tag.TAG_INT);
        int version = tag.getInt("version");
        if (version < 1 || version > 3) {
            throw new IllegalArgumentException("Unsupported fluid storage version");
        }
        require(tag, "cells", Tag.TAG_COMPOUND);
        var cells = tag.getCompound("cells");
        fields(cells, Set.of("extent", "entries"));

        var orders = new HashMap<UUID, SupplyOrder<FluidVariantKey>>();
        var orderRows = compounds(tag, "orders");
        if (orderRows.size() + context.orders().size() > CacheState.MAX_ORDERS) {
            throw new IllegalArgumentException("Too many shared orders");
        }
        for (var entry : orderRows) {
            var row = (CompoundTag) entry;
            fields(row, Set.of("id", "player", "variant", "revision", "remaining", "uncertain"));
            require(row, "id", Tag.TAG_INT_ARRAY);
            require(row, "player", Tag.TAG_INT_ARRAY);
            require(row, "variant", Tag.TAG_STRING);
            require(row, "revision", Tag.TAG_LONG);
            require(row, "remaining", Tag.TAG_INT);
            require(row, "uncertain", Tag.TAG_BYTE);
            if (row.getByte("uncertain") != 0 && row.getByte("uncertain") != 1) {
                throw new IllegalArgumentException("Invalid fluid order flag");
            }
            var id = row.getUUID("id");
            var order = new SupplyOrder<>(
                    id,
                    row.getUUID("player"),
                    FluidVariantKey.decode(row.getString("variant"), registries),
                    row.getLong("revision"),
                    row.getInt("remaining"),
                    row.getBoolean("uncertain"));
            if (orders.put(id, order) != null) {
                throw new IllegalArgumentException("Duplicate fluid order");
            }
        }

        var state = readCells(tag, cells, context, orders, registries, version);
        var residuals = new HashMap<FluidVariantKey, ItemStack>();
        var suspended = new HashMap<FluidVariantKey, CompoundTag>();
        var seen = new HashSet<FluidVariantKey>();
        for (var entry : compounds(tag, "residuals")) {
            var row = (CompoundTag) entry;
            fields(row, Set.of("variant", "package"));
            require(row, "variant", Tag.TAG_STRING);
            require(row, "package", Tag.TAG_COMPOUND);
            var key = FluidVariantKey.decode(row.getString("variant"), registries);
            if (!seen.add(key)) {
                throw new IllegalArgumentException("Duplicate fluid residual");
            }
            var raw = row.getCompound("package");
            fields(raw, Set.of("id", "count", "components"));
            if (FluidPackages.unavailableResidual(raw)) {
                require(raw, "components", Tag.TAG_COMPOUND);
                suspended.put(key, raw);
                continue;
            }
            var box = ItemStack.parse(registries, row.getCompound("package")).orElseThrow();
            if (!box.save(registries).equals(row.getCompound("package"))) {
                throw new IllegalArgumentException("Fluid residual cannot round-trip without alteration");
            }
            if (residuals.put(key, box) != null) {
                throw new IllegalArgumentException("Duplicate fluid residual");
            }
        }
        return new CacheFluids(state, residuals, suspended);
    }

    private static ListTag compounds(CompoundTag tag, String key) {
        require(tag, key, Tag.TAG_LIST);
        var rows = (ListTag) tag.get(key);
        if (!rows.isEmpty() && rows.getElementType() != Tag.TAG_COMPOUND) {
            throw new IllegalArgumentException("Invalid fluid list: " + key);
        }
        return rows;
    }

    private static CompoundTag writeCells(CacheState<FluidVariantKey> state) {
        var result = new CompoundTag();
        result.putInt("extent", state.cells().size());
        var entries = new ListTag();
        for (int slot = 0; slot < state.cells().size(); slot++) {
            var cell = state.cells().get(slot);
            if (cell.equals(Cell.<FluidVariantKey>empty())) {
                continue;
            }
            var row = new CompoundTag();
            row.putInt("slot", slot);
            if (cell.filter() != null) {
                row.putString("filter", cell.filter().encoded());
            }
            row.putLong("amount", cell.amount());
            row.putLong("min", cell.minimum());
            row.putLong("max", cell.maximum());
            row.putLong("revision", cell.filterRevision());
            // Old min/max remain groups. Exact mB thresholds live in a separate additive column.
            var controls = new CompoundTag();
            controls.putInt("lower", cell.settings().lower());
            controls.putInt("upper", cell.settings().upper());
            controls.putInt("batch", cell.settings().batch());
            controls.putInt("return_batch", cell.settings().returnBatch());
            row.put("volume_controls", controls);
            entries.add(row);
        }
        result.put("entries", entries);
        return result;
    }

    private static CacheState<FluidVariantKey> readCells(
            CompoundTag holder,
            CompoundTag tag,
            CacheState<?> context,
            Map<UUID, SupplyOrder<FluidVariantKey>> orders,
            HolderLookup.Provider registries,
            int version) {
        require(tag, "extent", Tag.TAG_INT);
        if (tag.getInt("extent") != context.cells().size()) {
            throw new IllegalArgumentException("Fluid cell count differs from cache");
        }
        var cells = new ArrayList<Cell<FluidVariantKey>>(Collections.nCopies(context.cells().size(), Cell.empty()));
        var used = new HashSet<Integer>();
        for (var entry : compounds(tag, "entries")) {
            var row = (CompoundTag) entry;
            fields(row, version == 1
                    ? Set.of("slot", "filter", "amount", "min", "max", "revision")
                    : Set.of("slot", "filter", "amount", "min", "max", "revision", "volume_controls"));
            require(row, "slot", Tag.TAG_INT);
            for (String key : List.of("amount", "min", "max", "revision")) {
                require(row, key, Tag.TAG_LONG);
            }
            int slot = row.getInt("slot");
            if (slot < 0 || slot >= cells.size() || !used.add(slot)) {
                throw new IllegalArgumentException("Invalid or duplicate fluid slot");
            }
            if (row.contains("filter")) {
                require(row, "filter", Tag.TAG_STRING);
            }
            var key = row.contains("filter") ? FluidVariantKey.decode(row.getString("filter"), registries) : null;
            int min = Math.toIntExact(row.getLong("min"));
            int max = Math.toIntExact(row.getLong("max"));
            var settings = CellSettings.fromGroups(key, min, max);
            if (version >= 2) {
                require(row, "volume_controls", Tag.TAG_COMPOUND);
                var controls = row.getCompound("volume_controls");
                fields(controls, version >= 3
                        ? Set.of("lower", "upper", "batch", "return_batch")
                        : Set.of("lower", "upper"));
                require(controls, "lower", Tag.TAG_INT);
                require(controls, "upper", Tag.TAG_INT);
                if (version >= 3) {
                    require(controls, "batch", Tag.TAG_INT);
                    require(controls, "return_batch", Tag.TAG_INT);
                }
                settings = new CellSettings(
                        controls.getInt("lower"),
                        controls.getInt("upper"),
                        version >= 3 ? controls.getInt("batch") : 1,
                        -1,
                        version >= 3 ? controls.getInt("return_batch") : 1);
            }
            cells.set(slot, new Cell<>(key, Math.toIntExact(row.getLong("amount")), min, max, row.getLong("revision"), settings));
        }
        var state = CapacityCodec.read(holder, context.id(), context.level(), context.revision(), cells, orders);
        if (!state.capacity().equals(context.capacity())) {
            throw new IllegalArgumentException("Fluid capacity differs from cache");
        }
        return state;
    }

    private static void require(CompoundTag tag, String key, int type) {
        if (!tag.contains(key, type)) {
            throw new IllegalArgumentException("Missing or mistyped fluid field: " + key);
        }
    }

    private static void fields(CompoundTag tag, Set<String> allowed) {
        if (!allowed.containsAll(tag.getAllKeys())) {
            throw new IllegalArgumentException("Unknown fluid storage fields");
        }
    }
}
