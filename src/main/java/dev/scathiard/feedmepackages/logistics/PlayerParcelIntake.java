package dev.scathiard.feedmepackages.logistics;

import com.simibubi.create.content.logistics.box.PackageItem;
import dev.scathiard.feedmepackages.compat.fluidlogistics.FluidPackages;
import dev.scathiard.feedmepackages.domain.CacheState;
import dev.scathiard.feedmepackages.domain.MaterialVariant;
import dev.scathiard.feedmepackages.fluid.FluidParcelIntake;
import dev.scathiard.feedmepackages.fluid.FluidVariantKey;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.service.CachePolicy;
import dev.scathiard.feedmepackages.storage.CacheRecord;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Player receipt policy, independent of how a native parcel reached the inventory.
 * All contents must qualify before any goods move. Existing cache-owned residuals bypass this gate. */
final class PlayerParcelIntake {
    private PlayerParcelIntake() {}
    record Plan(CacheRecord record, ItemStack remainder) {}

    static boolean eligible(CacheRecord record, ItemStack box, HolderLookup.Provider registries) {
        try {
            if (FluidPackages.isFamily(box)) {
                var fluid = FluidPackages.read(box);
                if (fluid.isEmpty()) return false;
                var key = FluidVariantKey.of(fluid, registries);
                return CachePolicy.admits(key, registries) && belowLimits(record.fluids().state(), key);
            }
            if (!ParcelEnvelope.items(box)) return false;
            var contents = PackageItem.getContents(box);
            boolean any = false;
            for (int index = 0; index < contents.getSlots(); index++) {
                var stack = contents.getStackInSlot(index);
                if (stack.isEmpty()) continue;
                if (stack.getCount() > stack.getMaxStackSize()) return false;
                var key = ItemVariantKey.of(stack, registries);
                if (!CachePolicy.admits(key, registries) || !belowLimits(record.state(), key)) return false;
                any = true;
            }
            return any;
        } catch (IllegalArgumentException unsupported) {
            return false;
        }
    }

    private static <K extends MaterialVariant> boolean belowLimits(CacheState<K> state, K key) {
        int slot = state.find(key);
        if (!state.writable(slot)) return false;
        var cell = state.cells().get(slot);
        return cell.amount() < state.capacity().items(key)
                && (cell.settings().upper() < 0 || cell.amount() < cell.settings().upper());
    }

    /** Ordinary parcels carry goods, not proof of an FMP order. Never settle pending here.
     * The caller has checked eligibility; the return line is not an intake quantity cap. */
    static Plan ordinary(ServerPlayer player, CacheRecord record, ItemStack box) {
        if (!PackageItem.getAddress(box).equals(SupplyService.address(player))) return null;
        if (FluidPackages.isFamily(box)) {
            var key = FluidVariantKey.of(FluidPackages.read(box), player.registryAccess());
            var intake = FluidParcelIntake.simulate(record.fluids().state(), box, key, null, true, player.registryAccess());
            return intake.moved() == 0 ? null : new Plan(record.withFluids(record.fluids().withState(intake.state())), intake.remainder());
        }
        var intake = ParcelIntake.simulate(record.state(), box, null, null, true, player.registryAccess());
        return intake.moved() == 0 ? null : new Plan(record.withState(intake.state()), intake.remainder());
    }
}
