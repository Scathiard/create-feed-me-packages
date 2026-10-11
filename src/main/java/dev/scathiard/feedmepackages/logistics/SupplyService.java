package dev.scathiard.feedmepackages.logistics;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.domain.CacheState;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import dev.scathiard.feedmepackages.storage.CacheHandle;
import net.minecraft.server.level.ServerPlayer;
import java.util.UUID;

public final class SupplyService {
    private SupplyService() {}
    public static String address(ServerPlayer player) { return "FMP@" + player.getGameProfile().getName(); }

    public static void tick(ServerPlayer player) { tick(LogisticsEndpoint.player(player)); }
    public static void tick(LogisticsEndpoint endpoint) {
        var player = endpoint.entity(); var access = endpoint.access();
        if (access.active() && (dev.scathiard.feedmepackages.consumption.CraftingReservations.operating(access.handle().cacheId())
                || dev.scathiard.feedmepackages.interaction.FluidActions.operating(access.handle().cacheId()))) return;
        ReceiveService.resume(endpoint);
        if (player.tickCount % 20 != 0) return;
        access = endpoint.access();
        if (!access.active() || access.handle().networkId() == null || !endpoint.mayRequest(access.handle().networkId())) return;
        var handle = access.handle(); var ledger = CacheLedger.get(player.getServer());
        int slots = ledger.find(handle.cacheId()).state().cells().size();
        for (int slot = 0; slot < slots; slot++) {
            var record = ledger.find(handle.cacheId());
            if (record.fluids().state().cells().get(slot).filter() != null) request(endpoint, handle, slot, true);
            else request(endpoint, handle, slot, false);
        }
    }

    static int request(LogisticsEndpoint endpoint, CacheHandle handle, int slot, boolean fluid) {
        var player = endpoint.entity();
        var access = endpoint.access();
        if (!access.active() || !handle.equals(access.handle()) || handle.networkId() == null
                || !endpoint.mayRequest(handle.networkId())) return 0;
        var ledger = CacheLedger.get(player.getServer());
        var before = ledger.find(handle.cacheId()); var state = fluid ? before.fluids().state() : before.state();
        if (slot < 0 || slot >= state.cells().size() || state.cells().get(slot).filter() == null) return 0;
        net.minecraft.world.item.ItemStack template; String encoded;
        if (fluid) {
            var key = before.fluids().state().cells().get(slot).filter();
            if (before.fluids().hasResidual(key)) return 0;
            template = dev.scathiard.feedmepackages.compat.fluidlogistics.FluidPackages.requestKey(key.stack(player.registryAccess(), 1));
            encoded = key.encoded();
        } else {
            var key = before.state().cells().get(slot).filter();
            if (!before.residual(key).isEmpty()) return 0;
            template = key.stack(player.registryAccess(), 1); encoded = key.encoded();
        }
        if (template.isEmpty()) return 0;
        int wanted = state.requestable(slot);
        if (wanted == 0 || before.state().orders().size() + before.fluids().state().orders().size() >= CacheState.MAX_ORDERS) return 0;
        var cell = state.cells().get(slot);
        PackageDispatch.Plan requests; long plannedTotal;
        try {
            requests = PackageDispatch.prepare(handle.networkId(), template, wanted, endpoint.address(), fluid);
            if (requests == null) return 0;
            plannedTotal = requests.remaining();
        } catch (RuntimeException unavailable) {
            FeedMePackages.LOGGER.warn("FMP request planning failed before dispatch; no reservation was created", unavailable);
            return 0;
        }
        if (plannedTotal < cell.settings().batch() || plannedTotal > wanted) return 0;
        int planned = (int) plannedTotal;
        access = endpoint.access();
        if (!access.active() || !handle.equals(access.handle()) || ledger.find(handle.cacheId()) != before
                || !endpoint.mayRequest(handle.networkId())) return 0;
        UUID requestId = UUID.randomUUID();
        dev.scathiard.feedmepackages.storage.CacheRecord reserved;
        if (fluid) {
            var edit = before.fluids().state().edit(); edit.request(slot, requestId, player.getUUID(), planned, true);
            reserved = before.withFluids(before.fluids().withState(edit.finish()));
        } else {
            var edit = before.state().edit(); edit.request(slot, requestId, player.getUUID(), planned, true);
            reserved = before.withState(edit.finish());
        }
        ledger.replace(access.handle(), state.revision(), reserved);
        var route = new ParcelSeal(UUID.randomUUID(), state.id(), player.getUUID(), requestId,
                encoded, cell.filterRevision(), endpoint.address(), false, "");
        try (var scope = fluid ? DispatchScope.fluid(ledger, player.registryAccess(), route) : new DispatchScope(ledger, player.registryAccess(), route)) {
            requests.send();
            long unsentTotal = requests.remaining();
            if (unsentTotal < 0 || unsentTotal > planned || fluid && scope.fluidUnits() != planned - unsentTotal)
                throw new IllegalStateException("Native dispatch did not agree with its sealed output");
            int unsent = (int) unsentTotal;
            var current = ledger.find(state.id()); dev.scathiard.feedmepackages.storage.CacheRecord confirmed;
            if (fluid) {
                var edit = current.fluids().state().edit(); edit.confirmDispatch(requestId, unsent);
                confirmed = current.withFluids(current.fluids().withState(edit.finish()));
            } else {
                var edit = current.state().edit(); edit.confirmDispatch(requestId, unsent);
                confirmed = current.withState(edit.finish());
            }
            ledger.replace(access.handle(), current.state().revision(), confirmed);
            if (planned > unsent)
                LogisticsSignals.publish(state.id(), slot, dev.scathiard.feedmepackages.domain.LogisticsSignal.Kind.REQUEST);
            return planned - unsent;
        } catch (RuntimeException unknownOutcome) {
            LogisticsSignals.publish(state.id(), slot, dev.scathiard.feedmepackages.domain.LogisticsSignal.Kind.REQUEST);
            // Create may have produced real boxes already. Keep the reservation; never compensate/retry automatically.
            FeedMePackages.LOGGER.error("FMP dispatch outcome uncertain for request {}; manual reset may be needed", requestId, unknownOutcome);
            return planned;
        }
    }
}
