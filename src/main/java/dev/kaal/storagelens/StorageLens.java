package dev.kaal.storagelens;

import io.micronaut.context.ApplicationContext;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static dev.kaal.storagelens.Models.*;

public final class StorageLens {
    public static void main(String[] arguments) {
        Set<String> flags = Set.of("--scan", "--root", "--report", "--preview", "--icon", "--inventory", "--docker-audit", "--widget", "--help");
        Map<String, String> options = new LinkedHashMap<>();
        try {
            for (int i = 0; i < arguments.length; i++) {
                String flag = arguments[i];
                if (!flags.contains(flag)) throw new IllegalArgumentException("Unknown option: " + flag);
                if (flag.equals("--help") || flag.equals("--inventory") || flag.equals("--widget")) options.put(flag, "true");
                else if (i + 1 < arguments.length && !arguments[i + 1].startsWith("--")) options.put(flag, arguments[++i]);
                else throw new IllegalArgumentException("Missing value for " + flag);
            }
            if (options.containsKey("--help")) {
                System.out.println("Storage Lens 1.0.0\nRun with no arguments for the desktop app.\n"
                    + "  --scan DIRECTORY       Read-only command-line scan\n  --report NEW.csv       Save a CSV after a scan or inventory\n"
                    + "  --inventory            List package sizes and review status\n  --preview DIRECTORY    Render the four desktop screens, using a real scan\n"
                    + "  --root DIRECTORY       Scan root for previews (default: home)\n  --icon NEW.png         Write the desktop icon\n  --widget               Open only the compact storage toolbar\n  --docker-audit NEW.csv  Export a read-only Docker storage assessment\n"
                    + "There is no command-line delete or uninstall option."); return;
            }
            boolean cli = options.containsKey("--scan") || options.containsKey("--inventory") || options.containsKey("--preview") || options.containsKey("--icon") || options.containsKey("--docker-audit");
            if (!cli && !options.isEmpty() && !(options.size() == 1 && options.containsKey("--widget"))) throw new IllegalArgumentException("Use --report with --scan, --inventory or --preview.");
            if (cli && options.containsKey("--widget")) throw new IllegalArgumentException("Use --widget on its own for the compact desktop toolbar.");
            if (cli) System.setProperty("java.awt.headless", "true");
            if (options.containsKey("--icon")) {
                writeImage(Path.of(options.get("--icon")), Theme.icon());
                if (options.size() == 1) return;
            }
            ApplicationContext context = ApplicationContext.builder().environments("desktop").start();
            if (cli) { try (context) { runCommand(context, options); } }
            else openDesktop(context, options.containsKey("--widget"));
        } catch (Exception exception) {
            System.err.println("Storage Lens: " + Models.safe(exception.getMessage()));
            exception.printStackTrace(System.err); System.exit(1);
        }
    }
    private static void openDesktop(ApplicationContext context, boolean widgetOnly) {
        SwingUtilities.invokeLater(() -> {
            try {
                Theme.install(); StoragePanel panel = panel(context);
                JFrame frame = new JFrame("Storage Lens — Laptop Storage Cleaner"); frame.setName("storage-lens");
                frame.setIconImage(Theme.icon()); frame.setContentPane(panel); frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                frame.setMinimumSize(new Dimension(1160, 720)); frame.setSize(1400, 960); frame.setLocationRelativeTo(null);
                DesktopWidget widget = new DesktopWidget(frame, panel, context.getBean(StorageScanner.class), context.getBean(ScanPolicy.class),
                    () -> { panel.stop(); frame.dispose(); context.close(); System.exit(0); });
                frame.addWindowListener(new WindowAdapter() {
                    public void windowClosing(WindowEvent e) { widget.mainWindowClosing(); }
                });
                if (widgetOnly) widget.showToolbar(); else { frame.setVisible(true); panel.start(); }
            } catch (Exception exception) {
                System.err.println("Desktop startup failed: " + exception); context.close(); System.exit(1);
            }
        });
    }
    private static StoragePanel panel(ApplicationContext context) {
        return new StoragePanel(context.getBean(StorageScanner.class), context.getBean(AppInventoryService.class),
            context.getBean(CleanupService.class), context.getBean(UninstallService.class), context.getBean(AuditLog.class), context.getBean(ScanPolicy.class), context.getBean(DockerService.class),
            context.getBean(IdeCacheService.class), context.getBean(ManagedCacheService.class), context.getBean(DriveHealthService.class));
    }
    private static void runCommand(ApplicationContext context, Map<String, String> options) throws Exception {
        if (options.containsKey("--docker-audit")) {
            var docker = context.getBean(DockerService.class).load(); Path destination = Path.of(options.get("--docker-audit"));
            DockerService.export(destination, docker);
            System.out.println("Docker context: " + docker.endpoint().context() + "; " + docker.containers().size() + " containers, " + docker.volumes().size() + " volumes");
            System.out.println("Unused unshared build cache: " + Models.size(docker.unsharedCacheBytes()) + ". No Docker objects were changed.");
            if (options.size() == 1) return;
        }
        StorageScanner scanner = context.getBean(StorageScanner.class);
        Path root = Path.of(options.getOrDefault("--scan", options.getOrDefault("--root", System.getProperty("user.home"))));
        ScanResult result = null;
        if (options.containsKey("--scan") || options.containsKey("--preview")) {
            AtomicBoolean cancel = new AtomicBoolean(); Runtime.getRuntime().addShutdownHook(new Thread(() -> cancel.set(true)));
            long[] last = {0};
            result = scanner.scan(root, cancel, p -> {
                long now = System.nanoTime();
                if (now - last[0] > 3_000_000_000L) { System.out.printf(Locale.ROOT, "Scanning: %,d files, %s allocated, %,d skipped%n", p.files(), size(p.bytes()), p.skipped()); last[0] = now; }
            });
            System.out.printf(Locale.ROOT, "%s: %,d files, %s allocated / %s logical size, %,d skipped%n", result.cancelled() ? "Partial scan" : "Scan complete", result.files(), size(result.allocated()), size(result.bytes()), result.skipped());
            System.out.println("Cleanup candidates to review: " + size(result.reviewBytes()) + " across " + result.cleanup().size() + " files");
            for (Folder folder : result.folders().stream().limit(12).toList()) System.out.printf("  %10s  %s%n", size(folder.bytes()), Models.safe(folder.path().toString()));
            for (String warning : result.warnings()) System.out.println("Note: " + warning);
        }
        AppInventory inventory = context.getBean(AppInventoryService.class).load();
        System.out.println("Installed packages/applications: " + inventory.apps().size());
        if (options.containsKey("--inventory")) for (InstalledApp app : inventory.apps().stream().limit(40).toList())
            System.out.printf("%10s  %-32s  %s%n", size(app.bytes()), app.name(), app.status());
        if (options.containsKey("--report")) {
            Path path = Path.of(options.get("--report")); ReportWriter.write(path, result, inventory); System.out.println("Report: " + path.toAbsolutePath());
        }
        if (options.containsKey("--preview")) {
            ScanResult measured = result; Disk disk = scanner.disk(root); Path directory = Path.of(options.get("--preview")); Files.createDirectories(directory);
            SwingUtilities.invokeAndWait(() -> {
                try {
                    Theme.install(); StoragePanel panel = panel(context); panel.acceptScan(measured, disk); panel.acceptInventory(inventory);
                    for (String name : java.util.List.of("Overview", "Cleanup", "Large files", "Installed apps")) {
                        panel.showPage(name); panel.setSize(1440, 1000); layout(panel);
                        BufferedImage screenshot = new BufferedImage(1440, 1000, BufferedImage.TYPE_INT_RGB);
                        Graphics2D g = screenshot.createGraphics(); panel.printAll(g); g.dispose();
                        Path destination = directory.resolve(name.toLowerCase(Locale.ROOT).replace(' ', '-') + ".png");
                        writeImage(destination, screenshot); System.out.println("Preview: " + destination.toAbsolutePath());
                    }
                } catch (Exception exception) { throw new RuntimeException(exception); }
            });
        }
    }
    private static void layout(Container container) {
        container.doLayout(); for (Component child : container.getComponents()) if (child instanceof Container nested) layout(nested);
    }
    private static void writeImage(Path path, BufferedImage image) throws Exception {
        try (var output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            if (!ImageIO.write(image, "png", output)) throw new IllegalStateException("PNG encoder unavailable");
        }
    }
}
