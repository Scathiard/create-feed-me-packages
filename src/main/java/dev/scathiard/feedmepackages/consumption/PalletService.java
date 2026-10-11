package dev.scathiard.feedmepackages.consumption;

import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.item.PocketPalletItem;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import java.util.*;
import java.util.function.Function;

/** Player selection is a name, not material. The native placement bridge owns the short-lived lease. */
public final class PalletService {
    private PalletService() {}
    public record Choice(UUID cache, ItemVariantKey key) {}
    private static final Map<ServerPlayer, Choice> CHOICES = new WeakHashMap<>();
    private static final Map<ServerPlayer, UUID> CONTEXTS = new WeakHashMap<>();
    private static Function<UseOnContext, InteractionResult> clientUse = context -> InteractionResult.FAIL;
    public static void clientUse(Function<UseOnContext, InteractionResult> function) { clientUse = Objects.requireNonNull(function); }
    public static boolean holding(net.minecraft.world.entity.player.Player player) {
        return player.isAlive() && !player.isSpectator() && player.getMainHandItem().getItem() instanceof PocketPalletItem;
    }
    public static void tick(ServerPlayer player) {
        var access = AccessGate.resolve(player); UUID cache = access.active() ? access.handle().cacheId() : null;
        if (!Objects.equals(CONTEXTS.get(player), cache)) { CHOICES.remove(player); if (cache == null) CONTEXTS.remove(player); else CONTEXTS.put(player, cache); }
    }
    public static void forget(ServerPlayer player) { CHOICES.remove(player); CONTEXTS.remove(player); }
    public static Choice choice(ServerPlayer player) { tick(player); return CHOICES.get(player); }
    public static void cancelChoice(ServerPlayer player, UUID cache) {
        var choice = choice(player);
        if (choice != null && choice.cache().equals(cache)) CHOICES.remove(player);
    }
    public static boolean choose(ServerPlayer player, UUID cache, String encoded) {
        tick(player); var access = AccessGate.resolve(player);
        if (!holding(player) || !access.active() || !access.handle().cacheId().equals(cache)) return false;
        var record = CacheLedger.get(player.getServer()).find(cache);
        for (int slot = 0; slot < record.state().cells().size(); slot++) {
            var cell = record.state().cells().get(slot);
            if (cell.filter() != null && cell.filter().encoded().equals(encoded)
                    && cell.amount() > CraftingReservations.reservedCache(cache, slot, null)
                    && cell.filter().stack(player.registryAccess(), 1).getItem() instanceof BlockItem) {
                CHOICES.put(player, new Choice(cache, cell.filter())); return true;
            }
        }
        return false;
    }
    public static InteractionResult use(UseOnContext context) {
        var player = context.getPlayer();
        if (player == null || context.getHand() != InteractionHand.MAIN_HAND || !holding(player)) return InteractionResult.FAIL;
        if (context.getLevel().isClientSide) return clientUse.apply(context);
        if (!(player instanceof ServerPlayer server)) return InteractionResult.FAIL;
        var selection = choice(server); var access = AccessGate.resolve(server);
        if (!access.active()) return failed(server, "no_access");
        if (selection == null) return failed(server, "no_selection");
        if (server.containerMenu != server.inventoryMenu || server.level() != context.getLevel()
                || !server.canInteractWithBlock(context.getClickedPos(), 1.0)
                || !server.level().mayInteract(server, context.getClickedPos())
                || !server.level().getWorldBorder().isWithinBounds(context.getClickedPos())
                || context.getItemInHand() != server.getMainHandItem()) return failed(server, "denied");
        var ledger = CacheLedger.get(server.getServer()); var record = ledger.find(selection.cache());
        for (int slot = 0; slot < record.state().cells().size(); slot++) {
            var cell = record.state().cells().get(slot); if (!selection.key().equals(cell.filter())) continue;
            long available = cell.amount() - CraftingReservations.reservedCache(selection.cache(), slot, null);
            var prototype = selection.key().stack(server.registryAccess(), 1);
            if (available <= 0) return failed(server, "missing");
            if (!(prototype.getItem() instanceof BlockItem) || !prototype.isItemEnabled(server.level().enabledFeatures())) return failed(server, "denied");
            int offered = (int) Math.min(available, prototype.getMaxStackSize());
            return WorldPlacement.use(server, access.handle(), slot, selection.key(), prototype.copyWithCount(offered), context);
        }
        return failed(server, "missing");
    }
    static InteractionResult failed(ServerPlayer player, String reason) {
        player.displayClientMessage(Component.translatable("message.create_feed_me_packages.pallet." + reason), true); return InteractionResult.FAIL;
    }
}
