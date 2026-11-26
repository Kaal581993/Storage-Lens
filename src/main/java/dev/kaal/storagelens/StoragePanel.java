package dev.kaal.storagelens;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static dev.kaal.storagelens.Models.*;
import static dev.kaal.storagelens.Theme.*;

final class StoragePanel extends JPanel {
    private final StorageScanner scanner;
    private final AppInventoryService inventoryService;
    private final CleanupService cleanupService;
    private final UninstallService uninstallService;
    private final AuditLog audit;
    private final ScanPolicy policy;
    private final CardLayout pages = new CardLayout();
    private final JPanel pageBody = transparent(pages);
    final OverviewPanel overview;
    final FilesPanel cleanup, large;
    final AppsPanel apps;
    final DockerPanel docker;
    final BulkCachePanel bulk;
    final DriveHealthPanel health;
    private final Map<String, JButton> navigation = new LinkedHashMap<>();
    private final JLabel title = label("Make room for what matters.", 29, Font.BOLD, INK);
    private final JLabel subtitle = label("A clearer view of your laptop. Every cleanup starts with a review.", 13, Font.PLAIN, MUTED);
    final JLabel status = micro("READY · NOTHING REMOVED");
    private final JLabel currentPath = label("Home folder", 12, Font.PLAIN, MUTED);
    private final JProgressBar progress = new JProgressBar();
    private final JButton homeButton, fullButton, folderButton, cancelButton, exportButton;
    private final JTextArea activity = new JTextArea();
    private final AtomicBoolean cancel = new AtomicBoolean();
    private ScanResult result;
    private AppInventory inventory;
    private Disk disk;
    private boolean busy, loadingApps;
    private Path root;
    private Runnable widgetAction = () -> {};

