package dev.scathiard.feedmepackages.storage;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import top.theillusivec4.curios.api.CuriosApi;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** One-time legacy delivery. A durable receipt bridges world data and the player's separate save file. */
public final class LegacyRetirement {
    private LegacyRetirement() {}
    private static final Set<ServerPlayer> FAILED = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<ServerPlayer> WAITING = Collections.newSetFromMap(new WeakHashMap<>());
    public enum Outcome { NONE, WAITING_FOR_SPACE, TRANSFERRED, RECOVERED }
    public interface Persistence {
        void ledger(CacheLedger ledger);
        void player(ServerPlayer player, ItemStack replacement);
    }
    private record Slot(Supplier<ItemStack> get, Consumer<ItemStack> set) {}

    public static void tick(ServerPlayer player) {
        if (player.tickCount % 20 != 0 || FAILED.contains(player)
                || player instanceof net.neoforged.neoforge.common.util.FakePlayer) return;
        try {
            var result=transfer(player, durable(player));
            if(result==Outcome.WAITING_FOR_SPACE) {
                if(WAITING.add(player)) player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.create_feed_me_packages.retirement.waiting"),false);
            } else {
                WAITING.remove(player);
                if(result==Outcome.TRANSFERRED || result==Outcome.RECOVERED)
                    player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.create_feed_me_packages.retirement.done"),false);
            }
        }
        catch (RuntimeException failed) {
            // Never issue another pendant after an unknown save outcome within this session.
            FAILED.add(player);
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.create_feed_me_packages.retirement.failed"),false);
            FeedMePackages.LOGGER.error("FMP legacy delivery paused for {}; saved data retained", player.getUUID(), failed);
        }
    }

    public static Outcome transfer(ServerPlayer player, Persistence persistence) {
        try { return perform(player, persistence); }
        catch (RuntimeException failure) { CacheLedger.get(player.getServer()).interruptRetirement(player.getUUID()); throw failure; }
    }
    private static Outcome perform(ServerPlayer player, Persistence persistence) {
        if (!player.getServer().isSameThread() || !player.isAlive() || player.isSpectator()
                || player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()) return Outcome.NONE;
        var ledger = CacheLedger.get(player.getServer()); if (!ledger.problem().isEmpty()) return Outcome.NONE;
        var slots = slots(player); var transfer = ledger.retirement(player.getUUID());
        boolean pending = transfer != null && !transfer.completed();
        if (ledger.personal(player.getUUID()) != null || pending) {
            Slot destination = null; ItemStack replacement = null; boolean recovered = false;
            if (pending) {
                for (var slot : slots) {
                    var stack = slot.get().get();
                    if (stack.is(FmpRegistries.PENDANT.get()) && transfer.receipt().equals(stack.get(FmpRegistries.RETIREMENT_RECEIPT.get()))) {
                        if (destination != null || stack.getCount() != 1 || !transfer.cache().equals(stack.get(FmpRegistries.IDENTITY.get())))
                            throw new IllegalStateException("Conflicting legacy delivery receipts");
                        destination = slot; replacement = stack; recovered = true;
                    }
                }
            }
            if (destination == null) {
                for (var slot : slots) {
                    var stack = slot.get().get();
                    UUID owner = stack.get(FmpRegistries.OWNER.get());
                    if (stack.is(FmpRegistries.PERSONAL_PENDANT.get()) && stack.getCount() == 1
                            && (owner == null || owner.equals(player.getUUID()))) { destination = slot; break; }
                }
                if (destination == null) for (int i = 0; i < 36; i++) {
                    if (player.getInventory().getItem(i).isEmpty()) { destination = slots.get(i); break; }
                }
                if (destination == null) return Outcome.WAITING_FOR_SPACE;
                transfer = pending ? ledger.renewPendingReceipt(player.getUUID()) : ledger.beginRetirement(player.getUUID());
                persistence.ledger(ledger); // Receipt must exist on disk before its item can exist.
                replacement = ordinary(destination.get().get());
                replacement.set(FmpRegistries.IDENTITY.get(), transfer.cache());
                replacement.set(FmpRegistries.RETIREMENT_RECEIPT.get(), transfer.receipt());
                destination.set().accept(replacement);
            }
            persistence.player(player, replacement); // Silent native save failures are detected by the durable adapter.
            if (destination.get().get() != replacement) throw new IllegalStateException("Delivery slot changed during save");
            ledger.completeRetirement(player.getUUID(), transfer.receipt());
            persistence.ledger(ledger);
            ledger.resumeRetirement(player.getUUID());
            // Keep the receipt as a bearer credential, so revoked pre-personalization copies never regain access.
            clearOldKeys(slots); player.inventoryMenu.broadcastChanges();
            return recovered ? Outcome.RECOVERED : Outcome.TRANSFERRED;
        }
        clearOldKeys(slots);
        return Outcome.NONE;
    }

