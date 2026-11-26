package dev.kaal.storagelens;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static dev.kaal.storagelens.Models.*;
import static dev.kaal.storagelens.CleanupService.Mode.*;

class SafetyTest {
    @TempDir Path temporary;
    Path home;
    ScanPolicy policy;
    StorageScanner scanner;
    AuditLog audit;
    CleanupService cleaner;
    final Instant old = Instant.now().minus(Duration.ofDays(120));
    @BeforeEach void setup() throws Exception {
        home = Files.createDirectory(temporary.resolve("home")); policy = new ScanPolicy(home);
        scanner = new StorageScanner(policy); audit = new AuditLog(temporary.resolve("logs/activity.log")); cleaner = new CleanupService(policy, audit);
    }
    Path file(String path, String content, Instant modified) throws Exception {
        Path p = home.resolve(path); Files.createDirectories(p.getParent()); Files.writeString(p, content);
        Files.setLastModifiedTime(p, FileTime.from(modified)); return p;
    }
    Finding finding(Path file) throws Exception {
        var attr = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        Kind kind = policy.classify(file, attr.lastModifiedTime().toInstant(), Instant.now());
        return new Finding(file, attr.size(), attr.lastModifiedTime().toInstant(), Objects.toString(attr.fileKey(), ""), kind, policy.canAct(file), kind.explanation);
    }
    ScanResult scan() throws Exception { return scanner.scan(home, new AtomicBoolean(), p -> {}); }
    @Test void knownOldCachesAreSuggestedAndModelsAreKept() throws Exception {
        Path cache = file(".cache/thumbnails/old.png", "cache", old);
        Path model = file(".cache/huggingface/weights.bin", "precious offline model", old);
        var result = scan();
        assertTrue(result.cleanup().stream().anyMatch(f -> f.path().equals(cache) && f.kind() == Kind.CACHE));
        assertFalse(result.cleanup().stream().anyMatch(f -> f.path().equals(model)));
        assertTrue(Files.exists(model)); assertEquals(27, result.bytes());
    }
    @Test void recentPartialAndCacheAreNotSuggested() throws Exception {
        file("Downloads/active.crdownload", "busy", Instant.now()); file(".cache/thumbnails/new.png", "busy", Instant.now());
        assertTrue(scan().cleanup().isEmpty());
    }
    @Test void partialAndOldDownloadGetDifferentReasons() throws Exception {
        file("Downloads/failed.zmdownload", "old", old); file("Downloads/notes.pdf", "keep?", old);
        Set<Kind> kinds = new HashSet<>(scan().cleanup().stream().map(Finding::kind).toList());
        assertEquals(Set.of(Kind.PARTIAL, Kind.OLD_DOWNLOAD), kinds);
    }
    @Test void secretsAndConfigAreProtected() throws Exception {
        Path key = file(".ssh/id_ed25519", "private", old); Path env = file("project/.env", "private", old);
        file(".config/app/state", "private", old);
        assertFalse(policy.canAct(key)); assertFalse(policy.canAct(env));
        assertEquals(7, scan().bytes());
    }
    @Test void scanDoesNotFollowSymlinks() throws Exception {
        Path outside = Files.writeString(temporary.resolve("outside"), "external");
        Files.createSymbolicLink(home.resolve("linked.part"), outside);
        var result = scan(); assertEquals(0, result.files()); assertEquals(1, result.skipped()); assertTrue(result.cleanup().isEmpty());
        assertThrows(IOException.class, () -> scanner.scan(home.resolve("linked.part"), new AtomicBoolean(), p -> {}));
    }
    @Test void cancellationReturnsPartialResult() throws Exception {
        file("Downloads/old.part", "data", old);
        var result = scanner.scan(home, new AtomicBoolean(true), p -> {});
        assertTrue(result.cancelled()); assertEquals(0, result.files());
    }
    @Test void changedFilesAreRefused() throws Exception {
        Path p = file("Downloads/old.part", "original", old); Finding f = finding(p); Files.writeString(p, "new content");
        var result = cleaner.apply(List.of(f), DELETE, s -> {}); assertEquals(0, result.succeeded()); assertEquals(1, result.failed());
        assertEquals("new content", Files.readString(p)); assertTrue(audit.recent().contains("SKIPPED"));
    }
    @Test void sameSizeReplacedInodeIsRefused() throws Exception {
        Path p = file("Downloads/old.part", "data", old); Finding f = finding(p);
        Path replacement = file("Downloads/replacement", "data", old);
        Files.move(replacement, p, StandardCopyOption.REPLACE_EXISTING);
        assertEquals(1, cleaner.apply(List.of(f), DELETE, s -> {}).failed()); assertTrue(Files.exists(p));
    }
    @Test void parentSymlinkReplacementIsRefused() throws Exception {
        Path p = file("Downloads/sub/old.part", "data", old); Finding f = finding(p);
        Files.move(p.getParent(), home.resolve("saved")); Files.createSymbolicLink(p.getParent(), home.resolve("saved"));
        assertEquals(1, cleaner.apply(List.of(f), DELETE, s -> {}).failed()); assertTrue(Files.exists(home.resolve("saved/old.part")));
    }
    @Test void replacingLeafWithSymlinkIsRefused() throws Exception {
        Path p = file("Downloads/old.part", "data", old); Finding f = finding(p); Files.delete(p);
        Path other = file("Documents/keep", "precious", old); Files.createSymbolicLink(p, other);
        assertEquals(1, cleaner.apply(List.of(f), DELETE, s -> {}).failed()); assertEquals("precious", Files.readString(other));
    }
    @Test void protectedFindingCannotBeForgedAsActionable() throws Exception {
        Path p = file(".ssh/secret", "data", old); Finding actual = finding(p);
        Finding forged = new Finding(p, actual.bytes(), actual.modified(), actual.fileKey(), Kind.CACHE, true, "");
        assertEquals(1, cleaner.apply(List.of(forged), DELETE, s -> {}).failed()); assertTrue(Files.exists(p));
    }
    @Test void hardLinkedFilesAreKept() throws Exception {
        Path p = file("Downloads/old.part", "data", old); Files.createLink(home.resolve("keep"), p);
        assertEquals(1, cleaner.apply(List.of(finding(p)), DELETE, s -> {}).failed()); assertTrue(Files.exists(p));
    }
    @Test void managedContainerStorageIsVisibleButReadOnly() throws Exception {
        Path p = file(".docker/desktop/vms/0/Docker.raw", "container data", old);
        assertFalse(policy.canAct(p)); assertTrue(policy.note(p, Kind.LARGE).contains("owning app"));
        assertEquals(14, scan().bytes()); assertEquals(1, cleaner.apply(List.of(finding(p)), DELETE, s -> {}).failed());
        assertTrue(Files.exists(p));
    }
    @Test void deletionOnlyTouchesReviewedFilesAndDeduplicates() throws Exception {
        Path remove = file("Downloads/old.part", "data", old), keep = file("Downloads/keep.part", "keep", old);
        Finding f = finding(remove); var result = cleaner.apply(List.of(f, f), DELETE, s -> {});
        assertEquals(1, result.succeeded()); assertEquals(4, result.bytes()); assertFalse(Files.exists(remove)); assertTrue(Files.exists(keep));
        assertTrue(audit.recent().contains("SUCCESS DELETE"));
    }
    @Test void trashCanBeRestoredWithStandardMetadata() throws Exception {
        Path original = file("Downloads/a file #é.part", "restore this", old);
        var result = cleaner.apply(List.of(finding(original)), TRASH, s -> {}); assertEquals(1, result.succeeded(), result.messages().toString());
        assertFalse(Files.exists(original));
        Path moved, info;
        try (var files = Files.list(policy.trash().resolve("files")); var metadata = Files.list(policy.trash().resolve("info"))) {
            moved = files.findFirst().orElseThrow(); info = metadata.findFirst().orElseThrow();
        }
        String text = Files.readString(info); assertTrue(text.contains("[Trash Info]")); assertTrue(text.contains("DeletionDate="));
        String encoded = text.lines().filter(s -> s.startsWith("Path=")).findFirst().orElseThrow().substring(5);
        assertEquals(original.toString(), java.net.URLDecoder.decode(encoded, java.nio.charset.StandardCharsets.UTF_8));
        Files.move(moved, original); assertEquals("restore this", Files.readString(original));
    }
    @Test void alreadyTrashedFileCannotBeTrashedAgain() throws Exception {
        Path p = file(".local/share/Trash/files/test", "data", old);
        assertEquals(1, cleaner.apply(List.of(finding(p)), TRASH, s -> {}).failed()); assertTrue(Files.exists(p));
    }
    @Test void failedAuditPreventsCleanup() throws Exception {
        Path p = file("Downloads/old.part", "data", old);
        Path badLog = temporary.resolve("not-a-directory"); Files.writeString(badLog, "file");
        CleanupService service = new CleanupService(policy, new AuditLog(badLog.resolve("activity.log")));
        assertEquals(1, service.apply(List.of(finding(p)), DELETE, s -> {}).failed()); assertTrue(Files.exists(p));
    }
    @Test void exportPreservesExistingFilesAndNeutralizesFormulas() throws Exception {
        Path output = temporary.resolve("report.csv"); ReportWriter.write(output, scan(), null);
        assertTrue(Files.readString(output).startsWith("record,")); assertThrows(FileAlreadyExistsException.class, () -> ReportWriter.write(output, scan(), null));
        assertEquals("\"'=SUM(1,2)\"", ReportWriter.cell("=SUM(1,2)")); assertEquals("\"a\"\"b\"", ReportWriter.cell("a\"b"));
    }
    @Test void micronautResolvesAllServices() {
        try (ApplicationContext context = ApplicationContext.builder().environments("test").start()) {
            assertNotNull(context.getBean(StorageScanner.class)); assertNotNull(context.getBean(CleanupService.class));
            assertNotNull(context.getBean(AppInventoryService.class)); assertNotNull(context.getBean(UninstallService.class));
        }
    }
    @Test void pacmanMetadataDistinguishesOrphanRequiredAndEssential() {
        String template = "%NAME%\n{name}\n\n%VERSION%\n1.0-1\n\n%SIZE%\n1024\n\n%REASON%\n1\n\n%DESC%\nExample\n";
        var orphan = AppInventoryService.parsePacman(template.replace("{name}", "example"), Set.of("example"));
        assertTrue(orphan.removable()); assertTrue(orphan.status().startsWith("Orphan")); assertEquals(1024, orphan.bytes());
        assertFalse(AppInventoryService.parsePacman(template.replace("{name}", "example"), Set.of()).removable());
        assertFalse(AppInventoryService.parsePacman(template.replace("{name}", "linux-zen"), Set.of("linux-zen")).removable());
    }
    @Test void uninstallerNeverUsesShellOrSkipsConfirmation() {
        UninstallService service = new UninstallService(audit);
        var app = new InstalledApp("example", "1.0", 42, "pacman", "Explicit", "", true);
        assertEquals(List.of("/usr/bin/sudo", "/usr/bin/pacman", "-Rns", "--", "example"), service.command(app));
        assertThrows(IllegalArgumentException.class, () -> service.command(new InstalledApp("x;touch /tmp/bad", "", 0, "pacman", "", "", true)));
        assertThrows(IllegalArgumentException.class, () -> service.command(new InstalledApp("systemd", "", 0, "pacman", "", "", true)));
        assertEquals("--user", service.command(new InstalledApp("org.example.App", "", 0, "Flatpak user", "", "", true)).get(2));
    }
    @Test void packageSizesUseCorrectDecimalAndBinaryUnits() {
        assertEquals(1_500_000_000L, AppInventoryService.parseSize("1.5 GB"));
        assertEquals(1_610_612_736L, AppInventoryService.parseSize("1.5 GiB"));
        assertEquals(0, AppInventoryService.parseSize("unknown"));
    }
    @Test void missingPacmanReasonMeansExplicitInstall() {
        String metadata = "%NAME%\nzoom\n\n%VERSION%\n7.2.5-1\n\n%SIZE%\n999001294\n";
        InstalledApp app = AppInventoryService.parsePacman(metadata, Set.of());
        assertTrue(app.removable()); assertTrue(app.status().startsWith("Explicit install"));
    }
    @Test void sparseDiskImagesUseAllocatedBlocksIncludingUnicodePaths() throws Exception {
        assertTrue(NativeFiles.available(), "Build with ./build.sh so the C size reader is included");
        Path sparse = home.resolve("disk-é-🎯.raw");
        try (var output = new java.io.RandomAccessFile(sparse.toFile(), "rw")) {
            output.setLength(1024L * 1024 * 1024); output.seek(output.length() - 1); output.write(1);
        }
        long diskBytes = NativeFiles.allocated(sparse, Files.size(sparse));
        assertTrue(diskBytes < Files.size(sparse) / 100, "Sparse files must not be counted as fully allocated");
        var system = CommandRunner.run(List.of("/usr/bin/stat", "--printf=%b", sparse.toString()), Duration.ofSeconds(5));
        assertEquals(Long.parseLong(system.output()) * 512, diskBytes);
        var result = scan(); assertEquals(Files.size(sparse), result.bytes()); assertEquals(diskBytes, result.allocated());
        assertEquals(diskBytes, result.large().getFirst().allocated());
    }
}
