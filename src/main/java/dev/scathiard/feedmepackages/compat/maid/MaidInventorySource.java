package dev.scathiard.feedmepackages.compat.maid;

import dev.scathiard.feedmepackages.consumption.AmmoService;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.items.wrapper.CombinedInvWrapper;
import java.util.*;
import java.util.function.*;

/** Local view for the native meal/bow/ammo method. It cannot be inserted into, saved, or globally discovered. */
public final class MaidInventorySource extends CombinedInvWrapper {
    private final IItemHandler original;
    private final CacheSlots cache;
    private MaidInventorySource(IItemHandler original, CacheSlots cache) {
        super(new NativeSlots(original), cache); this.original = original; this.cache = cache;
    }
    public static CombinedInvWrapper meal(LivingEntity maid, CombinedInvWrapper original) {
        var source = MaidMaterialSource.open(maid);
        return source == null ? original : new MaidInventorySource(original,
                new CacheSlots(source, s -> s.getFoodProperties(maid) != null, 1, true));
    }
    public static CombinedInvWrapper bow(LivingEntity maid, CombinedInvWrapper original) {
        var source = MaidMaterialSource.open(maid);
        return source == null ? original : new MaidInventorySource(original,
                new CacheSlots(source, s -> s.getItem() instanceof ArrowItem, 64, false));
    }
    public static IItemHandler ammo(LivingEntity maid, IItemHandler original, Predicate<ItemStack> accepts, int requested) {
        var source = MaidMaterialSource.open(maid);
        return source == null ? original : new MaidInventorySource(original, new CacheSlots(source, accepts, requested, false));
    }
    /** Preserve external shortage-event providers and their real inventory before consulting cache slots. */
    public static int find(IItemHandler inventory, Predicate<ItemStack> accepts,
                           BiFunction<IItemHandler, Predicate<ItemStack>, Integer> nativeFind) {
        if (!(inventory instanceof MaidInventorySource source)) return nativeFind.apply(inventory, accepts);
        int slot = nativeFind.apply(source.original, accepts);
        return slot >= 0 ? slot : nativeFind.apply(source, accepts);
    }
    public void validateFreeArrow() {
        if (!cache.spent && cache.exposed != null && cache.source.available(cache.exposed) <= 0) throw new AmmoService.Unavailable();
    }
    private record Segment(ItemStack item, int start, int slots, int size, int amount) {
        int at(int slot) { return (int)Math.min(size, amount - (long)(slot - start) * size); }
    }
    private static final class CacheSlots implements IItemHandlerModifiable {
        private final MaidMaterialSource source;
        private final List<Segment> segments = new ArrayList<>();
        private final Map<Integer, Integer> taken = new HashMap<>(), shown = new HashMap<>();
        private final boolean meal;
        private int slots;
        private boolean spent;
        private ItemStack exposed;
        CacheSlots(MaidMaterialSource source, Predicate<ItemStack> accepts, int maximum, boolean meal) {
            this.source = source; this.meal = meal;
            for (var item : source.candidates(accepts)) {
                int amount = Math.min(maximum, source.available(item)), size = meal ? 1 : item.getMaxStackSize();
                if (amount <= 0) continue;
                int count = (int)Math.min(((long)amount + size - 1) / size, Integer.MAX_VALUE - (long)slots);
                if (count <= 0) break;
                segments.add(new Segment(item.copyWithCount(1), slots, count, size, amount)); slots += count;
            }
        }
        private Segment segment(int slot) {
            if (slot < 0 || slot >= slots) throw new IndexOutOfBoundsException(slot);
            for (var segment : segments) if (slot >= segment.start && slot - segment.start < segment.slots) return segment;
            throw new IndexOutOfBoundsException(slot);
        }
        private int available(int slot, Segment segment) {
            return Math.min(Math.max(0, segment.at(slot) - taken.getOrDefault(slot, 0)), source.available(segment.item));
        }
        @Override public int getSlots() { return slots; }
        @Override public ItemStack getStackInSlot(int slot) {
            var segment = segment(slot); int amount = available(slot, segment);
            shown.put(slot, amount); exposed = segment.item;
            return segment.item.copyWithCount(amount);
        }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
            var segment = segment(slot); int count = Math.min(Math.max(0, amount), available(slot, segment));
            if (count <= 0 || !simulate && !source.take(segment.item, count)) {
                if (meal && !simulate) throw new AmmoService.Unavailable();
                return ItemStack.EMPTY;
            }
            if (!simulate) { taken.merge(slot, count, Integer::sum); spent = true; }
            return segment.item.copyWithCount(count);
        }
        @Override public void setStackInSlot(int slot, ItemStack remainder) {
            var segment = segment(slot); Integer before = shown.remove(slot);
            if (before == null || !remainder.isEmpty() && !ItemStack.isSameItemSameComponents(segment.item, remainder))
                throw new AmmoService.Unavailable();
            int count = before - remainder.getCount();
            if (count < 0 || count > available(slot, segment) || count > 0 && !source.take(segment.item, count))
                throw new AmmoService.Unavailable();
            if (count > 0) { taken.merge(slot, count, Integer::sum); spent = true; }
        }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { segment(slot); return stack; }
        @Override public int getSlotLimit(int slot) { return segment(slot).size; }
        @Override public boolean isItemValid(int slot, ItemStack stack) { segment(slot); return false; }
    }
    private record NativeSlots(IItemHandler original) implements IItemHandlerModifiable {
        public int getSlots() { return original.getSlots(); }
        public ItemStack getStackInSlot(int slot) { return original.getStackInSlot(slot); }
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return original.insertItem(slot, stack, simulate); }
        public ItemStack extractItem(int slot, int amount, boolean simulate) { return original.extractItem(slot, amount, simulate); }
        public int getSlotLimit(int slot) { return original.getSlotLimit(slot); }
        public boolean isItemValid(int slot, ItemStack stack) { return original.isItemValid(slot, stack); }
        public void setStackInSlot(int slot, ItemStack stack) {
            if (!(original instanceof IItemHandlerModifiable modifiable)) throw new IllegalStateException("Native source is not writable");
            modifiable.setStackInSlot(slot, stack);
        }
    }
}
