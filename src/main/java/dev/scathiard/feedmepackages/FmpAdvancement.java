package dev.scathiard.feedmepackages;

import dev.scathiard.feedmepackages.compat.maid.MaidCompatibility;
import dev.scathiard.feedmepackages.item.PendantItem;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import java.util.Locale;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayer;

/** Vanilla owns progress, reloads and saves. Call from the server after the corresponding gameplay action. */
public enum FmpAdvancement {
    ROOT, FIRST_DELIVERY, AUTO_RETURN, POCKET_PALLET, CACHE_AMMO, MAID_LOGISTICS, MAX_LEVEL,
    SELF_REFERENCE, HUMAN_COURIER, RETURN_TO_SELF, MAID_BOSS, FORBIDDEN_NESTING;

    private final ResourceLocation id = ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID, name().toLowerCase(Locale.ROOT));

    private boolean unfinished(ServerPlayer player) {
        var advancement = player.server.getAdvancements().get(id);
        return advancement != null && !player.getAdvancements().getOrStartProgress(advancement).isDone();
    }

    public void award(ServerPlayer player) {
        if (player instanceof FakePlayer) return;
        var advancement = player.server.getAdvancements().get(id);
        if (advancement != null && !player.getAdvancements().getOrStartProgress(advancement).isDone())
            player.getAdvancements().award(advancement, "triggered");
    }

    public static void maidWorked(LivingEntity maid) {
        if (maid instanceof TamableAnimal pet && MaidCompatibility.isMaid(maid) && pet.getOwnerUUID() != null) {
            var owner = maid.getServer().getPlayerList().getPlayer(pet.getOwnerUUID());
            if (owner != null) MAID_LOGISTICS.award(owner);
        }
    }

    public static void maidWearsMax(TamableAnimal maid, AccessGate.Result equipped) {
        var ledger = CacheLedger.get(maid.getServer());
        if (ledger.find(equipped.handle().cacheId()).state().level() != 5 || maid.getOwnerUUID() == null) return;
        var owner = maid.getServer().getPlayerList().getPlayer(maid.getOwnerUUID());
        if (owner == null || !MAID_BOSS.unfinished(owner)) return;
        var own = AccessGate.resolve(owner);
        if (own.status() == AccessGate.Status.NOT_WORN
                || own.handle() != null && ledger.find(own.handle().cacheId()).state().level() < 5)
            MAID_BOSS.award(owner);
    }

    /** A rejected ghost only needs a registry name. Never materialize its components or put it in storage. */
    public static ItemStack rejectedTemplate(String name) {
        var key = ResourceLocation.tryParse(name);
        if (key == null) return ItemStack.EMPTY;
        var candidate = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(key)
                .map(net.minecraft.world.item.Item::getDefaultInstance).orElse(ItemStack.EMPTY);
        return rejectedItem(candidate) == null ? ItemStack.EMPTY : candidate;
    }

    /** Only inherently non-nestable containers and the pendant itself earn these two attempts. */
    public static FmpAdvancement rejectedItem(ItemStack stack) {
        if (stack.isEmpty()) return null;
        if (stack.getItem() instanceof PendantItem) return SELF_REFERENCE;
        return !stack.getItem().canFitInsideContainerItems() ? FORBIDDEN_NESTING : null;
    }
}
