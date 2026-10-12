package dev.scathiard.feedmepackages.fluid;

import dev.scathiard.feedmepackages.compat.fluidlogistics.FluidPackages;
import dev.scathiard.feedmepackages.domain.CacheState;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import java.util.UUID;

/** Pure proposed intake. The receipt boundary must authenticate before invoking this calculation. */
public final class FluidParcelIntake {
    private FluidParcelIntake() {}
    public record Plan(CacheState<FluidVariantKey> state, ItemStack remainder, int moved) {
        public Plan { remainder = remainder.copy(); }
        @Override public ItemStack remainder() { return remainder.copy(); }
    }
    public static Plan simulate(CacheState<FluidVariantKey> state, ItemStack box, FluidVariantKey requested,
            UUID requestId, boolean arrived, HolderLookup.Provider registries) {
        var contents = FluidPackages.read(box);
        if (contents.isEmpty() || !requested.equals(FluidVariantKey.of(contents, registries)))
            throw new IllegalArgumentException("Unrecognized fluid parcel or mismatched exact variant");
        int slot = state.find(requested);
        if (slot < 0) throw new IllegalArgumentException("No matching fluid filter");
        var edit = state.edit();
        int allowed = contents.getAmount();
        int accepted = dev.scathiard.feedmepackages.service.CachePolicy.admits(requested,registries) ? edit.insert(slot, requested, allowed) : 0;
        var remaining = FluidPackages.remainder(box, accepted);
        if (!arrived) edit.arrived(requestId, contents.getAmount());
        return new Plan(edit.finish(), remaining, accepted);
    }
}