    private static ItemStack ordinary(ItemStack old) {
        ItemStack result = old.isEmpty() ? FmpRegistries.PENDANT.toStack() : old.transmuteCopy(FmpRegistries.PENDANT.get(), 1);
        result.remove(FmpRegistries.IDENTITY.get()); result.remove(FmpRegistries.OWNER.get());
        result.remove(FmpRegistries.OWNER_NAME.get()); result.remove(FmpRegistries.PREVIEW.get());
        result.remove(FmpRegistries.RETIREMENT_RECEIPT.get());
        return result;
    }
    private static void clearOldKeys(List<Slot> slots) {
        for (var slot : slots) {
            var old = slot.get().get();
            if (old.is(FmpRegistries.PERSONAL_PENDANT.get()) && old.getCount() == 1) slot.set().accept(ordinary(old));
        }
    }
    private static List<Slot> slots(ServerPlayer player) {
        var result = new ArrayList<Slot>(); var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            final int slot = i; result.add(new Slot(() -> inventory.getItem(slot), stack -> inventory.setItem(slot, stack)));
        }
        CuriosApi.getCuriosInventory(player).ifPresent(curios -> curios.getCurios().values().forEach(handler -> {
            for (var stacks : List.of(handler.getStacks(), handler.getCosmeticStacks())) for (int i = 0; i < stacks.getSlots(); i++) {
                final int slot = i; result.add(new Slot(() -> stacks.getStackInSlot(slot), stack -> stacks.setStackInSlot(slot, stack)));
            }
        }));
        return result;
    }

    private static Persistence durable(ServerPlayer player) {
        var server = player.getServer(); Path world = server.getWorldPath(LevelResource.ROOT);
        return new Persistence() {
            public void ledger(CacheLedger ledger) {
                var expected = ledger.save(new CompoundTag(), player.registryAccess());
                server.overworld().getDataStorage().save();
                net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
                if (!read(world.resolve("data").resolve(CacheLedger.NAME + ".dat")).getCompound("data").equals(expected))
                    throw new IllegalStateException("Legacy ledger checkpoint did not reach disk");
            }
            public void player(ServerPlayer owner, ItemStack replacement) {
                // Also updates level.dat's Player in integrated worlds; saving only playerdata is insufficient there.
                server.saveEverything(true, true, true);
                net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
                CompoundTag saved = server.isSingleplayerOwner(owner.getGameProfile())
                        ? read(world.resolve("level.dat")).getCompound("Data").getCompound("Player")
                        : read(world.resolve("playerdata").resolve(owner.getStringUUID() + ".dat"));
                if (count(saved, (CompoundTag) replacement.save(owner.registryAccess())) != 1)
                    throw new IllegalStateException("Legacy item checkpoint did not reach disk exactly once");
            }
        };
    }
    private static CompoundTag read(Path path) {
        try { return NbtIo.readCompressed(path, NbtAccounter.create(64L * 1024 * 1024)); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot verify legacy checkpoint", e); }
    }
    private static int count(Tag tag, CompoundTag expected) {
        if (tag instanceof CompoundTag compound) {
            if (expected.getAllKeys().stream().allMatch(key -> Objects.equals(expected.get(key), compound.get(key)))) return 1;
            int result = 0; for (String key : compound.getAllKeys()) result = Math.addExact(result, count(compound.get(key), expected));
            return result;
        }
        if (tag instanceof ListTag list) { int result = 0; for (Tag child : list) result = Math.addExact(result, count(child, expected)); return result; }
        return 0;
    }
}
