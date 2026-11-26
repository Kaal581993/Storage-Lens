package dev.kaal.storagelens;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import static dev.kaal.storagelens.Models.*;

/** Owns the toolbar window and optional native system-tray icon for one app session. */
final class DesktopWidget implements AutoCloseable {
    private final JFrame dashboard;
    private final StoragePanel panel;
    private final StorageScanner scanner;
    private final ScanPolicy policy;
    private final Runnable exit;
    private final StorageWidgetPanel widget;
    private final JDialog toolbar;
    private final Timer refresh;
    private TrayIcon tray;
    private MenuItem trayStatus;
    private boolean closed, refreshing;

    DesktopWidget(JFrame dashboard, StoragePanel panel, StorageScanner scanner, ScanPolicy policy, Runnable exit) {
        this.dashboard = dashboard; this.panel = panel; this.scanner = scanner; this.policy = policy; this.exit = exit;
        widget = new StorageWidgetPanel(new StorageWidgetPanel.Actions(this::openDashboard,
            () -> { openDashboard(); panel.startScan(policy.home()); },
            () -> { openDashboard(); panel.showPage("Cleanup"); }, this::openDockerReport, this::quit));
        toolbar = new JDialog((Frame) null, "Storage Lens Widget", false);
        toolbar.setType(Window.Type.UTILITY); toolbar.setIconImage(Theme.icon()); toolbar.setContentPane(widget);
        toolbar.setResizable(false); toolbar.setAlwaysOnTop(true); toolbar.pack(); toolbar.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        toolbar.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent event) { if (tray == null && !dashboard.isVisible()) openDashboard(); }
        });
        panel.onWidget(this::showToolbar);
        attachTray();
        refresh = new Timer(30_000, event -> refreshDisk()); refresh.start(); refreshDisk();
    }
    private void attachTray() {
        if (!SystemTray.isSupported()) return;
        PopupMenu menu = new PopupMenu();
        trayStatus = new MenuItem("Storage Lens — reading storage…"); trayStatus.setEnabled(false); menu.add(trayStatus); menu.addSeparator();
        item(menu, "Show toolbar widget", this::showToolbar); item(menu, "Open dashboard", this::openDashboard);
        item(menu, "Scan home", () -> { openDashboard(); panel.startScan(policy.home()); });
        item(menu, "Review cleanup", () -> { openDashboard(); panel.showPage("Cleanup"); });
        item(menu, "Large files", () -> { openDashboard(); panel.showPage("Large files"); });
        item(menu, "Installed apps", () -> { openDashboard(); panel.showPage("Installed apps"); });
        item(menu, "Docker assessment", this::openDockerReport); menu.addSeparator(); item(menu, "Quit Storage Lens", this::quit);
        TrayIcon icon = new TrayIcon(Theme.icon(), "Storage Lens", menu); icon.setImageAutoSize(true);
        icon.addActionListener(event -> SwingUtilities.invokeLater(this::showToolbar));
        icon.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent event) { if (event.getButton() == MouseEvent.BUTTON1) SwingUtilities.invokeLater(DesktopWidget.this::showToolbar); }
        });
        try { SystemTray.getSystemTray().add(icon); tray = icon; }
        catch (AWTException | UnsupportedOperationException ignored) { /* The toolbar still works without a system tray. */ }
    }
    private void item(PopupMenu menu, String title, Runnable action) {
        MenuItem item = new MenuItem(title); item.addActionListener(event -> SwingUtilities.invokeLater(action)); menu.add(item);
    }
    void showToolbar() {
        if (closed) return;
        if (!toolbar.isVisible()) {
            GraphicsConfiguration configuration = dashboard.getGraphicsConfiguration();
            Rectangle bounds = configuration.getBounds(); Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
            toolbar.setLocation(Math.max(bounds.x + insets.left, bounds.x + bounds.width - insets.right - toolbar.getWidth() - 16),
                Math.max(bounds.y + insets.top, bounds.y + bounds.height - insets.bottom - toolbar.getHeight() - 16));
        }
        toolbar.setVisible(true); toolbar.toFront(); refreshDisk();
    }
    private void openDashboard() {
        if (closed) return;
        dashboard.setVisible(true); dashboard.setState(Frame.NORMAL); dashboard.toFront(); dashboard.requestFocus();
    }
    void mainWindowClosing() {
        if (tray != null || toolbar.isVisible()) dashboard.setVisible(false);
        else quit();
    }
    private void openDockerReport() {
        openDashboard(); panel.showPage("Docker");
    }
    private void refreshDisk() {
        if (closed || refreshing) return; refreshing = true;
        new SwingWorker<Disk, Void>() {
            protected Disk doInBackground() throws Exception { return scanner.disk(policy.home()); }
            protected void done() {
                refreshing = false; if (closed) return;
                try {
                    Disk disk = get(); widget.setDisk(disk); panel.acceptDisk(disk);
                    if (tray != null) { String summary = StorageWidgetPanel.traySummary(disk); tray.setToolTip(summary); trayStatus.setLabel(summary); }
                } catch (Exception exception) {
                    widget.unavailable(); if (tray != null) tray.setToolTip("Storage Lens — storage unavailable");
                }
            }
        }.execute();
    }
    private void quit() { if (!closed) { close(); exit.run(); } }
    @Override public void close() {
        if (closed) return; closed = true; refresh.stop();
        if (tray != null) { SystemTray.getSystemTray().remove(tray); tray = null; }
        toolbar.dispose();
    }
}
