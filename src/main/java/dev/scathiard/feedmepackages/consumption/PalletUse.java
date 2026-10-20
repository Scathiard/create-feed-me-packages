package dev.scathiard.feedmepackages.consumption;

import dev.scathiard.feedmepackages.item.PocketPalletItem;
import dev.scathiard.feedmepackages.interaction.InventoryTransfer;
import dev.scathiard.feedmepackages.network.PalletNetwork;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import java.util.*;
import java.util.function.*;

/** Native held-item callbacks see the leased material; the real inventory slot always keeps the pallet. */
public final class PalletUse {
    private PalletUse() {}
    // Client and integrated-server entities can compare equal by entity id. Never share a use session across logical threads.
    private static final ThreadLocal<Map<Player,Use>> SESSIONS = ThreadLocal.withInitial(IdentityHashMap::new);
    private static final ThreadLocal<Use> HAND = new ThreadLocal<>();
    private static Supplier<ItemStack> clientSelection = () -> ItemStack.EMPTY;
    public static void clientSelection(Supplier<ItemStack> selection) { clientSelection=selection; }
    public static boolean supported(ItemStack stack) {
        var item=stack.getItem();
        return !stack.isEmpty() && !stack.isDamageableItem() && (item instanceof BlockItem || item.getClass()==Item.class
                || item instanceof EnderEyeItem || item instanceof BucketItem || item instanceof MilkBucketItem
                || item instanceof PotionItem || stack.has(DataComponents.FOOD));
    }
    public static ItemStack selected(Player player) {
        if(player instanceof ServerPlayer server) {
            var choice=PalletService.choice(server);
            return choice==null || choice.fluid()!=null ? ItemStack.EMPTY : choice.key().stack(server.registryAccess(),1);
        }
        return clientSelection.get();
    }
    public static boolean intercept(Player player,InteractionHand hand) {
        return HAND.get()==null && hand==InteractionHand.MAIN_HAND && player.getMainHandItem().getItem() instanceof PocketPalletItem
                && !(selected(player).getItem() instanceof BlockItem) && supported(selected(player));
    }
    private static final class Use {
        final Player player; final ItemStack tool, original; final int hotbar; final CacheUseLease lease;
        ItemStack material; boolean settled, acknowledged; final int started;
        Use(Player player,ItemStack material,CacheUseLease lease) {
            this.player=player; started=player.tickCount; this.material=material; original=material.copy(); this.lease=lease;
            tool=player.getMainHandItem(); hotbar=player.getInventory().selected;
        }
        boolean valid() {
            return player.isAlive() && !player.isSpectator() && player.getInventory().selected==hotbar
                    && player.getInventory().getItem(hotbar)==tool && player.containerMenu==player.inventoryMenu
                    && (lease==null || lease.valid());
        }
        boolean pending() {
            if(player.isUsingItem()) acknowledged=true;
            return player.level().isClientSide && !acknowledged && player.tickCount-started<10
                    && player.getUseItem()==material && material.getUseDuration(player)>0;
        }
        void settle() {
            if(settled) return;
            try {
                boolean changed=material.isEmpty() || !ItemStack.isSameItemSameComponents(original,material);
                if(changed && lease!=null) {
                    lease.spent();
                    if(!material.isEmpty()) {
                        var plan=InventoryTransfer.insert(player.getInventory(),material,material.getCount());
                        if(plan.moved()==material.getCount()) plan.commit(player.getInventory());
                        else net.neoforged.neoforge.items.ItemHandlerHelper.giveItemToPlayer(player,material.copy());
                    }
                    PalletNetwork.placed((ServerPlayer)player,lease.handle.cacheId(),lease.key.encoded(),
                            MaterialTransaction.open((ServerPlayer)player).map(tx->tx.cacheAvailable(original)>0).orElse(false));
                }
            } finally { if(lease!=null) lease.close(); settled=true; SESSIONS.get().remove(player,this); }
        }
    }
    public static ItemStack hand(Player player,EquipmentSlot slot) {
        var use=HAND.get(); return use!=null && use.player==player && slot==EquipmentSlot.MAINHAND ? use.material : null;
    }
    public static boolean hand(Player player,EquipmentSlot slot,ItemStack value) {
        var use=HAND.get(); if(use==null || use.player!=player || slot!=EquipmentSlot.MAINHAND) return false;
        use.material=value; return true;
    }
    public static InteractionResult perform(Player player,Function<ItemStack,InteractionResult> nativeUse) {
        if(SESSIONS.get().containsKey(player)) return InteractionResult.FAIL;
        var material=selected(player); if(!supported(material) || material.getItem() instanceof BlockItem) return InteractionResult.FAIL;
        if(player instanceof ServerPlayer && !roomForRemainder(player,material)) return InteractionResult.FAIL;
        var lease=player instanceof ServerPlayer server ? CacheUseLease.reserve(server,material).orElse(null) : null;
        if(player instanceof ServerPlayer && lease==null) return InteractionResult.FAIL;
        var use=new Use(player,material.copyWithCount(1),lease); SESSIONS.get().put(player,use);
        if(!use.valid()) { use.settle();return InteractionResult.FAIL; }
        HAND.set(use);
        try { return nativeUse.apply(use.material); }
        finally {
            HAND.remove();
            if((!player.isUsingItem() && !use.pending()) || player.getUseItem()!=use.material) use.settle();
        }
    }
    private static boolean roomForRemainder(Player player,ItemStack item) {
        ItemStack remainder=item.getCraftingRemainingItem();
        if(item.getItem() instanceof BucketItem || item.getItem() instanceof MilkBucketItem) remainder=new ItemStack(Items.BUCKET);
        else if(item.getItem() instanceof PotionItem && !(item.getItem() instanceof ThrowablePotionItem)) remainder=new ItemStack(Items.GLASS_BOTTLE);
        return remainder.isEmpty() || InventoryTransfer.insert(player.getInventory(),remainder,1).moved()==1;
    }
    /** Keep the original useItem object, duration, events, effects and cancellation across native ticks. */
    public static void nativeTick(Player player,Runnable original) {
        var use=SESSIONS.get().get(player);
        if(use==null || HAND.get()!=null) { original.run();return; }
        if(!use.valid()) { player.stopUsingItem();use.settle();return; }
        HAND.set(use);
        try { original.run(); }
        finally { HAND.remove(); if(!player.isUsingItem() && !use.pending()) use.settle(); }
    }
    public static void tick(Player player) {
        var use=SESSIONS.get().get(player);if(use==null || HAND.get()==use) return;
        if(!use.valid() || (!player.isUsingItem() && !use.pending())) { player.stopUsingItem();use.settle(); }
    }
    public static void forget(Player player) {
        var use=SESSIONS.get().get(player); if(use!=null) { player.stopUsingItem();use.settle(); }
    }
}
