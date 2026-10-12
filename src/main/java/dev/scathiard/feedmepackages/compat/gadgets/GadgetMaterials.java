package dev.scathiard.feedmepackages.compat.gadgets;

import dev.scathiard.feedmepackages.FmpConfig;
import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.consumption.MaterialTransaction;
import dev.scathiard.feedmepackages.mixin.OptionalMixins;
import dev.scathiard.feedmepackages.service.AccessGate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Native sources run first. Only their remaining exact requests enter the cache transaction. */
public final class GadgetMaterials {
    private static Supplier<List<ItemStack>> clientSources = List::of;
    private GadgetMaterials() {}
    public static void clientSources(Supplier<List<ItemStack>> source) { clientSources = Objects.requireNonNull(source); }
    public static boolean enabled() { return OptionalMixins.gadgetsCompatible() && FmpConfig.gadgetsEnabled(); }
    public static boolean guard(Player player) {
        return enabled() && player instanceof ServerPlayer server && server.getServer().isSameThread() && AccessGate.resolve(server).active();
    }
    public static void fill(Player player, List<ItemStack> remaining, boolean simulate) {
        if (remaining.isEmpty() || !enabled() || !(player instanceof ServerPlayer server) || !server.getServer().isSameThread()) return;
        var tx = MaterialTransaction.open(server).orElse(null); if (tx == null) return;
        for (var material : remaining) {
            if (material.isEmpty() || material.getCount() <= 0 || !tx.takeCache(material, material.getCount())) return;
        }
        if (simulate || tx.commit()) remaining.clear();
    }
    public static int count(Player player, ItemStack prototype, int nativeCount) {
        if (prototype.isEmpty() || !OptionalMixins.gadgetsCompatible() || player.isSpectator()) return nativeCount;
        long cached = 0;
        if (player instanceof ServerPlayer server) {
            if (!enabled() || !server.getServer().isSameThread()) return nativeCount;
            cached = MaterialTransaction.open(server).map(tx -> tx.cacheAvailable(prototype)).orElse(0);
        } else if (player.level().isClientSide) {
            for (var stack : clientSources.get()) if (ItemStack.isSameItemSameComponents(stack, prototype)) cached += stack.getCount();
        }
        return (int)Math.min(Integer.MAX_VALUE, Math.max(0, (long)nativeCount) + cached);
    }
    /** Captured around the author's temporary block placement, never a general world rollback. */
    public record Placement(BlockSnapshot before, Level level, BlockState placedState, BlockEntity placedEntity) {
        public void restoreIfOwned() {
            if (placedEntity == null || level.getBlockEntity(before.getPos()) != placedEntity
                    || !level.getBlockState(before.getPos()).equals(placedState)) return;
            if (!before.restore()) FeedMePackages.LOGGER.error("FMP could not restore rejected gadget placement at {}", before.getPos());
        }
    }
}
