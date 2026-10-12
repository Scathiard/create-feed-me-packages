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
    /** Grid / spiral switch; unbound by default so it cannot collide with {@link #PANEL_FOLD} (both GUI, Tab). */
    public static final net.minecraft.client.KeyMapping PALLET_MODE = new net.minecraft.client.KeyMapping(
            "key.create_feed_me_packages.pallet_mode", net.neoforged.neoforge.client.settings.KeyConflictContext.GUI,
            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN, "key.categories.create_feed_me_packages");
    /** R-002: fold / unfold the logistics panel; rebindable and shown in the controls page. */
    public static final net.minecraft.client.KeyMapping PANEL_FOLD = new net.minecraft.client.KeyMapping(
            "key.create_feed_me_packages.panel_fold", net.neoforged.neoforge.client.settings.KeyConflictContext.GUI,
            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, org.lwjgl.glfw.GLFW.GLFW_KEY_TAB, "key.categories.create_feed_me_packages");
    /**
     * R-002: the "one-key collect" key. Unbound by default; when bound it reuses the panel transfer
     * button's existing {@code CacheActions.Action.COLLECT_MATCHING} verbatim - no new semantics.
     */
    public static final net.minecraft.client.KeyMapping PALLET_COLLECT = new net.minecraft.client.KeyMapping(
            "key.create_feed_me_packages.pallet_collect", net.neoforged.neoforge.client.settings.KeyConflictContext.GUI,
            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN, "key.categories.create_feed_me_packages");
    @SubscribeEvent public static void keys(net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent event) {
        event.register(SELECT_TEMPLATE);
        event.register(PALLET_MODE);
        event.register(PALLET_SELECT);
        event.register(PANEL_FOLD);
        event.register(PALLET_COLLECT);
    }
    @SubscribeEvent public static void setup(FMLClientSetupEvent event) { event.enqueueWork(() -> { LogisticsPanel.register(); ClientMaterials.register(); ClientCompatibility.register(); PalletClient.register(); }); }
}
