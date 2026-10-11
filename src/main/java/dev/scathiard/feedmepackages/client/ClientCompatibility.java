package dev.scathiard.feedmepackages.client;

import dev.scathiard.feedmepackages.FmpConfig;
import dev.scathiard.feedmepackages.mixin.OptionalMixins;
import dev.scathiard.feedmepackages.network.CompatibilityStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.util.HashSet;
import java.util.Set;

/** Warn once per changed failure on joining/reload, including players not wearing a pendant. */
public final class ClientCompatibility {
    private ClientCompatibility() {}
    private static CompatibilityStatus.State serverFxnt, serverEmi, serverTacz, serverWand, serverGadgets, serverEffortless, serverSophisticated, serverMaid;
    private static Set<String> reported = Set.of();
    public static boolean sophisticatedSourcesAvailable() { return serverSophisticated == CompatibilityStatus.State.READY && dev.scathiard.feedmepackages.compat.sophisticated.SophisticatedAccess.enabled(); }
    public static boolean effortlessSourcesAvailable() { return serverEffortless == CompatibilityStatus.State.READY && OptionalMixins.effortlessCompatible(); }
    public static boolean gadgetsSourcesAvailable() { return serverGadgets == CompatibilityStatus.State.READY && OptionalMixins.gadgetsCompatible(); }
    public static boolean fxntSourcesAvailable() { return serverFxnt == CompatibilityStatus.State.READY; }
    public static boolean emiSourcesAvailable() { return serverEmi == CompatibilityStatus.State.READY && OptionalMixins.emiPreviewCompatible(); }
    public static boolean taczSourcesAvailable() { return serverTacz == CompatibilityStatus.State.READY
            && dev.scathiard.feedmepackages.compat.tacz.TaczMaterials.ready()
            && dev.scathiard.feedmepackages.compat.tacz.TaczClientSources.ready(); }
    public static void register() {
        CompatibilityStatus.receiveOnClient(message -> { serverFxnt = message.fxnt(); serverEmi = message.emi(); serverTacz = message.tacz(); serverWand = message.wand(); serverGadgets = message.gadgets(); serverEffortless = message.effortless(); serverSophisticated = message.sophisticated(); serverMaid = message.maid(); });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> { serverFxnt = null; serverEmi = null; serverTacz = null; serverWand = null; serverGadgets = null; serverEffortless = null; serverSophisticated = null; serverMaid = null; reported = Set.of(); });
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
    }
    private static void tick() {
        var player = Minecraft.getInstance().player;
        if (player == null || serverFxnt == null || player.tickCount % 20 != 0) return;
        var failures = new HashSet<String>();
        if (serverMaid == CompatibilityStatus.State.CONFIG_DISABLED) failures.add("maid_config_disabled");
        else if (serverMaid == CompatibilityStatus.State.API_MISMATCH) failures.add("maid_server_mismatch");
        if (serverSophisticated == CompatibilityStatus.State.CONFIG_DISABLED) failures.add("sophisticated_config_disabled");
        else if (serverSophisticated == CompatibilityStatus.State.API_MISMATCH) failures.add("sophisticated_server_mismatch");
        else if (serverSophisticated == CompatibilityStatus.State.READY && ModList.get().isLoaded("sophisticatedbackpacks")
                && !dev.scathiard.feedmepackages.compat.sophisticated.SophisticatedAccess.ready()) failures.add("sophisticated_client_mismatch");
        if (serverEffortless == CompatibilityStatus.State.CONFIG_DISABLED) failures.add("effortless_config_disabled");
        else if (serverEffortless == CompatibilityStatus.State.API_MISMATCH) failures.add("effortless_server_mismatch");
        else if (serverEffortless == CompatibilityStatus.State.ABSENT && ModList.get().isLoaded("effortlessbuilding")) failures.add("effortless_server_missing");
        else if (serverEffortless == CompatibilityStatus.State.READY && ModList.get().isLoaded("effortlessbuilding") && !OptionalMixins.effortlessCompatible()) failures.add("effortless_client_mismatch");
        if (serverGadgets == CompatibilityStatus.State.CONFIG_DISABLED) failures.add("gadgets_config_disabled");
        else if (serverGadgets == CompatibilityStatus.State.API_MISMATCH) failures.add("gadgets_server_mismatch");
        else if (serverGadgets == CompatibilityStatus.State.ABSENT && ModList.get().isLoaded("buildinggadgets2")) failures.add("gadgets_server_missing");
        else if (serverGadgets == CompatibilityStatus.State.READY && ModList.get().isLoaded("buildinggadgets2") && !OptionalMixins.gadgetsCompatible()) failures.add("gadgets_client_mismatch");
        if (serverWand == CompatibilityStatus.State.CONFIG_DISABLED) failures.add("wand_config_disabled");
        else if (serverWand == CompatibilityStatus.State.API_MISMATCH) failures.add("wand_server_mismatch");
        if (serverTacz == CompatibilityStatus.State.ABSENT && ModList.get().isLoaded("tacz")) failures.add("tacz_server_missing");
        else if (serverTacz == CompatibilityStatus.State.CONFIG_DISABLED) failures.add("tacz_config_disabled");
        else if (serverTacz == CompatibilityStatus.State.API_MISMATCH) failures.add("tacz_server_mismatch");
        else if (serverTacz == CompatibilityStatus.State.READY && ModList.get().isLoaded("tacz")
                && (!dev.scathiard.feedmepackages.compat.tacz.TaczMaterials.ready()
                    || !dev.scathiard.feedmepackages.compat.tacz.TaczClientSources.ready())) failures.add("tacz_client_mismatch");
        if (serverEmi == CompatibilityStatus.State.ABSENT && ModList.get().isLoaded("emi")) failures.add("emi_server_missing");
        else if (serverEmi == CompatibilityStatus.State.CONFIG_DISABLED) failures.add("emi_config_disabled");
        else if (serverEmi == CompatibilityStatus.State.API_MISMATCH) failures.add("emi_server_mismatch");
        else if (serverEmi == CompatibilityStatus.State.READY && ModList.get().isLoaded("emi") && !OptionalMixins.emiPreviewCompatible()) failures.add("emi_client_mismatch");
        if (serverFxnt == CompatibilityStatus.State.CONFIG_DISABLED) failures.add("fxnt_config_disabled");
        else if (serverFxnt == CompatibilityStatus.State.API_MISMATCH) failures.add("fxnt_server_mismatch");
        if (serverFxnt == CompatibilityStatus.State.READY && ModList.get().isLoaded("emi") && FmpConfig.emiCraftingEnabled()
                && !OptionalMixins.fxntEmiOriginCompatible()) failures.add("fxnt_client_mismatch");
        if (serverFxnt == CompatibilityStatus.State.READY && ModList.get().isLoaded("jei")) {
            if (!FmpConfig.jeiCraftingEnabled()) failures.add("fxnt_jei_disabled");
            else if (!OptionalMixins.fxntPreviewFailures().isEmpty()) failures.add("fxnt_client_mismatch");
        }
        for (var failure : failures) if (!reported.contains(failure))
            player.displayClientMessage(Component.translatable("gui.create_feed_me_packages.compat." + failure), false);
        reported = Set.copyOf(failures);
    }
}
