package dev.scathiard.feedmepackages.service;

import dev.scathiard.feedmepackages.FmpConfig;
import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.domain.CacheCapacity;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.storage.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import java.util.*;

/** Config and data-pack rules only restrict admission. Identity and permission validation remain independent. */
public final class CachePolicy {
    private CachePolicy() {}
    public static final TagKey<Item> DENIED = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID, "cache_denied"));
    public static final TagKey<Item> ALLOWED = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID, "cache_allowed"));
    public static final TagKey<net.minecraft.world.level.material.Fluid> FLUID_DENIED = TagKey.create(Registries.FLUID,
            ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID, "cache_denied"));
    public static final TagKey<net.minecraft.world.level.material.Fluid> FLUID_ALLOWED = TagKey.create(Registries.FLUID,
            ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID, "cache_allowed"));
    public static boolean admits(ItemVariantKey variant, HolderLookup.Provider registries) {
        var stack = variant.stack(registries, 1); var rules = FmpConfig.rules();
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return rules.allowed().contains(id) || stack.is(ALLOWED)
                || stack.getTags().anyMatch(tag -> rules.allowed().contains("#"+tag.location()))
                || !rules.denied().contains(id) && !stack.is(DENIED)
                    && stack.getTags().noneMatch(tag -> rules.denied().contains("#"+tag.location()));
    }
    public static boolean admits(dev.scathiard.feedmepackages.fluid.FluidVariantKey variant, HolderLookup.Provider registries) {
        var stack = variant.stack(registries, 1); var rules = FmpConfig.rules();
        String id = net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(stack.getFluid()).toString();
        return rules.allowed().contains(id) || stack.is(FLUID_ALLOWED)
                || stack.getTags().anyMatch(tag -> rules.allowed().contains("#" + tag.location()))
                || !rules.denied().contains(id) && !stack.is(FLUID_DENIED)
                    && stack.getTags().noneMatch(tag -> rules.denied().contains("#" + tag.location()));
    }
    public static boolean admits(dev.scathiard.feedmepackages.domain.MaterialVariant variant, HolderLookup.Provider registries) {
        if(variant instanceof ItemVariantKey item) return admits(item,registries);
        if(variant instanceof dev.scathiard.feedmepackages.fluid.FluidVariantKey fluid) return admits(fluid,registries);
        return false;
    }
    public static void synchronize(CacheLedger ledger, CacheHandle handle) {
        var record=ledger.find(handle.cacheId()); var capacity=FmpConfig.rules().capacity(record.state().level());
        if(record.state().capacity().equals(capacity))return;
        // A foreign-container callback must not mutate the ledger under an active material transaction.
        if(dev.scathiard.feedmepackages.consumption.CraftingReservations.operating(handle.cacheId())
                || dev.scathiard.feedmepackages.interaction.FluidActions.operating(handle.cacheId()))return;
        var edit=record.state().edit();edit.resize(capacity);
        ledger.replace(handle,record.state().revision(),record.withState(edit.finish()));
    }
}
