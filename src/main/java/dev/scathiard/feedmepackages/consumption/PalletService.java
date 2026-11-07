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
    public record Choice(UUID cache, ItemVariantKey key, dev.scathiard.feedmepackages.fluid.FluidVariantKey fluid) {
        public Choice(UUID cache,ItemVariantKey key) { this(cache,key,null); }
        public String encoded() { return fluid==null ? key.encoded() : "fluid:"+fluid.encoded(); }
    }
    private static final Map<ServerPlayer, Choice> CHOICES = new WeakHashMap<>();
    private static final Map<ServerPlayer, UUID> CONTEXTS = new WeakHashMap<>();
    private static final String SAVED_CHOICE = "create_feed_me_packages:pallet_choice";
    private static Function<UseOnContext, InteractionResult> clientUse = context -> InteractionResult.FAIL;
    public static void clientUse(Function<UseOnContext, InteractionResult> function) { clientUse = Objects.requireNonNull(function); }
    public static boolean holding(net.minecraft.world.entity.player.Player player) {
        return player.isAlive() && !player.isSpectator() && player.getMainHandItem().getItem() instanceof PocketPalletItem;
    }
    public static void tick(ServerPlayer player) {
        PalletUse.tick(player);
        dev.scathiard.feedmepackages.network.PalletFluids.tick(player);
        var access = AccessGate.resolve(player); UUID cache = access.active() ? access.handle().cacheId() : null;
        if (!Objects.equals(CONTEXTS.get(player), cache)) {
            UUID previous=CONTEXTS.remove(player); CHOICES.remove(player);
            // A real access/cache change clears the old preference. Initial login may
            // briefly have no Curios inventory; leave its saved choice until access is known.
            if(previous!=null) player.getPersistentData().remove(SAVED_CHOICE);
            if(cache!=null) { CONTEXTS.put(player,cache); restore(player,cache); }
        }
    }
    public static void forget(ServerPlayer player) { PalletUse.forget(player); CHOICES.remove(player); CONTEXTS.remove(player); }
    public static Choice choice(ServerPlayer player) { tick(player); return CHOICES.get(player); }
    public static void cancelChoice(ServerPlayer player, UUID cache) {
        var choice = choice(player);
        if (choice != null && choice.cache().equals(cache)) {
            CHOICES.remove(player); player.getPersistentData().remove(SAVED_CHOICE);
        }
    }
    private static void remember(ServerPlayer player, Choice choice) {
        CHOICES.put(player,choice);
        var tag=new net.minecraft.nbt.CompoundTag(); tag.putUUID("cache",choice.cache()); tag.putString("material",choice.encoded());
        player.getPersistentData().put(SAVED_CHOICE,tag);
    }
    /** A saved name never grants stock or access. Resolve it against this cache's real filters. */
    private static void restore(ServerPlayer player, UUID cache) {
        var tag=player.getPersistentData().getCompound(SAVED_CHOICE);
        if(!tag.hasUUID("cache") || !cache.equals(tag.getUUID("cache"))) {
            player.getPersistentData().remove(SAVED_CHOICE); return;
        }
        String encoded=tag.getString("material");
        var record=CacheLedger.get(player.getServer()).find(cache);
        for(var cell:record.state().cells())
            if(cell.filter()!=null && cell.filter().encoded().equals(encoded)
                    && PalletUse.supported(cell.filter().stack(player.registryAccess(),1))) {
                CHOICES.put(player,new Choice(cache,cell.filter())); return;
            }
        for(var cell:record.fluids().state().cells())
            if(cell.filter()!=null && ("fluid:"+cell.filter().encoded()).equals(encoded)) {
                CHOICES.put(player,new Choice(cache,null,cell.filter())); return;
            }
        // Empty stock keeps its filter and selection. A removed/unsupported filter does not.
        player.getPersistentData().remove(SAVED_CHOICE);
    }
    public static boolean choose(ServerPlayer player, UUID cache, String encoded) {
        tick(player); var access = AccessGate.resolve(player);
        if (!holding(player) || !access.active() || !access.handle().cacheId().equals(cache)) return false;
        var record = CacheLedger.get(player.getServer()).find(cache);
        if(encoded.startsWith("fluid:")) {
            dev.scathiard.feedmepackages.fluid.FluidVariantKey fluid;
            try { fluid=dev.scathiard.feedmepackages.fluid.FluidVariantKey.decode(encoded.substring(6),player.registryAccess()); }
            catch(IllegalArgumentException invalid) { return false; }
            int slot=record.fluids().state().find(fluid);
            if(slot<0 || record.fluids().state().cells().get(slot).amount()<=dev.scathiard.feedmepackages.interaction.FluidActions.reserved(cache,slot)) return false;
            remember(player,new Choice(cache,null,fluid));return true;
        }
        for (int slot = 0; slot < record.state().cells().size(); slot++) {
            var cell = record.state().cells().get(slot);
            if (cell.filter() != null && cell.filter().encoded().equals(encoded)
                    && cell.amount() > CraftingReservations.reservedCache(cache, slot, null)
                    && PalletUse.supported(cell.filter().stack(player.registryAccess(), 1))) {
                remember(player, new Choice(cache, cell.filter())); return true;
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
        if(selection.fluid()!=null) return PalletFluidUse.use(server,context,selection.fluid());
        var ledger = CacheLedger.get(server.getServer()); var record = ledger.find(selection.cache());
        for (int slot = 0; slot < record.state().cells().size(); slot++) {
            var cell = record.state().cells().get(slot); if (!selection.key().equals(cell.filter())) continue;
            long available = cell.amount() - CraftingReservations.reservedCache(selection.cache(), slot, null);
            var prototype = selection.key().stack(server.registryAccess(), 1);
            if (available <= 0) return failed(server, "missing");
            if (!(prototype.getItem() instanceof BlockItem) || !prototype.isItemEnabled(server.level().enabledFeatures())) return failed(server, "denied");
            int offered = (int) Math.min(available, prototype.getMaxStackSize());
            var result = WorldPlacement.use(server, access.handle(), slot, selection.key(), prototype.copyWithCount(offered), context);
            // WorldPlacement only returns a consuming result after its native placement lease committed.
            // The display receipt is not a second consumption path.
            if (result.consumesAction()) {
                var current = ledger.find(selection.cache());
                var remaining = current == null || slot>=current.state().cells().size() ? null : current.state().cells().get(slot);
                boolean next = remaining != null && selection.key().equals(remaining.filter())
                        && remaining.amount() > CraftingReservations.reservedCache(selection.cache(), slot, null);
                dev.scathiard.feedmepackages.network.PalletNetwork.placed(server,selection.cache(),selection.key().encoded(),next);
            }
            return result;
        }
        return failed(server, "missing");
    }
    static InteractionResult failed(ServerPlayer player, String reason) {
        // Ordinary placement rejections are quiet; missing-stock and setup guidance remain.
        if (!"denied".equals(reason) && !"no_placement".equals(reason))
            player.displayClientMessage(Component.translatable("message.create_feed_me_packages.pallet." + reason), true);
        return InteractionResult.FAIL;
    }
}
