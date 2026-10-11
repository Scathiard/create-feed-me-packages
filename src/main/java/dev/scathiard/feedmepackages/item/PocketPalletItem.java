package dev.scathiard.feedmepackages.item;

import dev.scathiard.feedmepackages.consumption.PalletService;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.*;
import java.util.List;

public final class PocketPalletItem extends Item {
    public PocketPalletItem(Properties properties) { super(properties.stacksTo(1)); }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        return InteractionResultHolder.fail(player.getItemInHand(hand));
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> text, TooltipFlag flag) {
        text.add(Component.translatable("item.create_feed_me_packages.pocket_pallet.tip"));
    }
    public static void hooks() {
        NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.LeftClickBlock event) -> {
            if (PalletService.holding(event.getEntity())) event.setCanceled(true);
        });
        NeoForge.EVENT_BUS.addListener((AttackEntityEvent event) -> {
            if (PalletService.holding(event.getEntity())) event.setCanceled(true);
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) -> {
            if (event.getEntity() instanceof ServerPlayer player) PalletService.tick(player);
        });
        NeoForge.EVENT_BUS.addListener((top.theillusivec4.curios.api.event.CurioChangeEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) PalletService.tick(player);
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) PalletService.forget(player);
        });
    }
}
