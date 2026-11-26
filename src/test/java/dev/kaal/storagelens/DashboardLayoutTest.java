package dev.kaal.storagelens;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static dev.kaal.storagelens.Models.*;
import static org.junit.jupiter.api.Assertions.*;

class DashboardLayoutTest {
    @Test void cardsStayInsideViewportAfterScanUpdatesAndResizing() throws Exception {
        System.setProperty("java.awt.headless", "true");
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            OverviewPanel dashboard = new OverviewPanel(kind -> {}, path -> {});
            dashboard.setDriveHealth(new DriveHealthService.Health(new DriveHealthService.Device("nvme0", "CT1000E100SSD8"),
                Instant.now(), null, null, null, null, null, null, 2, "No such file or directory"), null);
            ScanResult result = new ScanResult(Path.of("/home/kaal"), Instant.now(), 1_464_345,
                380L << 30, 244L << 30, 93_044, false, List.of(), List.of(),
                List.of(new Folder(Path.of("/home/kaal/.docker"), 91L << 30, 250),
                    new Folder(Path.of("/home/kaal/.cache"), 48L << 30, 450_000)),
                Map.of(Kind.CACHE, 4L << 30), List.of(
                    "Showing the largest 30000 of 117774 cleanup candidates. Scan a smaller directory for the rest.",
                    "Disk sizes use allocated filesystem blocks and account for sparse files. Hard links, shared extents, compression and snapshots can still change space recovered.",
                    "93044 entries skipped: protected paths, links, inaccessible directories or virtual filesystems."));
            for (int width : new int[]{1683, 900, 1200, 1920, 900}) {
                dashboard.setSize(width, 780);
                layout(dashboard);
                dashboard.setData(result, new Disk(380L << 30, 77L << 30));
                // A live window validates repeatedly as wrapped notes acquire their real width.
                for (int pass = 0; pass < 4; pass++) layout(dashboard);
                JScrollPane scroll = (JScrollPane) dashboard.getComponent(0);
                JViewport viewport = scroll.getViewport();
                Container content = (Container) viewport.getView();
                int available = viewport.getExtentSize().width;
                assertEquals(available, content.getWidth(), "Dashboard must track viewport width at " + width);
                assertEquals(0, viewport.getViewPosition().x, "No horizontal drift at " + width);
                int bottom = 0;
                for (Component section : content.getComponents()) {
                    if (section instanceof Box.Filler) continue;
                    assertEquals(0, section.getX(), "Sections must share the left edge at " + width);
                    assertTrue(section.getX() + section.getWidth() <= available,
                        "Dashboard section clips past the right edge at " + width);
                    assertTrue(section.getHeight() > 0, "Dashboard sections must remain visible after resizing at " + width);
                    assertTrue(section.getY() >= bottom, "Dashboard sections must not overlap at " + width);
                    assertTrue(section.getHeight() < 600, "Wrapped metadata must not stretch a card at " + width);
                    bottom = section.getY() + section.getHeight();
                }
                assertEquals(125, content.getComponent(0).getHeight(), "Summary cards must remain visible after resizing");
                assertEquals(262, content.getComponent(1).getHeight(), "Capacity and category cards must remain visible");
            }
        });
    }
    private static void layout(Container parent) {
        parent.doLayout();
        for (Component child : parent.getComponents()) if (child instanceof Container nested) layout(nested);
    }
}
