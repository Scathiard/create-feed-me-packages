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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Probe native Polymorph recipe selection inside Tom's crafting terminal. Test-only. */
final class TomsPolymorphClientReview {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final ResourceLocation IRON = ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID, "poly_iron_test");
    private static final ResourceLocation GOLD = ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID, "poly_gold_test");
    private static final BlockPos POS = new BlockPos(0, -60, 2);
    private static final boolean CONTROL = System.getProperty("fmp.templateReview.part", "")
            .equals("toms-conflict-control");
    private static int stage, ticks, changed;
    private static boolean resultHoverPrepared;
    private static CompletableFuture<Void> work;
    private static ResourceLocation chosenRecipe;
    private static Item chosenItem;

    static boolean tick() throws ReflectiveOperationException {
        ticks++;
        if (work != null) {
            if (!work.isDone()) return false;
            work.join(); work = null;
        }
        require(ticks - changed < 300, "Tom's+Polymorph timed out at stage " + stage);
        switch (stage) {
            case 0 -> {
                require(net.neoforged.fml.ModList.get().isLoaded("toms_storage")
                                && (CONTROL || net.neoforged.fml.ModList.get().isLoaded("polymorph")),
                        "Pinned Tom's/Polymorph review dependencies are absent");
                MC.options.guiScale().set(1);
                MC.resizeDisplay();
                server(player -> {
                    player.getInventory().clearContent();
                    player.getInventory().setItem(0, new ItemStack(Items.STONE_BRICKS));
                    ConsumptionTests.seed(player, 0, new ItemStack(Items.STONE_BRICKS), 8);
                    require(player.getServer().getRecipeManager().byKey(IRON).isPresent()
                                    && player.getServer().getRecipeManager().byKey(GOLD).isPresent(),
                            "Test-only recipe conflict is missing");
                    Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("toms_storage", "crafting_terminal"));
                    player.serverLevel().setBlockAndUpdate(POS, block.defaultBlockState());
                    var terminal = player.serverLevel().getBlockEntity(POS);
                    require(terminal instanceof MenuProvider, "Tom's terminal is absent");
                    player.connection.teleport(.5, -60, .5, 0, 0);
                    player.openMenu((MenuProvider) terminal);
                }); next();
            }
            case 1 -> {
                if (!(MC.screen instanceof AbstractContainerScreen<?> screen)
                        || !screen.getMenu().getClass().getName().equals("com.tom.storagemod.menu.CraftingTerminalMenu")
                        || ticks - changed < 12) return false;
                require(LogisticsPanel.visibleCells(screen).isEmpty(), "FMP panel claimed Tom's terminal");
                TemplateClientReview.nativeSlotClick(inventorySlot(screen, 0)); next();
            }
            case 2 -> {
                if (ticks - changed < 5 || !MC.player.containerMenu.getCarried().is(Items.STONE_BRICKS)) return false;
                TemplateClientReview.nativeSlotClick(1); next();
            }
            case 3 -> {
                if (ticks - changed < 15 || !MC.player.containerMenu.getCarried().isEmpty()) return false;
                var screen = (AbstractContainerScreen<?>) MC.screen;
                var result = screen.getMenu().getSlot(0).getItem();
                if (!result.is(Items.IRON_NUGGET) && !result.is(Items.GOLD_NUGGET)) return false;
                if (CONTROL) {
                    chosenItem = result.getItem();
                    stage = 7; changed = ticks;
                    FeedMePackages.LOGGER.info("FMP_TOMS_CONFLICT_CONTROL_RESULT {}", result);
                    return false;
                }
                Object widget = widget();
                if (widget == null) return false;
                Object selector = selection(widget);
                if (selector == null || outputs(selector).size() < 2) return false;
                AbstractWidget button = openButton(widget);
                require(button.visible && !LogisticsPanel.visibleCells(screen).stream()
                        .anyMatch(cell -> cell.bounds().contains(button.getX() + 8, button.getY() + 8)),
                        "Polymorph selector overlaps FMP panel on Tom's terminal");
                TemplateClientReview.capture("toms-polymorph-selector");
                TemplateClientReview.click(button.getX() + 8, button.getY() + 8); next();
            }
            case 4 -> {
                Object selector = selection(widget());
                if (selector == null || !(boolean) selector.getClass().getMethod("isActive").invoke(selector)
                        || ticks - changed < 4) return false;
                ItemStack current = ((AbstractContainerScreen<?>) MC.screen).getMenu().getSlot(0).getItem();
                AbstractWidget choice = null;
                for (Object candidate : outputs(selector)) {
                    ResourceLocation id = (ResourceLocation) candidate.getClass().getMethod("getResourceLocation").invoke(candidate);
                    ItemStack output = (ItemStack) candidate.getClass().getMethod("getOutput").invoke(candidate);
                    if ((id.equals(IRON) || id.equals(GOLD)) && !ItemStack.isSameItemSameComponents(output, current)) {
                        choice = (AbstractWidget) candidate;
                        chosenRecipe = id; chosenItem = output.getItem(); break;
                    }
                }
                require(choice != null, "Tom's terminal had no non-default Polymorph choice");
                TemplateClientReview.capture("toms-polymorph-options");
                TemplateClientReview.click(choice.getX() + 12, choice.getY() + 12); next();
            }
            case 5 -> {
                if (ticks - changed < 12) return false;
                server(player -> {
                    try {
                        var apiClass = Class.forName("com.illusivesoulworks.polymorph.api.PolymorphApi");
                        var api = apiClass.getMethod("getInstance").invoke(null);
                        var data = apiClass.getMethod("getPlayerRecipeData", Player.class).invoke(api, player);
                        var selected = (net.minecraft.world.item.crafting.RecipeHolder<?>) data.getClass()
                                .getMethod("getSelectedRecipe").invoke(data);
                        require(selected != null && selected.id().equals(chosenRecipe),
                                "Polymorph native packet did not select requested Tom's recipe");
                    } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
                    require(player.containerMenu.getSlot(0).getItem().is(chosenItem),
                            "Tom's terminal result did not follow native Polymorph selection");
                    require(ConsumptionTests.stock(player, 0) == 8,
                            "Polymorph selection changed FMP cache");
                }); next();
            }
            case 6 -> {
                if (!MC.player.containerMenu.getSlot(0).getItem().is(chosenItem)) return false;
                Object selector = selection(widget());
                boolean active = selector != null && (boolean) selector.getClass().getMethod("isActive").invoke(selector);
                FeedMePackages.LOGGER.info("FMP_TOMS_POLYMORPH_AFTER_SELECT chooserOpen={} result={}",
                        active, MC.player.containerMenu.getSlot(0).getItem());
                TemplateClientReview.capture("toms-polymorph-after-select");
                if (active) {
                    AbstractWidget button = openButton(widget());
                    TemplateClientReview.click(button.getX() + 8, button.getY() + 8);
                }
                next();
            }
            case 7 -> {
                if (ticks - changed < 6) return false;
                if (!CONTROL) {
                    Object selector = selection(widget());
                    require(selector == null || !(boolean) selector.getClass().getMethod("isActive").invoke(selector),
                            "Tom's Polymorph chooser remained open after dismissing");
                    if (!resultHoverPrepared) {
                        var screen = (AbstractContainerScreen<?>) MC.screen;
                        Slot result = screen.getMenu().getSlot(0);
                        TemplateClientReview.move(screen.getGuiLeft() + result.x + 8,
                                screen.getGuiTop() + result.y + 8);
                        resultHoverPrepared = true;
                        changed = ticks;
                        return false;
                    }
                }
                TemplateClientReview.nativeSlotClick(0);
                next();
            }
            case 8 -> {
                if (ticks - changed < 8) return false;
                server(player -> {
                    FeedMePackages.LOGGER.info("FMP_TOMS_POLYMORPH_RESULT_TRACE selected={} carry={} result={} grid={} inventory={} cache={}",
                            chosenItem, player.containerMenu.getCarried(), player.containerMenu.getSlot(0).getItem(),
                            player.containerMenu.getSlot(1).getItem(), player.getInventory().getItem(0),
                            ConsumptionTests.stock(player, 0));
                    require(player.containerMenu.getCarried().is(chosenItem)
                                    && player.containerMenu.getCarried().getCount() == 1,
                            "Tom's+Polymorph result click did not yield selected output");
                    require(player.containerMenu.getSlot(1).getItem().isEmpty()
                                    && player.getInventory().getItem(0).isEmpty()
                                    && ConsumptionTests.stock(player, 0) == 8,
                            "Tom's+Polymorph craft lost or duplicated physical input or touched FMP cache");
                    player.closeContainer();
                }); next();
            }
            case 9 -> {
                if (ticks - changed < 8 || MC.screen != null) return false;
                if (CONTROL) FeedMePackages.LOGGER.info("FMP_TOMS_CONFLICT_CONTROL_PASSED nativeResult=1 "
                        + "physicalInput=1 cacheUnchanged=8");
                else FeedMePackages.LOGGER.info("FMP_TOMS_POLYMORPH_CLIENT_PASSED nonDefault={} "
                        + "nativeSelectionPacket=true selectedOutput=1 physicalInput=1 cacheUnchanged=8", chosenRecipe);
                return true;
            }
        }
        return false;
    }

    private static int inventorySlot(AbstractContainerScreen<?> screen, int index) {
        for (int i = 0; i < screen.getMenu().slots.size(); i++) {
            Slot slot = screen.getMenu().getSlot(i);
            if (slot.container == MC.player.getInventory() && slot.getContainerSlot() == index) return i;
        }
        throw new IllegalStateException("Tom's menu lacks physical slot " + index);
    }
    private static Object widget() throws ReflectiveOperationException {
        return ((Optional<?>) Class.forName("com.illusivesoulworks.polymorph.client.RecipesWidget")
                .getMethod("get").invoke(null)).orElse(null);
    }
    private static Object selection(Object widget) throws ReflectiveOperationException {
        return widget == null ? null : widget.getClass().getMethod("getSelectionWidget").invoke(widget);
    }
    private static AbstractWidget openButton(Object widget) throws ReflectiveOperationException {
        Field field = Class.forName("com.illusivesoulworks.polymorph.api.client.base.AbstractRecipesWidget")
                .getDeclaredField("openButton");
        field.setAccessible(true);
        return (AbstractWidget) field.get(widget);
    }
    @SuppressWarnings("unchecked")
    private static List<Object> outputs(Object selection) throws ReflectiveOperationException {
        return (List<Object>) selection.getClass().getMethod("getOutputWidgets").invoke(selection);
    }
    private static void server(Consumer<ServerPlayer> action) {
        var id = MC.player.getUUID(); var server = MC.getSingleplayerServer();
        work = server.submit(() -> action.accept(server.getPlayerList().getPlayer(id)));
    }
    private static void next() { stage++; changed = ticks; FeedMePackages.LOGGER.info("FMP_TOMS_POLYMORPH_STAGE {}", stage); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
