package dev.scathiard.feedmepackages.gametest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Test-only result contract. A failed assertion always wins over later work or cleanup. */
public final class ClientReviewResult {
    private final Path path;
    private final Properties values = new Properties();
    private boolean failed;

    public ClientReviewResult(Path path, String run, String mode, String artifact) throws IOException {
        this.path = path;
        values.setProperty("run", run);
        values.setProperty("mode", mode);
        values.setProperty("artifact", artifact);
        values.setProperty("status", "RUNNING");
        Files.createDirectories(path.toAbsolutePath().getParent());
        try (var out = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) {
            values.store(out, "FMP isolated client review");
        }
    }
    public void fail(Throwable problem) {
        if (failed) return;
        failed = true;
        values.setProperty("status", "FAILED");
        values.setProperty("failure", problem.toString());
        write();
    }
    public void succeed() {
        if (failed) throw new IllegalStateException("A failed review cannot pass");
        values.setProperty("status", "PASSED");
        write();
    }
    private void write() {
        try (var out = Files.newOutputStream(path)) { values.store(out, "FMP isolated client review"); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    public static CompletableFuture<Void> enqueue(CompletableFuture<Void> previous, Runnable operation, Executor executor) {
        return (previous == null ? CompletableFuture.<Void>completedFuture(null) : previous)
                .thenRunAsync(operation, executor);
    }
    /** Every cleanup step is attempted; none may replace the original failure. */
    public static void cleanup(Throwable first, Runnable... steps) {
        for (var step : steps) try { step.run(); }
        catch (Throwable secondary) { if (secondary != first) first.addSuppressed(secondary); }
    }
}
