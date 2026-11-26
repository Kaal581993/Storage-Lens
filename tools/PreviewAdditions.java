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
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import static dev.kaal.storagelens.DockerModels.*;
import static dev.kaal.storagelens.Models.size;

/** Paints real components from read-only measurements; never invokes a cleanup action. */
public final class PreviewAdditions {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Usage: PreviewAdditions usage.json info.json NEW_OUTPUT_DIRECTORY");
        System.setProperty("java.awt.headless", "true");
        ObjectMapper json = new ObjectMapper();
        String engineId = json.readTree(Files.readString(Path.of(args[1]))).path("ID").asText();
        if (engineId.isBlank()) throw new IllegalArgumentException("Snapshot has no Engine identity");
        Snapshot docker = DockerService.parse(new DockerApiClient().activeEndpoint(), engineId, json.readTree(Files.readString(Path.of(args[0]))));
        Path directory = Path.of(args[2]); Files.createDirectories(directory);
        DockerService.export(directory.getParent().resolve("docker-storage-review-20261123.csv"), docker);
        writeGist(directory.getParent().resolve("docker-cleanup-review.txt"), docker, Files.getLastModifiedTime(Path.of(args[0])).toInstant());
        try (ApplicationContext context = ApplicationContext.builder().environments("desktop").start()) {
            StorageScanner scanner = context.getBean(StorageScanner.class);
            ScanPolicy policy = context.getBean(ScanPolicy.class);
            Path root = policy.home().resolve(".cache");
            var result = scanner.scan(root, new AtomicBoolean(), progress -> {});
            var disk = scanner.disk(root);
            var inventory = context.getBean(AppInventoryService.class).load();
            System.out.println("Read-only cache scan: " + result.files() + " files; " + result.cleanup().size() + " retained candidates");
            SwingUtilities.invokeAndWait(() -> {
                try {
                    Theme.install();
                    StoragePanel panel = new StoragePanel(scanner, context.getBean(AppInventoryService.class), context.getBean(CleanupService.class),
                        context.getBean(UninstallService.class), context.getBean(AuditLog.class), policy, context.getBean(DockerService.class));
                    panel.acceptScan(result, disk); panel.acceptInventory(inventory); panel.docker.setSnapshot(docker);
                    for (String page : List.of("Overview", "Cleanup", "Docker")) {
                        panel.showPage(page);
                        render(panel, 1440, 1000, directory.resolve(page.toLowerCase(Locale.ROOT) + ".png"));
                        render(panel, 1160, 860, directory.resolve(page.toLowerCase(Locale.ROOT) + "-narrow.png"));
                    }
                    for (Component component : panel.docker.getComponents()) if (component instanceof JTabbedPane tabs) tabs.setSelectedIndex(1);
                    render(panel, 1440, 1000, directory.resolve("docker-volumes.png"));
                    StorageWidgetPanel widget = new StorageWidgetPanel(new StorageWidgetPanel.Actions(() -> {}, () -> {}, () -> {}, () -> {}, () -> {}));
                    widget.setDisk(disk); render(widget, 480, 163, directory.resolve("widget.png"));
                } catch (Exception exception) { throw new RuntimeException(exception); }
            });
        }
    }
    private static void render(JComponent component, int width, int height, Path path) throws Exception {
        component.setSize(width, height);
        for (int pass = 0; pass < 4; pass++) layout(component);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics(); component.printAll(graphics); graphics.dispose();
        try (var output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            if (!ImageIO.write(image, "png", output)) throw new IllegalStateException("PNG encoder unavailable");
        }
        System.out.println("Preview: " + path.toAbsolutePath());
    }
    private static void layout(java.awt.Container container) {
        container.doLayout(); for (Component child : container.getComponents()) if (child instanceof java.awt.Container nested) layout(nested);
    }
    private static void writeGist(Path path, Snapshot docker, Instant captured) throws Exception {
        Set<String> tests = Set.of("kaal-balance-check", "kaal-infra-check");
        var candidates = docker.containers().stream().filter(DockerModels.Container::removable).toList();
        var testVolumes = docker.volumes().stream().filter(v -> tests.contains(v.project())).toList();
        var unreferenced = docker.volumes().stream().filter(v -> v.references() == 0).toList();
        StringBuilder text = new StringBuilder("Storage Lens — Docker storage assessment\n");
        text.append("Captured: ").append(captured).append("\nContext: ").append(docker.endpoint().context())
            .append("\nEngine: ").append(docker.engineId()).append("\n\n")
            .append(docker.containers().size()).append(" containers; ").append(docker.volumes().size()).append(" volumes; ")
            .append(docker.cache().size()).append(" build-cache records. No Docker resource was changed.\n\n")
            .append("Engine-reported image layers: ").append(size(docker.imageBytes())).append("; volume data: ").append(size(docker.volumeBytes())).append(".\n")
            .append("Images are retained for existing projects. Shared layers and cache accounting overlap; do not add these figures together.\n\n")
            .append("First cleanup to review: build cache\nUnused, unshared cache across all ages: ").append(size(docker.unsharedCacheBytes()))
            .append(". Listed cache including shared layers: ").append(size(docker.cacheBytes())).append(". These are estimates, not additive savings.\n")
            .append("The app defaults to dangling cache unused for seven days. Containers, images and volumes are retained.\n")
            .append("Later builds can take longer and need downloads. Actual reclaimed bytes are available only after the reviewed action.\n\n")
            .append("Optional isolated test-container removal: ").append(candidates.size()).append(" containers; ")
            .append(size(candidates.stream().mapToLong(DockerModels.Container::bytes).sum())).append(" reported writable data\n");
        for (var container : candidates) text.append("  ").append(container.name()).append(" — ").append(size(container.bytes())).append('\n');
        text.append("Only these two known test Compose projects are eligible. Removing containers discards container-local changes.\n")
            .append("Persistent volumes are retained; the test stacks can be recreated from Compose.\n\n")
            .append("Test volumes: ").append(size(testVolumes.stream().mapToLong(v -> Math.max(0, v.bytes())).sum())).append(" in total\n");
        for (var volume : testVolumes) text.append("  ").append(volume.name()).append(" — ").append(size(Math.max(0, volume.bytes())))
            .append("; ").append(volume.references()).append(" references; ").append(volume.reason()).append('\n');
        text.append("Deleting these volumes resets test messages, Redis state or app memory permanently.\n")
            .append("The small potential saving does not justify losing test data you still need. Referenced volumes cannot be removed in the app.\n\n")
            .append("Largest other volumes — preserve existing project data\n");
        for (var volume : docker.volumes().stream().filter(v -> !tests.contains(v.project())).limit(10).toList())
            text.append("  ").append(volume.name()).append(" — ").append(size(Math.max(0, volume.bytes())))
                .append("; ").append(volume.references()).append(" references\n");
        text.append("Keep oracle_data, mysql_data, mongo_data, kaal_kafka1/2/3_data and the anonymous PostgreSQL volume mounted by PG-vector-db.\n")
            .append("PG-vector-db is referenced by both the portfolio project and PayTM_KAALEdge's PostgreSQL setup.\n")
            .append("Standalone Kafka/ZooKeeper and unknown-owner resources are protected too.\n\n")
            .append("Unreferenced volumes: ").append(unreferenced.size()).append("; ")
            .append(size(unreferenced.stream().mapToLong(v -> Math.max(0, v.bytes())).sum())).append(" reported data.\n")
            .append("No references does not prove a volume is disposable; unknown-owner volumes remain protected.\n")
            .append("Do not delete Docker.raw. It contains the complete Docker Desktop VM data.\n")
            .append("This assessment covers only the selected desktop-linux daemon, not the separate default Docker context.\n\n")
            .append("Use the Docker page to refresh before acting. Every action requires review and a typed confirmation.\n")
            .append("No broad system/volume prune or image deletion is implemented.\n\n")
            .append("Sources: https://docs.docker.com/reference/cli/docker/builder/prune/\n")
            .append("https://docs.docker.com/reference/api/engine/version/v1.57/operations/ContainerDelete/\n")
            .append("https://docs.docker.com/desktop/troubleshoot-and-support/faqs/linuxfaqs/\n");
        Files.writeString(path, text, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        System.out.println("Assessment: " + path.toAbsolutePath());
    }
}
