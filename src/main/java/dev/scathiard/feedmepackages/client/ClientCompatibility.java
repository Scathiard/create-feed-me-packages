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
import java.util.function.BooleanSupplier;
import java.util.Set;

/** Warn once per changed failure on joining/reload, including players not wearing a pendant. */
public final class ClientCompatibility {
    private ClientCompatibility() {}
    private static CompatibilityStatus.State serverFxnt, serverEmi, serverTacz, serverWand, serverGadgets, serverEffortless, serverSophisticated, serverMaid, serverFactory, serverSbw;
    private static CompatibilityStatus.State serverCraftingTweaks,serverPaper,serverBee,serverPhantom,serverBiotech;
    private record Warning(String reason, String mod) {}
    private static Set<Warning> reported = Set.of();
    public static boolean sbwSourcesAvailable() { return serverSbw==CompatibilityStatus.State.READY && OptionalMixins.sbwCompatible(); }
    public static boolean sophisticatedSourcesAvailable() { return serverSophisticated == CompatibilityStatus.State.READY && dev.scathiard.feedmepackages.compat.sophisticated.SophisticatedAccess.enabled(); }
    public static boolean effortlessSourcesAvailable() { return serverEffortless == CompatibilityStatus.State.READY && OptionalMixins.effortlessCompatible(); }
    public static boolean gadgetsSourcesAvailable() { return serverGadgets == CompatibilityStatus.State.READY && OptionalMixins.gadgetsCompatible(); }
    public static boolean fxntSourcesAvailable() { return serverFxnt == CompatibilityStatus.State.READY; }
    public static boolean emiSourcesAvailable() { return serverEmi == CompatibilityStatus.State.READY && OptionalMixins.emiPreviewCompatible(); }
    public static boolean taczSourcesAvailable() { return serverTacz == CompatibilityStatus.State.READY
            && dev.scathiard.feedmepackages.compat.tacz.TaczMaterials.ready()
            && dev.scathiard.feedmepackages.compat.tacz.TaczClientSources.ready(); }
    public static void register() {
        CompatibilityStatus.receiveOnClient(message -> { serverFxnt = message.fxnt(); serverEmi = message.emi(); serverTacz = message.tacz(); serverWand = message.wand(); serverGadgets = message.gadgets(); serverEffortless = message.effortless(); serverSophisticated = message.sophisticated(); serverMaid = message.maid(); serverFactory = message.factory(); serverSbw=message.sbw(); serverCraftingTweaks=message.craftingTweaks(); serverPaper=message.paper(); serverBee=message.bee(); serverPhantom=message.phantom(); serverBiotech=message.biotech(); });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> { serverFxnt = null; serverEmi = null; serverTacz = null; serverWand = null; serverGadgets = null; serverEffortless = null; serverSophisticated = null; serverMaid = null; serverFactory = null; serverSbw=null; serverCraftingTweaks=null; serverPaper=null; serverBee=null; serverPhantom=null; serverBiotech=null; reported = Set.of(); });
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
    }
    private static void tick() {
        var player = Minecraft.getInstance().player;
        if (player == null || serverFxnt == null || player.tickCount % 20 != 0) return;
        var failures = new HashSet<Warning>();
        check(failures, "Crafting Tweaks", serverCraftingTweaks, false, OptionalMixins::craftingTweaksSafe);
        check(failures, "Paper Couriers", serverPaper, false, () -> true);
        check(failures, "Create: Mobile Packages", serverBee, false, () -> true);
        check(failures, "Create: Phantom Logistics", serverPhantom, false, () -> true);
        check(failures, "Create: BioTech", serverBiotech, false, () -> true);
        check(failures, "Superb Warfare", serverSbw, false, OptionalMixins::sbwCompatible);
        check(failures, "Create: Factory Logistics", serverFactory, false, () -> true);
        check(failures, "Touhou Little Maid", serverMaid, false, () -> true);
        check(failures, "Sophisticated Core", serverSophisticated, false,
                () -> !dev.scathiard.feedmepackages.compat.sophisticated.SophisticatedAccess.installed()
                        || dev.scathiard.feedmepackages.compat.sophisticated.SophisticatedAccess.ready());
        var mods = ModList.get();
        check(failures, "Effortless Building", serverEffortless, mods.isLoaded("effortlessbuilding"),
                () -> !mods.isLoaded("effortlessbuilding") || OptionalMixins.effortlessCompatible());
        check(failures, "Building Gadgets 2", serverGadgets, mods.isLoaded("buildinggadgets2"),
                () -> !mods.isLoaded("buildinggadgets2") || OptionalMixins.gadgetsCompatible());
        check(failures, "Construction Wand", serverWand, false, () -> true);
        check(failures, "TaCZ", serverTacz, mods.isLoaded("tacz"),
                () -> !mods.isLoaded("tacz") || taczSourcesAvailable());
        check(failures, "EMI", serverEmi, mods.isLoaded("emi"),
                () -> !mods.isLoaded("emi") || OptionalMixins.emiPreviewCompatible());
        check(failures, "Create: Storage", serverFxnt, false, () ->
                (!mods.isLoaded("emi") || !FmpConfig.emiCraftingEnabled() || OptionalMixins.fxntEmiOriginCompatible())
                && (!mods.isLoaded("jei") || !FmpConfig.jeiCraftingEnabled() || OptionalMixins.fxntPreviewFailures().isEmpty()));
        for (String reason : new String[]{"missing", "mismatch"}) {
            String names = failures.stream().filter(w -> w.reason().equals(reason) && !reported.contains(w))
                    .map(Warning::mod).sorted().collect(java.util.stream.Collectors.joining(", "));
            if (!names.isEmpty())
                player.displayClientMessage(Component.translatable("gui.create_feed_me_packages.compat." + reason, names), false);
        }
        reported = Set.copyOf(failures);
    }

    private static void check(Set<Warning> failures, String mod, CompatibilityStatus.State state,
                              boolean warnIfAbsent, BooleanSupplier clientReady) {
        if (state == CompatibilityStatus.State.API_MISMATCH || state == CompatibilityStatus.State.READY && !clientReady.getAsBoolean())
            failures.add(new Warning("mismatch", mod));
        else if (state == CompatibilityStatus.State.ABSENT && warnIfAbsent)
            failures.add(new Warning("missing", mod));
    }
}
