package dev.kaal.storagelens;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static dev.kaal.storagelens.Models.*;

class IdeCacheTest {
    @TempDir Path temp;
    Path home; ScanPolicy policy; AuditLog audit; CleanupService cleaner; IdeCacheService service;
    AtomicBoolean active;
    static final String OLD = ".cache/JetBrains/IdeaIC2026.1/", CURRENT = ".cache/JetBrains/IntelliJIdea2025.3/";
    @BeforeEach void setup() throws Exception {
        home = Files.createDirectory(temp.resolve("home")); policy = new ScanPolicy(home); audit = new AuditLog(temp.resolve("audit/activity.log")); active = new AtomicBoolean();
        IdeIdleCheck idle = root -> active.get() ? "IDE started; protected" : null;
        cleaner = new CleanupService(policy, audit, idle); service = new IdeCacheService(policy, cleaner, audit, idle);
    }
    Path file(String name) throws Exception { Path path = home.resolve(name); Files.createDirectories(path.getParent()); return Files.writeString(path, "keep or rebuild"); }
    IdeCacheService.Plan scan() throws Exception { return service.scan(new AtomicBoolean(), update -> {}); }
    List<Path> roots(IdeCacheService.Plan plan) { return plan.groups().stream().map(g -> g.instance().root()).toList(); }
    @Test void allVersionsIncludeFreshIndexesAndPreserveIdePersistence() throws Exception {
        Path a = file(OLD + "index/fresh.dat"), b = file(CURRENT + "compile-server/data/cache.dat");
        var keep = List.of(file(OLD + "LocalHistory/changes.storage"), file(OLD + "caches/records.dat"), file(CURRENT + "plugins/plugin.jar"),
            file(CURRENT + "projects/session.xml"), file(".config/JetBrains/IdeaIC2026.1/options/settings.xml"), file("Desktop/project/src/Main.java"), file(".cache/huggingface/hub/model.bin"));
        var plan = scan(); assertEquals(2, plan.groups().size()); assertEquals(2, plan.groups().stream().mapToInt(g -> g.files().size()).sum());
        assertEquals(2, service.apply(plan, roots(plan), new AtomicBoolean(), update -> {}).succeeded());
        assertFalse(Files.exists(a)); assertFalse(Files.exists(b)); for (Path path : keep) assertTrue(Files.exists(path), path.toString());
        assertTrue(Files.isDirectory(a.getParent()));
    }
    @Test void completeReviewDoesNotStopAtThirtyThousandFiles() throws Exception {
        Path directory = Files.createDirectories(home.resolve(OLD + "index"));
        for (int i = 0; i < 30_025; i++) Files.createFile(directory.resolve(i + ".dat"));
        var plan = scan(); assertEquals(30_025, plan.groups().getFirst().files().size());
        Path csv = temp.resolve("complete.csv"); IdeCacheService.export(csv, plan);
        try (var lines = Files.lines(csv)) { assertEquals(30_026, lines.count()); }
        assertThrows(FileAlreadyExistsException.class, () -> IdeCacheService.export(csv, plan));
    }
    @Test void activeIdeIsUnselectableAndCannotBeCleaned() throws Exception {
        Path path = file(CURRENT + "index/a"); active.set(true); var plan = scan(); assertFalse(plan.groups().getFirst().eligible());
        assertThrows(IOException.class, () -> service.apply(plan, roots(plan), new AtomicBoolean(), update -> {})); assertTrue(Files.exists(path));
    }
    @Test void ideStartedAfterReviewIsRefusedBeforeDeletion() throws Exception {
        Path path = file(OLD + "index/a"); var plan = scan(); active.set(true);
        assertThrows(IOException.class, () -> service.apply(plan, roots(plan), new AtomicBoolean(), update -> {})); assertTrue(Files.exists(path));
    }
    @Test void changedFileRefusesTheWholeSelectionBeforeAnyDeletion() throws Exception {
        Path keep = file(OLD + "index/a"), changed = file(CURRENT + "index/b"); var plan = scan(); Files.writeString(changed, "changed after review");
        assertThrows(IOException.class, () -> service.apply(plan, roots(plan), new AtomicBoolean(), update -> {})); assertTrue(Files.exists(keep)); assertTrue(Files.exists(changed));
    }
    @Test void newUnreviewedFileInvalidatesTheDirectorySnapshot() throws Exception {
        Path keep = file(OLD + "index/a"); var plan = scan(); Path created = file(OLD + "index/new");
        Files.setLastModifiedTime(created.getParent(), FileTime.from(Instant.now().plusSeconds(5)));
        assertThrows(IOException.class, () -> service.apply(plan, roots(plan), new AtomicBoolean(), update -> {})); assertTrue(Files.exists(keep)); assertTrue(Files.exists(created));
    }
    @Test void linkedAndUnknownDirectoriesAreNeverEligible() throws Exception {
        Path keep = file("Desktop/important.dat"); Path index = Files.createDirectories(home.resolve(OLD + "index"));
        Files.createSymbolicLink(index.resolve("linked"), keep); file(".cache/JetBrains/unknown-owner/index/file");
        var plan = scan(); assertEquals(1, plan.groups().size()); assertFalse(plan.groups().getFirst().eligible()); assertTrue(Files.exists(keep));
    }
    @Test void hardLinkedCachesRemainProtected() throws Exception {
        Path path = file(OLD + "index/a"); Files.createLink(home.resolve("retained-link"), path);
        assertFalse(scan().groups().getFirst().eligible()); assertTrue(Files.exists(path));
    }
    @Test void cancellationStopsAtABatchBoundary() throws Exception {
        for (int i = 0; i < 257; i++) file(OLD + "index/" + i);
        var plan = scan(); AtomicBoolean cancel = new AtomicBoolean();
        var result = service.apply(plan, roots(plan), cancel, update -> { if (update.contains("cache files removed")) cancel.set(true); });
        assertEquals(128, result.succeeded()); assertEquals(129, plan.groups().getFirst().files().stream().filter(f -> Files.exists(f.path())).count());
    }
    @Test void processStartedBetweenBatchesStopsTheRemainingFiles() throws Exception {
        for (int i = 0; i < 257; i++) file(OLD + "index/" + i);
        var plan = scan(); var result = service.apply(plan, roots(plan), new AtomicBoolean(), update -> { if (update.contains("cache files removed")) active.set(true); });
        assertEquals(128, result.succeeded()); assertEquals(129, plan.groups().getFirst().files().stream().filter(f -> Files.exists(f.path())).count());
    }
    @Test void auditFailurePreventsDeletion() throws Exception {
        Path path = file(OLD + "index/a"); var plan = scan(); Path badParent = Files.writeString(temp.resolve("not-a-directory"), "x");
        var bad = new IdeCacheService(policy, cleaner, new AuditLog(badParent.resolve("audit")), root -> null);
        assertThrows(IOException.class, () -> bad.apply(plan, roots(plan), new AtomicBoolean(), update -> {})); assertTrue(Files.exists(path));
    }
    @Test void forgedPlanCannotIncludeAnOrdinaryPersonalFile() throws Exception {
        file(OLD + "index/a"); var plan = scan(); var original = plan.groups().getFirst(); Path keep = file("Downloads/keep.txt");
        var attrs = Files.readAttributes(keep, java.nio.file.attribute.BasicFileAttributes.class);
        var finding = new Finding(keep, attrs.size(), attrs.lastModifiedTime().toInstant(), attrs.fileKey().toString(), Kind.CACHE, true, "forged");
        var group = new IdeCacheService.Group(original.instance(), List.of(finding), List.of(), 1, null);
        var forged = new IdeCacheService.Plan(Instant.now(), List.of(group), false);
        assertThrows(IOException.class, () -> service.apply(forged, roots(forged), new AtomicBoolean(), update -> {})); assertTrue(Files.exists(keep));
    }
    @Test void genericCleanerCannotDeleteHuggingFaceUvOrPersistentIdeFiles() throws Exception {
        for (String name : List.of(".cache/huggingface/hub/model.bin", ".cache/uv/archive-v0/data", OLD + "LocalHistory/history", OLD + "caches/records.dat", ".local/share/JetBrains/IdeaIC2026.1/plugin.jar")) {
            Path path = file(name); var attrs = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
            var finding = new Finding(path, attrs.size(), attrs.lastModifiedTime().toInstant(), attrs.fileKey().toString(), Kind.CACHE, true, "forged");
            assertEquals(1, cleaner.apply(List.of(finding), CleanupService.Mode.DELETE, update -> {}).failed()); assertTrue(Files.exists(path));
        }
    }
    @Test void processMatchingHandlesVersionsCustomPathsAndUnknownVisibility() {
        Path root = home.resolve(OLD); var matching = IdeActivityGuard.identify(42, "/opt/jbr/bin/java", new String[]{"-Didea.paths.selector=IdeaIC2026.1", "com.intellij.idea.Main"});
        assertNotNull(IdeActivityGuard.reason(new IdeActivityGuard.View(true, List.of(matching)), root));
        assertNull(IdeActivityGuard.reason(new IdeActivityGuard.View(true, List.of(matching)), home.resolve(CURRENT)));
        assertNotNull(IdeActivityGuard.reason(new IdeActivityGuard.View(false, List.of()), root));
        var custom = IdeActivityGuard.identify(43, "/opt/jbr/bin/java", new String[]{"-Didea.system.path=" + root, "com.intellij.idea.Main"});
        assertNotNull(IdeActivityGuard.reason(new IdeActivityGuard.View(true, List.of(custom)), root));
        var builder = IdeActivityGuard.identify(44, "/usr/bin/java", new String[]{"org.jetbrains.jps.cmdline.Launcher"});
        assertNotNull(IdeActivityGuard.reason(new IdeActivityGuard.View(true, List.of(builder)), root));
        assertNull(IdeActivityGuard.identify(45, "/usr/bin/java", new String[]{"-jar", "storage-lens.jar"}));
    }
    @Test void bulkReviewStartsEmptyAndProtectsAnActiveVersion() throws Exception {
        file(OLD + "index/a"); file(CURRENT + "index/b"); var plan = scan(); var first = plan.groups().getFirst(); var second = plan.groups().getLast();
        var blocked = new IdeCacheService.Group(second.instance(), second.files(), second.directories(), second.totalBytes(), "Running IDE");
        SwingUtilities.invokeAndWait(() -> {
            Theme.install(); BulkCachePanel panel = new BulkCachePanel(null, null, () -> {}, value -> {}, () -> {});
            panel.setPlan(new IdeCacheService.Plan(plan.captured(), List.of(first, blocked), false));
            assertEquals(false, panel.table.getValueAt(0, 0)); assertFalse(panel.table.isCellEditable(1, 0));
            panel.table.getModel().setValueAt(true, 1, 0); assertEquals(false, panel.table.getValueAt(1, 0));
            panel.table.getModel().setValueAt(true, 0, 0); assertEquals(true, panel.table.getValueAt(0, 0));
            panel.setExternalBusy(true); assertFalse(panel.table.isCellEditable(0, 0));
        });
    }
}
