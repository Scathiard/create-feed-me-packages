package dev.scathiard.feedmepackages.compat.sophisticated;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.FmpConfig;
import dev.scathiard.feedmepackages.consumption.CraftingReservations;
import dev.scathiard.feedmepackages.mixin.OptionalMixins;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.fml.ModList;
import java.lang.reflect.*;
import java.util.*;

/** Optional native ports. Only the real BackpackContainer and its own crafting upgrade are accepted. */
public final class SophisticatedAccess {
    private SophisticatedAccess() {}
    private record Ports(Class<?> backpack, Class<?> crafting, Method first, Method recipeSlots, Method wrapper,
                         Method owner, Method id, Method open, Method setOpen, Method setTab, Method openTab,
                         Method refill, Method recipeUsed, Method transfer) {}
    private static Ports ports;
    public static void initialize() {
        if (!ModList.get().isLoaded("sophisticatedbackpacks") || !OptionalMixins.sophisticatedCompatible()) return;
        try {
            var loader = SophisticatedAccess.class.getClassLoader();
            String root = "net.p3pp3rf1y.sophisticatedcore.";
            var backpack = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer", false, loader);
            var crafting = Class.forName(root + "upgrades.crafting.CraftingUpgradeContainer", false, loader);
            var wrapper = Class.forName(root + "upgrades.crafting.CraftingUpgradeWrapper", false, loader);
            var transfer = Class.forName(root + "compat.recipeviewers.common.CraftingContainerRecipeTransferHandlerServer", false, loader);
            ports = new Ports(backpack, crafting, backpack.getMethod("getOpenOrFirstCraftingContainer", RecipeType.class),
                    crafting.getMethod("getRecipeSlots"), crafting.getMethod("getUpgradeWrapper"), crafting.getMethod("getPlayer"),
                    crafting.getMethod("getUpgradeContainerId"), crafting.getMethod("isOpen"), crafting.getMethod("setIsOpen", boolean.class),
                    backpack.getMethod("setOpenTabId", int.class), backpack.getMethod("getOpenContainer"),
                    wrapper.getMethod("shouldRefillCraftingGridNBT"), crafting.getMethod("setRecipeUsed", ResourceLocation.class),
                    transfer.getMethod("setItemsWithStacks", Player.class, ResourceLocation.class, RecipeType.class,
                            List.class, List.class, List.class, boolean.class));
            FeedMePackages.LOGGER.info("FMP Sophisticated Backpacks crafting ports ready");
        } catch (ReflectiveOperationException | LinkageError failed) {
            ports = null;
            FeedMePackages.LOGGER.warn("FMP Sophisticated Backpacks sources disabled: native ports changed", failed);
        }
    }
    public static boolean ready() { return ports != null; }
    public static boolean enabled() { return ready() && FmpConfig.sophisticatedEnabled(); }
    public record Context(Player player, AbstractContainerMenu menu, Object crafting, Object wrapper, int upgrade,
                          List<Slot> grid, List<Slot> inventory) {
        public Context { grid = List.copyOf(grid); inventory = List.copyOf(inventory); }
        public List<Slot> all() { var all = new ArrayList<>(grid); all.addAll(inventory); return all; }
        public boolean current() {
            var now = resolve(player);
            return now != null && now.menu == menu && now.crafting == crafting && now.wrapper == wrapper
                    && now.grid.equals(grid) && now.inventory.equals(inventory);
        }
        public boolean refill() { return (boolean) invoke(ports.refill, wrapper); }
        public void open() {
            if ((boolean) invoke(ports.open, crafting)) return;
            ((Optional<?>) invoke(ports.openTab, menu)).ifPresent(old -> invoke(ports.setOpen, old, false));
            invoke(ports.setOpen, crafting, true); invoke(ports.setTab, menu, upgrade);
        }
        public void recipeUsed(ResourceLocation recipe) { invoke(ports.recipeUsed, crafting, recipe); }
        public void transfer(ResourceLocation recipe, List<ItemStack> stacks, boolean maximum) {
            invoke(ports.transfer, null, player, recipe, RecipeType.CRAFTING, stacks,
                    grid.stream().map(s -> s.index).toList(), inventory.stream().map(s -> s.index).toList(), maximum);
        }
    }
    public static Context resolve(Player player) {
        if (!enabled() || player == null || player.isSpectator() || !player.isAlive()
                || player.containerMenu == null || !ports.backpack.isInstance(player.containerMenu)) return null;
        var menu = player.containerMenu;
        if (player instanceof ServerPlayer server && (!server.getServer().isSameThread() || !menu.stillValid(player))) return null;
        Object crafting = ((Optional<?>) invoke(ports.first, menu, RecipeType.CRAFTING)).orElse(null);
        if (!ports.crafting.isInstance(crafting) || invoke(ports.owner, crafting) != player) return null;
        var raw = (List<?>) invoke(ports.recipeSlots, crafting);
        if (raw.size() != 9 || raw.stream().anyMatch(s -> !(s instanceof Slot))) return null;
        var grid = raw.stream().map(Slot.class::cast).toList();
        if (new HashSet<>(grid).size() != 9 || grid.stream().anyMatch(s -> s.index < 0 || menu.getSlot(s.index) != s || !s.mayPickup(player))) return null;
        var inventory = menu.slots.stream().filter(s -> !grid.contains(s) && s.isActive() && s.mayPickup(player))
                .filter(s -> !(player instanceof ServerPlayer server) || s.container != player.getInventory()
                        || CraftingReservations.reservedInventory(server, s.getContainerSlot()) == 0).toList();
        if (inventory.size() > 512 || new HashSet<>(inventory).size() != inventory.size()) return null;
        return new Context(player, menu, crafting, invoke(ports.wrapper, crafting),
                (int) invoke(ports.id, crafting), grid, inventory);
    }
    private static Object invoke(Method method, Object instance, Object... args) {
        try { return method.invoke(instance, args); }
        catch (InvocationTargetException failed) {
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failed.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("Native Sophisticated crafting port failed", failed.getCause());
        } catch (ReflectiveOperationException failed) { throw new IllegalStateException("Native Sophisticated crafting port unavailable", failed); }
    }
}
