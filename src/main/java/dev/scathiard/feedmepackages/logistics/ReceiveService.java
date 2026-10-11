package dev.scathiard.feedmepackages.logistics;

import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.interaction.InventoryTransfer;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** Takes ownership only after a complete simulation. A false return leaves the source untouched.
 *  A residual is owned per exact filter variant (one per item cell), so a package for one item is never
 *  blocked by a pending residual belonging to a different item. */
public final class ReceiveService {
    private ReceiveService() {}
    private static final Map<ServerPlayer, UUID> WARNED_RESIDUALS = new WeakHashMap<>();
    private record Plan(CacheRecord replacement) {}

    private static Plan simulate(LivingEntity player, CacheHandle handle, ItemStack box, boolean residual) {
        if (box.has(FmpRegistries.FLUID_PARCEL_SEAL.get())) {
            var candidate = dev.scathiard.feedmepackages.fluid.FluidReceipt.simulate(player, handle, box, residual);
            return candidate == null ? null : new Plan(candidate);
        }
        var seal = box.get(FmpRegistries.PARCEL_SEAL.get());
        if (seal == null) return null;
        if (dev.scathiard.feedmepackages.compat.fluidlogistics.FluidPackages.isFamily(box)) return null;
        // A residual is already owned by this cache; its next lawful holder may resume it.
        // External handoffs still require the original recipient, including arrived parcels.
        if (residual ? !seal.arrived() : !seal.playerId().equals(player.getUUID())) return null;
        CacheLedger ledger = CacheLedger.get(player.getServer());
        if (!ParcelAuthentication.valid(ledger, player.registryAccess(), box, seal)) return null;
        var record = ledger.find(handle.cacheId()); var state = record.state();
        ItemVariantKey requested;
        try { requested = ItemVariantKey.decode(seal.variant(), player.registryAccess()); }
        catch (IllegalArgumentException invalidVariant) { return null; }
        var target = ledger.parcelTarget(seal.cacheId(), requested, seal.filterRevision());
        if (target == null || !target.cacheId().equals(handle.cacheId())) return null;
        ParcelIntake.Plan intake;
        try { intake = ParcelIntake.simulate(state, box, requested, seal.requestId(), seal.arrived(), player.registryAccess()); }
        catch (IllegalArgumentException rejectedContents) { return null; }
        boolean anyRemainder = !intake.remainder().isEmpty();
        // Each cell holds at most one residual. A fresh parcel for the same variant is refused only when that
        // cell is already busy; a parcel for a different variant is received while another cell's residual pends.
        if (!residual && anyRemainder && !record.residual(requested).isEmpty()) return null;
        if (residual && intake.moved() == 0) return null;
        if (!residual && seal.arrived() && intake.moved() == 0) return null;
        var nextResiduals = new HashMap<>(record.residuals());
        if (anyRemainder) {
            var remaining = intake.remainder();
            var arrivedSeal = new ParcelSeal(seal.parcelId(), target.cacheId(), seal.playerId(), seal.requestId(), seal.variant(),
                    target.revision(), seal.address(), true, "");
            ParcelAuthentication.apply(ledger, player.registryAccess(), remaining, arrivedSeal);
            nextResiduals.put(requested, remaining);
        } else if (residual) {
            nextResiduals.remove(requested);
        }
        return new Plan(record.withState(intake.state()).withResidual(requested, nextResiduals.getOrDefault(requested, ItemStack.EMPTY)));
    }

    public static boolean receive(ServerPlayer player, ItemStack source) {
        return receive(LogisticsEndpoint.player(player), source);
    }
    public static boolean receive(LogisticsEndpoint endpoint, ItemStack source) {
        var player = endpoint.entity();
        if (source.isEmpty() || !source.has(FmpRegistries.PARCEL_SEAL.get()) && !source.has(FmpRegistries.FLUID_PARCEL_SEAL.get())) return false;
        var access = endpoint.access(); if (!access.active()) return false;
        if (dev.scathiard.feedmepackages.consumption.CraftingReservations.operating(access.handle().cacheId())
                || dev.scathiard.feedmepackages.interaction.FluidActions.operating(access.handle().cacheId())) return false;
        var ledger = CacheLedger.get(player.getServer()); var before = ledger.find(access.handle().cacheId());
        Plan plan = simulate(player, access.handle(), source, false); if (plan == null) return false;
        var again = endpoint.access();
        if (!again.active() || !again.handle().equals(access.handle()) || ledger.find(access.handle().cacheId()) != before) return false;
        // No callbacks between the state replacement and relinquishing the original stack.
        ledger.replace(access.handle(), before.state().revision(), plan.replacement()); source.setCount(0);
        LogisticsSignals.received(before.state(), plan.replacement().state());
        LogisticsSignals.received(before.fluids().state(), plan.replacement().fluids().state());
        if (player instanceof ServerPlayer serverPlayer) serverPlayer.getInventory().setChanged();
        return true;
    }

