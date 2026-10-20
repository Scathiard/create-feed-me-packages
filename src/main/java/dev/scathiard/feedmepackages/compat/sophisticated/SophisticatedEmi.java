package dev.scathiard.feedmepackages.compat.sophisticated;

import dev.emi.emi.api.recipe.*;
import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import dev.emi.emi.api.stack.EmiStack;
import dev.scathiard.feedmepackages.FmpConfig;
import dev.scathiard.feedmepackages.client.*;
import dev.scathiard.feedmepackages.consumption.CraftingPlanner;
import dev.scathiard.feedmepackages.network.TransferOrigin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.ArrayList;

/** Augments the author's existing EMI handler. Its physical-only operations keep their native path. */
public final class SophisticatedEmi {
    private SophisticatedEmi() {}
    private static boolean enabled() {
        return FmpConfig.emiCraftingEnabled() && ClientCompatibility.emiSourcesAvailable()
                && ClientCompatibility.sophisticatedSourcesAvailable() && ClientMaterials.craftingEnabled();
    }
    public static EmiPlayerInventory inventory(AbstractContainerScreen<?> screen,EmiPlayerInventory nativeInventory) {
        if(!enabled() || Minecraft.getInstance().player.containerMenu!=screen.getMenu()
                || SophisticatedAccess.resolve(Minecraft.getInstance().player)==null) return nativeInventory;
        var stacks=new ArrayList<EmiStack>(nativeInventory.inventory.values());
        ClientMaterials.craftingStacks().forEach(s->stacks.add(EmiStack.of(s.copyWithCount(Math.min(s.getCount(),9*s.getMaxStackSize())))));
        return new EmiPlayerInventory(stacks);
    }
    @SuppressWarnings("unchecked")
    public static boolean fill(EmiRecipe recipe,EmiCraftContext<?> request,boolean execute) {
        var mc=Minecraft.getInstance();var player=mc.player;
        if(!enabled() || player==null || request.getScreenHandler()!=player.containerMenu || !player.containerMenu.getCarried().isEmpty()
                || recipe.getId()==null || !recipe.supportsRecipeTree()) return false;
        var context=SophisticatedAccess.resolve(player);if(context==null) return false;
        var holder=player.level().getRecipeManager().byKey(recipe.getId()).orElse(null);
        if(holder==null || !(holder.value() instanceof CraftingRecipe)) return false;
        boolean maximum=request.getAmount()>1;
        var sources=new ArrayList<ItemStack>();context.all().forEach(s->sources.add(s.getItem().copy()));
        var physical=CraftingPlanner.solve((RecipeHolder<CraftingRecipe>)holder,3,3,64,1,maximum,sources,player.level());
        sources.addAll(ClientMaterials.craftingStacks());
        var combined=CraftingPlanner.solve((RecipeHolder<CraftingRecipe>)holder,3,3,64,1,maximum,sources,player.level());
        if(combined.error()!=CraftingPlanner.Error.NONE || physical.error()==CraftingPlanner.Error.NONE && physical.sets()>=combined.sets()) return false;
        if(execute) {
            int action=switch(request.getDestination()) { case NONE->0;case CURSOR->1;case INVENTORY->2; };
            context.open();mc.setScreen(request.getScreen());
            PacketDistributor.sendToServer(new SophisticatedTransfer.Request(context.menu().containerId,context.upgrade(),ClientMaterials.cacheId(),holder.id(),maximum,TransferOrigin.Viewer.EMI,action));
        }
        return true;
    }
}
