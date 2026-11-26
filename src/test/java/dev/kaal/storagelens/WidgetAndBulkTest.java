package dev.kaal.storagelens;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static dev.kaal.storagelens.Models.*;

class WidgetAndBulkTest {
    @Test void widgetUpdatesStorageAndOpensOnlyTheRequestedAction() throws Exception {
        System.setProperty("java.awt.headless", "true");
        SwingUtilities.invokeAndWait(() -> {
            Theme.install(); List<String> calls = new ArrayList<>();
            StorageWidgetPanel widget = new StorageWidgetPanel(new StorageWidgetPanel.Actions(
                () -> calls.add("open"), () -> calls.add("scan"), () -> calls.add("cleanup"), () -> calls.add("docker"), () -> calls.add("quit")));
            widget.setDisk(new Disk(1000, 200)); assertEquals("200 B free", widget.available.getText()); assertEquals(800, widget.usage.getValue());
            widget.dockerButton.doClick(); assertEquals(List.of("docker"), calls);
            widget.cleanupButton.doClick(); assertEquals(List.of("docker", "cleanup"), calls);
            widget.setDisk(new Disk(0, 0)); assertEquals("Storage unavailable", widget.available.getText());
            widget.setDisk(new Disk(1000, 2000)); assertEquals(0, widget.usage.getValue());
        });
    }
    @Test void bulkCacheReviewIncludesOnlyActionableCachesRegardlessOfFilter() throws Exception {
        System.setProperty("java.awt.headless", "true");
        SwingUtilities.invokeAndWait(() -> {
            Theme.install(); AtomicReference<List<Finding>> reviewed = new AtomicReference<>();
            FilesPanel panel = new FilesPanel(true, files -> fail("Unexpected trash"), reviewed::set, path -> {});
            Finding cache = f("cache", Kind.CACHE, true), protectedCache = f("read-only-cache", Kind.CACHE, false), personal = f("photo", Kind.LARGE, true), partial = f("download.part", Kind.PARTIAL, true);
            panel.setFiles(List.of(cache, protectedCache, personal, partial)); panel.search.setText("photo"); panel.selectVisible();
            panel.bulkCaches.doClick(); assertEquals(List.of(cache), reviewed.get());
            assertEquals(List.of(personal), panel.selected()); panel.setBusy(true); assertFalse(panel.bulkCaches.isEnabled());
        });
    }
    @Test void dockerRowsStartUnselectedAndCannotSelectProtectedResources() throws Exception {
        System.setProperty("java.awt.headless", "true");
        SwingUtilities.invokeAndWait(() -> {
            Theme.install(); DockerPanel panel = new DockerPanel(null);
            panel.containers.setRows(List.of(new DockerPanel.Row("protected", "PG-vector-db", 100, "", "exited", "Protected database", false),
                new DockerPanel.Row("test", "kaal-infra-check-redis-1", 20, "kaal-infra-check", "exited", "Test container", true)));
            assertTrue(panel.containers.selected().isEmpty()); assertFalse(panel.containers.remove.isEnabled());
            panel.containers.table.getModel().setValueAt(true, 0, 0); assertTrue(panel.containers.selected().isEmpty());
            panel.containers.table.getModel().setValueAt(true, 1, 0); assertEquals("test", panel.containers.selected().getFirst().key());
        });
    }
    private static Finding f(String name, Kind kind, boolean actionable) {
        return new Finding(Path.of("/fixture", name), 10, Instant.now(), "fixture", kind, actionable, "Review");
    }
}
