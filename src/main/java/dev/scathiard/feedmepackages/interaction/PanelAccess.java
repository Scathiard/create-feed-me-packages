package dev.scathiard.feedmepackages.interaction;

import dev.scathiard.feedmepackages.compat.maid.MaidAccess;
import dev.scathiard.feedmepackages.compat.maid.MaidMenus;
import dev.scathiard.feedmepackages.logistics.SupplyService;
import dev.scathiard.feedmepackages.service.AccessGate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import java.util.List;

/** Manual panel access follows the actual menu. Global crafting/ammo access still belongs to the player. */
public final class PanelAccess {
    private PanelAccess() {}
    public static boolean supported(AbstractContainerMenu menu) {
        return menu instanceof InventoryMenu || menu instanceof CraftingMenu || MaidMenus.supports(menu);
    }
    public static AccessGate.Result resolve(ServerPlayer player) {
        if (!player.getServer().isSameThread()) throw new IllegalStateException("Panel access outside server thread");
        var menu = player.containerMenu;
        if (menu instanceof InventoryMenu || menu instanceof CraftingMenu) return AccessGate.resolve(player);
        var maid = MaidMenus.target(menu);
        if (player.isAlive() && !player.isSpectator() && maid instanceof TamableAnimal pet
                && maid.level() == player.level() && pet.isOwnedBy(player) && menu.stillValid(player))
            return MaidAccess.resolve(maid);
        return new AccessGate.Result(AccessGate.Status.NOT_WORN, null, List.of());
    }
    public static boolean maid(ServerPlayer player) { return MaidMenus.supports(player.containerMenu); }
    public static String address(ServerPlayer player) {
        var maid = MaidMenus.target(player.containerMenu);
        return maid != null && resolve(player).active() ? MaidAccess.address(maid) : SupplyService.address(player);
    }
}
