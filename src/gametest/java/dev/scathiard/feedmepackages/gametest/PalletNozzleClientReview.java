package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.client.*;
import dev.scathiard.feedmepackages.domain.PalletFluidFeedback;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Native client use packets and real fluid debit; no direct animation triggers. */
final class PalletNozzleClientReview {
    private static final Minecraft MC=Minecraft.getInstance();
    private static int phase,ticks,changed;
    private static long receipts;
    private static CompletableFuture<Void> work;
    private static PalletMaterial water,lava;
    private static final BlockPos[] TARGETS={new BlockPos(7,3,10),new BlockPos(9,3,10),new BlockPos(11,3,10)};
    static boolean tick() throws Exception {
        if(phase==20)return true;
        ticks++;
        if(work!=null){if(!work.isDone())return false;work.join();work=null;}
        if(ticks-changed>200)throw new IllegalStateException("Nozzle review timeout phase="+phase);
        int elapsed=ticks-changed;
        switch(phase) {
            case 0 -> { server(p->{
                p.closeContainer();p.getInventory().clearContent();TestPlayers.necklace(p).setStackInSlot(0,FmpRegistries.PENDANT.toStack());
                p.getInventory().setItem(0,FmpRegistries.POCKET_PALLET.toStack());p.getInventory().selected=0;
                for(int x=5;x<=12;x++)for(int z=9;z<=11;z++)for(int y=3;y<=4;y++)p.serverLevel().setBlock(new BlockPos(x,y,z),Blocks.STONE.defaultBlockState(),3);
                for(var pos:TARGETS)p.serverLevel().setBlock(pos.above(),Blocks.AIR.defaultBlockState(),3);
                p.teleportTo(p.serverLevel(),8.5,4,8.5,0,45);
                InteractionTests.seedFluid(p,0,new FluidStack(Fluids.WATER,3000));InteractionTests.seedFluid(p,1,new FluidStack(Fluids.LAVA,1000));
                p.inventoryMenu.broadcastChanges();
            });next(); }
            case 1 -> {
                if(elapsed<20 || PalletClient.candidates().size()!=2)break;
                water=PalletClient.candidates().stream().filter(m->m.fluid().getFluid()==Fluids.WATER).findFirst().orElseThrow();
                lava=PalletClient.candidates().stream().filter(m->m.fluid().getFluid()==Fluids.LAVA).findFirst().orElseThrow();
                PalletClient.choose(water);next();
            }
            case 2 -> {
                if(!PalletClient.displayed().equals(water) || PalletClient.modePose(0).progress()!=1)break;
                require(PalletClient.fluidPose(0).equals(PalletFluidFeedback.Pose.REST),"Selection produced a fake fluid use");
                receipts=PalletClient.feedReceipts();use(new BlockPos(6,3,10));next();
            }
            case 3 -> {
                if(elapsed<8)break;
                require(PalletClient.feedReceipts()==receipts && PalletClient.fluidPose(0).equals(PalletFluidFeedback.Pose.REST),"Rejected placement emitted fluid feedback");
                server(p->require(InteractionTests.fluidStock(p,0)==3000,"Rejected placement charged fluid"));
                use(TARGETS[0]);next();
            }
            case 4 -> { if(PalletClient.feedReceipts()==receipts)break;require(PalletClient.feedReceipts()==receipts+1,"First success receipt mismatch");next(); }
            case 5 -> {
                require(PalletClient.fluidPose(0).emitting() && PalletClient.fluidPose(0).tip()>0,"No water jet/valve motion after actual use");
                TemplateClientReview.capture("r014-water-nozzle-pressed");
                FeedMePackages.LOGGER.info("FMP_R014_FIRST_FEEDBACK pose={}",PalletClient.fluidPose(0));
                use(TARGETS[1]);next();
            }
            case 6 -> { if(PalletClient.feedReceipts()==receipts+1)break;require(PalletClient.feedReceipts()==receipts+2,"Rapid use lost/doubled receipt");next(); }
            case 7 -> {
                require(PalletClient.fluidPose(0).emitting(),"Rapid use failed to restart fluid feedback");
                TemplateClientReview.capture("r014-water-rapid-use");use(TARGETS[2]);next();
            }
            case 8 -> { if(PalletClient.feedReceipts()==receipts+2)break;require(PalletClient.feedReceipts()==receipts+3,"Final-use receipt mismatch");next(); }
            case 9 -> {
                require(!PalletClient.hasCargo() && PalletClient.fluidPose(0).emitting()
                        && PalletClient.feedbackFluid().getFluid()==Fluids.WATER,"Last liquid cut off its successful emission or left fake cargo");
                TemplateClientReview.capture("r014-last-water-empty-chamber-feedback");
                server(p->{require(InteractionTests.fluidStock(p,0)==0 && p.getInventory().countItem(Items.BUCKET)==0,"Three uses did not conserve fluid/containers");
                    for(var pos:TARGETS)require(p.level().getFluidState(pos.above()).isSource(),"Native use did not place source at "+pos);});next();
            }
            case 10 -> {
                if(elapsed<8)break;require(PalletClient.fluidPose(0).equals(PalletFluidFeedback.Pose.REST),"Completed feedback was queued or retained");
                TemplateClientReview.capture("r014-empty-chamber-rest");use(TARGETS[0]);next();
            }
            case 11 -> {
                if(elapsed<8)break;require(PalletClient.feedReceipts()==receipts+3 && PalletClient.fluidPose(0).equals(PalletFluidFeedback.Pose.REST),"Empty use replayed feedback");
                server(p->{InteractionTests.seedFluid(p,0,new FluidStack(Fluids.WATER,2000));p.serverLevel().setBlock(TARGETS[0].above(),Blocks.AIR.defaultBlockState(),3);});next();
            }
            case 12 -> {
                if(PalletClient.amount(water)!=2000)break;
                require(PalletClient.fluidPose(0).equals(PalletFluidFeedback.Pose.REST),"Restocking emitted fluid");use(TARGETS[0]);next();
            }
            case 13 -> {if(PalletClient.feedReceipts()==receipts+3)break;require(PalletClient.feedReceipts()==receipts+4,"Restocked-use receipt mismatch");next();}
            case 14 -> {
                require(PalletClient.fluidPose(0).emitting(),"Switch fixture missed active feedback");
                PalletClient.choose(lava);require(!PalletClient.fluidPose(0).emitting() && PalletClient.feedbackFluid().isEmpty(),"Changing fluid retained old water jet");next();
            }
            case 15 -> {
                if(!PalletClient.displayed().equals(lava) || elapsed<6)break;
                require(PalletClient.modePose(0).progress()==1 && PalletClient.fluidPose(0).equals(PalletFluidFeedback.Pose.REST),"Same-form selection replayed transform/use");
                TemplateClientReview.capture("r014-lava-chamber");work=MC.reloadResourcePacks();next();
            }
            case 16 -> {
                if(elapsed<5)break;
                require(PalletClient.displayed().equals(lava) && PalletClient.modePose(0).progress()==1
                        && PalletClient.fluidPose(0).equals(PalletFluidFeedback.Pose.REST),"Resource reload retained stale nozzle motion");
                for(String part:new String[]{"fluid_head","fluid_pipe","nozzle_collar","nozzle_middle","nozzle_tip"})
                    require(MC.getModelManager().getModel(PalletRenderer.modelId(part))!=MC.getModelManager().getMissingModel(),"Model absent after reload: "+part);
                TemplateClientReview.capture("r014-lava-after-resource-reload");
                FeedMePackages.LOGGER.info("FMP_R014_NOZZLE_PASSED nativeUsePackets=true rejectedTargetNoFeedback=true rapidUse=true lastFluidFeedback=true emptyNoFeedback=true restockNoFeedback=true switchCancelsOldJet=true actualFluidTint=true reload=true fluidDebit=3000+1000");
                phase=20;return true;
            }
        }
        return false;
    }
    private static void use(BlockPos pos) {
        MC.gameMode.useItemOn(MC.player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(pos).add(0,.5,0),Direction.UP,pos,false));
    }
    private static void server(Consumer<ServerPlayer> action) { var server=MC.getSingleplayerServer();var id=MC.player.getUUID();work=server.submit(()->action.accept(server.getPlayerList().getPlayer(id))); }
    private static void next() {FeedMePackages.LOGGER.info("FMP_R014_NOZZLE_STAGE {} complete",phase);phase++;changed=ticks;}
    private static void require(boolean ok,String message) {if(!ok)throw new IllegalStateException(message);}
}
