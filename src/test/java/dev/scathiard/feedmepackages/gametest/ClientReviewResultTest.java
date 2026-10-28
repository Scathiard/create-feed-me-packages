package dev.scathiard.feedmepackages.gametest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Properties;
import java.util.ArrayList;
import java.util.concurrent.CompletionException;
import static org.junit.jupiter.api.Assertions.*;

class ClientReviewResultTest {
    @TempDir Path folder;
    @Test void firstAsyncAssertionCannotBeReplacedByLaterSetup() {
        var ran = new ArrayList<String>();
        var first = ClientReviewResult.enqueue(null, () -> { throw new AssertionError("first"); }, Runnable::run);
        var second = ClientReviewResult.enqueue(first, () -> ran.add("setup"), Runnable::run);
        assertThrows(CompletionException.class, second::join);
        assertTrue(ran.isEmpty());
    }
    @Test void cleanupStillStopsAfterScreenshotAndDisconnectThrow() throws Exception {
        var result = new ClientReviewResult(folder.resolve("result"), "new", "materials", "hash");
        var first = new AssertionError("original"); var ran = new ArrayList<String>();
        ClientReviewResult.cleanup(first, () -> result.fail(first),
                () -> { throw new IllegalStateException("screenshot"); },
                () -> { throw new IllegalStateException("disconnect"); }, () -> ran.add("stop"));
        assertEquals(2, first.getSuppressed().length); assertEquals(java.util.List.of("stop"), ran);
        assertThrows(IllegalStateException.class, result::succeed);
        var values = new Properties(); try(var in=Files.newInputStream(folder.resolve("result"))) { values.load(in); }
        assertEquals("FAILED", values.getProperty("status")); assertTrue(values.getProperty("failure").contains("original"));
    }
    @Test void existingEvidenceIsNeverOverwrittenByAnotherRun() throws Exception {
        var path=folder.resolve("result"); new ClientReviewResult(path,"one","maid","hash").succeed();
        byte[] before=Files.readAllBytes(path);
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> new ClientReviewResult(path,"two","maid","hash"));
        assertArrayEquals(before, Files.readAllBytes(path));
    }
    @Test void partialRunDoesNotClaimPassAndSuccessfulRunCarriesIdentity() throws Exception {
        var path=folder.resolve("result");var result=new ClientReviewResult(path,"id","mode","sha");
        var values=new Properties();try(var in=Files.newInputStream(path)){values.load(in);}
        assertEquals("RUNNING",values.getProperty("status"));result.succeed();
        try(var in=Files.newInputStream(path)){values.load(in);}
        assertEquals("PASSED",values.getProperty("status"));assertEquals("id",values.getProperty("run"));
        assertEquals("mode",values.getProperty("mode"));assertEquals("sha",values.getProperty("artifact"));
    }
}
