package dev.scathiard.feedmepackages.client;

import dev.scathiard.feedmepackages.FeedMePackages;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@EventBusSubscriber(modid = FeedMePackages.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientBootstrap {
    private ClientBootstrap() {}
    public static final net.minecraft.client.KeyMapping SELECT_TEMPLATE = new net.minecraft.client.KeyMapping(
            "key.create_feed_me_packages.select_template", net.neoforged.neoforge.client.settings.KeyConflictContext.GUI,
            com.mojang.blaze3d.platform.InputConstants.Type.MOUSE, 2, "key.categories.create_feed_me_packages");
    public static final net.minecraft.client.KeyMapping PALLET_SELECT = new net.minecraft.client.KeyMapping(
            "key.create_feed_me_packages.pallet_select", net.neoforged.neoforge.client.settings.KeyConflictContext.IN_GAME,
            com.mojang.blaze3d.platform.InputConstants.Type.MOUSE, 2, "key.categories.create_feed_me_packages");
    public static final net.minecraft.client.KeyMapping PALLET_MODE = new net.minecraft.client.KeyMapping(
            "key.create_feed_me_packages.pallet_mode", net.neoforged.neoforge.client.settings.KeyConflictContext.GUI,
            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, org.lwjgl.glfw.GLFW.GLFW_KEY_TAB, "key.categories.create_feed_me_packages");
    @SubscribeEvent public static void keys(net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent event) {
        event.register(SELECT_TEMPLATE);
        event.register(PALLET_MODE);
        event.register(PALLET_SELECT);
    }
    @SubscribeEvent public static void setup(FMLClientSetupEvent event) { event.enqueueWork(() -> { LogisticsPanel.register(); ClientMaterials.register(); ClientCompatibility.register(); PalletClient.register(); }); }
}
