package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.client.LogisticsPanel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Native pointer gestures while the FMP panel shifts vanilla slots to the right. */
final class MouseTweaksClientReview {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final String MODE = System.getProperty("fmp.templateReview.part", "mousetweaks");
    private static final boolean RMB_OFF = MODE.equals("mousetweaks-rmb-off");
    private static final boolean LMB_OFF = MODE.equals("mousetweaks-lmb-off");
    private static final boolean WHEEL_OFF = MODE.equals("mousetweaks-wheel-off");
    private static int stage, ticks, changed;
    private static CompletableFuture<Void> work;

    static boolean tick() throws ReflectiveOperationException {
        ticks++;
        if (work != null) {
            if (!work.isDone()) return false;
            work.join(); work = null;
        }
        require(ticks - changed < 300, "Mouse Tweaks timed out at stage " + stage);
        switch (stage) {
            case 0 -> {
                require(net.neoforged.fml.ModList.get().isLoaded("mousetweaks"), "Pinned Mouse Tweaks release is absent");
                require(mouseTweaksConfig() != null, "Mouse Tweaks config was not initialized");
                require(MODE.equals("mousetweaks") || RMB_OFF || LMB_OFF || WHEEL_OFF,
                        "Unknown Mouse Tweaks isolated switch mode");
                server(player -> {
                    player.getInventory().clearContent();
                    ConsumptionTests.seed(player, 0, new ItemStack(Items.STONE_BRICKS), 8);
                    player.getInventory().setItem(0, new ItemStack(Items.DIRT, 4));
                    player.getInventory().setItem(9, new ItemStack(Items.DIRT, 2));
                    player.getInventory().setItem(10, new ItemStack(Items.DIRT, 2));
                    player.inventoryMenu.broadcastChanges();
                }); next();
            }
            case 1 -> { MC.setScreen(new InventoryScreen(MC.player)); next(); }
            case 2 -> {
                if (!(MC.screen instanceof InventoryScreen) || LogisticsPanel.visibleCells(MC.screen).size() != 9
                        || MC.player.getInventory().getItem(0).getCount() != 4 || ticks - changed < 12) return false;
                TemplateClientReview.capture("mouse-tweaks-panel-and-vanilla");
                activateSwitches();
                TemplateClientReview.nativeSlotClick(36); next();
            }
            case 3 -> {
                if (!MC.player.containerMenu.getCarried().is(Items.DIRT)
                        || MC.player.containerMenu.getCarried().getCount() != 4 || ticks - changed < 4) return false;
                press(37, 1); next();
            }
            case 4 -> { if (ticks - changed < 3) return false; move(38); next(); }
            case 5 -> { if (ticks - changed < 3) return false; move(39); next(); }
            case 6 -> { if (ticks - changed < 3) return false; release(39, 1); next(); }
            case 7 -> {
                if (ticks - changed < 8) return false;
                verifySwitches();
                server(player -> {
                    var menu = player.inventoryMenu;
                    if (!RMB_OFF) require(menu.getSlot(37).getItem().is(Items.DIRT)
                                    && menu.getSlot(38).getItem().is(Items.DIRT),
                            "Mouse Tweaks right drag missed an adjacent moved vanilla slot");
                    require(menu.getSlot(37).getItem().getCount() + menu.getSlot(38).getItem().getCount()
                                    + menu.getSlot(39).getItem().getCount() + menu.getCarried().getCount() == 4,
                            "Mouse Tweaks right drag duplicated or lost physical dirt");
                    require(ConsumptionTests.stock(player, 0) == 8, "Mouse Tweaks touched FMP stock");
                }); next();
            }
            case 8 -> {
                if (ticks - changed < 4) return false;
                if (!MC.player.containerMenu.getCarried().isEmpty())
                    TemplateClientReview.nativeSlotClick(40);
                next();
            }
            case 9 -> {
                if (ticks - changed < 8 || !MC.player.containerMenu.getCarried().isEmpty()) return false;
                press(9, 0); next();
            }
            case 10 -> {
                if (ticks - changed < 3) return false;
                move(10);
                next();
            }
            case 11 -> { if (ticks - changed < 3) return false; release(10, 0); next(); }
            case 12 -> {
                var menu = MC.player.containerMenu;
                if (ticks - changed < 12) return false;
                verifySwitches();
                if (!LMB_OFF && (!menu.getSlot(9).getItem().isEmpty()
                        || !menu.getSlot(10).getItem().isEmpty()
                        || !menu.getCarried().is(Items.DIRT) || menu.getCarried().getCount() != 4)) return false;
                server(player -> {
                    var actual = player.inventoryMenu;
                    if (LMB_OFF) require(actual.getSlot(9).getItem().getCount()
                                    + actual.getSlot(10).getItem().getCount() + actual.getCarried().getCount() == 4,
                            "LMB-disabled native drag lost or duplicated physical dirt");
                    else require(actual.getSlot(9).getItem().isEmpty() && actual.getSlot(10).getItem().isEmpty()
                                    && actual.getCarried().is(Items.DIRT) && actual.getCarried().getCount() == 4,
                            "Server did not settle the combined physical stacks");
                    require(ConsumptionTests.stock(player, 0) == 8, "Mouse Tweaks left drag touched cache");
                }); next();
            }
            case 13 -> {
                if (!MC.player.containerMenu.getCarried().isEmpty()) TemplateClientReview.nativeSlotClick(11);
                next();
            }
            case 14 -> {
                if (ticks - changed < 8 || !MC.player.containerMenu.getCarried().isEmpty()) return false;
                var bounds = LogisticsPanel.visibleCells(MC.screen).getFirst().bounds();
                wheel(bounds.x() + 8, bounds.y() + 8, -1); next();
            }
            case 15 -> {
                if (ticks - changed < 6) return false;
                server(player -> {
                    require(ConsumptionTests.stock(player, 0) == 8
                                    && player.getInventory().countItem(Items.DIRT) == 8,
                            "Mouse Tweaks panel wheel moved an item or changed cache stock");
                    player.inventoryMenu.getSlot(1).set(new ItemStack(Items.COBBLESTONE, 4));
                    player.inventoryMenu.broadcastChanges();
                }); next();
            }
            case 16 -> {
                if (ticks - changed < 8 || MC.player.containerMenu.getSlot(1).getItem().getCount() != 4) return false;
                wheelAtSlot(1, 1); next();
            }
            case 17 -> {
                if (ticks - changed < 6) return false;
                if (MC.player.containerMenu.getSlot(1).getItem().getCount() == 4) {
                    wheelAtSlot(1, -1); next();
                } else stage = 18;
            }
            case 18 -> {
                if (ticks - changed < 8) return false;
                verifySwitches();
                server(player -> {
                    int grid = player.inventoryMenu.getSlot(1).getItem().getCount();
                    int bag = player.getInventory().countItem(Items.COBBLESTONE);
                    int carried = player.inventoryMenu.getCarried().getCount();
                    FeedMePackages.LOGGER.info("FMP_MOUSE_TWEAKS_WHEEL_RESULT mode={} grid={} bag={} carried={}",
                            MODE, grid, bag, carried);
                    if (WHEEL_OFF) require(grid == 4 && bag == 0 && carried == 0,
                            "Disabled Mouse Tweaks wheel unexpectedly moved inventory material");
                    else require(grid < 4 && grid + bag + carried == 4,
                            "Mouse Tweaks native wheel did not move exactly conserved material between vanilla slots");
                    require(ConsumptionTests.stock(player, 0) == 8
                                    && player.getInventory().countItem(Items.DIRT) == 8,
                            "Vanilla-slot wheel affected FMP panel or prior dragged items");
                    player.closeContainer();
                }); next();
            }
            case 19 -> {
                if (ticks - changed < 8 || MC.screen != null) return false;
                FeedMePackages.LOGGER.info("FMP_MOUSE_TWEAKS_CLIENT_PASSED mode={} "
                        + "nativeDragConserved=true panelWheelIsolated=true vanillaOffsetCorrect=true cacheUnchanged=8", MODE);
                return true;
            }
        }
        return false;
    }

