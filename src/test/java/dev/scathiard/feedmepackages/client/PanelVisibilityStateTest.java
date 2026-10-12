package dev.scathiard.feedmepackages.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PanelVisibilityStateTest {
    @TempDir Path directory;

    private Path file() { return directory.resolve("config").resolve(PanelVisibilityState.FILE_NAME); }

    @Test void firstLaunchIsExpandedAndLoadingDoesNotCreateFiles() throws IOException {
        assertFalse(new PanelVisibilityState(file()).load());
        assertFalse(Files.exists(file().getParent()));
    }

    @Test void bothChoicesSurviveNewInstancesAndReplaceThePreviousChoice() throws IOException {
        var state = new PanelVisibilityState(file());
        state.save(true);
        assertTrue(new PanelVisibilityState(file()).load());
        state.save(false);
        assertFalse(new PanelVisibilityState(file()).load());
        state.save(true);
        assertTrue(new PanelVisibilityState(file()).load());
        try (var entries = Files.list(file().getParent())) {
            assertEquals(1, entries.count(), "Atomic save must not leave temporary files");
        }
    }

    @Test void invalidAndTruncatedFilesAreReportedWithoutRewritingThem() throws IOException {
        Files.createDirectories(file().getParent());
        for (String invalid : new String[]{"", "collaps", "true", "expanded\ncollapsed", "x".repeat(33)}) {
            Files.writeString(file(), invalid);
            assertThrows(IOException.class, () -> new PanelVisibilityState(file()).load());
            assertEquals(invalid, Files.readString(file()));
        }
    }

    @Test void windowsNewlinesAreAccepted() throws IOException {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), "collapsed\r\n");
        assertTrue(new PanelVisibilityState(file()).load());
    }

    @Test void failedReplaceKeepsExistingTargetAndCleansOnlyItsTemporaryFile() throws IOException {
        Files.createDirectories(file());
        var existing = file().resolve("keep.txt");
        Files.writeString(existing, "untouched");
        assertThrows(IOException.class, () -> new PanelVisibilityState(file()).save(true));
        assertEquals("untouched", Files.readString(existing));
        try (var entries = Files.list(file().getParent())) {
            assertEquals(1, entries.count());
        }
    }

    @Test void failedParentCreationDoesNotTouchAnUnrelatedFile() throws IOException {
        Files.writeString(file().getParent(), "not a directory");
        assertThrows(IOException.class, () -> new PanelVisibilityState(file()).save(true));
        assertEquals("not a directory", Files.readString(file().getParent()));
    }
}
