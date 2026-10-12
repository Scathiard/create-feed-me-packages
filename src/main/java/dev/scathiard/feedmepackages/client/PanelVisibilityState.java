package dev.scathiard.feedmepackages.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** This installation's last panel visibility, independent of worlds, items and server settings. */
final class PanelVisibilityState {
    static final String FILE_NAME = "create_feed_me_packages-ui-state.txt";
    private final Path file;

    PanelVisibilityState(Path file) {
        this.file = file.toAbsolutePath();
    }

    boolean load() throws IOException {
        String state;
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(33);
            if (bytes.length > 32) throw new IOException("Panel visibility state is too large");
            state = new String(bytes, StandardCharsets.US_ASCII).strip();
        } catch (NoSuchFileException missing) {
            return false;
        }
        return switch (state) {
            case "collapsed" -> true;
            case "expanded" -> false;
            default -> throw new IOException("Invalid panel visibility state");
        };
    }

    void save(boolean collapsed) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".fmp-panel-", ".tmp");
        try {
            try (var output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var bytes = ByteBuffer.wrap((collapsed ? "collapsed\n" : "expanded\n")
                        .getBytes(StandardCharsets.US_ASCII));
                while (bytes.hasRemaining()) output.write(bytes);
                output.force(true);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unavailable) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