    private static void press(int slot, int button) throws ReflectiveOperationException {
        var screen = (AbstractContainerScreen<?>)MC.screen;
        var target = screen.getMenu().getSlot(slot);
        TemplateClientReview.mouse(screen.getGuiLeft() + target.x + 8,
                screen.getGuiTop() + target.y + 8, button, 1);
    }
    private static void move(int slot) throws ReflectiveOperationException {
        var screen = (AbstractContainerScreen<?>)MC.screen;
        var target = screen.getMenu().getSlot(slot);
        TemplateClientReview.move(screen.getGuiLeft() + target.x + 8,
                screen.getGuiTop() + target.y + 8);
    }
    private static void release(int slot, int button) throws ReflectiveOperationException {
        var screen = (AbstractContainerScreen<?>)MC.screen;
        var target = screen.getMenu().getSlot(slot);
        TemplateClientReview.mouse(screen.getGuiLeft() + target.x + 8,
                screen.getGuiTop() + target.y + 8, button, 0);
    }
    private static void wheelAtSlot(int slot, double delta) throws ReflectiveOperationException {
        var screen = (AbstractContainerScreen<?>)MC.screen;
        var target = screen.getMenu().getSlot(slot);
        wheel(screen.getGuiLeft() + target.x + 8, screen.getGuiTop() + target.y + 8, delta);
    }
    private static void wheel(double x, double y, double delta) throws ReflectiveOperationException {
        TemplateClientReview.move(x, y);
        var method = MouseHandler.class.getDeclaredMethod("onScroll", long.class, double.class, double.class);
        method.setAccessible(true);
        method.invoke(MC.mouseHandler, MC.getWindow().getWindow(), 0.0, delta);
    }

