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

/** r005 authored parts; dynamic cargo and motion are strictly local first-person display. */
@EventBusSubscriber(modid=FeedMePackages.MOD_ID,bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class PalletRenderer extends BlockEntityWithoutLevelRenderer {
    private static final List<String> PARTS=List.of("fixed","tray_slide","jaw_left","jaw_right","outlet_gate");
    private static PalletRenderer instance;
    private PalletRenderer() { super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),Minecraft.getInstance().getEntityModels()); }
    public static ModelResourceLocation modelId(String name) {
        return ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(FeedMePackages.MOD_ID,"item/pocket_pallet/"+name));
    }
    @SubscribeEvent public static void models(ModelEvent.RegisterAdditional event) {
        event.register(modelId("whole"));event.register(modelId("fallback"));
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
        var motion=PalletClient.feedPose(mc.getTimer().getGameTimeDeltaPartialTick(false));
        draw(manager.getModel(modelId("fixed")),stack,pose,buffers,light,overlay);
        part("tray_slide",0,0,motion.tray(),stack,pose,buffers,light,overlay);
        part("jaw_left",-motion.jaws(),0,0,stack,pose,buffers,light,overlay);
        part("jaw_right",motion.jaws(),0,0,stack,pose,buffers,light,overlay);
        pose.pushPose();pose.translate(8/16.0,10.7/16.0,1.1/16.0);
        pose.mulPose(Axis.XP.rotationDegrees((float)motion.gate()));pose.translate(-8/16.0,-10.7/16.0,-1.1/16.0);
        draw(manager.getModel(modelId("outlet_gate")),stack,pose,buffers,light,overlay);pose.popPose();
        var cargo=PalletClient.displayed();
        if(motion.cargo() && !cargo.isEmpty()) {
            var use=cargo.fluid().isEmpty()?usePose(mc.player,cargo.item(),mc.getTimer().getGameTimeDeltaPartialTick(false))
                    :dev.scathiard.feedmepackages.domain.PalletUsePose.REST;
            // Animate the held material, never the housing, tray or jaws.
            pose.pushPose();pose.translate(8/16.0,(8.9+use.lift())/16.0,motion.cargoZ()/16.0);
            pose.mulPose(Axis.YP.rotationDegrees((float)use.turn()));pose.scale(3/16f,3/16f,3/16f);
            if(cargo.fluid().isEmpty()) mc.getItemRenderer().renderStatic(cargo.item(),ItemDisplayContext.NONE,light,overlay,pose,buffers,mc.level,0);
            else fluidCargo(cargo.fluid(),pose,buffers,light,overlay);
            pose.popPose();
        }
    }
    private static dev.scathiard.feedmepackages.domain.PalletUsePose usePose(net.minecraft.client.player.LocalPlayer player,ItemStack cargo,float partial) {
        var rest=dev.scathiard.feedmepackages.domain.PalletUsePose.REST;
        if(player==null || !player.isUsingItem() || player.getUsedItemHand()!=net.minecraft.world.InteractionHand.MAIN_HAND) return rest;
        var used=player.getUseItem();var animation=used.getUseAnimation();
        if((animation!=UseAnim.EAT && animation!=UseAnim.DRINK) || !ItemStack.isSameItemSameComponents(used,cargo)) return rest;
        return dev.scathiard.feedmepackages.domain.PalletUsePose.at(player.getTicksUsingItem()+partial);
    }
    private static void fluidCargo(net.neoforged.neoforge.fluids.FluidStack fluid,PoseStack pose,MultiBufferSource buffers,int light,int overlay) {
        var ext=IClientFluidTypeExtensions.of(fluid.getFluid()); var texture=ext.getStillTexture(fluid);
        if(texture==null) return;
        var sprite=Minecraft.getInstance().getTextureAtlas(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS).apply(texture);
        var vertices=buffers.getBuffer(RenderType.entityTranslucent(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS));
        int tint=ext.getTintColor(fluid);var matrix=pose.last();
        vertices.addVertex(matrix,-.5f,-.5f,0).setColor(tint).setUv(sprite.getU0(),sprite.getV1()).setOverlay(overlay).setLight(light).setNormal(matrix,0,0,1);
        vertices.addVertex(matrix,.5f,-.5f,0).setColor(tint).setUv(sprite.getU1(),sprite.getV1()).setOverlay(overlay).setLight(light).setNormal(matrix,0,0,1);
        vertices.addVertex(matrix,.5f,.5f,0).setColor(tint).setUv(sprite.getU1(),sprite.getV0()).setOverlay(overlay).setLight(light).setNormal(matrix,0,0,1);
        vertices.addVertex(matrix,-.5f,.5f,0).setColor(tint).setUv(sprite.getU0(),sprite.getV0()).setOverlay(overlay).setLight(light).setNormal(matrix,0,0,1);
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
