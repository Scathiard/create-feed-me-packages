package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.client.ClientMaterials;
import dev.scathiard.feedmepackages.client.LogisticsPanel;
import dev.scathiard.feedmepackages.consumption.CraftingService;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.RecipeBookCategories;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.GameType;
import net.neoforged.fml.ModList;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Actual client/server packets and engine inputs, only in the explicitly enabled isolated review driver. */
final class ClientConsumptionReview {
    private static int stage, tick, changed;
    private static int jeiCacheScenario;
    private static CompletableFuture<Void> work;
    private static UUID cacheId;
    private static boolean bookOpened;
    private static ItemStack heldBow, heldCrossbow, unrelated;
    static boolean tick() {
        tick++; if (work != null) { if (!work.isDone()) return false; work.join(); work = null; }
        var mc = Minecraft.getInstance();
        switch (stage) {
            case 0 -> { mc.player.closeContainer(); next(); }
            case 1 -> { if (!waited(20)) break; server(ClientConsumptionReview::setup); next(); }
            case 2 -> {
                if (!waited(20)) break;
                require(ClientMaterials.active() && mc.player.getProjectile(mc.player.getMainHandItem()).is(Items.ARROW), "Closed-GUI client did not see cache-only ammunition");
                mc.options.keyUse.setDown(true); mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND); next();
            }
            case 3 -> {
                if (!waited(25)) break;
                require(mc.player.isUsingItem(), "Cache-only bow use did not start the client animation");
                mc.options.keyUse.setDown(false); mc.gameMode.releaseUsingItem(mc.player); next();
            }
            case 4 -> {
                if (!waited(20)) break;
                require(mc.player.getMainHandItem().getDamageValue() == 1, "Native bow durability did not synchronize");
                server(p -> {
                    require(ConsumptionTests.stock(p, 1) == 7 && ConsumptionTests.stock(p, 0) == 12, "Real bow network shot used wrong quantities");
                    require(p.getMainHandItem() == heldBow && heldBow.getDamageValue() == 1 && p.getInventory().getItem(10) == unrelated,
                            "Cache shot replaced the native weapon/unrelated instance");
                    heldCrossbow = new ItemStack(Items.CROSSBOW); p.setItemInHand(InteractionHand.MAIN_HAND, heldCrossbow); p.containerMenu.broadcastFullState();
                }); stage = 401; changed = tick;
            }
            case 401 -> {
                if (!waited(20) || !mc.player.getMainHandItem().is(Items.CROSSBOW)) break;
                mc.options.keyUse.setDown(true); mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND); stage = 402; changed = tick;
            }
            case 402 -> {
                if (!waited(35)) break;
                require(mc.player.isUsingItem(), "Cache crossbow did not enter native use");
                mc.options.keyUse.setDown(false); mc.gameMode.releaseUsingItem(mc.player); stage = 403; changed = tick;
            }
            case 403 -> {
                if (!waited(20)) break;
                require(net.minecraft.world.item.CrossbowItem.isCharged(mc.player.getMainHandItem()), "Charged crossbow did not synchronize");
                server(p -> require(p.getMainHandItem() == heldCrossbow && net.minecraft.world.item.CrossbowItem.isCharged(heldCrossbow)
                        && ConsumptionTests.stock(p, 1) == 6 && p.getInventory().getItem(10) == unrelated, "Native cached charge lost held state/instance"));
                stage = 404; changed = tick;
            }
            case 404 -> {
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND); stage = 405; changed = tick;
            }
            case 405 -> {
                if (!waited(20)) break;
                require(!net.minecraft.world.item.CrossbowItem.isCharged(mc.player.getMainHandItem()) && mc.player.getMainHandItem().getDamageValue() == 1,
                        "Crossbow firing state/durability did not synchronize");
                server(p -> require(p.getMainHandItem() == heldCrossbow && heldCrossbow.getDamageValue() == 1 && ConsumptionTests.stock(p, 1) == 6
                        && p.getInventory().getItem(10) == unrelated, "Native shot recharged ammo or replaced an unrelated instance"));
                FeedMePackages.LOGGER.info("FMP_NATIVE_WEAPON_INSTANCES_PASSED bowDamage=1 crossbowDamage=1 cachedArrows=6");
                mc.setScreen(new InventoryScreen(mc.player)); stage = 5; changed = tick;
            }
            case 5 -> {
                if (!waited(20)) break;
                var screen = (InventoryScreen) mc.screen;
                if (!bookOpened) {
                    if (!screen.getRecipeBookComponent().isVisible()) ClientReview.click(screen.getGuiLeft() + 114, screen.height / 2 - 13);
                    bookOpened = true; changed = tick; break;
                }
                require(screen.getRecipeBookComponent().isVisible(), "Recipe-book button did not keep the real page visible");
                var recipe = recipe();
                boolean craftable = mc.player.getRecipeBook().getCollection(RecipeBookCategories.CRAFTING_SEARCH).stream()
                        .anyMatch(c -> c.getRecipes().stream().anyMatch(r -> r.id().equals(recipe.id())) && c.isCraftable(recipe));
                require(craftable, "Vanilla recipe book cannot see cache-only materials");
                var bookX = (screen.width - 147) / 2 - (screen.width < 379 ? 0 : 86);
                for (var area : LogisticsPanel.exclusions(screen))
                    require(area.x() + area.width() <= bookX || area.x() >= bookX + 147, "Cache panel overlapped the visible recipe book");
                ClientReview.capture("05-recipe-book"); clickBookRecipe(screen, recipe.id()); next();
            }
            case 6 -> {
                if (!waited(20)) break;
                server(p -> require(ConsumptionTests.stock(p, 0) == 12 && CraftingService.grid(p.containerMenu).getItem(0).is(Items.OAK_LOG)
                        && p.getInventory().getItem(10) == unrelated, "Native recipe-book preparation consumed cache material or restacked unrelated item"));
                require(mc.player.containerMenu.getSlot(0).getItem().is(Items.OAK_PLANKS), "Real recipe result did not sync to client");
                mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId, 0, 0, ClickType.PICKUP, mc.player); next();
            }
            case 7 -> {
                if (!waited(20)) break;
                server(p -> require(ConsumptionTests.stock(p, 0) == 11 && craftedPlanks(p) == 4 && CraftingService.grid(p.containerMenu).isEmpty()
                        && p.getInventory().getItem(10) == unrelated, "Client crafting click state: cache=" + ConsumptionTests.stock(p, 0)
                        + " cursor=" + p.containerMenu.getCarried() + " grid=" + CraftingService.grid(p.containerMenu).getItems()
                        + " unrelatedSame=" + (p.getInventory().getItem(10) == unrelated)));
                next();
            }
            case 8 -> {
                mc.player.closeContainer(); stage = 801; changed = tick;
            }
            case 801 -> {
                if (!waited(20)) break;
                if (!ModList.get().isLoaded("jei")) { stage=30; changed=tick; break; }
                server(ClientConsumptionReview::setup); stage = 9; changed = tick;
            }
            case 9 -> { if (!waited(20)) break; mc.setScreen(new InventoryScreen(mc.player)); next(); }
            case 10 -> { if (!waited(20)) break; require(LogisticsPanel.recipeReady(), "Recipe transfer session was not ready"); jei("openTransfer"); next(); }
            case 11 -> {
                if (!waited(20)) break;
                jei("previewTransfer"); server(p -> {
                    require(ConsumptionTests.stock(p, 0) == 12 && CraftingService.grid(p.containerMenu).isEmpty(), "Native JEI preview moved cache material");
                }); stage = 112; changed = tick;
            }
            case 112 -> {
                if (!waited(10)) break;
                if (jeiCacheScenario == 2 || jeiCacheScenario == 3) jei("transferMaximum");
                else jei("clickTransfer");
                stage = 113; changed = tick;
            }
            case 113 -> {
                if (!waited(20)) break;
                // API maximum transfer does not close JEI; native '+' does.
                if (!(mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>)) {
                    mc.screen.onClose(); changed = tick; break;
                }
                server(p -> {
                    int prepared = CraftingService.grid(p.containerMenu).getItems().stream().filter(v -> v.is(Items.OAK_LOG)).mapToInt(ItemStack::getCount).sum();
                    int expected = jeiCacheScenario == 2 || jeiCacheScenario == 3 ? 15 : 1;
                    require(prepared == expected && ConsumptionTests.stock(p, 0) == 12,
                            "JEI cache fill consumed stock or ignored native maximum: " + prepared);
                    long reserved = dev.scathiard.feedmepackages.consumption.CraftingReservations.reservedCache(cacheId, 0, null);
                    require(reserved == (expected == 15 ? 12 : 1), "JEI fill has wrong backed reservation");
                });
                stage = jeiCacheScenario >= 4 ? 118 : 114; changed = tick;
            }
            case 118 -> { if (!waited(20)) break; jei("openTransfer"); stage = 119; changed = tick; }
            case 119 -> {
                if (!waited(20)) break; jei("previewTransfer"); jei("clickTransfer"); stage = 120; changed = tick;
            }
            case 120 -> {
                if (!waited(20)) break;
                server(p -> require(ConsumptionTests.stock(p, 0) == 12
                        && dev.scathiard.feedmepackages.consumption.CraftingReservations.reservedCache(cacheId, 0, null) == 2,
                        "Second JEI '+' materialized or duplicated an existing backed grid"));
                stage = 114; changed = tick;
            }
            case 114 -> {
                if (!waited(10)) break;
                var screen = (net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) mc.screen;
                var result = mc.player.containerMenu.getSlot(0);
                require(result.getItem().is(Items.OAK_PLANKS), "JEI-backed native result is missing");
                ClientReview.capture("jei-cache-" + jeiCacheScenario);
                ClientReview.click(screen.getGuiLeft() + result.x + 8, screen.getGuiTop() + result.y + 8);
                stage = 115; changed = tick;
            }
            case 115 -> {
                if (!waited(20)) break;
                server(p -> {
                    boolean mixed = jeiCacheScenario == 2 || jeiCacheScenario == 3;
                    int remaining = CraftingService.grid(p.containerMenu).getItems().stream().filter(v -> v.is(Items.OAK_LOG)).mapToInt(ItemStack::getCount).sum();
                    require(ConsumptionTests.stock(p, 0) == (mixed ? 12 : 11) && craftedPlanks(p) == 4
                            && remaining == (mixed ? 14 : jeiCacheScenario >= 4 ? 1 : 0),
                            "Native JEI result click refilled, overcharged or ignored physical-first consumption");
                }); stage = 1151; changed = tick;
            }
            case 1151 -> {
                // The server assertion must finish before sending close; close can return the cursor to inventory.
                mc.player.closeContainer(); stage = 116; changed = tick;
            }
            case 116 -> {
                if (!waited(20)) break;
                server(p -> {
                    boolean mixed = jeiCacheScenario == 2 || jeiCacheScenario == 3;
                    require(CraftingService.grid(p.containerMenu).isEmpty()
                            && ConsumptionTests.stock(p, 0) == (mixed ? 12 : 11)
                            && p.getInventory().items.stream().filter(v -> v.is(Items.OAK_LOG)).mapToInt(ItemStack::getCount).sum() == (mixed ? 2 : 0)
                            && p.getInventory().items.stream().filter(v -> v.is(Items.OAK_PLANKS)).mapToInt(ItemStack::getCount).sum() == 4,
                            "Closing JEI preparation materialized cache aliases or lost real leftovers");
                    FeedMePackages.LOGGER.info("FMP_JEI_CACHE_SOURCES_PASSED scenario={} grid={} maximum={} repeat={} cache={} output=4", jeiCacheScenario,
                            jeiCacheScenario % 2 == 0 ? "2x2" : "3x3", mixed, jeiCacheScenario >= 4, ConsumptionTests.stock(p, 0));
                    jeiCacheScenario++;
                    setup(p);
                    if (jeiCacheScenario >= 6) p.getInventory().setItem(8, new ItemStack(Items.OAK_LOG));
                    else if (jeiCacheScenario >= 2 && jeiCacheScenario < 4) p.getInventory().setItem(8, new ItemStack(Items.OAK_LOG, 3));
                    if (jeiCacheScenario < 6 && jeiCacheScenario % 2 != 0) {
                        var pos = p.blockPosition();
                        p.serverLevel().setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.CRAFTING_TABLE.defaultBlockState());
                        p.openMenu(new net.minecraft.world.SimpleMenuProvider((id, inventory, owner) -> new net.minecraft.world.inventory.CraftingMenu(id, inventory,
                                net.minecraft.world.inventory.ContainerLevelAccess.create(p.level(), pos)), net.minecraft.network.chat.Component.literal("JEI cache workbench")));
                    }
                    p.containerMenu.broadcastFullState();
                }); stage = 117; changed = tick;
            }
            case 117 -> {
                if (!waited(30)) break;
                if (jeiCacheScenario >= 6 || jeiCacheScenario % 2 == 0) mc.setScreen(new InventoryScreen(mc.player));
                stage = 121; changed = tick;
            }
            case 121 -> {
                if (!waited(20) || !LogisticsPanel.recipeReady()) break;
                jei("openTransfer"); stage = jeiCacheScenario >= 6 ? 111 : 11; changed = tick;
            }
            case 111 -> { if (!waited(20)) break; jei("previewTransfer"); stage = 12; changed = tick; }
            case 12 -> { if (!waited(10)) break; ClientReview.capture("06-jei-recipe"); jei("clickTransfer"); next(); }
            case 13 -> {
                if (!waited(20)) break;
                require(mc.screen instanceof InventoryScreen && mc.player.containerMenu.getSlot(0).getItem().is(Items.OAK_PLANKS), "JEI plus did not return to the filled original grid");
                server(p -> {
                    require(ConsumptionTests.stock(p, 0) == 12 && CraftingService.grid(p.containerMenu).getItems().stream().filter(v -> v.is(Items.OAK_LOG)).mapToInt(ItemStack::getCount).sum() == 1, "Native JEI plus modified cache stock");
                });
                stage = 20; changed = tick;
            }
            case 20 -> {
                if (!waited(10)) break;
                mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId, 0, 0, ClickType.PICKUP, mc.player);
                next();
            }
            case 21 -> {
                if (!waited(20)) break;
                server(p -> require(ConsumptionTests.stock(p, 0) == 12 && craftedPlanks(p) == 4,
                        "Native JEI-filled craft did not consume exactly one backpack log"));
                stage = 211; changed = tick;
            }
            case 211 -> { mc.player.closeContainer(); stage = 22; changed = tick; }
            case 22 -> {
                if (!waited(20)) break;
                server(p -> {
                    require(ConsumptionTests.stock(p, 0) == 12 && CraftingService.grid(p.containerMenu).isEmpty()
                            && p.getInventory().items.stream().filter(v -> v.is(Items.OAK_PLANKS)).mapToInt(ItemStack::getCount).sum() == 4
                            && p.getInventory().items.stream().noneMatch(v -> v.is(Items.OAK_LOG)),
                            "Closing after JEI craft materialized unused preparation or lost output");
                    FeedMePackages.LOGGER.info("FMP_JEI_NATIVE_OWNER_PASSED cache=12 output=4 unused=0");
                    TestPlayers.necklace(p).setStackInSlot(0, ItemStack.EMPTY);
                    p.getInventory().setItem(8, new ItemStack(Items.OAK_LOG)); p.containerMenu.broadcastFullState();
                });
                stage = 14; changed = tick;
            }
            case 14 -> { if (!waited(30)) break; require(!ClientMaterials.active(), "Unwear did not clear client material hints"); mc.setScreen(new InventoryScreen(mc.player)); next(); }
            case 15 -> { if (!waited(20)) break; jei("openTransfer"); next(); }
            case 16 -> { if (!waited(20)) break; jei("previewTransfer"); next(); }
            case 17 -> { if (!waited(10)) break; jei("clickTransfer"); next(); }
            case 18 -> {
                if (!waited(20)) break;
                server(p -> {
                    var grid = CraftingService.grid(p.containerMenu);
                    int logs = 0;
                    for (int i = 0; i < grid.getContainerSize(); i++) {
                        var stack = grid.getItem(i);
                        require(stack.isEmpty() || stack.is(Items.OAK_LOG), "Unworn JEI moved an unexpected ingredient");
                        if (stack.is(Items.OAK_LOG)) logs += stack.getCount();
                    }
                    // JEI's native centered layout may place a 1x1 input at another legal grid position.
                    require(logs == 1 && p.containerMenu.getSlot(0).getItem().is(Items.OAK_PLANKS)
                            && p.containerMenu.getSlot(0).getItem().getCount() == 4, "Unworn JEI did not retain native backpack transfer: " + grid.getItems());
                    require(CacheLedger.get(p.getServer()).find(cacheId).state().cells().getFirst().amount() == 12, "Unworn native transfer consumed cache");
                }); next();
            }
            case 19 -> { if (waited(10)) { stage=30; changed=tick; } }
            case 30 -> { mc.player.closeContainer(); next(); }
            case 31 -> {
                if (!waited(20)) break;
                server(p -> {
                    setup(p); TestPlayers.necklace(p).getStackInSlot(0).set(FmpRegistries.NETWORK.get(),UUID.randomUUID());
                    p.getInventory().setItem(8,new ItemStack(Items.OAK_LOG,5)); p.containerMenu.broadcastFullState();
                }); next();
            }
            case 32 -> { if (!waited(20)) break; mc.setScreen(new InventoryScreen(mc.player)); next(); }
            case 33 -> {
                if (!waited(30) || !LogisticsPanel.recipeReady()) break;
                if (layout().compact()) {
                    var entry=layout().bounds(); ClientReview.click(entry.x()+8,entry.y()+8); changed=tick; break;
                }
                require(LogisticsPanel.visibleCells(mc.screen).size()==9,"Compact entry failed to restore the full grid");
                var box=layout().foldButton(); ClientReview.capture("07-baseline023-open");
                ClientReview.click(box.x()+6,box.y()+6); next();
            }
            case 34 -> {
                if (!waited(10)) break;
                require(layout().hidden() && LogisticsPanel.visibleCells(mc.screen).isEmpty(),"Fold still exposed cache cells");
                var areas=LogisticsPanel.exclusions(mc.screen);
                require(areas.size()==1 && areas.getFirst().width()==13 && areas.getFirst().height()==13,"Fold still excluded the old panel or plugins");
                ClientReview.capture("08-baseline023-folded"); var box=layout().foldButton(); ClientReview.click(box.x()+6,box.y()+6); next();
            }
            case 35 -> {
                if (!waited(10)) break;
                require(!layout().hidden() && LogisticsPanel.visibleCells(mc.screen).size()==9,"Unfold did not restore the original grid");
                var box=layout().transferButton(); ClientReview.click(box.x()+6,box.y()+6); next();
            }
            case 36 -> {
                if (!waited(20)) break;
                server(p -> {
                    require(ConsumptionTests.stock(p,0)==17 && p.getInventory().getItem(8).isEmpty(),"Real collect button failed to transfer the backpack logs");
                    p.containerMenu.setCarried(new ItemStack(Items.OAK_LOG,3)); p.containerMenu.broadcastChanges();
                }); next();
            }
            case 37 -> {
                if (!waited(20)) break;
                var wrong=LogisticsPanel.visibleCells(mc.screen).stream().filter(c->c.slot()==1).findFirst().orElseThrow().bounds();
                ClientReview.click(wrong.x()+8,wrong.y()+8); next();
            }
            case 38 -> {
                if (!waited(20)) break;
                require(mc.player.containerMenu.getCarried().isEmpty(),"Assisted client deposit did not clear the real cursor");
                server(p -> require(ConsumptionTests.stock(p,0)==20 && ConsumptionTests.stock(p,1)==8,"Assisted deposit changed the wrong filter"));
                ClientReview.capture("09-baseline023-collected"); next();
            }
            case 39 -> {
                if (!waited(10)) break;
                verifyMixedMaterialHints();
                server(dev.scathiard.feedmepackages.network.MaterialHints::forget);
                FeedMePackages.LOGGER.info("FMP_BASELINE023_CLIENT_PASSED fold=13px collect=5 assisted=3 malformed_hint=isolated");
                next();
            }
            case 40 -> { if (waited(20)) return true; }
            default -> throw new IllegalStateException("Unexpected consumption review stage");
        }
        return false;
    }
    private static void setup(ServerPlayer player) {
        dev.scathiard.feedmepackages.consumption.CraftingReservations.forget(player);
        player.setGameMode(GameType.SURVIVAL); player.getInventory().clearContent(); player.containerMenu.setCarried(ItemStack.EMPTY);
        CraftingService.grid(player.containerMenu).clearContent();
        TestPlayers.necklace(player).setStackInSlot(0, FmpRegistries.PENDANT.toStack());
        ConsumptionTests.seed(player, 0, new ItemStack(Items.OAK_LOG), 12); ConsumptionTests.seed(player, 1, new ItemStack(Items.ARROW), 8);
        cacheId = AccessGate.resolve(player).handle().cacheId(); heldBow = new ItemStack(Items.BOW); player.setItemInHand(InteractionHand.MAIN_HAND, heldBow);
        unrelated = new ItemStack(Items.BUNDLE); player.getInventory().setItem(10, unrelated);
        player.awardRecipes(List.of(player.getServer().getRecipeManager().byKey(ResourceLocation.withDefaultNamespace("oak_planks")).orElseThrow()));
        player.containerMenu.broadcastFullState();
    }
    @SuppressWarnings("unchecked") private static RecipeHolder<CraftingRecipe> recipe() {
        return (RecipeHolder<CraftingRecipe>) Minecraft.getInstance().level.getRecipeManager().byKey(ResourceLocation.withDefaultNamespace("oak_planks")).orElseThrow();
    }
    private static boolean waited(int count) { return tick - changed >= count; }
    private static int craftedPlanks(ServerPlayer player) {
        // The combined pack can send native result clicks directly to inventory. Both destinations
        // are real output; exactly four must exist, and close must not create another four.
        var cursor = player.containerMenu.getCarried();
        if (!cursor.isEmpty() && !cursor.is(Items.OAK_PLANKS)) return -1;
        int bag = player.getInventory().items.stream().filter(s -> s.is(Items.OAK_PLANKS)).mapToInt(ItemStack::getCount).sum();
        FeedMePackages.LOGGER.info("FMP_NATIVE_CRAFT_DESTINATIONS cursor={} inventory={}", cursor.getCount(), bag);
        return cursor.getCount() + bag;
    }
    private static void clickBookRecipe(InventoryScreen screen, ResourceLocation id) {
        try {
            // Optional mods add enough unlocked recipes to move oak planks off the first page.
            // Use the real search input, rather than assuming one fixed recipe-book page.
            var searchField = net.minecraft.client.gui.screens.recipebook.RecipeBookComponent.class.getDeclaredField("searchBox");
            searchField.setAccessible(true);
            var search = (net.minecraft.client.gui.components.EditBox) searchField.get(screen.getRecipeBookComponent());
            require(search.getValue().isEmpty(), "Recipe-book fixture unexpectedly retained a search");
            ClientReview.click(search.getX() + 4, search.getY() + 4);
            var typed = net.minecraft.client.KeyboardHandler.class.getDeclaredMethod("charTyped", long.class, int.class, int.class);
            typed.setAccessible(true);
            var mc = Minecraft.getInstance();
            for (int point : new ItemStack(Items.OAK_PLANKS).getHoverName().getString().codePoints().toArray())
                typed.invoke(mc.keyboardHandler, mc.getWindow().getWindow(), point, 0);
            var field = net.minecraft.client.gui.screens.recipebook.RecipeBookComponent.class.getDeclaredField("recipeBookPage"); field.setAccessible(true);
            var page = field.get(screen.getRecipeBookComponent());
            var buttons = page.getClass().getDeclaredField("buttons"); buttons.setAccessible(true);
            for (var value : (List<?>)buttons.get(page)) {
                var button = (net.minecraft.client.gui.screens.recipebook.RecipeButton)value;
                if (button.visible && button.getRecipe().id().equals(id)) {
                    ClientReview.click(button.getX() + button.getWidth() / 2, button.getY() + button.getHeight() / 2); return;
                }
            }
            throw new IllegalStateException("Requested recipe was not visible on the actual book page");
        } catch (ReflectiveOperationException problem) { throw new IllegalStateException("Cannot inspect source-verified recipe-book buttons", problem); }
    }
    private static dev.scathiard.feedmepackages.client.PanelLayout layout() {
        try { var field=LogisticsPanel.class.getDeclaredField("layout"); field.setAccessible(true);
            return (dev.scathiard.feedmepackages.client.PanelLayout)field.get(null);
        } catch (ReflectiveOperationException problem) { throw new IllegalStateException(problem); }
    }
    private static void verifyMixedMaterialHints() {
        var mc=Minecraft.getInstance();
        var encoded=dev.scathiard.feedmepackages.item.ItemVariantKey.of(new ItemStack(Items.OAK_LOG),mc.player.registryAccess()).encoded();
        var packet=new dev.scathiard.feedmepackages.network.MaterialHints.Message(UUID.randomUUID(),1,true,true,false,
                List.of(encoded,"invalid-template"),List.of(17,99),mc.player.containerMenu.containerId,List.of(0,3),List.of(),cacheId);
        var bytes=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),mc.player.registryAccess());
        try {
            dev.scathiard.feedmepackages.network.MaterialHints.Message.CODEC.encode(bytes,packet);
            var decoded=dev.scathiard.feedmepackages.network.MaterialHints.Message.CODEC.decode(bytes);
            var accept=ClientMaterials.class.getDeclaredMethod("accept",dev.scathiard.feedmepackages.network.MaterialHints.Message.class);
            accept.setAccessible(true); accept.invoke(null,decoded);
            require(ClientMaterials.active() && ClientMaterials.craftingStacks().size()==1
                    && ClientMaterials.craftingStacks().getFirst().is(Items.OAK_LOG)
                    && ClientMaterials.craftingStacks().getFirst().getCount()==17,"One unreadable hint wiped valid materials or exposed unnamed stock");
        } catch (ReflectiveOperationException problem) { throw new IllegalStateException(problem); }
        finally { bytes.release(); }
    }
    private static void next() { stage++; changed = tick; FeedMePackages.LOGGER.info("FMP_CLIENT_CONSUMPTION_STAGE {}", stage); }
    private static void require(boolean ok, String error) { if (!ok) throw new IllegalStateException(error); }
    private static void server(Consumer<ServerPlayer> operation) {
        var mc = Minecraft.getInstance(); var id = mc.player.getUUID(); var server = mc.getSingleplayerServer();
        work = CompletableFuture.runAsync(() -> operation.accept(server.getPlayerList().getPlayer(id)), server);
    }
    private static void jei(String name) {
        try { Class.forName("dev.scathiard.feedmepackages.gametest.JeiClientReview").getMethod(name).invoke(null); }
        catch (ReflectiveOperationException exception) { throw new IllegalStateException("JEI transfer review failed at " + name, exception); }
    }
}
