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
 * Public JEI extension. The cache is a real source, so this handler never decides "not enough material"
 * from a guess: with a live logistics panel the SERVER arbitrates through the same {@code FILL_RECIPE}
 * intent the panel uses - even when the client's material hints have not arrived - and every refusal
 * names its own reason. Only a player without a live panel falls back to JEI's own transfer, and that
 * fallback is now announced in the log instead of being silent.
 *
 * <p>Two sources of answers, in this order: the handler our takeover DISPLACED (someone else's, which may
 * know extra sources such as a worn backpack) is consulted first, JEI's generic transfer is the fallback,
 * and the cache only tops up whatever the first step left over.
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
        // Preview. The order encodes one rule: the client does not get to veto the server.
        // 1) Whoever answers natively (the displaced handler, or JEI's generic one) succeeds -> the button is usable.
        var nativePreview = nativeTransfer(menu, recipe, slots, player, maximum, false);
        lastPreview = type(nativePreview);
        if (nativePreview == null) {
            once("previewNative", "FMP JEI plus preview: {} answered OK (grid/backpack already covers it)", via(menu));
            return null;
        }
        // 2) Panel live: everything else is RELEASED to the server, even when our own estimate says "missing".
        //    The client view is only an estimate - hint counts are hints, and the grid reading carries our own
        //    in-flight reservations - while the server arbitrates the real transfer. A preview stricter than the
        //    click is exactly how a stocked cache ends up looking empty: the button is greyed, so the click that
        //    would have succeeded never happens. Uncertain therefore means "allow", not "refuse".
        if (!hints) { once("previewNoHints", "FMP JEI plus preview: no client material view; released to the server: {}", inputs(recipe, player, panel, false, maximum)); return null; }
        var estimate = ClientCrafting.estimate(player, recipe, maximum, true);
        // The estimate is logged (one line per reason) but never decides.
        once("preview" + menu.getClass().getSimpleName() + estimate.result(), "FMP JEI plus preview: {} via={} native={} checked={}", inputs(recipe, player, panel, true, maximum), via(menu), type(nativePreview), estimate.result());
        if (PreviewPolicy.provablyUnsolvable(hints, estimate.result() == CraftingService.Result.MISSING, ClientMaterials.craftingStacks().isEmpty())) return error("missing_material");
        once("previewReleased" + estimate.result(), "FMP JEI plus preview released to the server (client estimate {} does not decide)", estimate.result());
        return null;
    }
    /**
     * The only case in which this client speaks for the server is {@link PreviewPolicy#provablyUnsolvable}:
     * a cache view that exists, is empty, and agrees with our own miss. Anything weaker is released.
     */
    /**
     * The panel is live, so the cache is a real source: hand the intent to the server and let it answer.
     * Returning an error here without asking was what made a stocked cache look empty to JEI. The log line
     * is printed for every click - it is the one place that shows every input the client had, who answered,
     * what our top-up did, and which sentence the player was given.
     */
    private IRecipeTransferError performTransfer(C menu, RecipeHolder<CraftingRecipe> recipe, IRecipeSlotsView slots, Player player, boolean maximum) {
        var missing = missing(player, recipe, maximum);
        // 1) The displaced handler (or JEI's generic transfer) first: grid + player inventory + whatever else it
        //    knows, exactly as JEI would do it without us. Server rules unchanged.
        var nativeResult = nativeTransfer(menu, recipe, slots, player, maximum, true);
        // 2) Then let the server top up the remaining grid gap from the cache (FILL_RECIPE as before).
        if (LogisticsPanel.fillRecipeForViewer(recipe.id(), maximum, missing.components())) {
            FeedMePackages.LOGGER.info("FMP JEI plus clicked: {} via={} preview={} native={} topup=sent notice=awaited(server verdict) missing=[{}]", inputs(recipe, player, true, ClientMaterials.active(), maximum), via(menu), lastPreview, type(nativeResult), missing.log());
            return null;
        }
        if (nativeResult == null) {
            FeedMePackages.LOGGER.info("FMP JEI plus clicked: {} via={} preview={} native=ok topup=not-needed notice=none", inputs(recipe, player, true, ClientMaterials.active(), maximum), via(menu), lastPreview);
            return null; // The grid/backpack alone already satisfied the click.
        }
        boolean ready = LogisticsPanel.recipeReady();
        String reason = !ready ? "panel_not_ready" : LogisticsPanel.unbound() ? "unbound" : "inactive";
        // The intent was never sent, so the server will never answer: say it here, on the action bar, because the
        // panel's own notice can be covered by the recipe page that asked for this transfer.
        ClientNotice.speak(reason, missing.components());
        FeedMePackages.LOGGER.info("FMP JEI plus clicked: {} via={} preview={} native={} topup=never-sent notice={} missing=[{}]", inputs(recipe, player, true, ClientMaterials.active(), maximum), via(menu), lastPreview, type(nativeResult), ClientNotice.reasonKey(reason), missing.log());
        return error(reason);
    }
    /** The best-effort sentence material: rendered entries for the player, plain text for the log. */
    private record Missing(List<Component> components, String log) {}
    /**
     * Best-effort naming of what this client could not cover, for the sentence shown to the player. Purely
     * explanatory: it never decides whether a transfer may be attempted, and a failure here never breaks it.
     */
    private static Missing missing(Player player, RecipeHolder<CraftingRecipe> recipe, boolean maximum) {
        try {
            var components = new ArrayList<Component>();
            var names = new ArrayList<String>();
            for (var stack : ClientCrafting.shortfall(player, recipe, maximum, true)) {
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
    /** Who answers the native half right now: the handler we displaced, or JEI's generic transfer. */
    private String via(C menu) {
        var delegate = delegate(menu);
        return delegate == null ? "generic-fallback" : "displaced:" + delegate.getClass().getSimpleName();
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
     * The handler our takeover displaced, when it can serve this menu. Never our own handler (the plugin
     * refuses to store it), so delegating cannot recurse.
     */
    @SuppressWarnings("unchecked")
    private IRecipeTransferHandler<C, RecipeHolder<CraftingRecipe>> delegate(C menu) {
        var original = FmpJeiPlugin.displacedHandler().orElse(null);
        if (original == null || menu == null) return null;
        try {
            if (!original.getContainerClass().isInstance(menu)) return null;
        } catch (Throwable t) {
            return null;
        }
        return (IRecipeTransferHandler<C, RecipeHolder<CraftingRecipe>>) original;
    }
    /**
     * The native half: the displaced handler first (it may know sources we cannot see, e.g. a worn backpack),
     * JEI's generic transfer as the fallback. Kept in one place so preview and click cannot diverge.
     */
    private IRecipeTransferError nativeTransfer(C menu, RecipeHolder<CraftingRecipe> recipe, IRecipeSlotsView slots, Player player, boolean maximum, boolean perform) {
        var delegate = delegate(menu);
        if (delegate != null) {
            try {
                return delegate.transferRecipe(menu, recipe, slots, player, maximum, perform);
            } catch (Throwable t) {
                once("delegateFailed" + t.getClass().getName(), "FMP JEI plus: the displaced handler {} threw {} - falling back to JEI's generic transfer", delegate.getClass().getSimpleName(), t.getClass().getName());
            }
        }
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
