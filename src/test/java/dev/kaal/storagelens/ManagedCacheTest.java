package dev.kaal.storagelens;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ManagedCacheTest {
    @TempDir Path temp;
    Path home, root; ScanPolicy policy; ManagedCacheService service; final List<List<String>> calls = new ArrayList<>();
    @BeforeEach void setup() throws Exception {
        home = Files.createDirectory(temp.resolve("home")); root = Files.createDirectories(home.resolve(".cache/uv")); policy = new ScanPolicy(home);
        Files.writeString(root.resolve("CACHEDIR.TAG"), "Signature: 8a477f597d28d172789f06886806bc55\n");
        Files.createDirectories(home.resolve(".local/bin")); Files.copy(Path.of("/usr/bin/true"), home.resolve(".local/bin/uv")); home.resolve(".local/bin/uv").toFile().setExecutable(true);
        service = new ManagedCacheService(policy, new AuditLog(temp.resolve("audit/activity.log")), (command, timeout, environment) -> {
            calls.add(command); if (command.contains("cache")) assertEquals("5", environment.get("UV_LOCK_TIMEOUT")); return new CommandRunner.Result(0, "Native cache fixture completed");
        });
    }
    @Test void pruneDelegatesToOnlyNativeUvCacheMaintenanceWithLocksAndNoDownloads() throws Exception {
        var review = service.inspectUv(); assertNull(review.unavailable());
        service.cleanUv(review, ManagedCacheService.UvAction.PRUNE); assertEquals(1, calls.size());
        var command = calls.getFirst(); assertEquals(List.of("cache", "prune"), command.subList(1, 3));
        assertEquals(root.toString(), command.get(command.indexOf("--cache-dir") + 1)); assertTrue(command.containsAll(List.of("--offline", "--no-config", "--no-python-downloads")));
        assertFalse(command.contains("--force")); assertFalse(command.contains("run"));
    }
    @Test void fullClearUsesTheSeparateExplicitCacheCleanOperation() throws Exception {
        service.cleanUv(service.inspectUv(), ManagedCacheService.UvAction.CLEAN); assertEquals("clean", calls.getFirst().get(2));
    }
    @Test void unrecognizedCacheSignatureIsRefused() throws Exception {
        Files.delete(root.resolve("CACHEDIR.TAG")); var review = service.inspectUv(); assertNotNull(review.unavailable());
        assertThrows(IOException.class, () -> service.cleanUv(review, ManagedCacheService.UvAction.CLEAN)); assertTrue(calls.isEmpty());
    }
    @Test void replacementOrLinkedCacheDirectoryCannotBeCleaned() throws Exception {
        var review = service.inspectUv(); Files.move(root, home.resolve("original")); Files.createDirectory(root);
        assertThrows(IOException.class, () -> service.cleanUv(review, ManagedCacheService.UvAction.CLEAN));
        Files.delete(root); Files.createSymbolicLink(root, home.resolve("original")); assertNotNull(service.inspectUv().unavailable()); assertTrue(calls.isEmpty());
    }
    @Test void arbitraryExecutableCannotBeSubstitutedIntoAReview() throws Exception {
        var review = service.inspectUv(); var forged = new ManagedCacheService.UvReview(root, review.identity(), 0, Path.of("/usr/bin/true"), null);
        assertThrows(IOException.class, () -> service.cleanUv(forged, ManagedCacheService.UvAction.CLEAN)); assertTrue(calls.isEmpty());
    }
    @Test void auditFailurePreventsTheNativeCommand() throws Exception {
        Path bad = Files.writeString(temp.resolve("bad-parent"), "x");
        var failed = new ManagedCacheService(policy, new AuditLog(bad.resolve("audit")), (command, timeout, environment) -> { fail("Mutation without request log"); return null; });
        var review = failed.inspectUv(); assertThrows(IOException.class, () -> failed.cleanUv(review, ManagedCacheService.UvAction.PRUNE));
    }
    @Test void busyToolIsReportedWithoutForcingTheCacheLock() throws Exception {
        var failed = new ManagedCacheService(policy, new AuditLog(temp.resolve("audit/activity.log")), (command, timeout, environment) -> new CommandRunner.Result(1, "cache is busy"));
        var review = failed.inspectUv(); assertTrue(assertThrows(IOException.class, () -> failed.cleanUv(review, ManagedCacheService.UvAction.CLEAN)).getMessage().contains("busy"));
    }
    @Test void packagePreviewAndCleanupKeepThreeVersionsAndNeverUninstall() throws Exception {
        service.packagePreview(); assertEquals(List.of("/usr/bin/paccache", "--dryrun", "--keep", "3"), calls.getFirst());
        assertEquals(List.of("/usr/bin/sudo", "--", "/usr/bin/paccache", "--remove", "--keep", "3"), ManagedCacheService.packageCommand());
    }
}
