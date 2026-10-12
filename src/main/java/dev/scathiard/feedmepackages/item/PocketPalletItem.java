package dev.scathiard.feedmepackages.item;

import dev.scathiard.feedmepackages.consumption.PalletService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.*;

public final class PocketPalletItem extends Item {
    public PocketPalletItem(Properties properties) { super(properties.stacksTo(1)); }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        return InteractionResultHolder.fail(player.getItemInHand(hand));
    }
    public static void hooks() {
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
