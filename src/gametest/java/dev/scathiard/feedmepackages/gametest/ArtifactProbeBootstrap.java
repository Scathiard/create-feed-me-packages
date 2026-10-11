package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/** Only the separately packaged test mod owns these subscriptions; never shipped in FMP. */
@EventBusSubscriber(modid = ArtifactProbeBootstrap.ID, bus = EventBusSubscriber.Bus.MOD)
public final class ArtifactProbeBootstrap {
    public static final String ID = "fmp_isolated_probe";
    @SubscribeEvent public static void register(RegisterEvent event) {
        ConsumptionTests.testItem(event); GadgetsTests.register(event); InteractionTests.registerFluidProbe(event);
    }
    @SubscribeEvent public static void capabilities(RegisterCapabilitiesEvent event) {
        InteractionTests.registerFluidCapability(event);
    }
    @SubscribeEvent public static void verify(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            var main=ModList.get().getModFileById(FeedMePackages.MOD_ID).getFile().getFilePath();
            var probe=ModList.get().getModFileById(ID).getFile().getFilePath();
            if (!main.toString().endsWith(".jar") || !probe.toString().endsWith(".jar") || main.equals(probe))
                throw new IllegalStateException("Artifact verification is not using two separate JARs: "+main+" / "+probe);
            try {
                var hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(main)));
                FeedMePackages.LOGGER.info("FMP_ARTIFACT_PROBE_SOURCE productionJar={} sha256={} externalDriver={}",main,hash,probe);
            } catch (java.io.IOException | java.security.NoSuchAlgorithmException failed) {
                throw new IllegalStateException("Cannot establish artifact provenance",failed);
            }
        });
    }
    @EventBusSubscriber(modid = ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Client {
        @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
            TemplateClientReview.setup(event); ClientReview.setup(event);
        }
    }
}
