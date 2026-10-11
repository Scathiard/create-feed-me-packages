package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.FmpConfig;
import dev.scathiard.feedmepackages.client.ClientMaterials;
import dev.scathiard.feedmepackages.client.ClientCompatibility;
import dev.scathiard.feedmepackages.compat.jei.FmpJeiPlugin;
import dev.scathiard.feedmepackages.consumption.CraftingService;
import dev.scathiard.feedmepackages.item.ItemVariantKey;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.*;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Actual JEI layout, native mouse plus-button and fxntstorage network packet on an integrated server. */
final class FxntClientReview {
    private static int stage, tick, changed;
    private static CompletableFuture<Void> work;
    private static UUID cache;
    private static ItemStack wornBackpack;
    static boolean tick() throws Exception {
        tick++;
        if (work != null) { if (!work.isDone()) return false; work.join(); work = null; }
        var mc = Minecraft.getInstance();
        switch (stage) {
            case 0 -> { mc.player.closeContainer(); next(); }
            case 1 -> { if (!waited()) break; server(p -> { FmpConfig.EMI.set(false); FmpConfig.refreshServer(); setup(p); }); next(); }
            case 2 -> {
                if (!waited() || !(mc.screen instanceof CraftingScreen) || !ClientMaterials.active() || !ClientCompatibility.fxntSourcesAvailable()) break;
                var runtime = FmpJeiPlugin.runtime().orElseThrow();
                var handler = runtime.getRecipeTransferManager().getRecipeTransferHandler(mc.player.containerMenu,
                        runtime.getRecipeManager().getRecipeCategory(mezz.jei.api.constants.RecipeTypes.CRAFTING)).orElseThrow();
                require(handler.getClass().getName().equals("net.fxnt.fxntstorage.compat.jei.JEICraftingTransferHandler"), "JEI transfer owner is not fxntstorage: " + handler.getClass());
                JeiClientReview.openTransfer(); next();
            }
            case 3 -> { if (!waited()) break; JeiClientReview.previewTransfer(); TemplateClientReview.capture("16-fxnt-cache-preview"); server(p -> require(stock(p) == 8, "Preview consumed cache")); next(); }
            case 4 -> { if (!waited()) break; JeiClientReview.clickTransfer(); next(); }
            case 5 -> {
                if (!waited()) break;
                server(p -> require(stock(p) == 7 && CraftingService.grid(p.containerMenu).getItems().stream()
                        .filter(s -> s.is(Items.OAK_LOG)).mapToInt(ItemStack::getCount).sum() == 1, "Real plus packet did not move one cached log"));
                require(mc.player.containerMenu.getSlot(0).getItem().is(Items.OAK_PLANKS), "Native result did not sync");
                TemplateClientReview.capture("17-fxnt-filled");
                mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId, 0, 0, ClickType.PICKUP, mc.player); next();
            }
            case 6 -> {
                if (!waited()) break;
                server(p -> require(stock(p) == 7 && p.containerMenu.getCarried().getCount() == 4 && CraftingService.grid(p.containerMenu).isEmpty(), "Result taking debited cache twice or refilled"));
                mc.player.closeContainer(); next();
            }
            case 7 -> {
                if (!waited()) break;
                server(p -> {
                    p.getInventory().clearContent(); TestPlayers.necklace(p).setStackInSlot(0, ItemStack.EMPTY); openWorkbench(p);
                }); next();
            }
            case 8 -> { if (!waited() || !(mc.screen instanceof CraftingScreen) || ClientMaterials.active()) break; JeiClientReview.openTransfer(); next(); }
            case 9 -> {
                if (!waited()) break; JeiClientReview.previewMissing(); mc.screen.onClose();
                server(p -> { p.getInventory().setItem(8, new ItemStack(Items.OAK_LOG)); p.containerMenu.broadcastChanges(); }); next();
            }
            case 10 -> { if (!waited()) break; JeiClientReview.openTransfer(); next(); }
            case 11 -> { if (!waited()) break; JeiClientReview.previewTransfer(); JeiClientReview.clickTransfer(); next(); }
            case 12 -> {
                if (!waited()) break;
                server(p -> require(stock(p) == 7 && p.getInventory().getItem(8).isEmpty()
                        && CraftingService.grid(p.containerMenu).getItems().stream().filter(s -> s.is(Items.OAK_LOG)).mapToInt(ItemStack::getCount).sum() == 1,
                        "Unworn native transfer changed cache or stopped working")); next();
            }
            case 13 -> { if (!waited()) break; mc.player.closeContainer(); next(); }
            case 14 -> { if (!waited()) break; server(FxntClientReview::setupThreeWay); next(); }
            case 15 -> {
                if (!waited() || !(mc.screen instanceof CraftingScreen) || !ClientMaterials.active()) break;
                JeiClientReview.openTransfer("chest"); next();
            }
            case 16 -> {
                if (!waited()) break;
                FeedMePackages.LOGGER.info("FMP_FXNT_CLIENT_SOURCES side=client-view cache={} worn={} container={} hints={} inventory={}",
                        ClientMaterials.cacheId(), mc.player.getItemBySlot(EquipmentSlot.CHEST),
                        mc.player.getItemBySlot(EquipmentSlot.CHEST).getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY),
                        ClientMaterials.craftingStacks(), mc.player.getInventory().getItem(8));
                JeiClientReview.previewTransfer(); TemplateClientReview.capture("18-fxnt-three-way-preview");
                server(p -> require(stock(p) == 2 && backpackStock(p) == 3, "Mixed preview consumed a source")); next();
            }
            case 17 -> { if (!waited()) break; JeiClientReview.clickTransfer(); next(); }
            case 18 -> {
                if (!waited()) break;
                server(p -> {
                    require(stock(p) == 0 && backpackStock(p) == 0 && p.getInventory().getItem(8).isEmpty()
                            && CraftingService.grid(p.containerMenu).getItems().stream().filter(s -> s.is(Items.OAK_PLANKS)).mapToInt(ItemStack::getCount).sum() == 8,
                            "Real worn-backpack packet did not combine inventory=3, cache=2, backpack=3");
                    require(p.getItemBySlot(EquipmentSlot.CHEST) == wornBackpack, "Worn backpack was replaced during transfer");
                    FeedMePackages.LOGGER.info("FMP_FXNT_CLIENT_THREE_WAY side=server-cache inventory=3 cache=2 backpack=3 grid=8 sameWornInstance=true");
                });
                require(mc.player.containerMenu.getSlot(0).getItem().is(Items.CHEST), "Mixed recipe result did not sync");
                TemplateClientReview.capture("19-fxnt-three-way-filled");
                mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId, 0, 0, ClickType.PICKUP, mc.player); next();
            }
            case 19 -> {
                if (!waited()) break;
                server(p -> require(stock(p) == 0 && backpackStock(p) == 0 && p.containerMenu.getCarried().is(Items.CHEST)
                        && p.containerMenu.getCarried().getCount() == 1 && CraftingService.grid(p.containerMenu).isEmpty(),
                        "Mixed output duplicated or silently refilled")); next();
            }
            case 20 -> { if (!waited()) break; mc.player.closeContainer(); next(); }
            case 21 -> { if (!waited()) break; server(p -> setupMixed(p, Items.OAK_LOG, 1, true)); next(); }
            case 22 -> {
                if (!waited() || !(mc.screen instanceof CraftingScreen) || !ClientMaterials.active()) break;
                JeiClientReview.openTransfer(); next();
            }
            case 23 -> { if (!waited()) break; JeiClientReview.previewTransfer(); JeiClientReview.transferMaximum(); next(); }
            case 24 -> {
                if (!waited()) break;
                server(p -> {
                    int named = p.getInventory().items.stream().filter(s -> s.is(Items.OAK_LOG) && s.has(DataComponents.CUSTOM_NAME)).mapToInt(ItemStack::getCount).sum();
                    int plain = p.getInventory().items.stream().filter(s -> s.is(Items.OAK_LOG) && !s.has(DataComponents.CUSTOM_NAME)).mapToInt(ItemStack::getCount).sum();
                    require(stock(p) == 2 && backpackStock(p) == 0 && named == 3 && plain == 1
                            && CraftingService.grid(p.containerMenu).isEmpty() && p.containerMenu.getSlot(0).getItem().isEmpty(),
                            "Mixed-component refusal duplicated backing items or lost proven published native debits");
                    require(p.getItemBySlot(EquipmentSlot.CHEST) == wornBackpack, "Native publication replaced the worn item");
                    FeedMePackages.LOGGER.info("FMP_FXNT_CLIENT_REFUND side=server-cache cache=2 ordinary=1 namedReturned=3 backing=0 grid=0 nativePublicationObserved=true");
                }); next();
            }
            case 25 -> { if (!waited()) break; mc.player.closeContainer(); next(); }
            case 26 -> { if (!waited()) break; server(FxntClientReview::setupStonecutter); next(); }
            case 27 -> {
                if (!waited() || !(mc.player.containerMenu instanceof StonecutterMenu) || !ClientMaterials.active()) break;
                JeiClientReview.openStonecutting(); next();
            }
            case 28 -> { if (!waited()) break; JeiClientReview.previewTransfer(); JeiClientReview.clickTransfer(); next(); }
            case 29 -> {
                if (!waited()) break;
                server(p -> require(stock(p) == 3 && p.containerMenu.getSlot(0).getItem().is(Items.STONE)
                        && p.containerMenu.getSlot(0).getItem().getCount() == 1, "Stonecutter packet did not take one cache item"));
                // Recipe selection and result taking remain the native stonecutter's operations.
                var menu = (StonecutterMenu) mc.player.containerMenu;
                int choice = -1;
                for (int i = 0; i < menu.getRecipes().size(); i++) if (menu.getRecipes().get(i).value().getResultItem(mc.level.registryAccess()).is(Items.STONE_SLAB)) choice = i;
                require(choice >= 0, "Native stone slab choice missing");
                mc.gameMode.handleInventoryButtonClick(menu.containerId, choice); next();
            }
            case 30 -> {
                if (!waited()) break; require(mc.player.containerMenu.getSlot(1).getItem().is(Items.STONE_SLAB), "Stonecutter result missing");
                TemplateClientReview.capture("20-fxnt-stonecutter");
                mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId, 1, 0, ClickType.PICKUP, mc.player); next();
            }
            case 31 -> {
                if (!waited()) break;
                server(p -> require(stock(p) == 3 && p.containerMenu.getCarried().is(Items.STONE_SLAB) && p.containerMenu.getCarried().getCount() == 2
                        && p.containerMenu.getSlot(0).getItem().isEmpty(), "Stonecutter consumption duplicated or refilled"));
                mc.player.closeContainer(); next();
            }
            case 32 -> { if (!waited()) break; server(FxntClientReview::setupBackpackMenu); next(); }
            case 33 -> {
                if (!waited()) break;
                var payload = Class.forName("net.fxnt.fxntstorage.network.packet.KeyPressedPacket")
                        .getConstructor(byte.class, boolean.class, java.util.Optional.class).newInstance((byte) 2, true, java.util.Optional.empty());
                net.neoforged.neoforge.network.PacketDistributor.sendToServer((net.minecraft.network.protocol.common.custom.CustomPacketPayload)payload); next();
            }
            case 34 -> {
                if (!waited() || !backpackMenu(mc.player.containerMenu) || !ClientMaterials.active()) break;
                JeiClientReview.openTransfer(); next();
            }
            case 35 -> {
                if (!waited()) break; JeiClientReview.previewMissing(); mc.screen.onClose();
                // Invoke the same native UI action as its panel button, without constructing/reloading its container.
                var type = Class.forName("net.fxnt.fxntstorage.backpack.upgrade.UpgradeType"); var crafting = type.getField("CRAFTING").get(null);
                mc.player.containerMenu.getClass().getMethod("togglePanelExpanded", type).invoke(mc.player.containerMenu, crafting); next();
            }
            case 36 -> {
                if (!waited() || !nativeReady(mc.player.containerMenu)) break;
                server(p -> require(nativeReady(p.containerMenu), "Native panel-open action did not reach the server"));
                JeiClientReview.openTransfer(); next();
            }
            case 37 -> { if (!waited()) break; JeiClientReview.previewTransfer(); JeiClientReview.clickTransfer(); next(); }
            case 38 -> {
                if (!waited()) break;
                server(p -> require(stock(p) == 7 && backpackGrid(p.containerMenu).stream().map(Slot::getItem).filter(s -> s.is(Items.OAK_LOG))
                        .mapToInt(ItemStack::getCount).sum() == 1, "Backpack crafting packet did not commit one cache log"));
                var result = backpackResult(mc.player.containerMenu); require(result.getItem().is(Items.OAK_PLANKS), "Native backpack result missing");
                TemplateClientReview.capture("21-fxnt-backpack-crafting");
                mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId, result.index, 0, ClickType.PICKUP, mc.player); next();
            }
            case 39 -> {
                if (!waited()) break;
                server(p -> require(stock(p) == 7 && p.containerMenu.getCarried().is(Items.OAK_PLANKS) && p.containerMenu.getCarried().getCount() == 4
                        && backpackGrid(p.containerMenu).stream().allMatch(s -> s.getItem().isEmpty()), "Backpack result duplicated or refilled"));
                mc.player.closeContainer(); next();
            }
            case 40 -> {
                if (!waited()) break;
                require(disabledNotices() == 0, "Healthy adapter emitted a disabled notice");
                server(p -> { FmpConfig.FXNT.set(false); FmpConfig.refreshServer(); setup(p); }); next();
            }
            case 41 -> {
                if (!waited() || !(mc.screen instanceof CraftingScreen) || ClientCompatibility.fxntSourcesAvailable()) break;
                JeiClientReview.openTransfer(); next();
            }
            case 42 -> {
                if (!waited()) break; JeiClientReview.previewMissing(); mc.screen.onClose();
                server(p -> { p.getInventory().setItem(8, new ItemStack(Items.OAK_LOG)); p.containerMenu.broadcastChanges(); }); next();
            }
            case 43 -> { if (!waited()) break; JeiClientReview.openTransfer(); next(); }
            case 44 -> { if (!waited()) break; JeiClientReview.previewTransfer(); JeiClientReview.clickTransfer(); next(); }
            case 45 -> {
                if (!waited()) break;
                server(p -> {
                    require(stock(p) == 8 && p.getInventory().getItem(8).isEmpty()
                            && CraftingService.grid(p.containerMenu).getItems().stream().filter(s -> !s.isEmpty()).allMatch(s -> s.is(Items.OAK_LOG))
                            && CraftingService.grid(p.containerMenu).getItems().stream().mapToInt(ItemStack::getCount).sum() == 1,
                            "Disabled adapter touched cache or muted native inventory");
                    FmpConfig.FXNT.set(true); FmpConfig.refreshServer();
                }); next();
            }
            case 46 -> {
                if (!waited() || !ClientCompatibility.fxntSourcesAvailable()) break;
                require(disabledNotices() == 1, "Disabled-adapter chat notice was missing or repeated");
                FeedMePackages.LOGGER.info("FMP_FXNT_MENUS_PASSED stonecutter=true backpackCrafting=true closedPanelPreserved=true disabledNativeFallback=true chatNoticeOnce=true serverStatusRestored=true");
                mc.player.closeContainer(); next();
            }
            case 47 -> {
                if (!waited()) break;
                server(p -> {
                    FmpConfig.JEI.set(false); FmpConfig.EMI.set(true); FmpConfig.refreshServer(); setup(p);
                    p.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY); p.inventoryMenu.broadcastChanges();
                    p.getInventory().setItem(8, new ItemStack(Items.OAK_LOG)); p.containerMenu.broadcastChanges();
                    @SuppressWarnings("unchecked") var recipe = (net.minecraft.world.item.crafting.RecipeHolder<net.minecraft.world.item.crafting.CraftingRecipe>)p.level().getRecipeManager()
                            .byKey(ResourceLocation.withDefaultNamespace("oak_planks")).orElseThrow();
                    require(CraftingService.place(p, recipe, false, false, true) == CraftingService.Result.OK, "Disabled JEI alias fixture failed");
                }); next();
            }
            case 48 -> { if (!waited() || !(mc.screen instanceof CraftingScreen)) break; JeiClientReview.openTransfer(); next(); }
            case 49 -> { if (!waited()) break; JeiClientReview.previewTransfer(); JeiClientReview.transferMaximum(); next(); }
            case 50 -> {
                if (!waited()) break;
                server(p -> {
                    try { require(stock(p) == 8 && CraftingService.grid(p.containerMenu).getItems().stream().mapToInt(ItemStack::getCount).sum() == 1
                            && p.getInventory().getItem(8).isEmpty(), "Disabled JEI maximum spent cache or blocked native inventory"); }
                    finally { FmpConfig.JEI.set(true); FmpConfig.EMI.set(true); FmpConfig.refreshServer(); }
                }); next();
            }
            case 51 -> { if (!waited()) break; FeedMePackages.LOGGER.info("FMP_FXNT_BROWSER_SWITCHES_PASSED jeiWorksWithEmiOff=true jeiDisabledMaximumCacheUnchanged=true"); return true; }
        }
        return false;
    }
    private static void setup(ServerPlayer player) {
        player.getInventory().clearContent(); TestPlayers.necklace(player).setStackInSlot(0, FmpRegistries.PENDANT.toStack());
        var handle = AccessGate.resolve(player).handle(); cache = handle.cacheId(); var ledger = CacheLedger.get(player.getServer()); var before = ledger.find(cache);
        var edit = before.state().edit(); var key = ItemVariantKey.of(new ItemStack(Items.OAK_LOG), player.registryAccess());
        edit.filter(0, key); edit.insert(0, key, 8); ledger.replace(handle, before.state().revision(), before.withState(edit.finish()));
        openWorkbench(player);
    }
    private static void openWorkbench(ServerPlayer player) {
        BlockPos position = player.blockPosition().east(); player.serverLevel().setBlockAndUpdate(position, Blocks.CRAFTING_TABLE.defaultBlockState());
        player.openMenu(new SimpleMenuProvider((id, inventory, owner) -> new CraftingMenu(id, inventory,
                ContainerLevelAccess.create(player.level(), position)), Component.literal("FMP fxnt source check")));
        player.containerMenu.broadcastFullState();
    }
    private static void setupStonecutter(ServerPlayer player) {
        player.getInventory().clearContent(); TestPlayers.necklace(player).setStackInSlot(0, FmpRegistries.PENDANT.toStack());
        var handle = AccessGate.resolve(player).handle(); cache = handle.cacheId(); var ledger = CacheLedger.get(player.getServer()); var before = ledger.find(cache);
        var edit = before.state().edit(); var key = ItemVariantKey.of(new ItemStack(Items.STONE), player.registryAccess());
        edit.filter(0, key); edit.insert(0, key, 4); ledger.replace(handle, before.state().revision(), before.withState(edit.finish()));
        player.inventoryMenu.broadcastChanges(); var position = player.blockPosition().east(); player.serverLevel().setBlockAndUpdate(position, Blocks.STONECUTTER.defaultBlockState());
        player.openMenu(new SimpleMenuProvider((id, inventory, owner) -> new StonecutterMenu(id, inventory, ContainerLevelAccess.create(player.level(), position)), Component.literal("FMP native stonecutting")));
        player.containerMenu.broadcastFullState();
    }
    static void setupBackpackMenu(ServerPlayer player) {
        setup(player); player.closeContainer();
        var initialContents = net.minecraft.core.NonNullList.withSize(133, ItemStack.EMPTY);
        initialContents.set(132, new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("fxntstorage", "backpack_crafting_upgrade"))));
        require(!initialContents.get(132).isEmpty(), "Pinned crafting upgrade missing");
        wornBackpack = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("fxntstorage", "backpack")));
        wornBackpack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(initialContents));
        player.setItemSlot(EquipmentSlot.CHEST, wornBackpack); player.inventoryMenu.broadcastChanges();
    }
    private static boolean backpackMenu(AbstractContainerMenu menu) { return menu.getClass().getName().equals("net.fxnt.fxntstorage.backpack.client.menu.BackpackMenu"); }
    static boolean nativeReady(AbstractContainerMenu menu) {
        try { return (Boolean) menu.getClass().getMethod("isCraftingPanelReady").invoke(menu); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    @SuppressWarnings("unchecked") static java.util.List<Slot> backpackGrid(AbstractContainerMenu menu) {
        try { return (java.util.List<Slot>)menu.getClass().getMethod("getCraftingMatrixSlots").invoke(menu); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    static Slot backpackResult(AbstractContainerMenu menu) {
        try { return (Slot)menu.getClass().getMethod("getCraftingResultSlot").invoke(menu); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    static void setupThreeWay(ServerPlayer player) {
        setupMixed(player, Items.OAK_PLANKS, 3, false);
    }
    private static void setupMixed(ServerPlayer player, Item material, int ordinary, boolean named) {
        player.getInventory().clearContent(); TestPlayers.necklace(player).setStackInSlot(0, FmpRegistries.PENDANT.toStack());
        var handle = AccessGate.resolve(player).handle(); cache = handle.cacheId(); var ledger = CacheLedger.get(player.getServer()); var before = ledger.find(cache);
        var edit = before.state().edit(); var key = ItemVariantKey.of(new ItemStack(material), player.registryAccess());
        edit.filter(0, key); edit.insert(0, key, 2); ledger.replace(handle, before.state().revision(), before.withState(edit.finish()));
        player.getInventory().setItem(8, new ItemStack(material, ordinary));
        // Fixture creation before equip: never reload an existing third-party container or attachment.
        wornBackpack = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("fxntstorage", "backpack")));
        require(!wornBackpack.isEmpty(), "Pinned backpack item missing");
        var stored = new ItemStack(material, 3);
        if (named) stored.set(DataComponents.CUSTOM_NAME, Component.literal("Preserve exact native items"));
        wornBackpack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(java.util.List.of(stored)));
        player.setItemSlot(EquipmentSlot.CHEST, wornBackpack);
        // A workbench's slot sync does not contain armor. Send the fixture equip before opening it.
        player.inventoryMenu.broadcastChanges(); openWorkbench(player);
    }
    private static int backpackStock(ServerPlayer player) {
        return player.getItemBySlot(EquipmentSlot.CHEST).getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY)
                .stream().filter(s -> !s.isEmpty()).mapToInt(ItemStack::getCount).sum();
    }
    private static long stock(ServerPlayer player) { return CacheLedger.get(player.getServer()).find(cache).state().cells().get(0).amount(); }
    @SuppressWarnings("unchecked") private static long disabledNotices() throws ReflectiveOperationException {
        var chat = Minecraft.getInstance().gui.getChat(); var field = chat.getClass().getDeclaredField("allMessages"); field.setAccessible(true);
        var messages = (java.util.List<net.minecraft.client.GuiMessage>) field.get(chat);
        String expected = Component.translatable("gui.create_feed_me_packages.compat.fxnt_config_disabled").getString();
        return messages.stream().filter(message -> message.content().getString().equals(expected)).count();
    }
    private static void server(Consumer<ServerPlayer> operation) {
        var mc = Minecraft.getInstance(); var id = mc.player.getUUID(); var server = mc.getSingleplayerServer();
        work = CompletableFuture.runAsync(() -> operation.accept(server.getPlayerList().getPlayer(id)), server);
    }
    private static boolean waited() { return tick - changed >= 25; }
    private static void next() { stage++; changed = tick; FeedMePackages.LOGGER.info("FMP_FXNT_CLIENT_STAGE {}", stage); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
