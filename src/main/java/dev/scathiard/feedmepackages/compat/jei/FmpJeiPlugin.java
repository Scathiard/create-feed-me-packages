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

/** Registers presentation/templates only; validated native transfer gains cache sources through optional mixins. */
@JeiPlugin
public final class FmpJeiPlugin implements IModPlugin {
    private static IJeiRuntime runtime;
    @Override public ResourceLocation getPluginUid() { return ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID, "jei"); }
    @Override public void onRuntimeAvailable(IJeiRuntime value) {
        value.getIngredientManager().removeIngredientsAtRuntime(mezz.jei.api.constants.VanillaTypes.ITEM_STACK,
                List.of(dev.scathiard.feedmepackages.registry.FmpRegistries.PERSONAL_PENDANT.toStack(),
                        dev.scathiard.feedmepackages.registry.FmpRegistries.PRIVATE_LINK.toStack(),
                        dev.scathiard.feedmepackages.registry.FmpRegistries.INCOMPLETE_PRIVATE_LINK.toStack()));
        runtime = value; LogisticsPanel.recipeOverlay("jei", candidate -> runtime != null && candidate == runtime.getRecipesGui());
        dev.scathiard.feedmepackages.client.TemplateBrowsers.registerMaterial("jei", () -> {
            if (runtime == null) return dev.scathiard.feedmepackages.client.TemplateBrowsers.Template.EMPTY;
            var overlay = runtime.getIngredientListOverlay();
            var item = overlay.isListDisplayed() ? overlay.getIngredientUnderMouse(mezz.jei.api.constants.VanillaTypes.ITEM_STACK) : null;
            var fluid = overlay.isListDisplayed() ? overlay.getIngredientUnderMouse(mezz.jei.api.neoforge.NeoForgeTypes.FLUID_STACK) : null;
            if (fluid != null) return dev.scathiard.feedmepackages.client.TemplateBrowsers.Template.of(fluid);
            if (item != null) return dev.scathiard.feedmepackages.client.TemplateBrowsers.Template.of(item);
            var bookmark = runtime.getBookmarkOverlay();
            fluid = bookmark.getIngredientUnderMouse(mezz.jei.api.neoforge.NeoForgeTypes.FLUID_STACK);
            return fluid != null ? dev.scathiard.feedmepackages.client.TemplateBrowsers.Template.of(fluid)
                    : dev.scathiard.feedmepackages.client.TemplateBrowsers.Template.of(bookmark.getItemStackUnderMouse());
        });
        // JEI's bookmark/history buttons are outside Screen.children and ignore ingredient exclusion areas.
        LogisticsPanel.overlayBottomInset(28);
    }
    @Override public void onRuntimeUnavailable() {
        runtime = null; LogisticsPanel.recipeOverlay("jei", candidate -> false); LogisticsPanel.overlayBottomInset(0);
        dev.scathiard.feedmepackages.client.TemplateBrowsers.remove("jei");
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
        if (net.neoforged.fml.ModList.get().isLoaded("touhou_little_maid")) registerMaid(registration);
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void registerMaid(IGuiHandlerRegistration registration) {
        try {
            Class type = Class.forName("com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.AbstractMaidContainerGui")
                    .asSubclass(AbstractContainerScreen.class);
            register(registration, type);
        } catch (ReflectiveOperationException | LinkageError | ClassCastException unavailable) {
            FeedMePackages.LOGGER.warn("FMP maid JEI exclusion unavailable: native screen API changed", unavailable);
        }
    }
    private static <T extends AbstractContainerScreen<?>> void register(IGuiHandlerRegistration registration, Class<T> type) {
        // JEI explicitly disallows global ghost handlers on the vanilla inventories.
        // FMP handles drops into its own cells using the public hovered-template provider.
        registration.addGuiContainerHandler(type, new IGuiContainerHandler<>() {
            @Override public List<Rect2i> getGuiExtraAreas(T screen) {
                return LogisticsPanel.exclusions(screen).stream().map(r -> new Rect2i(r.x(), r.y(), r.width(), r.height())).toList();
            }
        });
    }
}
