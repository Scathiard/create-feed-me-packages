package dev.scathiard.feedmepackages.fluid;

import dev.scathiard.feedmepackages.logistics.ParcelAuthentication;
import dev.scathiard.feedmepackages.logistics.ParcelSeal;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.storage.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** Authentication and per-cell receipt ownership. ReceiveService owns the single publication boundary. */
public final class FluidReceipt {
    private FluidReceipt() {}
    public static CacheRecord simulate(LivingEntity player, CacheHandle handle, ItemStack box, boolean residual) {
        var seal = box.get(FmpRegistries.FLUID_PARCEL_SEAL.get());
        if (seal == null || (residual ? !seal.arrived() : !seal.playerId().equals(player.getUUID()))) return null;
        var ledger = CacheLedger.get(player.getServer());
        if (!ParcelAuthentication.validFluid(ledger, player.registryAccess(), box, seal)) return null;
        FluidVariantKey requested;
        try { requested = FluidVariantKey.decode(seal.variant(), player.registryAccess()); }
        catch (IllegalArgumentException invalid) { return null; }
        var target = ledger.fluidParcelTarget(seal.cacheId(), requested, seal.filterRevision());
        if (target == null || !target.cacheId().equals(handle.cacheId())) return null;
        var record = ledger.find(handle.cacheId()); FluidParcelIntake.Plan intake;
        try {
            intake = FluidParcelIntake.simulate(record.fluids().state(), box, requested, seal.requestId(), seal.arrived(),
                    player.registryAccess());
        } catch (IllegalArgumentException rejected) { return null; }
        var remaining = intake.remainder();
        if (!residual && !remaining.isEmpty() && record.fluids().hasResidual(requested)) return null;
        if ((residual || seal.arrived()) && intake.moved() == 0) return null;
        var fluids = record.fluids().withState(intake.state());
        if (!remaining.isEmpty()) {
            ParcelAuthentication.applyFluid(ledger, player.registryAccess(), remaining, new ParcelSeal(seal.parcelId(),
                    target.cacheId(), seal.playerId(), seal.requestId(), seal.variant(), target.revision(), seal.address(), true, ""));
            fluids = fluids.withResidual(requested, remaining);
        } else if (residual) fluids = fluids.withResidual(requested, ItemStack.EMPTY);
        return record.withFluids(fluids);
    }
}