    public static void resume(ServerPlayer player) {
        resume(LogisticsEndpoint.player(player));
    }
    public static void resume(LogisticsEndpoint endpoint) {
        var player = endpoint.entity();
        var access = endpoint.access(); if (!access.active()) return;
        if (dev.scathiard.feedmepackages.consumption.CraftingReservations.operating(access.handle().cacheId())
                || dev.scathiard.feedmepackages.interaction.FluidActions.operating(access.handle().cacheId())) return;
        var ledger = CacheLedger.get(player.getServer());
        if (ledger.find(access.handle().cacheId()).anyResidual()) {
            // Resume every cell's residual independently; different cells never contend.
            for (var entry : List.copyOf(ledger.find(access.handle().cacheId()).residuals().entrySet())) {
                var record = ledger.find(access.handle().cacheId());
                var plan = simulate(player, access.handle(), entry.getValue(), true);
                if (plan != null) {
                    ledger.replace(access.handle(), record.state().revision(), plan.replacement());
                    LogisticsSignals.received(record.state(), plan.replacement().state());
                }
            }
            for (var entry : List.copyOf(ledger.find(access.handle().cacheId()).fluids().residuals().entrySet())) {
                var record = ledger.find(access.handle().cacheId());
                var plan = simulate(player, access.handle(), entry.getValue(), true);
                if (plan != null) {
                    ledger.replace(access.handle(), record.state().revision(), plan.replacement());
                    LogisticsSignals.received(record.fluids().state(), plan.replacement().fluids().state());
                }
            }
            if (player instanceof ServerPlayer serverPlayer) returnUnmatchedResidual(serverPlayer, access.handle());
        } else WARNED_RESIDUALS.remove(player);
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        // Bounded original inventory + offhand; no hidden slots, no recursion into backpacks.
        for (int slot = 0; slot < serverPlayer.getInventory().getContainerSize(); slot++) {
            ItemStack stack = serverPlayer.getInventory().getItem(slot);
            if (receive(endpoint, stack)) serverPlayer.getInventory().setItem(slot, ItemStack.EMPTY);
        }
    }

    /** Hand back a genuine unusable remainder only when one whole native parcel has a safe destination. */
    private static void returnUnmatchedResidual(ServerPlayer player, CacheHandle handle) {
        var ledger = CacheLedger.get(player.getServer()); var record = ledger.find(handle.cacheId());
        if (!record.anyResidual()) { WARNED_RESIDUALS.remove(player); return; }
        for (var entry : List.copyOf(record.residuals().entrySet())) {
            ItemVariantKey variant = entry.getKey(); ItemStack box = entry.getValue();
            var seal = box.get(FmpRegistries.PARCEL_SEAL.get());
            if (seal == null || !seal.arrived() || !ParcelAuthentication.valid(ledger, player.registryAccess(), box, seal)) {
                if (!handle.cacheId().equals(WARNED_RESIDUALS.put(player, handle.cacheId())))
                    dev.scathiard.feedmepackages.FeedMePackages.LOGGER.warn("Retained unverifiable residual in cache {}; no items moved. Inspect the cache ledger before repairing data.", handle.cacheId());
                continue;
            }
            if (!unmatched(player, handle.cacheId(), variant, seal)) continue;
            WARNED_RESIDUALS.remove(player);
            var current = ledger.find(handle.cacheId());
            var inventory = InventoryTransfer.insert(player.getInventory(), box, box.getCount());
            if (inventory.moved() != box.getCount() || !inventory.stillValid(player.getInventory())) continue;
            ledger.replace(handle, current.state().revision(), current.withResidual(variant, ItemStack.EMPTY));
            inventory.commit(player.getInventory());
            player.containerMenu.broadcastChanges();
        }
        for (var entry : List.copyOf(ledger.find(handle.cacheId()).fluids().residuals().entrySet())) {
            var box = entry.getValue(); var seal = box.get(FmpRegistries.FLUID_PARCEL_SEAL.get());
            if (seal == null || !seal.arrived() || !ParcelAuthentication.validFluid(ledger, player.registryAccess(), box, seal)) {
                if (!handle.cacheId().equals(WARNED_RESIDUALS.put(player, handle.cacheId())))
                    dev.scathiard.feedmepackages.FeedMePackages.LOGGER.warn("Retained unverifiable fluid residual in cache {}; nothing moved", handle.cacheId());
                continue;
            }
            var target = ledger.fluidParcelTarget(seal.cacheId(), entry.getKey(), seal.filterRevision());
            if (target != null && target.cacheId().equals(handle.cacheId())) continue;
            var current = ledger.find(handle.cacheId());
            var inventory = InventoryTransfer.insert(player.getInventory(), box, 1);
            if (inventory.moved() != 1 || !inventory.stillValid(player.getInventory())) continue;
            ledger.replace(handle, current.state().revision(), current.withFluids(current.fluids().withResidual(entry.getKey(), ItemStack.EMPTY)));
            inventory.commit(player.getInventory()); player.containerMenu.broadcastChanges();
        }
    }

    /** A residual is unroutable if its own cell no longer exists or any of its contents has no matching cell. */
    private static boolean unmatched(ServerPlayer player, UUID cacheId, ItemVariantKey variant, ParcelSeal seal) {
        var ledger = CacheLedger.get(player.getServer());
        var target = ledger.parcelTarget(seal.cacheId(), variant, seal.filterRevision());
        if (target == null || !target.cacheId().equals(cacheId)) return true;
        var record = ledger.find(cacheId); var box = record.residual(variant);
        if (box.isEmpty()) return false;
        var contents = com.simibubi.create.content.logistics.box.PackageItem.getContents(box);
        for (int slot = 0; slot < contents.getSlots(); slot++) {
            var item = contents.getStackInSlot(slot); if (item.isEmpty()) continue;
            try { if (record.state().find(ItemVariantKey.of(item, player.registryAccess())) < 0) return true; }
            catch (IllegalArgumentException unsupported) { return true; }
        }
        return false;
    }
}
