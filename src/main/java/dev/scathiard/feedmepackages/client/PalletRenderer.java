package dev.scathiard.feedmepackages.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.scathiard.feedmepackages.FeedMePackages;
import dev.scathiard.feedmepackages.consumption.PalletService;
import dev.scathiard.feedmepackages.registry.FmpRegistries;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.extensions.common.*;
import java.util.*;

/** r014 working ends; dynamic cargo and reversible motion are local first-person display only. */
@EventBusSubscriber(modid=FeedMePackages.MOD_ID,bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class PalletRenderer extends BlockEntityWithoutLevelRenderer {
    private static final List<String> PARTS=List.of("create_body_fixed","feed_socket_fixed","rails_fixed","head_fixed",
            "tray_slide","jaw_left","jaw_right","outlet_gate","fluid_pipe","fluid_head","nozzle_collar","nozzle_middle","nozzle_tip");
    private static PalletRenderer instance;
    private PalletRenderer() { super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),Minecraft.getInstance().getEntityModels()); }
    public static ModelResourceLocation modelId(String name) {
        return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID,"item/pocket_pallet/"+name));
    }
    @SubscribeEvent public static void models(ModelEvent.RegisterAdditional event) {
        event.register(modelId("whole"));event.register(modelId("fallback"));event.register(modelId("fixed"));
        PARTS.forEach(name->event.register(modelId(name)));
    }
    @SubscribeEvent public static void extensions(RegisterClientExtensionsEvent event) {
        event.registerItem(new IClientItemExtensions() {
            @Override public boolean applyForgeHandTransform(PoseStack pose,net.minecraft.client.player.LocalPlayer player,
                    HumanoidArm arm,ItemStack stack,float partial,float equip,float swing) {
                if(arm!=player.getMainArm() || !player.getMainHandItem().is(FmpRegistries.POCKET_PALLET.get())) return false;
                // Keep the machine housing at the vanilla resting position. Native swing and
                // creative-use lowering must not stack on the authored tray/jaw animation.
                // The old item still lowers normally when the player switches away.
                pose.translate(arm==HumanoidArm.RIGHT?.56f:-.56f,-.52f,-.72f);
                return true;
            }
            @Override public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if(instance==null) instance=new PalletRenderer();
                return instance;
            }
        },FmpRegistries.POCKET_PALLET.get());
    }
    @SubscribeEvent public static void reloaded(ModelEvent.BakingCompleted event) { PalletClient.resetMotion(); }
    @Override public void renderByItem(ItemStack stack,ItemDisplayContext context,PoseStack pose,MultiBufferSource buffers,int light,int overlay) {
        var mc=Minecraft.getInstance();var manager=mc.getModelManager();
        var whole=manager.getModel(modelId("whole"));var missing=manager.getMissingModel();
        if(whole==missing) { draw(manager.getModel(modelId("fallback")),stack,pose,buffers,light,overlay);return; }
        boolean local=context.firstPerson() && mc.player!=null && PalletService.holding(mc.player)
                && ((context==ItemDisplayContext.FIRST_PERSON_RIGHT_HAND)==(mc.player.getMainArm()==HumanoidArm.RIGHT));
        if(!local || PARTS.stream().anyMatch(name->manager.getModel(modelId(name))==missing)) {
            draw(whole,stack,pose,buffers,light,overlay);return;
        }
        float partial=mc.getTimer().getGameTimeDeltaPartialTick(false);
        var mode=PalletClient.modePose(partial);var motion=PalletClient.feedPose(partial);var fluidMotion=PalletClient.fluidPose(partial);
        draw(manager.getModel(modelId("create_body_fixed")),stack,pose,buffers,light,overlay);
        draw(manager.getModel(modelId("feed_socket_fixed")),stack,pose,buffers,light,overlay);
        if(mode.itemVisible()) {
            clippedPart("rails_fixed",0,mode.itemOffset(),4.9,stack,pose,buffers,light,overlay,false);
            clippedPart("head_fixed",0,mode.itemOffset(),1,stack,pose,buffers,light,overlay,false);
            clippedPart("jaw_left",-motion.jaws(),mode.itemOffset(),1,stack,pose,buffers,light,overlay,false);
            clippedPart("jaw_right",motion.jaws(),mode.itemOffset(),1,stack,pose,buffers,light,overlay,false);
        }
        if(mode.trayVisible()) part("tray_slide",0,0,motion.tray(),stack,pose,buffers,light,overlay);
        pose.pushPose();pose.translate(8/16.0,10.7/16.0,1.1/16.0);
        pose.mulPose(Axis.XP.rotationDegrees((float)(mode.itemReady()?motion.gate():mode.gate())));pose.translate(-8/16.0,-10.7/16.0,-1.1/16.0);
        draw(manager.getModel(modelId("outlet_gate")),stack,pose,buffers,light,overlay);pose.popPose();
        var cargo=PalletClient.displayed();
        if(mode.fluidVisible()) {
            clippedPart("fluid_head",0,mode.fluidOffset(),1,stack,pose,buffers,light,overlay,true);
            clippedPart("fluid_pipe",0,mode.fluidOffset(),1,stack,pose,buffers,light,overlay,true);
            clippedPart("nozzle_collar",0,mode.fluidOffset(),1,stack,pose,buffers,light,overlay,false);
            clippedPart("nozzle_middle",0,mode.fluidOffset()+fluidMotion.middle(),1,stack,pose,buffers,light,overlay,false);
            clippedPart("nozzle_tip",0,mode.fluidOffset()+fluidMotion.tip(),1,stack,pose,buffers,light,overlay,false);
            if(PalletClient.hasCargo() && !cargo.fluid().isEmpty()) {
                pose.pushPose();pose.translate(0,0,mode.fluidOffset()/16);
                fluidBoxes(cargo.fluid(),FLUID_REGIONS,(float)((1-mode.fluidOffset())/16),pose,buffers,light,overlay);
                pose.popPose();
            }
        }
        if(mode.fluidVisible() && fluidMotion.emitting() && !PalletClient.feedbackFluid().isEmpty()) {
            pose.pushPose();pose.translate(0,0,mode.fluidOffset()/16);
            fluidEmission(PalletClient.feedbackFluid(),fluidMotion,(float)((1-mode.fluidOffset())/16),pose,buffers,light,overlay);
            pose.popPose();
        }
        if(mode.itemReady() && (motion.cargo() || PalletClient.usingCargo(mc.player,cargo.item())) && !cargo.isEmpty() && cargo.fluid().isEmpty()) {
            var use=usePose(mc.player,cargo.item(),partial);
            // The material moves while eating/drinking; the machine housing remains fixed.
            pose.pushPose();pose.translate(8/16.0,(8.9+use.lift())/16.0,motion.cargoZ()/16.0);
            pose.mulPose(Axis.YP.rotationDegrees((float)use.turn()));pose.scale(3/16f,3/16f,3/16f);
            mc.getItemRenderer().renderStatic(cargo.item(),ItemDisplayContext.NONE,light,overlay,pose,buffers,mc.level,0);
            pose.popPose();
        }
    }
    private static dev.scathiard.feedmepackages.domain.PalletUsePose usePose(net.minecraft.client.player.LocalPlayer player,ItemStack cargo,float partial) {
        var rest=dev.scathiard.feedmepackages.domain.PalletUsePose.REST;
        if(!PalletClient.usingCargo(player,cargo)) return rest;
        return dev.scathiard.feedmepackages.domain.PalletUsePose.at(player.getTicksUsingItem()+partial);
    }
    // r014's four display cavities. These contain no extra inventory or simulated liquid quantity.
    private static final double[][] FLUID_REGIONS={
        {7.26,8.06,-10.46,8.74,9.47,-7.04},{7.26,8.06,-6.96,8.74,9.47,-3.04},
        {7.26,8.06,-2.96,8.74,9.47,.96},{6.65,7.50,-13.50,9.35,10.10,-10.95}
    };
    private static final int[][] FACES={{0,3,2,1},{4,5,6,7},{0,4,7,3},{1,2,6,5},{0,1,5,4},{3,7,6,2}};
    private static final net.minecraft.core.Direction[] NORMALS={net.minecraft.core.Direction.NORTH,net.minecraft.core.Direction.SOUTH,
        net.minecraft.core.Direction.WEST,net.minecraft.core.Direction.EAST,net.minecraft.core.Direction.DOWN,net.minecraft.core.Direction.UP};
    private static void fluidBoxes(net.neoforged.neoforge.fluids.FluidStack fluid,double[][] boxes,float plane,PoseStack pose,MultiBufferSource buffers,int light,int overlay) {
        var ext=IClientFluidTypeExtensions.of(fluid.getFluid());var texture=ext.getStillTexture(fluid);
        if(texture==null) return;
        var atlas=net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS;
        var sprite=Minecraft.getInstance().getTextureAtlas(atlas).apply(texture);
        var consumer=buffers.getBuffer(RenderType.entityTranslucent(atlas));int tint=ext.getTintColor(fluid);
        int lit=LightTexture.pack(Math.max(LightTexture.block(light),fluid.getFluidType().getLightLevel(fluid)),LightTexture.sky(light));
        for(double[] box:boxes) {
            double[][] points={{box[0],box[1],box[2]},{box[3],box[1],box[2]},{box[3],box[4],box[2]},{box[0],box[4],box[2]},
                {box[0],box[1],box[5]},{box[3],box[1],box[5]},{box[3],box[4],box[5]},{box[0],box[4],box[5]}};
            for(int face=0;face<6;face++) {
                int[] data=new int[32];
                for(int v=0;v<4;v++) {
                    var point=points[FACES[face][v]];int offset=v*8;
                    for(int axis=0;axis<3;axis++) data[offset+axis]=Float.floatToRawIntBits((float)(point[axis]/16));
                    data[offset+3]=-1;
                    data[offset+4]=Float.floatToRawIntBits(v==0||v==3?sprite.getU0():sprite.getU1());
                    data[offset+5]=Float.floatToRawIntBits(v<2?sprite.getV1():sprite.getV0());
                }
                var quad=new net.minecraft.client.renderer.block.model.BakedQuad(data,-1,NORMALS[face],sprite,true);
                clippedQuad(quad,plane,pose,consumer,lit,overlay,tint);
            }
        }
    }
    private static final double[][] DROPS={{7.77,8.98,-17.05,.075},{8.25,8.72,-17.43,.065},{7.97,8.47,-17.13,.055}};
    private static void fluidEmission(net.neoforged.neoforge.fluids.FluidStack fluid,
            dev.scathiard.feedmepackages.domain.PalletFluidFeedback.Pose motion,float plane,
            PoseStack pose,MultiBufferSource buffers,int light,int overlay) {
        if(motion.jet()>0) {
            double tip=-15.72+motion.tip();
            fluidBoxes(fluid,new double[][]{{7.9,8.7,tip-1.33*motion.jet(),8.1,8.9,tip}},plane,pose,buffers,light,overlay);
        }
        for(int i=0;i<DROPS.length;i++) {
            double size=motion.dropScale(i)*DROPS[i][3];if(size<=0) continue;
            double age=motion.dropAge(i),x=DROPS[i][0]+(i-1)*age*.4,y=DROPS[i][1]-age*age*14,z=DROPS[i][2]-age*5;
            fluidBoxes(fluid,new double[][]{{x-size,y-size,z-size,x+size,y+size,z+size}},plane,pose,buffers,light,overlay);
        }
    }
    private static void clippedPart(String name,double x,double z,double plane,ItemStack stack,PoseStack pose,MultiBufferSource buffers,int light,int overlay,boolean window) {
        pose.pushPose();pose.translate(x/16,0,z/16);
        var model=Minecraft.getInstance().getModelManager().getModel(modelId(name));
        for(var pass:model.getRenderPasses(stack,true)) for(var type:pass.getRenderTypes(stack,true)) {
            var renderType=window?RenderType.entityCutoutNoCull(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS):type;
            var consumer=ItemRenderer.getFoilBufferDirect(buffers,renderType,true,stack.hasFoil());
            var random=net.minecraft.util.RandomSource.create(42);
            for(int face=0;face<=6;face++) {
                random.setSeed(42);
                var side=face==6?null:net.minecraft.core.Direction.values()[face];
                for(var quad:pass.getQuads(null,side,random)) clippedQuad(quad,(float)((plane-z)/16),pose,consumer,light,overlay,-1);
            }
        }
        pose.popPose();
    }
    private static void clippedQuad(net.minecraft.client.renderer.block.model.BakedQuad quad,float plane,PoseStack pose,
            com.mojang.blaze3d.vertex.VertexConsumer consumer,int light,int overlay,int tint) {
        int[] data=quad.getVertices();int inside=0;
        for(int i=2;i<data.length;i+=8) if(Float.intBitsToFloat(data[i])<=plane) inside++;
        if(inside==0) return;
        if(inside==4) { emit(quad,pose,consumer,light,overlay,tint);return; }
        var polygon=PalletClipping.clip(data,plane);
        if(polygon.size()<3) return;
        // A clipped quad can be a triangle or pentagon. Degenerate quads keep the native QUADS buffer.
        if(polygon.size()==4) {
            emit(clippedFace(quad,polygon.get(0),polygon.get(1),polygon.get(2),polygon.get(3)),pose,consumer,light,overlay,tint);
        } else for(int i=1;i<polygon.size()-1;i++)
            emit(clippedFace(quad,polygon.get(0),polygon.get(i),polygon.get(i+1),polygon.get(i+1)),pose,consumer,light,overlay,tint);
    }
    private static net.minecraft.client.renderer.block.model.BakedQuad clippedFace(net.minecraft.client.renderer.block.model.BakedQuad source,int[]... vertices) {
        int[] data=new int[32];for(int i=0;i<4;i++)System.arraycopy(vertices[i],0,data,i*8,8);
        return new net.minecraft.client.renderer.block.model.BakedQuad(data,source.getTintIndex(),source.getDirection(),source.getSprite(),source.isShade(),source.hasAmbientOcclusion());
    }
    private static void emit(net.minecraft.client.renderer.block.model.BakedQuad quad,PoseStack pose,com.mojang.blaze3d.vertex.VertexConsumer consumer,int light,int overlay,int tint) {
        consumer.putBulkData(pose.last(),quad,((tint>>16)&255)/255f,((tint>>8)&255)/255f,(tint&255)/255f,((tint>>>24)&255)/255f,light,overlay,true);
    }
    private static void part(String name,double x,double y,double z,ItemStack stack,PoseStack pose,MultiBufferSource buffers,int light,int overlay) {
        pose.pushPose();pose.translate(x/16,y/16,z/16);
        draw(Minecraft.getInstance().getModelManager().getModel(modelId(name)),stack,pose,buffers,light,overlay);pose.popPose();
    }
    private static void draw(BakedModel model,ItemStack stack,PoseStack pose,MultiBufferSource buffers,int light,int overlay) {
        // ItemRenderer has already applied the authored display transform and (-.5,-.5,-.5).
        // Drawing baked quads directly avoids invoking this custom renderer recursively.
        for(var pass:model.getRenderPasses(stack,true)) for(var type:pass.getRenderTypes(stack,true))
            Minecraft.getInstance().getItemRenderer().renderModelLists(pass,stack,light,overlay,pose,
                    ItemRenderer.getFoilBufferDirect(buffers,type,true,stack.hasFoil()));
    }
}