    private static Object mouseTweaksConfig() throws ReflectiveOperationException {
        return Class.forName("yalter.mousetweaks.Main").getField("config").get(null);
    }
    private static boolean switchValue(Object config, String name) throws ReflectiveOperationException {
        return config.getClass().getField(name).getBoolean(config);
    }
    private static void activateSwitches() throws ReflectiveOperationException {
        // Mouse Tweaks rereads its file on first seeing a screen. Initialize that screen first,
        // then change only its in-memory object; do not edit the user's or test profile's config.
        var main = Class.forName("yalter.mousetweaks.Main");
        var update = main.getDeclaredMethod("updateScreen", net.minecraft.client.gui.screens.Screen.class);
        update.setAccessible(true);
        update.invoke(null, MC.screen);
        Object config = mouseTweaksConfig();
        if (RMB_OFF) config.getClass().getField("rmbTweak").setBoolean(config, false);
        if (LMB_OFF) {
            config.getClass().getField("lmbTweakWithItem").setBoolean(config, false);
            config.getClass().getField("lmbTweakWithoutItem").setBoolean(config, false);
        }
        if (WHEEL_OFF) config.getClass().getField("wheelTweak").setBoolean(config, false);
        logSwitches(config);
        verifySwitches();
    }
    private static void verifySwitches() throws ReflectiveOperationException {
        Object config = mouseTweaksConfig();
        require(switchValue(config, "rmbTweak") != RMB_OFF
                        && switchValue(config, "lmbTweakWithItem") != LMB_OFF
                        && switchValue(config, "lmbTweakWithoutItem") != LMB_OFF
                        && switchValue(config, "wheelTweak") != WHEEL_OFF,
                "Mouse Tweaks switch changed after opening the inventory screen");
    }
    private static void logSwitches(Object config) throws ReflectiveOperationException {
        FeedMePackages.LOGGER.info("FMP_MOUSE_TWEAKS_SWITCH mode={} rmb={} lmbWith={} lmbWithout={} wheel={}",
                MODE, switchValue(config, "rmbTweak"), switchValue(config, "lmbTweakWithItem"),
                switchValue(config, "lmbTweakWithoutItem"), switchValue(config, "wheelTweak"));
    }
    private static void server(Consumer<ServerPlayer> action) {
        var id = MC.player.getUUID(); var server = MC.getSingleplayerServer();
        work = server.submit(() -> action.accept(server.getPlayerList().getPlayer(id)));
    }
    private static void next() { stage++; changed = ticks; FeedMePackages.LOGGER.info("FMP_MOUSE_TWEAKS_CLIENT_STAGE {}", stage); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
