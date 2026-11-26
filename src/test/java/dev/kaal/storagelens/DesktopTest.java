package dev.kaal.storagelens;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static dev.kaal.storagelens.Models.*;

class DesktopTest {
    @TempDir Path temp;
    @BeforeAll static void headless() { System.setProperty("java.awt.headless", "true"); }
    Finding f(String name, long bytes, Kind kind, boolean actionable) {
        return new Finding(temp.resolve(name), bytes, Instant.now(), "fixture", kind, actionable, kind.explanation);
    }
    @Test void selectionFollowsSortedAndFilteredRowsAndStartsEmpty() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            FilesPanel panel = new FilesPanel(true, rows -> fail("Unexpected trash"), rows -> fail("Unexpected deletion"), path -> {});
            Finding large = f("big.part", 900, Kind.PARTIAL, true), small = f("small.cache", 100, Kind.CACHE, true), protectedFile = f("read-only", 1_000, Kind.SYSTEM, false);
            panel.setFiles(List.of(large, small, protectedFile)); assertTrue(panel.selected().isEmpty()); assertFalse(panel.delete.isEnabled());
            panel.table.getRowSorter().toggleSortOrder(2);
            panel.search.setText("small"); assertEquals(1, panel.table.getRowCount()); panel.selectVisible(); assertEquals(List.of(small), panel.selected());
            panel.search.setText(""); panel.choose(Kind.PARTIAL); panel.selectVisible(); assertEquals(Set.of(large, small), new HashSet<>(panel.selected()));
            panel.category.setSelectedIndex(0); panel.selectVisible(); assertFalse(panel.selected().contains(protectedFile));
            panel.setBusy(true); assertFalse(panel.delete.isEnabled()); assertFalse(panel.table.getModel().isCellEditable(0, 0));
        });
    }
    @Test void appFiltersDistinguishUnknownUsageAndOrphans() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Theme.install(); AppsPanel panel = new AppsPanel(app -> fail("Unexpected uninstall"), () -> {});
            var ordinary = new InstalledApp("editor", "1", 800, "pacman", "Explicit install · usage unknown", "Editor", true);
            var orphan = new InstalledApp("old-lib", "1", 600, "pacman", "Orphan dependency · review", "Library", true);
            var required = new InstalledApp("systemd", "1", 100, "pacman", "Protected system component", "System", false);
            panel.setInventory(new AppInventory(List.of(ordinary, orphan, required), List.of("Usage unknown")));
            assertFalse(panel.uninstall.isEnabled()); panel.filter.setSelectedIndex(1); assertEquals(1, panel.table.getRowCount());
            assertEquals("old-lib", panel.table.getValueAt(0, 0)); panel.table.setRowSelectionInterval(0, 0); assertTrue(panel.uninstall.isEnabled());
            panel.filter.setSelectedIndex(3); panel.table.setRowSelectionInterval(0, 0); assertFalse(panel.uninstall.isEnabled());
        });
    }
    @Test void desktopPagesRenderFromScanResultsWithoutMutatingFiles() throws Exception {
        Path home = Files.createDirectory(temp.resolve("home")); Path keep = Files.writeString(home.resolve("keep.txt"), "keep this");
        ScanPolicy policy = new ScanPolicy(home); StorageScanner scanner = new StorageScanner(policy); AuditLog audit = new AuditLog(temp.resolve("activity.log"));
        var result = scanner.scan(home, new java.util.concurrent.atomic.AtomicBoolean(), p -> {});
        SwingUtilities.invokeAndWait(() -> {
            Theme.install(); StoragePanel panel = new StoragePanel(scanner, new AppInventoryService(), new CleanupService(policy, audit), new UninstallService(audit), audit, policy);
            panel.acceptScan(result, new Disk(1000, 400)); panel.acceptInventory(new AppInventory(List.of(), List.of()));
            assertEquals("400 B", panel.overview.free.getText()); assertTrue(panel.status.getText().contains("NOTHING REMOVED"));
            for (String page : List.of("Overview", "Cleanup", "Large files", "Installed apps", "Activity")) {
                panel.showPage(page); panel.setSize(1440, 1000); layout(panel);
                var image = new java.awt.image.BufferedImage(1440, 1000, java.awt.image.BufferedImage.TYPE_INT_RGB);
                var graphics = image.createGraphics(); panel.printAll(graphics); graphics.dispose();
                assertNotEquals(0, image.getRGB(25, 25));
            }
        });
        assertEquals("keep this", Files.readString(keep)); assertFalse(Files.exists(audit.path()));
    }
    private static void layout(java.awt.Container container) {
        container.doLayout(); for (var child : container.getComponents()) if (child instanceof java.awt.Container nested) layout(nested);
    }
}
