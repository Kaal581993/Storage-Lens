package dev.kaal.storagelens;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import static dev.kaal.storagelens.Models.*;
import static dev.kaal.storagelens.Theme.*;

final class OverviewPanel extends JPanel {
    final JLabel free = label("—", 30, Font.BOLD, INK), review = label("—", 30, Font.BOLD, TEAL), scanned = label("—", 30, Font.BOLD, INK);
    final JLabel files = label("Run a scan to measure files", 12, Font.PLAIN, MUTED);
    private final Ring ring = new Ring();
    private final JLabel diskTitle = label("Your drive, at a glance", 19, Font.BOLD, INK);
    private final JLabel used = label("Reading filesystem…", 12, Font.PLAIN, MUTED);
    private final JPanel folders = vertical();
    private final EnumMap<Kind, JLabel> categorySizes = new EnumMap<>(Kind.class);
    private final JTextArea notes = new JTextArea("Scan your home folder or choose a directory. Nothing is removed during a scan.");
    private final Consumer<Path> drill;
    private Runnable driveHealth = () -> {};
    private final JTextArea healthSummary = text("Read NVMe endurance and lifetime host writes. SMART access may need system authentication.");

    OverviewPanel(Consumer<Kind> cleanup, Consumer<Path> drill) {
        super(new BorderLayout()); setOpaque(false); this.drill = drill;
        DashboardBody content = new DashboardBody();
        JPanel stats = transparent(new GridLayout(1, 3, 16, 0));
        stats.add(stat("AVAILABLE ON DRIVE", free, label("Space available to your account", 12, Font.PLAIN, MUTED)));
        stats.add(stat("FILES TO REVIEW", review, label("Candidates, before your selection", 12, Font.PLAIN, MUTED)));
        stats.add(stat("ON DISK / SCANNED", scanned, files));
        stats.setMaximumSize(new Dimension(Integer.MAX_VALUE, 125)); stats.setPreferredSize(new Dimension(900, 125));
        content.section(stats, 18);
        JPanel middle = transparent(new GridLayout(1, 2, 18, 0));
        Card disk = new Card(new BorderLayout(14, 0));
        disk.add(ring, BorderLayout.WEST);
        JPanel diskText = vertical(); diskText.add(Box.createVerticalStrut(24)); diskText.add(micro("FILESYSTEM CAPACITY"));
        diskText.add(Box.createVerticalStrut(13)); diskText.add(diskTitle); diskText.add(Box.createVerticalStrut(11)); diskText.add(used);
        diskText.add(Box.createVerticalStrut(22));
        JTextArea tip = text("Start with downloads and caches.\nReview large personal files next."); tip.setForeground(MUTED); diskText.add(tip);
        disk.add(diskText, BorderLayout.CENTER); middle.add(disk);
        Card categories = new Card(new BorderLayout(0, 16));
        JPanel catHeader = transparent(new BorderLayout()); catHeader.add(label("A little room to breathe", 18, Font.BOLD, INK), BorderLayout.WEST);
        catHeader.add(micro("REVIEW FIRST"), BorderLayout.EAST); categories.add(catHeader, BorderLayout.NORTH);
        JPanel catRows = new JPanel(new GridLayout(4, 1, 0, 9)); catRows.setOpaque(false);
        for (Kind kind : List.of(Kind.CACHE, Kind.PARTIAL, Kind.OLD_DOWNLOAD, Kind.TRASH)) {
            JPanel row = transparent(new BorderLayout(8, 0));
            JButton name = new JButton(kind.label + "  →"); name.setHorizontalAlignment(SwingConstants.LEFT); name.setBorder(null); name.setContentAreaFilled(false);
            name.setForeground(kind == Kind.OLD_DOWNLOAD ? AMBER : INK); name.setFont(BODY); name.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            name.addActionListener(e -> cleanup.accept(kind)); row.add(name, BorderLayout.WEST);
            JLabel amount = label("—", 13, Font.BOLD, INK); amount.setFont(MONO.deriveFont(Font.BOLD, 13)); categorySizes.put(kind, amount);
            row.add(amount, BorderLayout.EAST); catRows.add(row);
        }
        categories.add(catRows, BorderLayout.CENTER); middle.add(categories);
        middle.setMaximumSize(new Dimension(Integer.MAX_VALUE, 262)); middle.setPreferredSize(new Dimension(900, 262));
        content.section(middle, 18);
        Card health = new Card(new BorderLayout(16, 10));
        health.add(label("NVMe endurance & writes", 17, Font.BOLD, INK), BorderLayout.NORTH);
        healthSummary.setRows(2); healthSummary.setFont(BODY.deriveFont(12f)); healthSummary.setForeground(MUTED); health.add(healthSummary);
        health.add(button("Drive health →", false, () -> driveHealth.run()), BorderLayout.EAST); content.section(health, 18);
        Card folderCard = new Card(new BorderLayout(0, 15));
        JPanel folderHeader = transparent(new BorderLayout()); folderHeader.add(label("Where your space goes", 18, Font.BOLD, INK), BorderLayout.WEST);
        folderHeader.add(micro("CLICK A FOLDER TO EXPLORE"), BorderLayout.EAST); folderCard.add(folderHeader, BorderLayout.NORTH);
        folders.add(label("Your largest folders will appear here after scanning.", 13, Font.PLAIN, MUTED));
        folderCard.add(folders, BorderLayout.CENTER); folderCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 375));
        content.section(folderCard, 14);
        notes.setLineWrap(true); notes.setWrapStyleWord(true); notes.setEditable(false); notes.setOpaque(false); notes.setFont(BODY.deriveFont(11f)); notes.setForeground(MUTED);
        notes.setRows(4);
        notes.setBorder(BorderFactory.createEmptyBorder(0, 4, 12, 4)); content.section(notes, 0);
        GridBagConstraints remaining = new GridBagConstraints();
        remaining.gridx = 0; remaining.gridy = content.getComponentCount(); remaining.weighty = 1;
        content.add(Box.createVerticalGlue(), remaining);
        JScrollPane scroll = new JScrollPane(content); scroll.setBorder(null); scroll.getViewport().setBackground(BG);
        scroll.getVerticalScrollBar().setUnitIncrement(22); scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER); add(scroll);
    }
    /** Keep scan text and preferred widths from moving the cards outside the viewport. */
    private static final class DashboardBody extends JPanel implements Scrollable {
        DashboardBody() { super(new GridBagLayout()); setOpaque(false); }
        void section(JComponent component, int gap) {
            // A wrapped text area has an enormous minimum height before it receives its first width.
            // Track the live preferred height and remove preferred-width pressure from the grid.
            JPanel row = new JPanel(new BorderLayout()) {
                @Override public Dimension getPreferredSize() { return new Dimension(0, component.getPreferredSize().height); }
                @Override public Dimension getMinimumSize() { return getPreferredSize(); }
            };
            row.setOpaque(false); row.add(component);
            GridBagConstraints constraints = new GridBagConstraints();
            constraints.gridx = 0; constraints.gridy = getComponentCount(); constraints.weightx = 1;
            constraints.fill = GridBagConstraints.HORIZONTAL; constraints.anchor = GridBagConstraints.NORTHWEST;
            constraints.insets = new Insets(0, 0, gap, 0); add(row, constraints);
        }
        public Dimension getPreferredScrollableViewportSize() { return new Dimension(900, 740); }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() {
            return getParent() instanceof JViewport viewport && viewport.getHeight() > getPreferredSize().height;
        }
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 22; }
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(22, (orientation == SwingConstants.VERTICAL ? visible.height : visible.width) - 22);
        }
    }
    private Card stat(String title, JLabel value, JLabel caption) {
        Card card = new Card(new BorderLayout(0, 7)); card.setBorder(BorderFactory.createEmptyBorder(17, 22, 17, 22));
        card.add(micro(title), BorderLayout.NORTH); card.add(value, BorderLayout.CENTER); card.add(caption, BorderLayout.SOUTH); return card;
    }
    static JTextArea text(String value) {
        JTextArea area = new JTextArea(value); area.setEditable(false); area.setLineWrap(true); area.setWrapStyleWord(true); area.setOpaque(false); area.setFont(BODY); return area;
    }
    void onDriveHealth(Runnable action) { driveHealth = Objects.requireNonNull(action); }
    void setDriveHealth(DriveHealthService.Health health, java.math.BigDecimal rating) {
        String left = health.remainingPercent() == null ? "SMART unavailable: " + health.message() : health.remainingPercent() + "% estimated endurance remaining";
        var budget = health.remainingTbw(rating);
        healthSummary.setText(health.device() + " · " + left + "\nHost writes: " + DriveHealthService.tb(health.writtenBytes())
            + " · Rated write budget remaining: " + (budget == null ? rating == null ? "enter manufacturer TBW in Drive health" : "SMART access needed" : budget.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + " TB (estimate)"));
        revalidate(); repaint();
    }
    void setData(ScanResult result, Disk disk) {
        if (disk != null) {
            free.setText(Models.size(disk.usable()));
            ring.fraction = disk.total() == 0 ? 0 : (double) disk.used() / disk.total();
            ring.center = Math.round(ring.fraction * 100) + "%"; ring.repaint();
            diskTitle.setText(ring.fraction >= .85 ? "Storage is getting tight" : "Room for what matters");
            used.setText(Models.size(disk.used()) + " of " + Models.size(disk.total()) + " used");
        }
        if (result == null) return;
        review.setText(Models.size(result.reviewBytes())); scanned.setText(Models.size(result.allocated()));
        files.setText(String.format(Locale.ROOT, "%,d files · %,d skipped", result.files(), result.skipped()));
        for (var entry : categorySizes.entrySet()) entry.getValue().setText(Models.size(result.totals().getOrDefault(entry.getKey(), 0L)));
        folders.removeAll();
        long max = result.folders().stream().mapToLong(Folder::bytes).max().orElse(1);
        for (Folder folder : result.folders().stream().limit(8).toList()) {
            JPanel row = transparent(new BorderLayout(20, 0)); row.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));
            JButton name = new JButton(Models.safe(display(folder.path()))); name.putClientProperty("html.disable", Boolean.TRUE);
            name.setHorizontalAlignment(SwingConstants.LEFT); name.setBorder(null); name.setForeground(INK); name.setFont(MONO); name.setContentAreaFilled(false);
            name.setPreferredSize(new Dimension(235, 22)); name.setToolTipText(folder.path().toString()); name.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            name.addActionListener(e -> drill.accept(folder.path())); row.add(name, BorderLayout.WEST);
            JProgressBar bar = new JProgressBar(0, 1000); bar.setValue((int) (1000.0 * folder.bytes() / Math.max(1, max)));
            bar.setForeground(TEAL); bar.setBackground(MINT); bar.setBorderPainted(false); bar.setPreferredSize(new Dimension(240, 9));
            JPanel barWrap = transparent(new GridBagLayout()); barWrap.add(bar); row.add(barWrap, BorderLayout.CENTER);
            JLabel amount = label(Models.size(folder.bytes()), 12, Font.BOLD, INK); amount.setHorizontalAlignment(SwingConstants.RIGHT); amount.setPreferredSize(new Dimension(95, 22));
            row.add(amount, BorderLayout.EAST); folders.add(row);
        }
        if (result.folders().isEmpty()) folders.add(label("No readable regular files in this directory.", 13, Font.PLAIN, MUTED));
        notes.setText("Scanned logical file size: " + Models.size(result.bytes()) + ". Disk sizes are measured separately.\n" + String.join("\n", result.warnings())); revalidate(); repaint();
    }
}
