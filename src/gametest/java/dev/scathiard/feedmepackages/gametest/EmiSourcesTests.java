package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.FmpConfig;
import dev.scathiard.feedmepackages.compat.emi.EmiCacheSources;
import dev.scathiard.feedmepackages.consumption.CraftingService;
import dev.scathiard.feedmepackages.interaction.CacheActions;
import dev.scathiard.feedmepackages.interaction.CursorReservations;
import dev.scathiard.feedmepackages.network.CompatibilityStatus;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.*;
import java.util.ArrayList;
import java.util.List;

@GameTestHolder(FeedMePackages.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EmiSourcesTests {
    private static boolean installed(GameTestHelper helper) {
        if (ModList.get().isLoaded("emi")) return true;
        FeedMePackages.LOGGER.info("FMP_EMI_SOURCE_TEST skipped: optional EMI absent"); helper.succeed(); return false;
    }
    private static ServerPlayer setup(GameTestHelper helper, ItemStack prototype, int count) {
        var player = TestPlayers.create(helper, FmpRegistries.PENDANT.toStack()); ConsumptionTests.seed(player, 0, prototype, count); return player;
    }
    private static long stock(ServerPlayer player) { return ConsumptionTests.stock(player, 0); }
    private static int inventory(ServerPlayer player, Item item) { return player.getInventory().items.stream().filter(s -> s.is(item)).mapToInt(ItemStack::getCount).sum(); }
    private static void fill(ServerPlayer player, ItemStack wanted) throws ReflectiveOperationException {
        var menu = player.containerMenu; var grid = CraftingService.grid(menu);
        var inputs = menu.slots.stream().filter(s -> s.container == player.getInventory() || s.container == grid).toList();
        var crafting = menu.slots.stream().filter(s -> s.container == grid).toList();
        var stacks = new ArrayList<ItemStack>(); for (int i = 0; i < crafting.size(); i++) stacks.add(i == 0 ? wanted.copy() : ItemStack.EMPTY);
        var type = Class.forName("dev.emi.emi.network.FillRecipeC2SPacket");
        var packet = type.getConstructor(AbstractContainerMenu.class, int.class, List.class, List.class, Slot.class, List.class)
                .newInstance(menu, 0, inputs, crafting, menu.getSlot(0), stacks);
        type.getMethod("apply", Player.class).invoke(packet, player);
    }
    @GameTest(template = "empty")
    public static void nativePacketUsesOnlyTheCacheShortfallAndDoesNotRefill(GameTestHelper helper) throws ReflectiveOperationException {
        if (!installed(helper)) return;
        var player = setup(helper, new ItemStack(Items.OAK_LOG), 12); player.getInventory().setItem(8, new ItemStack(Items.OAK_LOG, 2));
        helper.assertTrue(CompatibilityStatus.emi() == CompatibilityStatus.State.READY, "EMI server port not ready");
        fill(player, new ItemStack(Items.OAK_LOG, 5));
        helper.assertTrue(stock(player) == 9 && inventory(player, Items.OAK_LOG) == 0 && player.containerMenu.getSlot(1).getItem().getCount() == 5, "Native fill charged the wrong shortfall");
        player.containerMenu.clicked(0, 0, ClickType.PICKUP, player);
        helper.assertTrue(stock(player) == 9 && player.containerMenu.getSlot(1).getItem().getCount() == 4
                && player.containerMenu.getCarried().is(Items.OAK_PLANKS) && player.containerMenu.getCarried().getCount() == 4, "Native craft charged twice or refilled");
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void insufficientNativeFillReturnsOnlyPaidPhysicalItems(GameTestHelper helper) throws ReflectiveOperationException {
        if (!installed(helper)) return;
        var player = setup(helper, new ItemStack(Items.OAK_LOG), 2); player.getInventory().setItem(8, new ItemStack(Items.OAK_LOG));
        fill(player, new ItemStack(Items.OAK_LOG, 5));
        helper.assertTrue(stock(player) == 0 && inventory(player, Items.OAK_LOG) == 3 && CraftingService.grid(player.containerMenu).isEmpty(), "Partial native refund duplicated or lost materials");
        helper.succeed();
    }
    @GameTest(template = "empty")
    @SuppressWarnings("unchecked")
    public static void originalRecipeBookAliasesAreNotRealInput(GameTestHelper helper) throws ReflectiveOperationException {
        if (!installed(helper)) return;
        var player = setup(helper, new ItemStack(Items.OAK_LOG), 8);
        var recipe = (net.minecraft.world.item.crafting.RecipeHolder<net.minecraft.world.item.crafting.CraftingRecipe>)player.level().getRecipeManager()
                .byKey(ResourceLocation.withDefaultNamespace("oak_planks")).orElseThrow();
        helper.assertTrue(CraftingService.place(player, recipe, false, false, true) == CraftingService.Result.OK && stock(player) == 8, "Alias fixture failed");
        fill(player, new ItemStack(Items.OAK_LOG));
        helper.assertTrue(stock(player) == 7 && inventory(player, Items.OAK_LOG) == 0 && player.containerMenu.getSlot(1).getItem().getCount() == 1, "Alias became a duplicate physical item");
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void fullInventoryAndExactComponentsRemainSupported(GameTestHelper helper) throws ReflectiveOperationException {
        if (!installed(helper)) return;
        var named = new ItemStack(Items.OAK_LOG); named.set(DataComponents.CUSTOM_NAME, Component.literal("Exact cache source"));
        var player = setup(helper, named, 8);
        for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
        fill(player, new ItemStack(Items.OAK_LOG));
        helper.assertTrue(stock(player) == 8 && CraftingService.grid(player.containerMenu).isEmpty(), "EMI ignored exact components");
        fill(player, named.copyWithCount(2));
        helper.assertTrue(stock(player) == 6 && ItemStack.isSameItemSameComponents(player.containerMenu.getSlot(1).getItem(), named)
                && player.containerMenu.getSlot(1).getItem().getCount() == 2 && inventory(player, Items.DIRT) == 2304, "Full inventory prevented exact cached input");
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void cursorReservationIsNotAnExtraSource(GameTestHelper helper) throws ReflectiveOperationException {
        if (!installed(helper)) return;
        var player = setup(helper, new ItemStack(Items.OAK_LOG), 8); var opened = CacheActions.open(player);
        helper.assertTrue(CacheActions.execute(player, new CacheActions.Intent(opened.session(), opened.record().state().revision(),
                CacheActions.Action.TAKE_CURSOR, 0, 5, -1, "")) == CacheActions.Result.OK, "Cursor fixture failed");
        fill(player, new ItemStack(Items.OAK_LOG, 4));
        helper.assertTrue(stock(player) == 5 && inventory(player, Items.OAK_LOG) == 3 && CraftingService.grid(player.containerMenu).isEmpty(), "EMI spent cursor-reserved stock");
        CursorReservations.cancel(player); helper.succeed();
    }
    @GameTest(template = "empty")
    public static void disablingEmiRetainsNativeInventoryAndUnrelatedInstances(GameTestHelper helper) throws ReflectiveOperationException {
        if (!installed(helper)) return;
        var player = setup(helper, new ItemStack(Items.OAK_LOG), 8); player.getInventory().setItem(8, new ItemStack(Items.OAK_LOG));
        var unrelated = new ItemStack(Items.BUNDLE); player.getInventory().setItem(0, unrelated);
        boolean previous = FmpConfig.EMI.get();
        try {
            FmpConfig.EMI.set(false); FmpConfig.refreshServer(); fill(player, new ItemStack(Items.OAK_LOG));
            helper.assertTrue(stock(player) == 8 && player.containerMenu.getSlot(1).getItem().getCount() == 1
                    && player.getInventory().getItem(0) == unrelated, "Disabled adapter muted native transfer or replaced an unrelated instance");
        } finally { FmpConfig.EMI.set(previous); FmpConfig.refreshServer(); }
        player.containerMenu.getSlot(1).setByPlayer(ItemStack.EMPTY); fill(player, new ItemStack(Items.OAK_LOG));
        helper.assertTrue(stock(player) == 7 && player.getInventory().getItem(0) == unrelated, "Cache-only debit replaced unrelated item instances");
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void staleSourceAndOverstackRequestsCannotSpendCache(GameTestHelper helper) throws ReflectiveOperationException {
        if (!installed(helper)) return;
        var player = setup(helper, new ItemStack(Items.OAK_LOG), 8);
        helper.assertTrue(EmiCacheSources.append(player, List.of(), new ItemStack(Items.OAK_LOG, 65)).isEmpty(), "Overstack request exposed cache");
        var sources = EmiCacheSources.append(player, List.of(), new ItemStack(Items.OAK_LOG));
        helper.assertTrue(sources.size() == 1, "Source fixture failed");
        TestPlayers.necklace(player).setStackInSlot(0, ItemStack.EMPTY);
        var method = Class.forName("dev.emi.emi.network.FillRecipeC2SPacket").getDeclaredMethod("grabMatching", Player.class, List.class, List.class, List.class, ItemStack.class);
        method.setAccessible(true);
        int count = (Integer)method.invoke(null, player, sources, new ArrayList<ItemStack>(), List.of(), new ItemStack(Items.OAK_LOG));
        helper.assertTrue(count == 0 && sources.getFirst().getItem().getCount() == 1, "Stale source was consumed"); helper.succeed();
    }
}