    StoragePanel(StorageScanner scanner, AppInventoryService inventoryService, CleanupService cleanupService,
                 UninstallService uninstallService, AuditLog audit, ScanPolicy policy) {
        this(scanner, inventoryService, cleanupService, uninstallService, audit, policy, new DockerService(new DockerApiClient(), audit));
    }
    StoragePanel(StorageScanner scanner, AppInventoryService inventoryService, CleanupService cleanupService,
                 UninstallService uninstallService, AuditLog audit, ScanPolicy policy, DockerService dockerService) {
        this(scanner, inventoryService, cleanupService, uninstallService, audit, policy, dockerService,
            new IdeCacheService(policy, cleanupService, audit, new IdeActivityGuard()), new ManagedCacheService(policy, audit));
    }
    StoragePanel(StorageScanner scanner, AppInventoryService inventoryService, CleanupService cleanupService,
                 UninstallService uninstallService, AuditLog audit, ScanPolicy policy, DockerService dockerService,
                 IdeCacheService ideService, ManagedCacheService managedService) {
        this(scanner, inventoryService, cleanupService, uninstallService, audit, policy, dockerService, ideService, managedService, new DriveHealthService(audit));
    }
    StoragePanel(StorageScanner scanner, AppInventoryService inventoryService, CleanupService cleanupService,
                 UninstallService uninstallService, AuditLog audit, ScanPolicy policy, DockerService dockerService,
                 IdeCacheService ideService, ManagedCacheService managedService, DriveHealthService healthService) {
        super(new BorderLayout()); this.scanner = scanner; this.inventoryService = inventoryService;
        this.cleanupService = cleanupService; this.uninstallService = uninstallService; this.audit = audit; this.policy = policy;
        root = policy.home(); setBackground(BG);
        cleanup = new FilesPanel(true, f -> reviewCleanup(f, CleanupService.Mode.TRASH), f -> reviewCleanup(f, CleanupService.Mode.DELETE), this::reveal);
        large = new FilesPanel(false, f -> reviewCleanup(f, CleanupService.Mode.TRASH), f -> reviewCleanup(f, CleanupService.Mode.DELETE), this::reveal);
        apps = new AppsPanel(this::reviewUninstall, this::loadApps);
        docker = new DockerPanel(dockerService);
        bulk = new BulkCachePanel(ideService, managedService, () -> showPage("Docker"), this::setBusy, this::cacheChanged);
        cleanup.onManagedCaches(() -> showPage("Bulk caches"));
        overview = new OverviewPanel(kind -> { showPage("Cleanup"); cleanup.choose(kind); }, path -> { if (!busy) startScan(path); });
        health = new DriveHealthPanel(healthService, overview::setDriveHealth);
        overview.onDriveHealth(() -> showPage("Drive health"));
        pageBody.add(overview, "Overview"); pageBody.add(cleanup, "Cleanup"); pageBody.add(large, "Large files"); pageBody.add(apps, "Installed apps"); pageBody.add(docker, "Docker");
        pageBody.add(bulk, "Bulk caches");
        pageBody.add(health, "Drive health");
        activity.setEditable(false); activity.setFont(MONO); activity.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        JPanel activityPage = transparent(new BorderLayout(0, 14)); activityPage.add(new JScrollPane(activity));
        activityPage.add(button("Refresh activity", false, this::loadActivity), BorderLayout.SOUTH); pageBody.add(activityPage, "Activity");
        add(sidebar(), BorderLayout.WEST);
        JPanel main = transparent(new BorderLayout(0, 22)); main.setBorder(BorderFactory.createEmptyBorder(29, 28, 18, 28));
        JPanel top = vertical();
        JPanel eyebrow = transparent(new BorderLayout()); eyebrow.add(micro("YOUR LAPTOP / STORAGE"), BorderLayout.WEST);
        JLabel privacy = micro("●  LOCAL SCAN · FILE CONTENTS STAY PRIVATE"); privacy.setForeground(TEAL); eyebrow.add(privacy, BorderLayout.EAST);
        top.add(eyebrow); top.add(Box.createVerticalStrut(14)); top.add(title); top.add(Box.createVerticalStrut(8)); top.add(subtitle); top.add(Box.createVerticalStrut(23));
        JPanel toolbar = transparent(new BorderLayout(12, 0));
        JPanel controls = transparent(new FlowLayout(FlowLayout.LEFT, 7, 0));
        homeButton = button("Scan home", true, () -> startScan(policy.home()));
        folderButton = button("Choose folder…", false, this::chooseFolder);
        fullButton = button("Full disk", false, () -> startScan(Path.of("/")));
        cancelButton = button("Stop scan", false, () -> { cancel.set(true); status.setText("STOPPING · KEEPING PARTIAL RESULTS"); }); cancelButton.setEnabled(false);
        controls.add(homeButton); controls.add(folderButton); controls.add(fullButton); controls.add(cancelButton);
        toolbar.add(controls, BorderLayout.WEST); exportButton = button("Export report ↗", false, this::export);
        JPanel tools = transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        tools.add(button("Widget", false, () -> widgetAction.run())); tools.add(exportButton); toolbar.add(tools, BorderLayout.EAST);
        top.add(toolbar); main.add(top, BorderLayout.NORTH); main.add(pageBody, BorderLayout.CENTER);
        JPanel footer = transparent(new BorderLayout(12, 5)); JPanel footerLabels = vertical();
        footerLabels.add(status); footerLabels.add(Box.createVerticalStrut(5)); footerLabels.add(currentPath); footer.add(footerLabels, BorderLayout.CENTER);
        progress.setPreferredSize(new Dimension(170, 5)); progress.setForeground(TEAL); progress.setBackground(LINE); progress.setBorderPainted(false); footer.add(progress, BorderLayout.EAST);
        main.add(footer, BorderLayout.SOUTH); add(main, BorderLayout.CENTER); showPage("Overview");
    }
    private JPanel sidebar() {
        JPanel side = new JPanel(new BorderLayout()); side.setBackground(NAV); side.setPreferredSize(new Dimension(230, 700));
        side.setBorder(BorderFactory.createEmptyBorder(30, 20, 23, 20)); JPanel content = vertical();
        JPanel brand = transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        brand.add(new JLabel(new ImageIcon(icon().getScaledInstance(33, 33, Image.SCALE_SMOOTH)))); brand.add(label("Storage Lens", 18, Font.BOLD, Color.WHITE)); content.add(brand);
        content.add(Box.createVerticalStrut(7)); JLabel edition = micro("DESKTOP / 01"); edition.setForeground(NAV_TEXT); content.add(edition); content.add(Box.createVerticalStrut(30));
        JLabel workspace = micro("WORKSPACE"); workspace.setForeground(NAV_TEXT); content.add(workspace); content.add(Box.createVerticalStrut(15));
        for (String name : List.of("Overview", "Cleanup", "Bulk caches", "Large files", "Installed apps", "Docker", "Drive health", "Activity")) {
            JButton link = new JButton(name); link.setHorizontalAlignment(SwingConstants.LEFT); link.setFont(BODY.deriveFont(Font.BOLD, 13));
            link.setAlignmentX(Component.LEFT_ALIGNMENT);
            link.setForeground(NAV_TEXT); link.setBackground(NAV); link.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 8)); link.setFocusPainted(false);
            link.setMaximumSize(new Dimension(Integer.MAX_VALUE, 47)); link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            link.addActionListener(e -> showPage(name)); navigation.put(name, link); content.add(link); content.add(Box.createVerticalStrut(5));
        }
        side.add(content, BorderLayout.NORTH); JPanel bottom = vertical();
        JLabel local = micro("●  ON YOUR DEVICE"); local.setForeground(new Color(0x88DFC0)); bottom.add(local); bottom.add(Box.createVerticalStrut(12));
        JTextArea promise = OverviewPanel.text("You choose what goes.\nNo scheduled cleanup.\nNo automatic uninstall."); promise.setForeground(NAV_TEXT); promise.setFont(BODY.deriveFont(12f)); bottom.add(promise);
        bottom.add(Box.createVerticalStrut(22)); JLabel version = micro("v1.0.0 · LINUX DESKTOP"); version.setForeground(NAV_TEXT); bottom.add(version);
        side.add(bottom, BorderLayout.SOUTH); return side;
    }
    void showPage(String name) {
        pages.show(pageBody, name); navigation.forEach((n, b) -> { b.setBackground(n.equals(name) ? new Color(0x29463D) : NAV); b.setForeground(n.equals(name) ? Color.WHITE : NAV_TEXT); });
        title.setText(switch (name) { case "Cleanup" -> "A careful clean starts here."; case "Bulk caches" -> "Clear rebuildable caches together."; case "Drive health" -> "Track your NVMe’s endurance."; case "Large files" -> "Find the files taking up space."; case "Installed apps" -> "Know what’s installed."; case "Docker" -> "Understand Docker’s storage."; case "Activity" -> "Every action, accounted for."; default -> "Make room for what matters."; });
        subtitle.setText(switch (name) { case "Cleanup" -> "Review each suggestion. Close the owning app before cleaning its cache."; case "Bulk caches" -> "Review IntelliJ versions, uv and downloaded package archives. Hugging Face is excluded."; case "Drive health" -> "Read-only SMART wear, lifetime host writes and the remaining rated write budget."; case "Large files" -> "The 500 largest files of at least 25 MiB. Size alone does not mean a file is disposable."; case "Installed apps" -> "Sort by installed size. Usage is unknown; orphan dependencies are candidates for review."; case "Docker" -> "Review build cache and isolated test resources. Existing project databases stay protected."; case "Activity" -> "An append-only local record of scans and cleanup requests. No file contents are logged."; default -> "A clearer view of your laptop. Every cleanup starts with a review."; });
        if (name.equals("Activity")) loadActivity();
        if (name.equals("Installed apps") && inventory == null) loadApps();
        if (name.equals("Docker")) docker.ensureLoaded();
        if (name.equals("Bulk caches")) bulk.ensureLoaded();
        if (name.equals("Drive health")) health.ensureLoaded();
    }
    void start() { startScan(root); loadApps(); health.ensureLoaded(); }
    void onWidget(Runnable action) { widgetAction = java.util.Objects.requireNonNull(action); }
    void acceptDisk(Disk value) { disk = value; overview.setData(null, value); }
    void stop() { cancel.set(true); }
    void startScan(Path requested) {
        if (busy) return;
        root = requested; cancel.set(false); setBusy(true); progress.setIndeterminate(true); cancelButton.setEnabled(true);
        status.setText("SCANNING · NO FILES ARE BEING CHANGED"); currentPath.setText(Models.safe(display(root)));
        new SwingWorker<ScanResult, Progress>() {
            protected ScanResult doInBackground() throws Exception {
                Disk measured = scanner.disk(root); SwingUtilities.invokeLater(() -> { disk = measured; overview.setData(null, disk); });
                return scanner.scan(root, cancel, this::publish);
            }
            protected void process(List<Progress> chunks) {
                Progress p = chunks.getLast(); status.setText(String.format(Locale.ROOT, "SCANNING · %,d FILES · %s ON DISK · %,d SKIPPED", p.files(), Models.size(p.bytes()), p.skipped()));
                currentPath.setText(Models.safe(display(p.current()))); currentPath.setToolTipText(Models.safe(p.current().toString()));
            }
            protected void done() {
                setBusy(false); progress.setIndeterminate(false); progress.setValue(100); cancelButton.setEnabled(false);
                try {
                    acceptScan(get(), disk);
                    audit.append("SCAN", root + " | " + result.files() + " files | " + result.bytes() + " logical bytes | cancelled=" + result.cancelled());
                } catch (Exception e) { error(e); }
            }
        }.execute();
    }
    void acceptScan(ScanResult scan, Disk measured) {
        result = scan; disk = measured; overview.setData(scan, disk); cleanup.setFiles(scan.cleanup()); large.setFiles(scan.large());
        status.setText((scan.cancelled() ? "PARTIAL SCAN" : "SCAN COMPLETE") + " · " + String.format(Locale.ROOT, "%,d FILES", scan.files()) + " · NOTHING REMOVED");
        currentPath.setText(Models.safe(display(scan.root())) + " · " + DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(scan.finished()));
    }
    void acceptInventory(AppInventory value) { inventory = value; apps.setInventory(value); }
    private void setBusy(boolean value) {
        busy = value; homeButton.setEnabled(!value); fullButton.setEnabled(!value); folderButton.setEnabled(!value);
        exportButton.setEnabled(!value); cleanup.setBusy(value); large.setBusy(value);
        bulk.setExternalBusy(value);
    }
    private void cacheChanged() {
        status.setText("CACHE CLEANUP FINISHED · RESCAN TO UPDATE FILE RESULTS");
        new SwingWorker<Disk, Void>() {
            protected Disk doInBackground() throws Exception { return scanner.disk(policy.home()); }
            protected void done() { try { acceptDisk(get()); } catch (Exception ignored) {} }
        }.execute();
    }
    void loadApps() {
        if (loadingApps) return; loadingApps = true;
        new SwingWorker<AppInventory, Void>() {
            protected AppInventory doInBackground() { return inventoryService.load(); }
            protected void done() { loadingApps = false; try { acceptInventory(get()); } catch (Exception e) { error(e); } }
        }.execute();
    }
    private void loadActivity() {
        try { activity.setText(audit.recent()); activity.setCaretPosition(activity.getDocument().getLength()); } catch (Exception e) { activity.setText(e.getMessage()); }
    }
    private void chooseFolder() {
        JFileChooser chooser = new JFileChooser(policy.home().toFile()); chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY); chooser.setDialogTitle("Choose a directory to inspect");
        chooser.setFileHidingEnabled(false); if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) startScan(chooser.getSelectedFile().toPath());
    }
    private void reveal(Path path) {
        try { new ProcessBuilder("/usr/bin/xdg-open", path.toString()).start(); } catch (Exception e) { error(e); }
    }
    private void reviewCleanup(List<Finding> selected, CleanupService.Mode mode) {
        if (busy) return;
        List<Finding> eligible = mode == CleanupService.Mode.TRASH ? selected.stream().filter(f -> f.kind() != Kind.TRASH).toList() : selected;
        if (!ReviewDialogs.cleanup(this, eligible, mode)) return;
        setBusy(true); status.setText("APPLYING YOUR REVIEWED SELECTION…");
        new SwingWorker<ActionResult, String>() {
            protected ActionResult doInBackground() { return cleanupService.apply(eligible, mode, this::publish); }
            protected void process(List<String> messages) { currentPath.setText(messages.getLast()); }
            protected void done() {
                setBusy(false);
                try {
                    ActionResult outcome = get();
                    String message = outcome.succeeded() + " files " + (mode == CleanupService.Mode.TRASH ? "moved to Trash" : "deleted") + "; " + outcome.failed() + " skipped.\n"
                        + Models.size(outcome.bytes()) + " allocated size. " + (mode == CleanupService.Mode.TRASH ? "Space is released only after Trash is emptied." : "Actual space recovered can differ.");
                    JTextArea details = new JTextArea(message + "\n\n" + String.join("\n", outcome.messages())); details.setEditable(false); details.setFont(MONO);
                    JScrollPane scroll = new JScrollPane(details); scroll.setPreferredSize(new Dimension(790, 400)); JOptionPane.showMessageDialog(StoragePanel.this, scroll, "Cleanup results", JOptionPane.INFORMATION_MESSAGE);
                    startScan(root);
                } catch (Exception e) { error(e); }
            }
        }.execute();
    }
    private void reviewUninstall(InstalledApp app) {
        try { List<String> command = uninstallService.command(app); if (ReviewDialogs.uninstall(this, app, command)) uninstallService.openTerminal(app); }
        catch (Exception e) { error(e); }
    }
    private void export() {
        JFileChooser chooser = new JFileChooser(policy.home().toFile()); chooser.setSelectedFile(new java.io.File("storage-lens-" + java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".csv"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try { ReportWriter.write(chooser.getSelectedFile().toPath(), result, inventory); status.setText("REPORT SAVED · " + Models.safe(chooser.getSelectedFile().getName())); }
        catch (Exception e) { error(e); }
    }
    private void error(Exception exception) {
        Throwable cause = exception.getCause() == null ? exception : exception.getCause(); status.setText("ACTION COULD NOT COMPLETE · REVIEW DETAILS");
        JOptionPane.showMessageDialog(this, Models.safe(cause.getMessage()), "Storage Lens", JOptionPane.ERROR_MESSAGE);
    }
}
