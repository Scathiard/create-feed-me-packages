package dev.scathiard.feedmepackages.gametest;

import dev.scathiard.feedmepackages.FeedMePackages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.fml.ModList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Shared completion only; the two existing interaction drivers retain their own scenarios. */
public final class ClientReviewLifecycle {
    private static ClientReviewResult result;
    private static boolean finishing, ended;
    private static long finishStarted;
    public static void start() {
        if (result != null) return;
        try {
            var main = ModList.get().getModFileById(FeedMePackages.MOD_ID).getFile().getFilePath();
            String hash = Files.isRegularFile(main) ? HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(main))) : "development";
            String expected = System.getProperty("fmp.reviewArtifact", "development");
            result = new ClientReviewResult(Path.of(System.getProperty("fmp.reviewResult")),
                    System.getProperty("fmp.reviewRun"), System.getProperty("fmp.reviewMode"), hash);
            if (!expected.equals(hash)) throw new IllegalStateException("Wrong review artifact: " + hash);
            FeedMePackages.LOGGER.info("FMP_CLIENT_REVIEW_STARTED run={} mode={} artifact={}",
                    System.getProperty("fmp.reviewRun"), System.getProperty("fmp.reviewMode"), hash);
        } catch (Exception failure) { throw new IllegalStateException("Cannot start isolated review", failure); }
    }
    public static boolean completing() {
        if (ended) return true;
        if (!finishing) return false;
        var mc = Minecraft.getInstance();
        if (System.nanoTime() - finishStarted > 60_000_000_000L)
            throw new IllegalStateException("Review did not finish leaving the world");
        if (mc.level == null && mc.getSingleplayerServer() == null) {
            mc.stop();
            result.succeed(); ended = true;
            FeedMePackages.LOGGER.info("FMP_CLIENT_REVIEW_TERMINAL PASSED run={}", System.getProperty("fmp.reviewRun"));
        }
        return true;
    }
    public static void finish() {
        if (finishing || ended) return;
        finishing = true; finishStarted = System.nanoTime();
        var mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.closeContainer();
        if (mc.level != null) mc.level.disconnect();
        mc.disconnect(new TitleScreen());
    }
    public static void fail(Throwable failure, Runnable screenshot) {
        if (ended) return;
        ended = true;
        var mc = Minecraft.getInstance();
        ClientReviewResult.cleanup(failure,
                () -> { if (result != null) result.fail(failure); }, screenshot,
                () -> { if (mc.level != null) mc.level.disconnect(); },
                () -> mc.disconnect(new TitleScreen()), mc::stop);
        FeedMePackages.LOGGER.error("FMP_CLIENT_REVIEW_TERMINAL FAILED run=" + System.getProperty("fmp.reviewRun"), failure);
    }
}
