package dev.scathiard.feedmepackages.compat.tacz;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.FmpConfig;
import dev.scathiard.feedmepackages.consumption.CraftingReservations;
import dev.scathiard.feedmepackages.consumption.MaterialTransaction;
import dev.scathiard.feedmepackages.mixin.OptionalMixins;
import dev.scathiard.feedmepackages.storage.CacheHandle;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.items.IItemHandler;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Supplier;

/** A short-lived source view used only inside native TaCZ queries/extraction. Never a global capability. */
public final class TaczMaterials {
    private TaczMaterials() {}
    private record Api(Class<?> ammo, Class<?> box, Method matches) {}
    private static Api api;
    private static boolean attempted;
    private static volatile boolean failed;
    private static Supplier<List<ItemStack>> clientSources = List::of;
    public static void clientSources(Supplier<List<ItemStack>> sources) { clientSources = Objects.requireNonNull(sources); }
    public static synchronized boolean ready() {
        if (!ModList.get().isLoaded("tacz") || !OptionalMixins.taczCompatible() || failed) return false;
        if (!attempted) {
            attempted = true;
            try {
                var ammo = Class.forName("com.tacz.guns.api.item.IAmmo");
                api = new Api(ammo, Class.forName("com.tacz.guns.api.item.IAmmoBox"),
                        ammo.getMethod("isAmmoOfGun", ItemStack.class, ItemStack.class));
            } catch (ReflectiveOperationException | LinkageError e) { unavailable(e); }
        }
        return api != null && !failed;
    }
    private static void unavailable(Throwable cause) {
        if (!failed) FeedMePackages.LOGGER.warn("FMP TaCZ material port disabled: public ammo API failed", cause);
        failed = true;
    }
    private static boolean accepts(ItemStack gun, ItemStack stack) {
        if (stack.isEmpty() || !api.ammo.isInstance(stack.getItem()) || api.box.isInstance(stack.getItem())) return false;
        try { return (boolean)api.matches.invoke(stack.getItem(), gun.copy(), stack.copy()); }
        catch (ReflectiveOperationException | LinkageError e) { unavailable(e); return false; }
    }
    /** Bounded, unreserved client hints. These never authorize or perform a debit. */
    public static List<ItemStack> clientHints(ItemStack gun) {
        if (!ready() || gun.isEmpty()) return List.of();
        return clientSources.get().stream().filter(stack -> accepts(gun, stack)).map(ItemStack::copy).toList();
    }
    public static Object append(LivingEntity actor, ItemStack gun, Object original, int requested) {
        if (!(actor instanceof Player player) || !(original instanceof IItemHandler nativeHandler)
                || requested <= 0 || gun.isEmpty() || player.isSpectator() || !ready()) return original;
        var segments = new ArrayList<Segment>(); CacheHandle handle = null;
        int slots = nativeHandler.getSlots();
        if (player instanceof ServerPlayer server) {
            if (!FmpConfig.taczAmmoEnabled() || !server.getServer().isSameThread()) return original;
            var transaction = MaterialTransaction.open(server).orElse(null);
            if (transaction == null) return original;
            handle = transaction.handle();
            for (var candidate : transaction.candidates(s -> accepts(gun, s))) {
                int amount = Math.min(requested, transaction.cacheAvailable(candidate));
                slots = append(segments, slots, candidate, amount);
            }
        } else if (player.level().isClientSide) {
            for (var candidate : clientSources.get()) if (accepts(gun, candidate))
                slots = append(segments, slots, candidate, Math.min(requested, candidate.getCount()));
        } else return original;
        return failed ? original : new Sources(player, nativeHandler, handle, segments, slots);
    }
    private static int append(List<Segment> segments, int start, ItemStack stack, int amount) {
        if (amount <= 0) return start;
        int size = stack.getMaxStackSize();
        long count = ((long)amount + size - 1) / size;
        // Native extraction takes an int; slot arithmetic must not overflow even for oversized custom magazines.
        int slots = (int)Math.min(count, (long)Integer.MAX_VALUE - start);
        if (slots > 0) segments.add(new Segment(stack.copyWithCount(1), start, slots, size,
                (int)Math.min(amount, (long)slots * size)));
        return start + slots;
    }
    private record Segment(ItemStack prototype, int start, int slots, int size, int amount) {
        int at(int slot) { return (int)Math.min(size, amount - (long)(slot - start) * size); }
    }
    private static final class Sources implements IItemHandler {
        private final Player player;
        private final IItemHandler original;
        private final CacheHandle handle;
        private final List<Segment> segments;
        private final int slots;
        private final Map<Integer, Integer> extracted = new HashMap<>();
        Sources(Player player, IItemHandler original, CacheHandle handle, List<Segment> segments, int slots) {
            this.player = player; this.original = original; this.handle = handle; this.segments = List.copyOf(segments); this.slots = slots;
        }
        @Override public int getSlots() { return slots; }
        private Segment segment(int slot) {
            if (slot < 0 || slot >= slots) throw new IndexOutOfBoundsException(slot);
            for (var segment : segments) if (slot >= segment.start && slot - segment.start < segment.slots) return segment;
            return null;
        }
        private int reserved(ItemStack stack) {
            if (!(player instanceof ServerPlayer server) || stack.isEmpty()) return 0;
            for (int i = 0; i < server.getInventory().items.size(); i++) if (server.getInventory().items.get(i) == stack)
                return CraftingReservations.reservedInventory(server, i);
            return 0;
        }
        private MaterialTransaction transaction() {
            if (!(player instanceof ServerPlayer server) || !FmpConfig.taczAmmoEnabled() || !ready()) return null;
            var tx = MaterialTransaction.open(server).orElse(null);
            return tx != null && tx.handle().equals(handle) ? tx : null;
        }
        private int remaining(int slot, Segment segment) { return Math.max(0, segment.at(slot) - extracted.getOrDefault(slot, 0)); }
        @Override public ItemStack getStackInSlot(int slot) {
            var segment = segment(slot);
            if (segment == null) {
                var stack = original.getStackInSlot(slot); int reserve = reserved(stack);
                // Native ammo boxes are mutated in-place by TaCZ; never return a detached box for consumption.
                if (reserve <= 0) return stack;
                if (api.box.isInstance(stack.getItem())) return ItemStack.EMPTY;
                return stack.copyWithCount(Math.max(0, stack.getCount() - reserve));
            }
            int amount = remaining(slot, segment);
            if (player instanceof ServerPlayer) {
                var tx = transaction(); amount = tx == null ? 0 : Math.min(amount, tx.cacheAvailable(segment.prototype));
            }
            return segment.prototype.copyWithCount(amount);
        }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (amount <= 0) return ItemStack.EMPTY;
            var segment = segment(slot);
            if (segment == null) {
                var stack = original.getStackInSlot(slot);
                return original.extractItem(slot, Math.min(amount, Math.max(0, stack.getCount() - reserved(stack))), simulate);
            }
            // Client stacks are only query hints; they can never become extracted inventory.
            var tx = transaction(); if (tx == null) return ItemStack.EMPTY;
            int take = Math.min(amount, Math.min(remaining(slot, segment), tx.cacheAvailable(segment.prototype)));
            if (take <= 0 || (!simulate && (!tx.takeCache(segment.prototype, take) || !tx.commit()))) return ItemStack.EMPTY;
            if (!simulate) extracted.merge(slot, take, Integer::sum);
            return segment.prototype.copyWithCount(take);
        }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return segment(slot) == null ? original.insertItem(slot, stack, simulate) : stack;
        }
        @Override public int getSlotLimit(int slot) {
            var segment = segment(slot); return segment == null ? original.getSlotLimit(slot) : segment.size;
        }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return segment(slot) == null && original.isItemValid(slot, stack); }
    }
}
