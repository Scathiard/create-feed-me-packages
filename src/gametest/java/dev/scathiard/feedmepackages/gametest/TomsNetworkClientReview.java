package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.client.LogisticsPanel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Native network withdrawal, craft, and connected full-inventory clear. Test-only. */
final class TomsNetworkClientReview {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final BlockPos POS = new BlockPos(0, -60, 2);
    private static final BlockPos CHEST = POS.north();
    private static int stage, ticks, changed;
    private static boolean craftHoverPrepared;
    private static CompletableFuture<Void> work;

    static boolean tick() throws ReflectiveOperationException {
        ticks++;
        if (work != null) {
            if (!work.isDone()) return false;
            work.join(); work = null;
        }
        require(ticks - changed < 300, "Tom's connected terminal timed out at stage " + stage);
        switch (stage) {
            case 0 -> {
                require(net.neoforged.fml.ModList.get().isLoaded("toms_storage"), "Tom's Storage is absent");
                MC.options.guiScale().set(1);
                MC.resizeDisplay();
                server(player -> {
                    player.getInventory().clearContent();
                    ConsumptionTests.seed(player, 0, new ItemStack(Items.STONE_BRICKS), 8);
                    player.serverLevel().setBlockAndUpdate(CHEST, Blocks.CHEST.defaultBlockState());
                    var chest = chest(player);
                    chest.setItem(0, new ItemStack(Items.STONE_BRICKS, 3));
                    Block terminalBlock = BuiltInRegistries.BLOCK.get(
                            ResourceLocation.fromNamespaceAndPath("toms_storage", "crafting_terminal"));
                    require(BuiltInRegistries.BLOCK.getKey(terminalBlock).getNamespace().equals("toms_storage"),
                            "Tom's crafting terminal is missing");
                    player.serverLevel().setBlockAndUpdate(POS, terminalBlock.defaultBlockState());
                    BlockEntity terminal = player.serverLevel().getBlockEntity(POS);
                    require(terminal instanceof MenuProvider, "Tom's connected terminal is absent");
                    player.connection.teleport(.5, -60, .5, 0, 0);
                    player.openMenu((MenuProvider) terminal);
                }); next();
            }
            case 1 -> {
                if (!(MC.screen instanceof AbstractContainerScreen<?> screen)
                        || !screen.getMenu().getClass().getName().equals("com.tom.storagemod.menu.CraftingTerminalMenu")
                        || ticks - changed < 15) return false;
                require(LogisticsPanel.visibleCells(screen).isEmpty(),
                        "FMP panel claimed Tom's own connected terminal");
                var networkSlot = networkSlot(screen);
                Object networkStack = networkSlot.getClass().getField("stack").get(networkSlot);
                if (networkStack == null) return false;
                var stack = (ItemStack) networkStack.getClass().getMethod("getStack").invoke(networkStack);
                if (!stack.is(Items.STONE_BRICKS)) return false;
                double[] xy = networkSlotPosition(screen, networkSlot);
                require(xy[1] > 0 && xy[1] < screen.height, "Network slot is clipped off-screen");
                TemplateClientReview.capture("toms-connected-stock");
                TemplateClientReview.move(xy[0], xy[1]); next();
            }
            case 2 -> {
                if (ticks - changed < 8) return false;
                var screen = (AbstractContainerScreen<?>) MC.screen;
                if (hoveredNetworkSlot() != 0) {
                    double[] xy = networkSlotPosition(screen, networkSlot(screen));
                    TemplateClientReview.move(xy[0], xy[1]);
                    return false;
                }
                TemplateClientReview.click(networkSlotPosition(screen, networkSlot(screen))[0],
                        networkSlotPosition(screen, networkSlot(screen))[1]);
                next();
            }
            case 3 -> {
                if (ticks - changed < 8 || !MC.player.containerMenu.getCarried().is(Items.STONE_BRICKS)
                        || MC.player.containerMenu.getCarried().getCount() != 3) return false;
                server(player -> {
                    require(chest(player).getItem(0).isEmpty(), "Native withdrawal did not debit the connected chest");
                    require(player.containerMenu.getCarried().is(Items.STONE_BRICKS)
                                    && player.containerMenu.getCarried().getCount() == 3,
                            "Native withdrawal did not yield exactly three bricks");
                    require(ConsumptionTests.stock(player, 0) == 8, "Network withdrawal touched FMP cache");
                }); next();
            }
            case 4, 5, 6 -> {
                var screen = (AbstractContainerScreen<?>) MC.screen;
                Slot slot = screen.getMenu().getSlot(stage - 3);
                double x = screen.getGuiLeft() + slot.x + 8;
                double y = screen.getGuiTop() + slot.y + 8;
                if (!craftHoverPrepared) {
                    // Tom's determines its virtual storage hover during rendering. Moving and
                    // pressing in one tick can click both the old network slot and craft grid.
                    TemplateClientReview.move(x, y);
                    craftHoverPrepared = true;
                    changed = ticks;
                    return false;
                }
                if (ticks - changed < 4 || hoveredNetworkSlot() != -1) return false;
                TemplateClientReview.mouse(x, y, 1, 1);
                TemplateClientReview.mouse(x, y, 1, 0);
                next();
            }
            case 7 -> {
                if (ticks - changed == 20) {
                    var menu = MC.player.containerMenu;
                    FeedMePackages.LOGGER.info("FMP_TOMS_NETWORK_TRACE result={} carry={} grid={} bagBricks={}",
                            menu.getSlot(0).getItem(), menu.getCarried(),
                            java.util.stream.IntStream.rangeClosed(1, 9).mapToObj(i -> menu.getSlot(i).getItem()).toList(),
                            MC.player.getInventory().countItem(Items.STONE_BRICKS));
                    server(player -> FeedMePackages.LOGGER.info("FMP_TOMS_NETWORK_SERVER_TRACE chest={} carry={} grid={} bagBricks={} cache={}",
                            chest(player).getItem(0), player.containerMenu.getCarried(),
                            java.util.stream.IntStream.rangeClosed(1, 9).mapToObj(i -> player.containerMenu.getSlot(i).getItem()).toList(),
                            player.getInventory().countItem(Items.STONE_BRICKS), ConsumptionTests.stock(player, 0)));
                    TemplateClientReview.capture("toms-connected-after-place");
                }
                if (ticks - changed < 8 || !MC.player.containerMenu.getCarried().isEmpty()
                        || !MC.player.containerMenu.getSlot(0).getItem().is(Items.STONE_BRICK_SLAB)) return false;
                require(MC.player.containerMenu.getSlot(0).getItem().getCount() == 6,
                        "Network-sourced recipe result has wrong count");
                TemplateClientReview.nativeSlotClick(0); next();
            }
            case 8 -> {
                if (ticks - changed < 8) return false;
                server(player -> {
                    require(player.containerMenu.getCarried().is(Items.STONE_BRICK_SLAB)
                                    && player.containerMenu.getCarried().getCount() == 6,
                            "Network-sourced craft did not yield exactly six slabs");
                    require(chest(player).getItem(0).isEmpty() && ConsumptionTests.stock(player, 0) == 8,
                            "Craft changed connected chest or FMP cache unexpectedly");
                    for (int i = 1; i <= 9; i++)
                        require(player.containerMenu.getSlot(i).getItem().isEmpty(), "Craft left hidden ingredient");
                }); next();
            }
            case 9 -> {
                TemplateClientReview.nativeSlotClick(findInventorySlot((AbstractContainerScreen<?>) MC.screen, 0));
                next();
            }
            case 10 -> {
                if (ticks - changed < 8 || !MC.player.containerMenu.getCarried().isEmpty()) return false;
                server(player -> {
                    require(player.getInventory().getItem(0).is(Items.STONE_BRICK_SLAB)
                                    && player.getInventory().getItem(0).getCount() == 6,
                            "Network-sourced output did not settle in physical inventory");
                    for (int i = 0; i < player.getInventory().getContainerSize(); i++)
                        if (player.getInventory().getItem(i).isEmpty())
                            player.getInventory().setItem(i, new ItemStack(Items.BEDROCK));
                    craftingGrid(player.serverLevel().getBlockEntity(POS)).setItem(0,
                            new ItemStack(Items.STONE_BRICKS, 2));
                    player.containerMenu.broadcastChanges();
                }); next();
            }
            case 11 -> {
                if (ticks - changed < 8 || !MC.player.containerMenu.getSlot(1).getItem().is(Items.STONE_BRICKS)) return false;
                var screen = (AbstractContainerScreen<?>) MC.screen;
                AbstractWidget clear = clearButton(screen);
                require(clear.visible && clear.active, "Tom's native clear button is unavailable");
                TemplateClientReview.capture("toms-connected-full-bag-clear");
                TemplateClientReview.click(clear.getX() + clear.getWidth() / 2.0,
                        clear.getY() + clear.getHeight() / 2.0);
                next();
            }
            case 12 -> {
                if (ticks - changed < 8) return false;
                server(player -> {
                    require(craftingGrid(player.serverLevel().getBlockEntity(POS)).getItem(0).isEmpty(),
                            "Connected clear left ingredients in grid");
                    require(chest(player).getItem(0).is(Items.STONE_BRICKS)
                                    && chest(player).getItem(0).getCount() == 2,
                            "Connected full-inventory clear did not return exactly two bricks to chest");
                    int ground = player.serverLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                            new AABB(POS).inflate(3)).stream().filter(entity ->
                            entity.getItem().is(Items.STONE_BRICKS)).mapToInt(entity -> entity.getItem().getCount()).sum();
                    require(ground == 0, "Connected clear also dropped bricks on ground: " + ground);
                    require(ConsumptionTests.stock(player, 0) == 8
                                    && player.getInventory().getItem(0).getCount() == 6,
                            "Connected clear changed FMP cache or prior craft result");
                    player.closeContainer();
                }); next();
            }
            case 13 -> {
                if (ticks - changed < 8 || MC.screen != null) return false;
                server(player -> {
                    // Move the two returned ingredients, do not manufacture a second copy.
                    var stock = chest(player).getItem(0);
                    require(stock.is(Items.STONE_BRICKS) && stock.getCount() == 2, "Prior return changed before reopen");
                    craftingGrid(player.serverLevel().getBlockEntity(POS)).setItem(0, stock);
                    for (int i = 0; i < chest(player).getContainerSize(); i++)
                        chest(player).setItem(i, new ItemStack(Items.BEDROCK, 64));
                    chest(player).setChanged();
                    player.getInventory().setItem(9, ItemStack.EMPTY);
                    player.openMenu((MenuProvider)player.serverLevel().getBlockEntity(POS));
                }); next();
            }
            case 14 -> {
                if (!terminalReady() || !MC.player.containerMenu.getSlot(1).getItem().is(Items.STONE_BRICKS)) return false;
                MC.screen.onClose(); next();
            }
            case 15 -> {
                if (ticks - changed < 8 || MC.screen != null) return false;
                server(player -> {
                    require(craftingGrid(player.serverLevel().getBlockEntity(POS)).getItem(0).getCount() == 2,
                            "Closing Tom's terminal lost its block-owned ingredients");
                    require(player.getInventory().countItem(Items.STONE_BRICKS) == 0 && ground(player) == 0,
                            "Closing duplicated retained ingredients into bag or ground");
                    player.openMenu((MenuProvider)player.serverLevel().getBlockEntity(POS));
                }); next();
            }
            case 16 -> {
                if (!terminalReady() || MC.player.containerMenu.getSlot(1).getItem().getCount() != 2) return false;
                TemplateClientReview.capture("toms-network-full-reopened-grid");
                clickClear(); next();
            }
            case 17 -> {
                if (ticks - changed < 10 || !MC.player.containerMenu.getSlot(1).getItem().isEmpty()) return false;
                server(player -> {
                    checkFullNetwork(player);
                    require(player.getInventory().countItem(Items.STONE_BRICKS) == 2 && ground(player) == 0,
                            "Full network with bag space did not return exactly two bricks to bag");
                    require(craftingGrid(player.serverLevel().getBlockEntity(POS)).getItem(0).isEmpty(),
                            "Full-network return left a duplicate in grid");
                    FeedMePackages.LOGGER.info("FMP_TOMS_RETURN_PASSED networkFull=true bagFull=false bag=2 ground=0 cache=8");
                }); next();
            }
            case 18 -> {
                server(player -> {
                    int slot = -1;
                    for (int i = 0; i < player.getInventory().items.size(); i++)
                        if (player.getInventory().getItem(i).is(Items.STONE_BRICKS)) slot = i;
                    require(slot >= 0 && player.getInventory().getItem(slot).getCount() == 2,
                            "Native fallback did not preserve one exact physical stack");
                    craftingGrid(player.serverLevel().getBlockEntity(POS)).setItem(0, player.getInventory().getItem(slot));
                    player.getInventory().setItem(slot, new ItemStack(Items.BEDROCK, 64));
                    player.containerMenu.broadcastChanges();
                }); next();
            }
            case 19 -> {
                if (!terminalReady() || MC.player.containerMenu.getSlot(1).getItem().getCount() != 2) return false;
                TemplateClientReview.capture("toms-network-and-bag-full-clear");
                clickClear(); next();
            }
            case 20 -> {
                if (ticks - changed < 10 || !MC.player.containerMenu.getSlot(1).getItem().isEmpty()) return false;
                server(player -> {
                    checkFullNetwork(player);
                    require(player.getInventory().countItem(Items.STONE_BRICKS) == 0 && ground(player) == 2,
                            "Full network and bag did not drop exactly the two retained ingredients");
                    require(craftingGrid(player.serverLevel().getBlockEntity(POS)).getItem(0).isEmpty(),
                            "Ground fallback also retained ingredients in grid");
                    FeedMePackages.LOGGER.info("FMP_TOMS_RETURN_PASSED networkFull=true bagFull=true bag=0 ground=2 cache=8");
                }); next();
            }
            case 21 -> { MC.screen.onClose(); next(); }
            case 22 -> {
                if (MC.screen != null || ticks - changed < 8) return false;
                MC.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(MC.player)); next();
            }
            case 23 -> {
                if (ticks - changed < 8 || LogisticsPanel.visibleCells(MC.screen).size() != 9) return false;
                TemplateClientReview.capture("toms-return-to-fmp-inventory");
                FeedMePackages.LOGGER.info("FMP_TOMS_NETWORK_CLIENT_PASSED nativeNetworkWithdrawal=3 "
                        + "nativeCraftOutput=6 fullBagClearToChest=2 closeReopenRetained=2 "
                        + "fullNetworkBagReturn=2 fullNetworkFullBagGround=2 fmpCacheUnchanged=8 panelRestored=true");
                return true;
            }
        }
        return false;
    }

    private static Object networkSlot(AbstractContainerScreen<?> screen) throws ReflectiveOperationException {
        return screen.getMenu().getClass().getMethod("getSlotByID", int.class).invoke(screen.getMenu(), 0);
    }
    private static double[] networkSlotPosition(AbstractContainerScreen<?> screen, Object slot)
            throws ReflectiveOperationException {
        return new double[]{screen.getGuiLeft() + slot.getClass().getField("xDisplayPosition").getInt(slot) + 8,
                screen.getGuiTop() + slot.getClass().getField("yDisplayPosition").getInt(slot) + 8};
    }
    private static int hoveredNetworkSlot() throws ReflectiveOperationException {
        var field = Class.forName("com.tom.storagemod.screen.AbstractStorageTerminalScreen")
                .getDeclaredField("slotIDUnderMouse");
        field.setAccessible(true);
        return field.getInt(MC.screen);
    }
    private static int findInventorySlot(AbstractContainerScreen<?> screen, int inventoryIndex) {
        for (int i = 0; i < screen.getMenu().slots.size(); i++) {
            Slot slot = screen.getMenu().getSlot(i);
            if (slot.container == MC.player.getInventory() && slot.getContainerSlot() == inventoryIndex) return i;
        }
        throw new IllegalStateException("Tom's menu lacks physical inventory slot " + inventoryIndex);
    }
    private static AbstractWidget clearButton(AbstractContainerScreen<?> screen) throws ReflectiveOperationException {
        Field field = screen.getClass().getDeclaredField("btnClr");
        field.setAccessible(true);
        return (AbstractWidget) field.get(screen);
    }
    private static boolean terminalReady() {
        return ticks - changed >= 10 && MC.screen instanceof AbstractContainerScreen<?> screen
                && screen.getMenu().getClass().getName().equals("com.tom.storagemod.menu.CraftingTerminalMenu");
    }
    private static void clickClear() throws ReflectiveOperationException {
        var screen = (AbstractContainerScreen<?>)MC.screen;
        require(LogisticsPanel.visibleCells(screen).isEmpty(), "FMP claimed a reopened foreign terminal");
        var button = clearButton(screen);
        require(button.visible && button.active, "Reopened terminal clear is unavailable");
        TemplateClientReview.click(button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0);
    }
    private static int ground(ServerPlayer player) {
        return player.serverLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new AABB(POS).inflate(3)).stream().filter(entity -> entity.getItem().is(Items.STONE_BRICKS))
                .mapToInt(entity -> entity.getItem().getCount()).sum();
    }
    private static void checkFullNetwork(ServerPlayer player) {
        for (int i = 0; i < chest(player).getContainerSize(); i++)
            require(chest(player).getItem(i).is(Items.BEDROCK) && chest(player).getItem(i).getCount() == 64,
                    "Full network was modified by return fallback");
        require(ConsumptionTests.stock(player, 0) == 8 && player.getInventory().getItem(0).is(Items.STONE_BRICK_SLAB)
                && player.getInventory().getItem(0).getCount() == 6, "Fallback changed FMP stock or prior output");
    }
    private static CraftingContainer craftingGrid(BlockEntity terminal) {
        try { return (CraftingContainer) terminal.getClass().getMethod("getCraftingInv").invoke(terminal); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private static ChestBlockEntity chest(ServerPlayer player) {
        require(player.serverLevel().getBlockEntity(CHEST) instanceof ChestBlockEntity,
                "Connected chest is absent");
        return (ChestBlockEntity) player.serverLevel().getBlockEntity(CHEST);
    }
    private static void server(Consumer<ServerPlayer> action) {
        var id = MC.player.getUUID(); var server = MC.getSingleplayerServer();
        work = server.submit(() -> action.accept(server.getPlayerList().getPlayer(id)));
    }
    private static void next() {
        stage++; changed = ticks; craftHoverPrepared = false;
        FeedMePackages.LOGGER.info("FMP_TOMS_NETWORK_STAGE {}", stage);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
