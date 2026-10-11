package dev.scathiard.feedmepackages.compat.emi;

import dev.emi.emi.api.*;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.widget.Bounds;
import dev.scathiard.feedmepackages.client.LogisticsPanel;
import dev.scathiard.feedmepackages.client.TemplateBrowsers;
import net.minecraft.world.item.ItemStack;

/** EMI discovers this optional adapter. Common bootstrap never references EMI or client classes. */
@EmiEntrypoint
public final class FmpEmiPlugin implements EmiPlugin {
    @Override public void register(EmiRegistry registry) {
        registry.removeEmiStacks(stack -> {
            var item = stack.getItemStack();
            return item.is(dev.scathiard.feedmepackages.registry.FmpRegistries.PERSONAL_PENDANT.get())
                    || item.is(dev.scathiard.feedmepackages.registry.FmpRegistries.PRIVATE_LINK.get())
                    || item.is(dev.scathiard.feedmepackages.registry.FmpRegistries.INCOMPLETE_PRIVATE_LINK.get());
        });
        // The API resolves the current screen only. Opening events carry the next screen instead,
        // so recognize the two documented implementation screens locally, without linking core code.
        LogisticsPanel.recipeOverlay("emi", candidate -> candidate != null && (
                candidate.getClass().getName().equals("dev.emi.emi.screen.RecipeScreen")
                || candidate.getClass().getName().equals("dev.emi.emi.screen.BoMScreen")));
        TemplateBrowsers.registerMaterial("emi", () -> {
            // The no-coordinate overload uses EMI's previous rendered frame, which can be stale on a click.
            return template(EmiApi.getHoveredStack(TemplateBrowsers.cursorX(), TemplateBrowsers.cursorY(), false).getStack());
        });
        registry.addGenericExclusionArea((screen, consumer) -> LogisticsPanel.exclusions(screen)
                .forEach(r -> consumer.accept(new Bounds(r.x(), r.y(), r.width(), r.height()))));
        // EMI consumes its native drag before NeoForge screen events; use its public drop callback.
        registry.addGenericDragDropHandler((screen, ingredient, x, y) -> {
            var stack = template(ingredient); if (stack.isEmpty()) return false;
            for (var cell : LogisticsPanel.visibleCells(screen))
                if (cell.bounds().contains(x, y)) return LogisticsPanel.setTemplate(cell.slot(), stack);
            return false;
        });
    }
    private static TemplateBrowsers.Template template(EmiIngredient ingredient) {
        if (ingredient == null || ingredient.isEmpty() || ingredient.getEmiStacks().size() != 1) return TemplateBrowsers.Template.EMPTY;
        var stack = ingredient.getEmiStacks().getFirst();
        if (stack.getKey() instanceof net.minecraft.world.level.material.Fluid fluid) {
            var liquid = new net.neoforged.neoforge.fluids.FluidStack(fluid, 1); liquid.applyComponents(stack.getComponentChanges());
            return TemplateBrowsers.Template.of(liquid);
        }
        return TemplateBrowsers.Template.of(stack.getItemStack());
    }
}
