package dev.scathiard.feedmepackages.compat.jei;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.client.LogisticsPanel;
import dev.scathiard.feedmepackages.growth.PendantSmithingRecipe;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IVanillaCategoryExtensionRegistration;
import mezz.jei.api.recipe.category.extensions.vanilla.smithing.ISmithingCategoryExtension;
import mezz.jei.api.gui.builder.IIngredientAcceptor;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.gui.screens.inventory.*;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/**
 * JEI discovers this isolated plugin. No core/bootstrap class links to its optional API.
 *
 * <p>Deliberate scope (user decision 2026-09-17): this plugin only makes JEI work WITH our own screens -
 * the panel session and its layout exclusion area, plus showing our pendant smithing recipe. It does NOT
 * take part in recipe transfer: no handler is registered, JEI's "+" is answered by JEI itself (or by
 * whoever owns that slot in the pack), and our cache is reached through our own logistics panel and the
 * vanilla recipe book.
 */
@JeiPlugin
public final class FmpJeiPlugin implements IModPlugin {
    private static IJeiRuntime runtime;
    @Override public ResourceLocation getPluginUid() { return ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID, "jei"); }
    @Override public void onRuntimeAvailable(IJeiRuntime value) {
        runtime = value; LogisticsPanel.recipeOverlay(candidate -> candidate == runtime.getRecipesGui());
        // JEI's bookmark/history buttons are outside Screen.children and ignore ingredient exclusion areas.
        LogisticsPanel.overlayBottomInset(28);
    }
    @Override public void onRuntimeUnavailable() {
        runtime = null; LogisticsPanel.recipeOverlay(candidate -> false); LogisticsPanel.overlayBottomInset(0);
    }
    public static Optional<IJeiRuntime> runtime() { return Optional.ofNullable(runtime); }
    @Override public void registerVanillaCategoryExtensions(IVanillaCategoryExtensionRegistration registration) {
        // The standard extension calls assemble(), which intentionally cannot manufacture an authoritative pendant.
        registration.getSmithingCategory().addExtension(PendantSmithingRecipe.class, new ISmithingCategoryExtension<PendantSmithingRecipe>() {
            @Override public <T extends IIngredientAcceptor<T>> void setTemplate(PendantSmithingRecipe recipe, T acceptor) {
                acceptor.addIngredients(recipe.templateIngredient());
            }
            @Override public <T extends IIngredientAcceptor<T>> void setBase(PendantSmithingRecipe recipe, T acceptor) {
                acceptor.addIngredients(recipe.baseIngredient());
            }
            @Override public <T extends IIngredientAcceptor<T>> void setAddition(PendantSmithingRecipe recipe, T acceptor) {
                acceptor.addIngredients(recipe.additionIngredient());
            }
            @Override public <T extends IIngredientAcceptor<T>> void setOutput(PendantSmithingRecipe recipe, T acceptor) {
                acceptor.addItemStack(recipe.getResultItem(net.minecraft.client.Minecraft.getInstance().level.registryAccess()));
            }
        });
    }
    @Override public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        register(registration, InventoryScreen.class); register(registration, CraftingScreen.class); register(registration, CreativeModeInventoryScreen.class);
    }
    private static <T extends AbstractContainerScreen<?>> void register(IGuiHandlerRegistration registration, Class<T> type) {
        registration.addGuiContainerHandler(type, new IGuiContainerHandler<>() {
            @Override public List<Rect2i> getGuiExtraAreas(T screen) {
                return LogisticsPanel.exclusions(screen).stream().map(r -> new Rect2i(r.x(), r.y(), r.width(), r.height())).toList();
            }
        });
    }
}
