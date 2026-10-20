package dev.scathiard.feedmepackages.consumption;

import dev.scathiard.feedmepackages.fluid.FluidVariantKey;
import dev.scathiard.feedmepackages.interaction.FluidActions;
import dev.scathiard.feedmepackages.service.AccessGate;
import dev.scathiard.feedmepackages.storage.CacheLedger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

/** Direct fluid use has no bucket ItemStack and can never return a bucket. */
public final class PalletFluidUse {
    private PalletFluidUse() {}
    public static InteractionResult use(ServerPlayer player,UseOnContext context,FluidVariantKey key) {
        var access=AccessGate.resolve(player); if(!access.active() || !player.mayBuild()) return InteractionResult.FAIL;
        var ledger=CacheLedger.get(player.getServer());var record=ledger.find(access.handle().cacheId());
        int slot=record.fluids().state().find(key);if(slot<0) return InteractionResult.FAIL;
        var clicked=context.getClickedPos();var target=clicked.relative(context.getClickedFace());
        if(!allowed(player,clicked) || !allowed(player,target) || FluidActions.operating(access.handle().cacheId())) return InteractionResult.FAIL;
        int amount=(int)Math.min(1000,Math.max(0,record.fluids().state().cells().get(slot).amount()-FluidActions.reserved(access.handle().cacheId(),slot)));
        if(amount==0) return PalletService.failed(player,"missing");
        var tool=player.getMainHandItem();
        var source=new FluidTank(amount) {
            private boolean authorized() {
                var now=AccessGate.resolve(player);
                return now.active() && access.handle().equals(now.handle()) && player.getMainHandItem()==tool
                        && player.isAlive() && !player.isSpectator() && player.containerMenu==player.inventoryMenu;
            }
            @Override public net.neoforged.neoforge.fluids.FluidStack drain(net.neoforged.neoforge.fluids.FluidStack resource,FluidAction action) {
                return authorized()?super.drain(resource,action):net.neoforged.neoforge.fluids.FluidStack.EMPTY;
            }
            @Override public net.neoforged.neoforge.fluids.FluidStack drain(int maximum,FluidAction action) {
                return authorized()?super.drain(maximum,action):net.neoforged.neoforge.fluids.FluidStack.EMPTY;
            }
        };
        source.setFluid(key.stack(player.registryAccess(),amount));
        return FluidActions.worldUse(player,access.handle(),slot,key,source,()-> {
            var targetHandler=player.level().getCapability(Capabilities.FluidHandler.BLOCK,clicked,context.getClickedFace());
            if(targetHandler!=null) {
                var moved=FluidUtil.tryFluidTransfer(targetHandler,source,amount,true);
                int drawn=amount-source.getFluidAmount();
                if(moved.getAmount()>drawn) throw new IllegalStateException("Native target reported more liquid than drawn");
                // A target may accept less than its simulation. The private transfer remainder never left the cache.
                int unused=drawn-moved.getAmount();if(unused>0) source.fill(key.stack(player.registryAccess(),unused),IFluidHandler.FluidAction.EXECUTE);
                return !moved.isEmpty();
            }
            if(amount<1000) return false;
            var state=player.level().getBlockState(clicked);var fluid=source.getFluid();
            boolean contains=state.getBlock() instanceof LiquidBlockContainer block && block.canPlaceLiquid(player,player.level(),clicked,state,fluid.getFluid());
            BlockPos pos=contains || state.canBeReplaced(fluid.getFluid()) ? clicked : target;
            if(!fluid.getFluidType().canBePlacedInLevel(player.level(),pos,fluid)) return false;
            if(EventHooks.onBlockPlace(player,BlockSnapshot.create(player.level().dimension(),player.level(),pos),context.getClickedFace())) return false;
            return FluidUtil.tryPlaceFluid(player,player.level(),context.getHand(),pos,source,fluid.copy());
        });
    }
    private static boolean allowed(ServerPlayer player,BlockPos pos) {
        return player.level().hasChunkAt(pos) && player.level().mayInteract(player,pos)
                && player.canInteractWithBlock(pos,1) && player.level().getWorldBorder().isWithinBounds(pos);
    }
}
