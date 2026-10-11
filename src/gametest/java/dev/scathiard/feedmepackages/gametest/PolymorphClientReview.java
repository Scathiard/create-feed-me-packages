package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.client.LogisticsPanel;
import dev.scathiard.feedmepackages.consumption.CraftingReservations;
import dev.scathiard.feedmepackages.consumption.CraftingService;
import dev.scathiard.feedmepackages.service.AccessGate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Two conflicting test-only recipes, native selector clicks, then one reserved-cache craft. */
final class PolymorphClientReview {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final ResourceLocation IRON = ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID, "poly_iron_test");
    private static final ResourceLocation GOLD = ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID, "poly_gold_test");
    private static int stage, ticks, changed;
    private static CompletableFuture<Void> work;
    private static ResourceLocation selectedRecipe;
    private static Item selectedItem;

    static boolean tick() throws ReflectiveOperationException {
        ticks++;
        if (work != null) {
            if (!work.isDone()) return false;
            work.join(); work = null;
        }
        require(ticks - changed < 300, "Polymorph client timed out at stage " + stage);
        switch (stage) {
            case 0 -> {
                require(net.neoforged.fml.ModList.get().isLoaded("polymorph"), "Pinned Polymorph release is absent");
                server(player -> {
                    player.getInventory().clearContent();
                    ConsumptionTests.seed(player, 0, new ItemStack(Items.STONE_BRICKS), 8);
                    var pos = new BlockPos(0, -60, 2);
                    player.serverLevel().setBlockAndUpdate(pos, Blocks.CRAFTING_TABLE.defaultBlockState());
                    player.connection.teleport(.5, -60, .5, 0, 0);
                    player.openMenu(new SimpleMenuProvider((id, inventory, owner) -> new CraftingMenu(id, inventory,
                            ContainerLevelAccess.create(player.level(), pos)), Component.literal("Native Polymorph review")));
                    @SuppressWarnings("unchecked") var recipe = (RecipeHolder<CraftingRecipe>)player.getServer().getRecipeManager()
                            .byKey(IRON).orElseThrow(() -> new IllegalStateException("Missing test-only iron recipe"));
                    require(player.getServer().getRecipeManager().byKey(GOLD).isPresent(), "Missing test-only gold recipe");
                    require(CraftingService.place(player, recipe, false, false, true) == CraftingService.Result.OK,
                            "Could not reserve one stone brick for the conflict grid");
                    require(ConsumptionTests.stock(player, 0) == 8 && CraftingReservations.reservedCache(
                            AccessGate.resolve(player).handle().cacheId(), 0, null) == 1,
                            "Grid preparation changed physical cache stock");
                }); next();
            }
            case 1 -> {
                if (!(MC.screen instanceof AbstractContainerScreen<?> screen) || !(screen.getMenu() instanceof CraftingMenu)
                        || LogisticsPanel.visibleCells(screen).size() != 9 || !LogisticsPanel.recipeReady()
                        || ticks - changed < 15) return false;
                var widget = widget(); if (widget == null) return false;
                var selector = selection(widget); if (selector == null || outputs(selector).size() < 2) return false;
                var button = openButton(widget); if (!button.visible) return false;
                var result = screen.getMenu().getSlot(0).getItem();
                if (!result.is(Items.IRON_NUGGET) && !result.is(Items.GOLD_NUGGET)) return false;
                var outputSlot = screen.getMenu().getSlot(0);
                require(button.getX() == screen.getGuiLeft() + outputSlot.x &&
                                button.getY() == screen.getGuiTop() + outputSlot.y - 22,
                        "Polymorph selector was shifted away from its native result-slot anchor");
                require(!TemplateClientReview.hostLayout().bounds().contains(button.getX() + 8, button.getY() + 8),
                        "Polymorph selector overlaps the FMP panel");
                TemplateClientReview.capture("polymorph-before-select");
                TemplateClientReview.click(button.getX() + 8, button.getY() + 8);
                next();
            }
            case 2 -> {
                var selector = selection(widget());
                if (selector == null || !(boolean)selector.getClass().getMethod("isActive").invoke(selector)
                        || ticks - changed < 4) return false;
                var current = ((AbstractContainerScreen<?>)MC.screen).getMenu().getSlot(0).getItem();
                AbstractWidget choice = null;
                for (var candidate : outputs(selector)) {
                    var id = (ResourceLocation)candidate.getClass().getMethod("getResourceLocation").invoke(candidate);
                    var stack = (ItemStack)candidate.getClass().getMethod("getOutput").invoke(candidate);
                    if ((id.equals(IRON) || id.equals(GOLD)) && !ItemStack.isSameItemSameComponents(stack, current)) {
                        choice = (AbstractWidget)candidate; selectedRecipe = id; selectedItem = stack.getItem(); break;
                    }
                }
                require(choice != null, "No non-default native recipe choice was displayed");
                require(!TemplateClientReview.hostLayout().bounds().contains(choice.getX() + 12, choice.getY() + 12),
                        "Polymorph output choice overlaps the FMP panel");
                TemplateClientReview.capture("polymorph-options");
                TemplateClientReview.click(choice.getX() + 12, choice.getY() + 12);
                next();
            }
            case 3 -> {
                if (ticks - changed < 12) return false;
                server(player -> {
                    try {
                        var apiClass = Class.forName("com.illusivesoulworks.polymorph.api.PolymorphApi");
                        var api = apiClass.getMethod("getInstance").invoke(null);
                        var data = apiClass.getMethod("getPlayerRecipeData", Player.class).invoke(api, player);
                        var selected = (RecipeHolder<?>)data.getClass().getMethod("getSelectedRecipe").invoke(data);
                        require(selected != null && selected.id().equals(selectedRecipe),
                                "Native selection packet chose the wrong recipe: " + selected);
                    } catch (ReflectiveOperationException reflection) { throw new IllegalStateException(reflection); }
                    require(player.containerMenu.getSlot(0).getItem().is(selectedItem),
                            "Server result did not follow the selected native recipe");
                    require(ConsumptionTests.stock(player, 0) == 8 && CraftingReservations.reservedCache(
                            AccessGate.resolve(player).handle().cacheId(), 0, null) == 1,
                            "Selecting a recipe consumed or duplicated reserved material");
                }); next();
            }
            case 4 -> {
                if (!((AbstractContainerScreen<?>)MC.screen).getMenu().getSlot(0).getItem().is(selectedItem)) return false;
                TemplateClientReview.nativeSlotClick(0);
                next();
            }
            case 5 -> {
                if (ticks - changed < 12) return false;
                server(player -> {
                    CraftingReservations.validate(player);
                    require(player.containerMenu.getCarried().is(selectedItem)
                                    && player.containerMenu.getCarried().getCount() == 1,
                            "Native result click did not give exactly one selected output");
                    require(ConsumptionTests.stock(player, 0) == 7 && CraftingReservations.reservedCache(
                            AccessGate.resolve(player).handle().cacheId(), 0, null) == 0,
                            "Native result click did not settle exactly one cache input");
                    require(CraftingService.grid(player.containerMenu).getItems().stream().allMatch(ItemStack::isEmpty)
                                    && player.getInventory().isEmpty(),
                            "Native result click left hidden or duplicated materials");
                }); next();
            }
            case 6 -> {
                FeedMePackages.LOGGER.info("FMP_POLYMORPH_CLIENT_PASSED nativeSelector=true nonDefault={} "
                        + "nativeSelectionPacket=true selectedResult=true cacheDebit=1 output=1 panelSeparate=true", selectedRecipe);
                return true;
            }
        }
        return false;
    }

    private static Object widget() throws ReflectiveOperationException {
        return ((Optional<?>)Class.forName("com.illusivesoulworks.polymorph.client.RecipesWidget")
                .getMethod("get").invoke(null)).orElse(null);
    }
    private static Object selection(Object widget) throws ReflectiveOperationException {
        return widget == null ? null : widget.getClass().getMethod("getSelectionWidget").invoke(widget);
    }
    private static AbstractWidget openButton(Object widget) throws ReflectiveOperationException {
        var field = Class.forName("com.illusivesoulworks.polymorph.api.client.base.AbstractRecipesWidget")
                .getDeclaredField("openButton"); field.setAccessible(true); return (AbstractWidget)field.get(widget);
    }
    @SuppressWarnings("unchecked")
    private static List<Object> outputs(Object selection) throws ReflectiveOperationException {
        return (List<Object>)selection.getClass().getMethod("getOutputWidgets").invoke(selection);
    }
    private static void server(Consumer<ServerPlayer> action) {
        var id = MC.player.getUUID(); var server = MC.getSingleplayerServer();
        work = server.submit(() -> action.accept(server.getPlayerList().getPlayer(id)));
    }
    private static void next() { stage++; changed = ticks; FeedMePackages.LOGGER.info("FMP_POLYMORPH_CLIENT_STAGE {}", stage); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
