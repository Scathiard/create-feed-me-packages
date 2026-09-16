package dev.scathiard.feedmepackages.compat.jei;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.client.ClientMaterials;
import dev.scathiard.feedmepackages.client.ClientCrafting;
import dev.scathiard.feedmepackages.client.LogisticsPanel;
import dev.scathiard.feedmepackages.consumption.CraftingService;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.transfer.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import java.util.*;

/**
 * Public JEI extension. The cache is a real source, so this handler never decides "not enough material"
 * from a guess: with a live logistics panel the SERVER arbitrates through the same {@code FILL_RECIPE}
 * intent the panel uses - even when the client's material hints have not arrived - and every refusal
 * names its own reason. Only a player without a live panel falls back to JEI's own transfer, and that
 * fallback is now announced in the log instead of being silent.
 */
final class FmpRecipeTransfer<C extends AbstractContainerMenu> implements IRecipeTransferHandler<C, RecipeHolder<CraftingRecipe>> {
    /** One log line per reason, so a single user run explains the whole path without spam. */
    private static final Set<String> LOGGED = Collections.synchronizedSet(new HashSet<>());
    private final Class<C> type;
    private final MenuType<C> menuType;
    private final int width;
    private final IRecipeTransferHandlerHelper helper;
    private final IRecipeTransferHandler<C, RecipeHolder<CraftingRecipe>> fallback;
    FmpRecipeTransfer(Class<C> type, MenuType<C> menuType, int width, IRecipeTransferHandlerHelper helper) {
        this.type = type; this.menuType = menuType; this.width = width; this.helper = helper;
        var info = helper.createBasicRecipeTransferInfo(type, menuType, RecipeTypes.CRAFTING, 1, width * width, width == 2 ? 9 : 10, 36);
        fallback = helper.createUnregisteredRecipeTransferHandler(info);
    }
    @Override public Class<C> getContainerClass() { return type; }
    @Override public Optional<MenuType<C>> getMenuType() { return Optional.ofNullable(menuType); }
    @Override public RecipeType<RecipeHolder<CraftingRecipe>> getRecipeType() { return RecipeTypes.CRAFTING; }
    @Override public IRecipeTransferError transferRecipe(C menu, RecipeHolder<CraftingRecipe> recipe, IRecipeSlotsView slots, Player player, boolean maximum, boolean perform) {
        boolean panel = LogisticsPanel.cacheLive(), hints = ClientMaterials.active();
        if (!panel) return withoutPanel(menu, recipe, slots, player, maximum, perform);
        if (perform) return performTransfer(recipe, maximum, player);
        if (!hints) { once("previewNoHints", "FMP JEI plus preview without hints: {}", inputs(recipe, player, panel, false, maximum)); return null; }
        var checked = ClientCrafting.check(player, recipe, maximum, true);
        once("preview" + menu.getClass().getSimpleName() + checked, "FMP JEI plus preview: {} checked={}", inputs(recipe, player, panel, true, maximum), checked);
        return switch (checked) {
            case OK -> null;
            case UNSUPPORTED -> error("unsupported_recipe");
            case TOO_COMPLEX -> error("too_complex");
            case NO_SPACE -> error("no_space");
            case MISSING -> error("missing_material");
            case INACTIVE, STALE -> null; // Uncertain: the click still lets the server arbitrate.
        };
    }
    /**
     * The panel is live, so the cache is a real source: hand the intent to the server and let it answer.
     * Returning an error here without asking was what made a stocked cache look empty to JEI. The log line
     * is printed for every click - it is the one place that shows every input the client had.
     */
    private IRecipeTransferError performTransfer(RecipeHolder<CraftingRecipe> recipe, boolean maximum, Player player) {
        FeedMePackages.LOGGER.info("FMP JEI plus clicked: {}", inputs(recipe, player, true, ClientMaterials.active(), maximum));
        if (LogisticsPanel.fillRecipe(recipe.id(), maximum)) return null;
        boolean ready = LogisticsPanel.recipeReady();
        once("refused", "FMP JEI plus refused locally: panelReady={} unbound={}", ready, LogisticsPanel.unbound());
        return error(!ready ? "panel_not_ready" : LogisticsPanel.unbound() ? "unbound" : "inactive");
    }
    /** Every input the 2x2/3x3 comparison needs, in one line: menu, width, hints, cache stacks, grid. */
    private String inputs(RecipeHolder<CraftingRecipe> recipe, Player player, boolean panel, boolean hints, boolean maximum) {
        var menu = player.containerMenu;
        int gridSize = menu == null || menu.slots.isEmpty() || !(menu.getSlot(1).container instanceof net.minecraft.world.inventory.CraftingContainer grid) ? -1 : grid.getItems().size();
        int hintStacks = ClientMaterials.stacks().size(), hintTotal = ClientMaterials.stacks().stream().mapToInt(ItemStack::getCount).sum();
        int cacheStacks = ClientMaterials.craftingStacks().size(), cacheTotal = ClientMaterials.craftingStacks().stream().mapToInt(ItemStack::getCount).sum();
        return "recipe=" + recipe.id() + " menu=" + (menu == null ? "none" : menu.getClass().getSimpleName()) + "/" + (menu == null ? -1 : menu.containerId)
                + " width=" + width + " panelLive=" + panel + " panelReady=" + LogisticsPanel.recipeReady() + " hintsActive=" + hints
                + " hintStacks=" + hintStacks + " hintTotal=" + hintTotal + " cacheStacks=" + cacheStacks + " cacheTotal=" + cacheTotal
                + " gridSlots=" + gridSize + " maximum=" + maximum;
    }
    /**
     * No live panel (unworn, disabled, or the snapshot never arrived): JEI's own transfer is the only
     * option, so the backpack keeps working - but the cache was not consulted, and the log says so.
     */
    private IRecipeTransferError withoutPanel(C menu, RecipeHolder<CraftingRecipe> recipe, IRecipeSlotsView slots, Player player, boolean maximum, boolean perform) {
        once("withoutPanel", "FMP JEI plus delegated to JEI's own transfer: no live logistics panel, the cache was not consulted");
        return nativeTransfer(menu, recipe, slots, player, maximum, perform);
    }
    private IRecipeTransferError nativeTransfer(C menu, RecipeHolder<CraftingRecipe> recipe, IRecipeSlotsView slots, Player player, boolean maximum, boolean perform) {
        if (width == 3) return fallback.transferRecipe(menu, recipe, slots, player, maximum, perform);
        var input = slots.getSlotViews(RecipeIngredientRole.INPUT);
        var mapped = new ArrayList<mezz.jei.api.gui.ingredient.IRecipeSlotView>();
        for (int index = 0; index < input.size(); index++) {
            if (index % 3 < 2 && index / 3 < 2) mapped.add(input.get(index));
            else if (!input.get(index).isEmpty()) return helper.createUserErrorWithTooltip(Component.translatable("jei.tooltip.error.recipe.transfer.too.large.player.inventory"));
        }
        return fallback.transferRecipe(menu, recipe, helper.createRecipeSlotsView(mapped), player, maximum, perform);
    }
    private static void once(String key, String format, Object... arguments) {
        if (LOGGED.add(key)) FeedMePackages.LOGGER.info(format, arguments);
    }    private IRecipeTransferError error(String key) { return helper.createUserErrorWithTooltip(Component.translatable("gui.create_feed_me_packages.result." + key)); }
}
