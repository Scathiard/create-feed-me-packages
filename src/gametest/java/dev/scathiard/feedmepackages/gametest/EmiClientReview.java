package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.client.ClientMaterials;
import dev.scathiard.feedmepackages.client.LogisticsPanel;
import dev.scathiard.feedmepackages.consumption.CraftingService;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.fml.ModList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Loaded reflectively only in EMI review mode, so optional classes cannot leak into ordinary clients. */
public final class EmiClientReview {
    private static int stage, tick, changed;
    private static CompletableFuture<Void> work;
    private static UUID cacheId;
    private static ItemStack nativeBackpack;
    /** Focused EMI run reuses the native-world driver and the existing reservation fixtures. */
    public static boolean tick() throws ReflectiveOperationException {
        tick++; if (work != null) { if (!work.isDone()) return false; work.join(); work = null; }
        var mc = Minecraft.getInstance();
        if (!waited(20) && stage != 0) return false;
        switch (stage) {
            case 0 -> {
                require(ModList.get().isLoaded("emi"), "EMI client review needs the real optional mod");
                mc.player.closeContainer(); server(p -> emiSetup(p, Items.OAK_LOG, 12, 2)); next();
            }
            case 1 -> { mc.setScreen(new InventoryScreen(mc.player)); next(); }
            case 2 -> {
                if (!LogisticsPanel.recipeReady() || !ClientMaterials.active()) return false;
                if (!dev.scathiard.feedmepackages.client.ClientCompatibility.emiSourcesAvailable()) return false;
                require(emiSelected("oak_planks").getClass().getName().equals(expectedHandler(false)), "EMI native inventory handler was replaced");
                require(emiLogCount() == 14, "EMI inventory hint lost the mixed source");
                server(p -> require(ConsumptionTests.stock(p, 0) == 12 && CraftingService.grid(p.containerMenu).isEmpty(), "EMI craftability query moved material"));
                emiOpen("oak_planks"); next();
            }
            case 3 -> { emiClickFill(); next(); }
            case 4 -> {
                if (!LogisticsPanel.recipeReady()) return false;
                require(mc.screen instanceof InventoryScreen, "EMI plus did not return to the inventory screen");
                server(p -> require(ConsumptionTests.stock(p, 0) == 12 && emiInventoryCount(p, Items.OAK_LOG) == 1
                        && emiGridCount(p) == 1, "EMI native fill did not move one physical input"));
                emiOpen("oak_planks"); next();
            }
            case 5 -> { emiClickFill(); next(); }
            case 6 -> {
                if (!LogisticsPanel.recipeReady()) return false;
                require(emiLogCount() == 14, "EMI counted backed grid previews a second time");
                server(p -> require(emiGridCount(p) == (ModList.get().isLoaded("fxntstorage") ? 1 : 2) && ConsumptionTests.stock(p, 0) == 12,
                        "Repeated fill changed the selected native handler's semantics"));
                TemplateClientReview.capture("emi-two-prepared"); TemplateClientReview.nativeSlotClick(0); next();
            }
            case 7 -> {
                server(p -> require(ConsumptionTests.stock(p, 0) == 12 && emiInventoryCount(p, Items.OAK_LOG) == (ModList.get().isLoaded("fxntstorage") ? 1 : 0)
                        && p.containerMenu.getCarried().is(Items.OAK_PLANKS) && p.containerMenu.getCarried().getCount() == 4,
                        "EMI-filled native result did not consume inventory first"));
                mc.player.closeContainer(); mc.setScreen(null); next();
            }
            case 8 -> {
                server(p -> {
                    require(ConsumptionTests.stock(p, 0) == 12 && emiInventoryCount(p, Items.OAK_LOG) == 1
                            && emiInventoryCount(p, Items.OAK_PLANKS) == 4 && CraftingService.grid(p.containerMenu).isEmpty(),
                            "Closing EMI grid materialized unused preparation");
                    emiSetup(p, Items.OAK_LOG, 12, 0);
                }); next();
            }
            case 9 -> { mc.setScreen(new InventoryScreen(mc.player)); next(); }
            case 10 -> {
                if (!LogisticsPanel.recipeReady()) return false;
                require(emiDispatch("oak_planks", dev.emi.emi.api.recipe.handler.EmiCraftContext.Destination.CURSOR, 1), "EMI direct cursor dispatch was rejected"); next();
            }
            case 11 -> {
                require(mc.player.containerMenu.getCarried().is(Items.OAK_PLANKS) && mc.player.containerMenu.getCarried().getCount() == 4,
                        "Direct native craft cursor did not sync to client");
                server(p -> require(ConsumptionTests.stock(p, 0) == 11 && p.containerMenu.getCarried().getCount() == 4, "Direct craft charged wrong amount"));
                TemplateClientReview.capture("emi-direct-cursor"); mc.player.closeContainer(); mc.setScreen(null); next();
            }
            case 12 -> { server(p -> emiSetup(p, Items.OAK_LOG, 12, 0)); next(); }
            case 13 -> { mc.setScreen(new InventoryScreen(mc.player)); next(); }
            case 14 -> {
                if (!LogisticsPanel.recipeReady()) return false;
                require(emiDispatch("oak_planks", dev.emi.emi.api.recipe.handler.EmiCraftContext.Destination.INVENTORY, Integer.MAX_VALUE), "EMI direct inventory dispatch was rejected"); next();
            }
            case 15 -> {
                server(p -> require(ConsumptionTests.stock(p, 0) == 0 && emiInventoryCount(p, Items.OAK_PLANKS) == 48
                        && CraftingService.grid(p.containerMenu).isEmpty(), "Direct maximum craft did not conserve all twelve logs"));
                TemplateClientReview.capture("emi-direct-inventory"); mc.player.closeContainer(); mc.setScreen(null); next();
            }
            case 16 -> {
                server(p -> {
                    emiSetup(p, Items.OAK_PLANKS, 7, 1);
                    var pos = p.blockPosition().below(); p.serverLevel().setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.CRAFTING_TABLE.defaultBlockState());
                    p.openMenu(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE.defaultBlockState().getMenuProvider(p.serverLevel(), pos));
                }); next();
            }
            case 17 -> {
                if (!LogisticsPanel.recipeReady() || !(mc.screen instanceof net.minecraft.client.gui.screens.inventory.CraftingScreen)) return false;
                require(emiSelected("chest").getClass().getName().equals(expectedHandler(true)), "EMI native workbench handler was replaced");
                emiOpen("chest"); next();
            }
            case 18 -> { emiClickFill(); next(); }
            case 19 -> {
                if (!LogisticsPanel.recipeReady()) return false;
                server(p -> require(emiGridCount(p) == 8 && ConsumptionTests.stock(p, 0) == 0
                        && emiInventoryCount(p, Items.OAK_PLANKS) == 0, "3x3 EMI native fill did not move exact mixed inputs"));
                TemplateClientReview.capture("emi-workbench-prepared"); TemplateClientReview.nativeSlotClick(0); next();
            }
            case 20 -> {
                server(p -> require(ConsumptionTests.stock(p, 0) == 0 && emiInventoryCount(p, Items.OAK_PLANKS) == 0
                        && p.containerMenu.getCarried().is(Items.CHEST) && p.containerMenu.getCarried().getCount() == 1,
                        "3x3 EMI native craft did not consume exactly seven cached and one carried plank"));
                mc.player.closeContainer(); mc.setScreen(null); next();
            }
            case 21 -> {
                server(p -> {
                    emiSetup(p, Items.OAK_LOG, 12, 1); TestPlayers.necklace(p).setStackInSlot(0, ItemStack.EMPTY);
                    p.containerMenu.broadcastFullState();
                }); next();
            }
            case 22 -> { if (ClientMaterials.active()) return false; mc.setScreen(new InventoryScreen(mc.player)); next(); }
            case 23 -> {
                require(!emiSelected("oak_planks").getClass().getName().endsWith("FmpEmiRecipeHandler"), "Unworn EMI retained FMP priority");
                emiOpen("oak_planks"); next();
            }
            case 24 -> { emiClickFill(); next(); }
            case 25 -> {
                server(p -> require(emiGridCount(p) == 1 && p.containerMenu.getSlot(0).getItem().is(Items.OAK_PLANKS)
                        && CacheLedger.get(p.getServer()).find(cacheId).state().cells().getFirst().amount() == 12,
                        "Unworn native EMI failed ordinary backpack fill or spent cache"));
                TemplateClientReview.capture("emi-unworn-native-fill"); next();
            }
            case 26 -> {
                mc.player.closeContainer(); mc.setScreen(null);
                if (!ModList.get().isLoaded("fxntstorage")) { stage = 33; changed = tick; } else next();
            }
            case 27 -> {
                server(p -> { FxntClientReview.setupThreeWay(p); nativeBackpack = p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST); }); next();
            }
            case 28 -> {
                if (!LogisticsPanel.recipeReady() || !(mc.screen instanceof net.minecraft.client.gui.screens.inventory.CraftingScreen)) return false;
                require(emiSelected("chest").getClass().getName().equals(expectedHandler(true)), "Third-party EMI handler was replaced");
                server(p -> require(ConsumptionTests.stock(p, 0) == 2 && emiInventoryCount(p, Items.OAK_PLANKS) == 3 && backpackCount(p) == 3, "Three-way preview mutated sources"));
                emiOpen("chest"); next();
            }
            case 29 -> { emiClickFill(); next(); }
            case 30 -> {
                server(p -> require(ConsumptionTests.stock(p, 0) == 0 && emiInventoryCount(p, Items.OAK_PLANKS) == 0 && backpackCount(p) == 0
                        && emiGridCount(p) == 8 && p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST) == nativeBackpack, "EMI three-way fill lost material or replaced worn backpack"));
                TemplateClientReview.capture("emi-fxnt-three-way"); TemplateClientReview.nativeSlotClick(0); next();
            }
            case 31 -> {
                server(p -> require(ConsumptionTests.stock(p, 0) == 0 && emiGridCount(p) == 0 && p.containerMenu.getCarried().is(Items.CHEST)
                        && p.containerMenu.getCarried().getCount() == 1 && backpackCount(p) == 0, "EMI three-way result refilled or spent twice")); next();
            }
            case 32 -> {
                FeedMePackages.LOGGER.info("FMP_EMI_FXNT_CLIENT_PASSED nativeHandler=true inventory=3 cache=2 backpack=3 grid=8 sameInstance=true");
                mc.player.closeContainer(); mc.setScreen(null); next();
            }
            case 33 -> { server(EmiClientReview::aliasFixture); next(); }
            case 34 -> {
                if (!(mc.screen instanceof InventoryScreen)) mc.setScreen(new InventoryScreen(mc.player));
                if (!LogisticsPanel.recipeReady() || !ClientMaterials.active()) return false;
                require(emiLogCount() == 10, "Recipe-book alias was counted twice in EMI preview"); emiOpen("oak_planks"); next();
            }
            case 35 -> { emiClickFill(); next(); }
            case 36 -> {
                server(p -> {
                    // A recipe-book alias is not an already paid EMI batch. First fill moves one real input.
                    FeedMePackages.LOGGER.info("FMP_EMI_ALIAS_CHECK cache={} grid={} ordinary={}", ConsumptionTests.stock(p, 0), emiGridCount(p), emiInventoryCount(p, Items.OAK_LOG));
                    require(ConsumptionTests.stock(p, 0) == 8 && emiGridCount(p) == 1 && emiInventoryCount(p, Items.OAK_LOG) == 1,
                            "Recipe-book alias became a physical duplicate during EMI fill");
                }); mc.player.closeContainer(); mc.setScreen(null); next();
            }
            case 37 -> {
                server(p -> { emiSetup(p, Items.OAK_LOG, 8, 1); prepareAlias(p); dev.scathiard.feedmepackages.FmpConfig.EMI.set(false);
                    dev.scathiard.feedmepackages.FmpConfig.refreshServer(); }); next();
            }
            case 38 -> {
                if (dev.scathiard.feedmepackages.client.ClientCompatibility.emiSourcesAvailable()) return false;
                mc.setScreen(new InventoryScreen(mc.player)); next();
            }
            case 39 -> {
                require(emiLogCount() == 1, "Disabled EMI preview still counts cache");
                var recipe = emiRecipe("oak_planks"); var handler = emiSelected(recipe);
                var grid = CraftingService.grid(mc.player.containerMenu); var alias = grid.getItem(0);
                var filler = Class.forName("dev.emi.emi.registry.EmiRecipeFiller");
                var stacks = (List<?>)filler.getMethod("getStacks", dev.emi.emi.api.recipe.handler.StandardRecipeHandler.class,
                        dev.emi.emi.api.recipe.EmiRecipe.class, net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class, int.class)
                        .invoke(null, handler, recipe, dev.emi.emi.api.EmiApi.getHandledScreen(), Integer.MAX_VALUE);
                require(stacks != null && stacks.stream().map(ItemStack.class::cast).filter(s -> s.is(Items.OAK_LOG)).mapToInt(ItemStack::getCount).sum() == 1,
                        "Maximum fill counted our alias as an already paid batch");
                require(grid.getItem(0) == alias && alias.getCount() == 1, "Query changed the real recipe-book grid");
                emiOpen("oak_planks"); next();
            }
            case 40 -> {
                if (ModList.get().isLoaded("fxntstorage"))
                    require(emiDispatch("oak_planks", dev.emi.emi.api.recipe.handler.EmiCraftContext.Destination.NONE, Integer.MAX_VALUE), "Disabled-cache maximum fill rejected ordinary input");
                else emiClickFill();
                next();
            }
            case 41 -> {
                require(disabledNotices() == 1, "EMI disabled notice missing or repeated");
                server(p -> {
                    FeedMePackages.LOGGER.info("FMP_EMI_DISABLED_CHECK cache={} grid={} ordinary={} fxnt={}", ConsumptionTests.stock(p, 0), emiGridCount(p), emiInventoryCount(p, Items.OAK_LOG), ModList.get().isLoaded("fxntstorage"));
                    try { require(ConsumptionTests.stock(p, 0) == 8 && emiGridCount(p) == 1 && emiInventoryCount(p, Items.OAK_LOG) == 0, "Disabled EMI spent cache or muted native inventory fill"); }
                    finally { dev.scathiard.feedmepackages.FmpConfig.EMI.set(true); dev.scathiard.feedmepackages.FmpConfig.refreshServer(); }
                }); next();
            }
            case 42 -> {
                if (!dev.scathiard.feedmepackages.client.ClientCompatibility.emiSourcesAvailable()) return false;
                FeedMePackages.LOGGER.info("FMP_EMI_SOURCE_CLIENT_PASSED nativeHandler=true aliasConserved=true independentSwitch=true chatNoticeOnce=true fxnt={}", ModList.get().isLoaded("fxntstorage"));
                mc.player.closeContainer(); mc.setScreen(null); next();
            }
            case 43 -> { server(p -> { dev.scathiard.feedmepackages.FmpConfig.JEI.set(false);
                dev.scathiard.feedmepackages.FmpConfig.refreshServer(); stoneFixture(p); }); next(); }
            case 44 -> {
                if (!(mc.player.containerMenu instanceof net.minecraft.world.inventory.StonecutterMenu) || !ClientMaterials.active()) return false;
                var recipe = dev.emi.emi.api.EmiApi.getRecipeManager().getRecipes(dev.emi.emi.api.recipe.VanillaEmiRecipeCategories.STONECUTTING).stream()
                        .filter(r -> r.getInputs().stream().flatMap(i -> i.getEmiStacks().stream()).anyMatch(s -> s.getItemStack().is(Items.STONE))
                                && r.getOutputs().stream().anyMatch(s -> s.getItemStack().is(Items.STONE_SLAB))).findFirst().orElseThrow();
                String expected = ModList.get().isLoaded("fxntstorage") ? "net.fxnt.fxntstorage.compat.emi.EMIStonecuttingRecipeHandler" : "dev.emi.emi.handler.StonecuttingRecipeHandler";
                require(emiSelected(recipe).getClass().getName().equals(expected), "Stonecutting handler was replaced");
                dev.emi.emi.api.EmiApi.displayRecipe(recipe); next();
            }
            case 45 -> { emiClickFill(); next(); }
            case 46 -> {
                server(p -> require(ConsumptionTests.stock(p, 0) == 3 && p.containerMenu.getSlot(0).getItem().is(Items.STONE)
                        && p.containerMenu.getSlot(0).getItem().getCount() == 1, "EMI stonecutting fill did not take one exact cache item"));
                var menu = (net.minecraft.world.inventory.StonecutterMenu)mc.player.containerMenu; int choice = -1;
                for (int i = 0; i < menu.getRecipes().size(); i++) if (menu.getRecipes().get(i).value().getResultItem(mc.level.registryAccess()).is(Items.STONE_SLAB)) choice = i;
                require(choice >= 0, "Native stone slab selection missing"); mc.gameMode.handleInventoryButtonClick(menu.containerId, choice); next();
            }
            case 47 -> {
                require(mc.player.containerMenu.getSlot(1).getItem().is(Items.STONE_SLAB), "Stonecutting result did not synchronize");
                TemplateClientReview.capture("emi-stonecutter");
                mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId, 1, 0, net.minecraft.world.inventory.ClickType.PICKUP, mc.player); next();
            }
            case 48 -> {
                server(p -> require(ConsumptionTests.stock(p, 0) == 3 && p.containerMenu.getCarried().is(Items.STONE_SLAB)
                        && p.containerMenu.getCarried().getCount() == 2 && p.containerMenu.getSlot(0).getItem().isEmpty(), "Stonecutting result duplicated or refilled"));
                mc.player.closeContainer(); mc.setScreen(null); next();
            }
            case 49 -> {
                if (!ModList.get().isLoaded("fxntstorage")) {
                    server(p -> { dev.scathiard.feedmepackages.FmpConfig.JEI.set(true); dev.scathiard.feedmepackages.FmpConfig.refreshServer(); });
                    stage = 57; return false;
                }
                server(FxntClientReview::setupBackpackMenu); next();
            }
            case 50 -> {
                var payload = Class.forName("net.fxnt.fxntstorage.network.packet.KeyPressedPacket").getConstructor(byte.class, boolean.class, java.util.Optional.class)
                        .newInstance((byte)2, true, java.util.Optional.empty());
                net.neoforged.neoforge.network.PacketDistributor.sendToServer((net.minecraft.network.protocol.common.custom.CustomPacketPayload)payload); next();
            }
            case 51 -> {
                if (!mc.player.containerMenu.getClass().getName().equals("net.fxnt.fxntstorage.backpack.client.menu.BackpackMenu") || !ClientMaterials.active()) return false;
                require(emiSelected("oak_planks") == null, "Closed backpack crafting panel was accepted by EMI");
                var type = Class.forName("net.fxnt.fxntstorage.backpack.upgrade.UpgradeType");
                mc.player.containerMenu.getClass().getMethod("togglePanelExpanded", type).invoke(mc.player.containerMenu, type.getField("CRAFTING").get(null)); next();
            }
            case 52 -> {
                if (!FxntClientReview.nativeReady(mc.player.containerMenu)) return false;
                server(p -> require(FxntClientReview.nativeReady(p.containerMenu), "Native panel action did not synchronize"));
                require(emiSelected("oak_planks").getClass().getName().equals("net.fxnt.fxntstorage.compat.emi.EMIBackpackCraftingRecipeHandler"), "Backpack EMI handler was replaced");
                emiOpen("oak_planks"); next();
            }
            case 53 -> { emiClickFill(); next(); }
            case 54 -> {
                server(p -> require(ConsumptionTests.stock(p, 0) == 7 && FxntClientReview.backpackGrid(p.containerMenu).stream().map(net.minecraft.world.inventory.Slot::getItem)
                        .filter(s -> s.is(Items.OAK_LOG)).mapToInt(ItemStack::getCount).sum() == 1, "Backpack EMI fill spent wrong amount"));
                var slot = FxntClientReview.backpackResult(mc.player.containerMenu); require(slot.getItem().is(Items.OAK_PLANKS), "Backpack result missing");
                TemplateClientReview.capture("emi-backpack-crafting");
                mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId, slot.index, 0, net.minecraft.world.inventory.ClickType.PICKUP, mc.player); next();
            }
            case 55 -> {
                server(p -> require(ConsumptionTests.stock(p, 0) == 7 && p.containerMenu.getCarried().is(Items.OAK_PLANKS) && p.containerMenu.getCarried().getCount() == 4
                        && FxntClientReview.backpackGrid(p.containerMenu).stream().allMatch(s -> s.getItem().isEmpty()), "Backpack EMI result duplicated or refilled")); next();
            }
            case 56 -> { server(p -> { dev.scathiard.feedmepackages.FmpConfig.JEI.set(true); dev.scathiard.feedmepackages.FmpConfig.refreshServer(); }); next(); }
            case 57 -> { FeedMePackages.LOGGER.info("FMP_EMI_MENUS_CLIENT_PASSED stonecutter=true backpackCrafting={} closedPanelPreserved=true jeiDisabledDuringMenus=true", ModList.get().isLoaded("fxntstorage")); return true; }
        }
        return false;
    }
    private static void emiSetup(ServerPlayer player, net.minecraft.world.item.Item item, int cache, int bag) {
        player.setGameMode(GameType.SURVIVAL); player.setInvulnerable(true); player.getInventory().clearContent();
        TestPlayers.necklace(player).setStackInSlot(0, FmpRegistries.PENDANT.toStack());
        ConsumptionTests.seed(player, 0, new ItemStack(item), cache); cacheId = AccessGate.resolve(player).handle().cacheId();
        if (bag > 0) player.getInventory().setItem(8, new ItemStack(item, bag));
        player.containerMenu.broadcastFullState();
    }
    private static int emiInventoryCount(ServerPlayer player, net.minecraft.world.item.Item item) {
        return player.getInventory().items.stream().filter(s -> s.is(item)).mapToInt(ItemStack::getCount).sum();
    }
    private static String expectedHandler(boolean workbench) {
        return ModList.get().isLoaded("fxntstorage") ? "net.fxnt.fxntstorage.compat.emi.EMI" + (workbench ? "Crafting" : "Inventory") + "RecipeHandler"
                : "dev.emi.emi.handler." + (workbench ? "Crafting" : "Inventory") + "RecipeHandler";
    }
    private static int backpackCount(ServerPlayer player) {
        return player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).getOrDefault(net.minecraft.core.component.DataComponents.CONTAINER,
                net.minecraft.world.item.component.ItemContainerContents.EMPTY).stream().filter(s -> !s.isEmpty()).mapToInt(ItemStack::getCount).sum();
    }
    private static void aliasFixture(ServerPlayer player) {
        emiSetup(player, Items.OAK_LOG, 8, 2);
        prepareAlias(player);
    }
    @SuppressWarnings("unchecked") private static void prepareAlias(ServerPlayer player) {
        var recipe = (net.minecraft.world.item.crafting.RecipeHolder<net.minecraft.world.item.crafting.CraftingRecipe>)player.level().getRecipeManager()
                .byKey(ResourceLocation.withDefaultNamespace("oak_planks")).orElseThrow();
        require(CraftingService.place(player, recipe, false, false, true) == CraftingService.Result.OK, "Recipe-book fixture failed");
    }
    private static void stoneFixture(ServerPlayer player) {
        emiSetup(player, Items.STONE, 4, 0); player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, ItemStack.EMPTY); player.inventoryMenu.broadcastChanges();
        var pos = player.blockPosition().east(); player.serverLevel().setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.STONECUTTER.defaultBlockState());
        player.openMenu(new net.minecraft.world.SimpleMenuProvider((id, inventory, owner) -> new net.minecraft.world.inventory.StonecutterMenu(id, inventory,
                net.minecraft.world.inventory.ContainerLevelAccess.create(player.level(), pos)), net.minecraft.network.chat.Component.literal("FMP EMI stonecutting")));
    }
    @SuppressWarnings("unchecked") private static long disabledNotices() throws ReflectiveOperationException {
        var chat = Minecraft.getInstance().gui.getChat(); var field = chat.getClass().getDeclaredField("allMessages"); field.setAccessible(true);
        var messages = (List<net.minecraft.client.GuiMessage>)field.get(chat);
        String text = net.minecraft.network.chat.Component.translatable("gui.create_feed_me_packages.compat.emi_config_disabled").getString();
        return messages.stream().filter(m -> m.content().getString().equals(text)).count();
    }
    private static int emiGridCount(ServerPlayer player) {
        return CraftingService.grid(player.containerMenu).getItems().stream().filter(s -> !s.isEmpty()).mapToInt(ItemStack::getCount).sum();
    }
    private static dev.emi.emi.api.recipe.EmiRecipe emiRecipe(String id) {
        var recipe = dev.emi.emi.api.EmiApi.getRecipeManager().getRecipe(ResourceLocation.withDefaultNamespace(id));
        require(recipe != null, "Native EMI recipe was not indexed: " + id); return recipe;
    }
    private static Object emiSelected(String id) throws ReflectiveOperationException {
        return emiSelected(emiRecipe(id));
    }
    private static Object emiSelected(dev.emi.emi.api.recipe.EmiRecipe recipe) throws ReflectiveOperationException {
        return Class.forName("dev.emi.emi.registry.EmiRecipeFiller").getMethod("getFirstValidHandler",
                dev.emi.emi.api.recipe.EmiRecipe.class, net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class)
                .invoke(null, recipe, dev.emi.emi.api.EmiApi.getHandledScreen());
    }
    private static long emiLogCount() {
        return dev.emi.emi.api.recipe.EmiPlayerInventory.of(Minecraft.getInstance().player).inventory.values().stream()
                .filter(s -> s.getItemStack().is(Items.OAK_LOG)).mapToLong(dev.emi.emi.api.stack.EmiStack::getAmount).sum();
    }
    private static void emiOpen(String id) { dev.emi.emi.api.EmiApi.displayRecipe(emiRecipe(id)); }
    private static boolean emiDispatch(String id, dev.emi.emi.api.recipe.handler.EmiCraftContext.Destination destination, int amount) throws ReflectiveOperationException {
        return (boolean) Class.forName("dev.emi.emi.registry.EmiRecipeFiller").getMethod("performFill",
                dev.emi.emi.api.recipe.EmiRecipe.class, net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class,
                dev.emi.emi.api.recipe.handler.EmiCraftContext.Type.class, dev.emi.emi.api.recipe.handler.EmiCraftContext.Destination.class, int.class)
                .invoke(null, emiRecipe(id), dev.emi.emi.api.EmiApi.getHandledScreen(),
                        dev.emi.emi.api.recipe.handler.EmiCraftContext.Type.CRAFTABLE, destination, amount);
    }
    private static void emiClickFill() throws ReflectiveOperationException {
        var screen = Minecraft.getInstance().screen;
        require(screen != null && screen.getClass().getName().equals("dev.emi.emi.screen.RecipeScreen"), "Native EMI recipe screen is not open");
        var field = screen.getClass().getDeclaredField("currentPage"); field.setAccessible(true);
        for (var group : (List<?>) field.get(screen)) {
            var type = group.getClass();
            int x = type.getField("x").getInt(group), y = type.getField("y").getInt(group);
            for (var widget : (List<?>) type.getField("widgets").get(group))
                if (widget.getClass().getName().equals("dev.emi.emi.api.widget.RecipeFillButtonWidget")) {
                    var button = (dev.emi.emi.api.widget.Widget) widget;
                    var enabled = widget.getClass().getDeclaredField("canFill"); enabled.setAccessible(true);
                    require(enabled.getBoolean(widget), "Native EMI fill button considers the recipe unavailable");
                    var bounds = button.getBounds();
                    TemplateClientReview.click(x + bounds.x() + bounds.width()/2.0, y + bounds.y() + bounds.height()/2.0); return;
                }
        }
        throw new IllegalStateException("Native EMI fill widget not present");
    }
    private static boolean waited(int count) { return tick - changed >= count; }
    private static void next() { stage++; changed = tick; FeedMePackages.LOGGER.info("FMP_EMI_CLIENT_STAGE {}", stage); }
    private static void require(boolean ok, String error) { if (!ok) throw new IllegalStateException(error); }
    private static void server(Consumer<ServerPlayer> operation) {
        var mc = Minecraft.getInstance(); var id = mc.player.getUUID(); var server = mc.getSingleplayerServer();
        work = CompletableFuture.runAsync(() -> operation.accept(server.getPlayerList().getPlayer(id)), server);
    }
}
