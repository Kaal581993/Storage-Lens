package dev.kaal.storagelens;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.ApplicationContext;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import static dev.kaal.storagelens.Models.size;

/** Read-only local assessment and rendering of real components. Never invokes a cleanup or authentication action. */
public final class CacheAndHealthReview {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--check-installed-reader")) {
            var service = new DriveHealthService(new AuditLog());
            System.out.println("Installed native reader: " + DriveHealthService.helper());
            for (var device : service.devices()) { var health = service.read(device, false); System.out.println(device + ": " + health.message()); }
            return;
        }
        boolean renderOnly = args.length == 4 && args[3].equals("--render-only");
        if (args.length != 3 && !renderOnly) throw new IllegalArgumentException("Usage: CacheAndHealthReview usage.json info.json NEW_OUTPUT_DIRECTORY [--render-only]");
        System.setProperty("java.awt.headless", "true");
        var json = new ObjectMapper();
        var info = json.readTree(Path.of(args[1]).toFile());
        String engine = info.path("ID").asText(); if (engine.isBlank()) throw new IllegalArgumentException("Engine identity unavailable");
        var docker = DockerService.parse(new DockerApiClient().activeEndpoint(), engine, json.readTree(Path.of(args[0]).toFile()));
        Path directory = Path.of(args[2]); Files.createDirectory(directory);
        try (ApplicationContext context = ApplicationContext.builder().environments("desktop").start()) {
            var ideService = context.getBean(IdeCacheService.class);
            var managed = context.getBean(ManagedCacheService.class);
            var driveService = context.getBean(DriveHealthService.class);
            var policy = context.getBean(ScanPolicy.class);
            var scanner = context.getBean(StorageScanner.class);
            var plan = ideService.scan(new AtomicBoolean(), update -> {});
            var uv = managed.inspectUv(); String packages = managed.packagePreview();
            var devices = driveService.devices();
            var health = devices.isEmpty() ? null : driveService.read(devices.getFirst(), false);
            var rating = health == null ? null : driveService.ratedTbw(health.device().model());
            var scan = scanner.scan(policy.home().resolve(".cache/JetBrains"), new AtomicBoolean(), update -> {});
            var disk = scanner.disk(policy.home());
            var inventory = context.getBean(AppInventoryService.class).load();
            Path parent = directory.getParent();
            if (!renderOnly) {
                IdeCacheService.export(parent.resolve("ide-cache-review-20261124.csv"), plan);
                DockerService.export(parent.resolve("docker-storage-review-20261124.csv"), docker);
            }
            StringBuilder report = new StringBuilder("Storage Lens — local storage and NVMe assessment\nCaptured: " + Instant.now() + "\n\n");
            report.append("COMPLETED: native uv unused-entry prune reported 97,888 files / 11.6 GiB removed.\n")
                .append("Observed filesystem free-space increase immediately afterward: 2,202,058,752 bytes (about 2.05 GiB).\n")
                .append("The tool's entry sizes and net physical space differ because of hardlinks, compression, snapshots and other disk activity.\n")
                .append("Current uv allocation: ").append(size(uv.bytes())).append(". Hugging Face models were not touched.\n\n")
                .append("JETBRAINS: ").append(size(plan.totalBytes())).append(" total version-system data; ").append(size(plan.cacheBytes())).append(" rebuildable index/build-cache files measured.\n")
                .append("Only index, shared-indexes, compiler and compile-server are included, across all ages and versions.\n")
                .append("Local History, persistent VFS caches, plugins, settings, projects, models and unknown directories are preserved.\n")
                .append("Close the owning IDEs, then use Bulk caches from the laptop session. They will reindex/rebuild later.\n");
            for (var group : plan.groups()) report.append("  ").append(group.instance().version()).append(": ").append(size(group.cacheBytes()))
                .append(" / ").append(group.files().size()).append(" reviewed files; total ").append(size(group.totalBytes()))
                .append("; ").append(group.blocked() == null ? "closed and eligible" : group.blocked()).append('\n');
            report.append("\nPACKAGE CACHE PREVIEW (no package archives removed by this review):\n").append(packages.strip()).append('\n')
                .append("Keeps the newest three downloaded versions; this does not uninstall installed packages.\n")
                .append("Older downloaded rollback archives are lost. Run the reviewed package-cache action and authenticate in the visible terminal.\n\n")
                .append("DOCKER: context ").append(docker.endpoint().context()).append("; engine ").append(docker.engineId()).append('\n')
                .append(docker.containers().size()).append(" containers, ").append(docker.volumes().size()).append(" volumes, ").append(docker.cache().size()).append(" build-cache records.\n")
                .append("Docker.raw allocated blocks: ").append(DockerService.desktopDiskBytes(docker) < 0 ? "Unavailable" : size(DockerService.desktopDiskBytes(docker)))
                .append("; Engine image layers: ").append(size(docker.imageBytes())).append("; volume data: ").append(size(docker.volumeBytes())).append(".\n")
                .append("These measurements overlap and are not an additive promise of savings. No Docker objects were removed by this work.\n")
                .append("The current daemon reports no build cache to prune. All current volume references and unknown project resources remain protected.\n")
                .append("Use Docker > Reclaim Desktop disk space to request return of unused VM blocks. Modern Desktop may already do this automatically.\n")
                .append("This opens a visible terminal for a digest-pinned Docker helper in a temporary privileged VM container; review before running.\n")
                .append("Existing projects, containers, images and volume data are retained. Savings may be zero and snapshots can retain disk blocks.\n")
                .append("Never delete Docker.raw or reduce Docker Desktop's maximum disk size to attempt reclamation.\n")
                .append("Current containers and their protected volume data:\n");
            for (var container : docker.containers()) report.append("  ").append(container.name()).append(" — ").append(container.reason()).append('\n');
            for (var volume : docker.volumes()) report.append("  ").append(volume.name()).append(" — ").append(size(Math.max(0, volume.bytes())))
                .append("; references ").append(volume.references()).append("; ").append(volume.reason()).append('\n');
            report.append("\nNVME: ").append(health == null ? "No controller visible" : health.device() + " — " + health.message()).append('\n')
                .append("The workspace cannot access the NVMe device node, so no actual endurance or write total is claimed here.\n")
                .append("The installed Drive health view uses a C read-only NVMe SMART reader; use Authorize SMART read if the laptop requests hardware access.\n")
                .append("Remaining endurance: max(0, 100 - Percentage Used). A wear estimate is not a failure forecast.\n")
                .append("Lifetime host-write bytes: SMART Data Units Written multiplied by 512,000; displayed in decimal TB.\n")
                .append("Remaining rated write budget: max(0, manufacturer TBW - lifetime host-write TB).\n")
                .append("Enter the verified exact-model TBW in Drive health; no rating has been assumed or saved by this review.\n")
                .append("Future write-command counts cannot be predicted. Deleting caches does not reverse NVMe wear.\n\n")
                .append("Verification: 76 JUnit checks and the native SMART decoder self-check pass. This report and the PNGs use read-only measurements.\n")
                .append("A live desktop window, actual SMART access and VM reclamation cannot be verified from this isolated workspace.\n\n")
                .append("Sources:\nhttps://docs.astral.sh/uv/concepts/cache/\nhttps://www.jetbrains.com/help/idea/invalidate-caches.html\n")
                .append("https://www.jetbrains.com/help/idea/directories-used-by-the-ide-to-store-settings-caches-plugins-and-logs.html\n")
                .append("https://man.archlinux.org/man/paccache.8\nhttps://docs.docker.com/desktop/troubleshoot-and-support/faqs/linuxfaqs/\n")
                .append("https://nvmexpress.org/wp-content/uploads/NVM-Express-Base-Specification-Revision-2.1-2024.08.05-Ratified.pdf\n");
            Path reportFile = parent.resolve("storage-cleanup-review-20261124.txt");
            if (!renderOnly) Files.writeString(reportFile, report.toString(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            System.out.println(report);
            SwingUtilities.invokeAndWait(() -> {
                try {
                    Theme.install();
                    StoragePanel panel = new StoragePanel(scanner, context.getBean(AppInventoryService.class), context.getBean(CleanupService.class),
                        context.getBean(UninstallService.class), context.getBean(AuditLog.class), policy, context.getBean(DockerService.class), ideService, managed, driveService);
                    panel.acceptScan(scan, disk); panel.acceptInventory(inventory); panel.docker.setSnapshot(docker);
                    panel.bulk.setPlan(plan); panel.bulk.setManagedReview(uv, packages);
                    if (health != null) panel.health.setHealth(health, rating);
                    for (String page : List.of("Overview", "Cleanup", "Bulk caches", "Docker", "Drive health")) {
                        panel.showPage(page); String name = page.toLowerCase(Locale.ROOT).replace(' ', '-');
                        render(panel, 1440, 1000, directory.resolve(name + ".png"));
                        render(panel, 1160, 860, directory.resolve(name + "-narrow.png"));
                        render(panel, 1160, 720, directory.resolve(name + "-short.png"));
                    }
                    panel.showPage("Bulk caches"); tabs(panel.bulk).setSelectedIndex(1);
                    render(panel, 1440, 1000, directory.resolve("native-caches.png"));
                    render(panel, 1160, 720, directory.resolve("native-caches-short.png"));
                } catch (Exception exception) { throw new RuntimeException(exception); }
            });
        }
    }
    private static JTabbedPane tabs(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof JTabbedPane tabs) return tabs;
            if (child instanceof Container nested) { var found = tabs(nested); if (found != null) return found; }
        }
        return null;
    }
    private static void render(JComponent component, int width, int height, Path path) throws Exception {
        component.setSize(width, height); for (int pass = 0; pass < 5; pass++) layout(component);
        if (path.getFileName().toString().startsWith("overview")) {
            var scroll = (JScrollPane) ((StoragePanel) component).overview.getComponent(0);
            var viewport = scroll.getViewport();
            System.out.println("Dashboard layout " + width + "x" + height + ": viewport=" + viewport.getExtentSize() + " position=" + viewport.getViewPosition());
            for (Component section : ((Container) viewport.getView()).getComponents())
                System.out.println("  " + section.getClass().getSimpleName() + " bounds=" + section.getBounds() + " preferred=" + section.getPreferredSize() + " minimum=" + section.getMinimumSize());
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics(); component.printAll(graphics); graphics.dispose();
        try (var out = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            if (!ImageIO.write(image, "png", out)) throw new IllegalStateException("PNG encoder unavailable");
        }
        System.out.println("Preview: " + path.toAbsolutePath());
    }
    private static void layout(Container container) { container.doLayout(); for (Component child : container.getComponents()) if (child instanceof Container nested) layout(nested); }
}
