package dev.kaal.storagelens;

import javax.swing.*;
import java.awt.*;
import java.util.Locale;
import static dev.kaal.storagelens.Models.*;
import static dev.kaal.storagelens.Theme.*;

/** Compact toolbar view; callbacks open the normal reviewed application flows. */
final class StorageWidgetPanel extends JPanel {
    record Actions(Runnable open, Runnable scan, Runnable cleanup, Runnable docker, Runnable quit) {}
    final JLabel available = label("Reading storage…", 23, Font.BOLD, INK);
    final JLabel capacity = label("", 11, Font.PLAIN, MUTED);
    final JProgressBar usage = new JProgressBar(0, 1000);
    final JButton openButton, scanButton, cleanupButton, dockerButton, quitButton;

    StorageWidgetPanel(Actions actions) {
        super(new BorderLayout(0, 12)); setBackground(BG);
        setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
        JPanel heading = transparent(new BorderLayout()); heading.add(micro("STORAGE LENS / WIDGET"), BorderLayout.WEST);
        JLabel local = micro("● LOCAL"); local.setForeground(TEAL); heading.add(local, BorderLayout.EAST); add(heading, BorderLayout.NORTH);
        JPanel reading = transparent(new BorderLayout(0, 7));
        JPanel numbers = transparent(new BorderLayout()); numbers.add(available, BorderLayout.WEST); numbers.add(capacity, BorderLayout.EAST);
        reading.add(numbers, BorderLayout.NORTH); usage.setForeground(TEAL); usage.setBackground(MINT);
        usage.setBorderPainted(false); usage.setPreferredSize(new Dimension(410, 7)); reading.add(usage, BorderLayout.SOUTH); add(reading, BorderLayout.CENTER);
        JPanel controls = transparent(new GridLayout(1, 5, 6, 0));
        openButton = button("Open", true, actions.open()); scanButton = button("Scan", false, actions.scan());
        cleanupButton = button("Cleanup", false, actions.cleanup()); dockerButton = button("Docker", false, actions.docker());
        quitButton = button("Quit", false, actions.quit());
        for (JButton action : new JButton[]{openButton, scanButton, cleanupButton, dockerButton, quitButton}) {
            action.setBorder(BorderFactory.createEmptyBorder(9, 5, 9, 5)); controls.add(action);
        }
        scanButton.setToolTipText("Open the dashboard and scan your home folder");
        cleanupButton.setToolTipText("Open cleanup review; no files are selected automatically");
        dockerButton.setToolTipText("Open Docker storage assessment and reviewed cleanup controls");
        add(controls, BorderLayout.SOUTH); setPreferredSize(new Dimension(480, 163));
    }
    void setDisk(Disk disk) {
        if (disk.total() <= 0) { unavailable(); return; }
        long free = Math.max(0, Math.min(disk.total(), disk.usable()));
        int used = (int) Math.round(1000.0 * (disk.total() - free) / disk.total());
        available.setText(Models.size(free) + " free");
        capacity.setText(String.format(Locale.ROOT, "%d%% used · %s total", Math.round(used / 10.0), Models.size(disk.total())));
        usage.setValue(used); usage.setForeground(used >= 950 ? RED : used >= 850 ? AMBER : TEAL);
    }
    void unavailable() { available.setText("Storage unavailable"); capacity.setText(""); usage.setValue(0); }
    static String traySummary(Disk disk) {
        if (disk.total() <= 0) return "Storage Lens — storage unavailable";
        return "Storage Lens — " + Models.size(Math.max(0, Math.min(disk.total(), disk.usable()))) + " free";
    }
}
