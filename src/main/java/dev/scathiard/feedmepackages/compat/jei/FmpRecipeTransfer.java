package dev.scathiard.feedmepackages.compat.jei;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.client.ClientMaterials;
import dev.scathiard.feedmepackages.client.ClientCrafting;
import dev.scathiard.feedmepackages.client.ClientNotice;
import dev.scathiard.feedmepackages.client.NoticeText;
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
 * Public JEI extension, registered through JEI's official API only ({@code IRecipeTransferRegistration}).
 * The cache is a real source, so this handler never decides "not enough material" from a guess: with a live
 * logistics panel the SERVER arbitrates through the same {@code FILL_RECIPE} intent the panel uses - even
 * when the client's material hints have not arrived - and every refusal names its own reason. Only a player
 * without a live panel falls back to JEI's own transfer (the generic, unregistered-handler simulation JEI
 * hands to plugins), and that fallback is announced in the log instead of being silent.
 *
 * <p>This class does NOT replace, veto or wrap anyone else's registered handler: no mixins, no reflection,
 * no writes into JEI's registry. When another mod's handler owns the (CraftingMenu, crafting) key, that
 * handler is simply the one JEI calls - the cache supply is then reached through our own panel and the
 * vanilla recipe book, which is a documented limitation, not something we work around.
 *
 * <p>The outcome a viewer sees is spoken on the ACTION BAR, because a JEI recipe page covers the panel.
 */
final class FmpRecipeTransfer<C extends AbstractContainerMenu> implements IRecipeTransferHandler<C, RecipeHolder<CraftingRecipe>> {
    /** One log line per reason, so a single user run explains the whole path without spam. */
    private static final Set<String> LOGGED = Collections.synchronizedSet(new HashSet<>());
    /** The last native preview outcome, so the click line can explain what the hover already found out. */
    private static volatile String lastPreview = "n/a";
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
        if (perform) return performTransfer(menu, recipe, slots, player, maximum);
        // Preview, rule v3 (see PreviewPolicy): "clickable means it will work; if it cannot work, grey it and
        // say what is missing". This REPLACES the always-allow round, whose premise was wrong: the grey button
        // at 02:03 was judged correctly (that client had no iron plates anywhere), so releasing everything made
        // the button lie. The one safety valve kept: a miss we cannot name is released, never refused.
        // 1) JEI's own transfer (the generic unregistered-handler simulation) can do this click -> usable.
        var nativePreview = nativeTransfer(menu, recipe, slots, player, maximum, false);
        lastPreview = type(nativePreview);
        if (nativePreview == null) {
            once("previewNative", "FMP JEI plus preview: {} native=ok checked=n/a decision={} missing=[]", inputs(recipe, player, panel, hints, maximum), PreviewPolicy.Decision.ALLOW_NATIVE);
            return null;
        }
        // 2) No client material view: the cache cannot be judged here at all, so release (as before).
        if (!hints) {
            once("previewNoHints", "FMP JEI plus preview: {} native={} checked=n/a decision={} missing=[] (no client material view)", inputs(recipe, player, panel, false, maximum), type(nativePreview), PreviewPolicy.Decision.ALLOW_UNPROVABLE);
            return null;
        }
        // 3) Our own estimate - the same planner the server's fill uses (grid + player slots + cache view).
        var estimate = ClientCrafting.estimate(player, recipe, maximum, true);
        var missing = missing(estimate.missing());
        var verdict = verdict(estimate.result());
        var decision = PreviewPolicy.decide(false, verdict, !missing.components().isEmpty());
        once("preview" + decision + verdict + missing.log(), "FMP JEI plus preview: {} native={} checked={} decision={} missing=[{}]", inputs(recipe, player, panel, true, maximum), type(nativePreview), estimate.result(), decision, missing.log());
        if (decision != PreviewPolicy.Decision.REFUSE) return null;
        return error(PreviewPolicy.reason(decision, verdict), missing);
    }
    /** Map the crafting-service result onto the preview policy's axes. */
    private static PreviewPolicy.Estimate verdict(CraftingService.Result result) {
        if (result == null) return PreviewPolicy.Estimate.INACTIVE;
        return switch (result) {
            case OK -> PreviewPolicy.Estimate.OK;
            case MISSING -> PreviewPolicy.Estimate.MISSING;
            case NO_SPACE -> PreviewPolicy.Estimate.NO_SPACE;
            case UNSUPPORTED -> PreviewPolicy.Estimate.UNSUPPORTED;
            case TOO_COMPLEX -> PreviewPolicy.Estimate.TOO_COMPLEX;
            case INACTIVE, STALE -> PreviewPolicy.Estimate.INACTIVE;
        };
    }
    /**
     * The panel is live, so the cache is a real source: hand the intent to the server and let it answer.
     * Returning an error here without asking was what made a stocked cache look empty to JEI. The log line
     * is printed for every click - it is the one place that shows every input the client had, who answered,
     * what our top-up did, and which sentence the player was given.
     */
    private IRecipeTransferError performTransfer(C menu, RecipeHolder<CraftingRecipe> recipe, IRecipeSlotsView slots, Player player, boolean maximum) {
        var missing = missing(player, recipe, maximum);
        // 1) JEI's own transfer first (the generic unregistered-handler simulation): grid + player inventory,
        //    exactly as JEI would do it without us. Server rules unchanged, nobody else's handler involved.
        var nativeResult = nativeTransfer(menu, recipe, slots, player, maximum, true);
        // 2) Then let the server top up the remaining grid gap from the cache (FILL_RECIPE as before).
        if (LogisticsPanel.fillRecipeForViewer(recipe.id(), maximum, missing.components())) {
            FeedMePackages.LOGGER.info("FMP JEI plus clicked: {} preview={} native={} topup=sent notice=awaited(server verdict) missing=[{}]", inputs(recipe, player, true, ClientMaterials.active(), maximum), lastPreview, type(nativeResult), missing.log());
            return null;
        }
        if (nativeResult == null) {
            FeedMePackages.LOGGER.info("FMP JEI plus clicked: {} preview={} native=ok topup=not-needed notice=none", inputs(recipe, player, true, ClientMaterials.active(), maximum), lastPreview);
            return null; // The grid/backpack alone already satisfied the click.
        }
        boolean ready = LogisticsPanel.recipeReady();
        String reason = !ready ? "panel_not_ready" : LogisticsPanel.unbound() ? "unbound" : "inactive";
        // The intent was never sent, so the server will never answer: say it here, on the action bar, because the
        // panel's own notice can be covered by the recipe page that asked for this transfer.
        ClientNotice.speak(reason, missing.components());
        FeedMePackages.LOGGER.info("FMP JEI plus clicked: {} preview={} native={} topup=never-sent notice={} missing=[{}]", inputs(recipe, player, true, ClientMaterials.active(), maximum), lastPreview, type(nativeResult), ClientNotice.reasonKey(reason), missing.log());
        return error(reason);
    }
    /** The best-effort sentence material: rendered entries for the player, plain text for the log. */
    private record Missing(List<Component> components, String log) {}
    /**
     * Naming what this client could not cover, from an estimate the caller already has. It never decides
     * whether a refusal is allowed - {@link PreviewPolicy} decides, using "could we name it at all".
     */
    private static Missing missing(List<ItemStack> shortfall) {
        try {
            var components = new ArrayList<Component>();
            var names = new ArrayList<String>();
            for (var stack : shortfall == null ? List.<ItemStack>of() : shortfall) {
                if (components.size() >= 6) break;
                var name = stack.getHoverName();
                components.add(ClientNotice.entry(name, stack.getCount()));
                names.add(NoticeText.entryText(name.getString(), stack.getCount()));
            }
            return new Missing(List.copyOf(components), NoticeText.join(names));
        } catch (Throwable t) {
            return new Missing(List.of(), "");
        }
    }
    /** Same, computing the estimate here (the click path: one solve, then the sentence). */
    private static Missing missing(Player player, RecipeHolder<CraftingRecipe> recipe, boolean maximum) {
        try {
            return missing(ClientCrafting.shortfall(player, recipe, maximum, true));
        } catch (Throwable t) {
            return new Missing(List.of(), "");
        }
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
    private static String type(IRecipeTransferError error) {
        if (error == null) return "ok";
        try { return String.valueOf(error.getType()); } catch (Throwable t) { return error.getClass().getSimpleName(); }
    }
    /**
     * No live panel (unworn, disabled, or the snapshot never arrived): JEI's own transfer is the only
     * option, so the backpack keeps working - but the cache was not consulted, and the log says so.
     */
    private IRecipeTransferError withoutPanel(C menu, RecipeHolder<CraftingRecipe> recipe, IRecipeSlotsView slots, Player player, boolean maximum, boolean perform) {
        once("withoutPanel", "FMP JEI plus delegated to JEI's own transfer: no live logistics panel, the cache was not consulted");
        return nativeTransfer(menu, recipe, slots, player, maximum, perform);
    }
    /**
     * JEI's generic transfer - the unregistered-handler simulation JEI itself hands to plugins - and nothing
     * else. No other mod's handler is wrapped, replaced or even looked up: if another mod owns the key, JEI
     * calls it and we are not in that path at all. Kept in one place so preview and click cannot diverge.
     */
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
    }
    private IRecipeTransferError error(String key) { return helper.createUserErrorWithTooltip(Component.translatable("gui.create_feed_me_packages.result." + key)); }
    /**
     * A refusal the player can act on: our reason, plus what is missing when this client could name it, plus
     * - unconditionally - which sources that estimate looked at, so nobody thinks a worn backpack or another
     * mod's container was consulted.
     */
    private IRecipeTransferError error(String key, Missing missing) {
        var text = Component.empty().append(Component.translatable("gui.create_feed_me_packages.result." + key));
        var detail = ClientNotice.detail(missing == null ? List.of() : missing.components());
        if (detail != null) {
            text.append(detail);
            text.append(Component.translatable("gui.create_feed_me_packages.result.missing_scope"));
        }
        return helper.createUserErrorWithTooltip(text);
    }
}
