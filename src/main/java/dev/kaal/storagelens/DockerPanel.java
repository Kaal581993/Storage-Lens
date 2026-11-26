package dev.kaal.storagelens;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import static dev.kaal.storagelens.DockerModels.*;
import static dev.kaal.storagelens.Theme.*;

final class DockerPanel extends JPanel {
    private final DockerService service;
    private Snapshot snapshot;
    private boolean busy;
    private final JLabel scope = micro("OPEN DOCKER DESKTOP, THEN REFRESH");
    private final JLabel cacheSize = label("—", 25, Font.BOLD, TEAL), volumeSize = label("—", 25, Font.BOLD, INK);
    private final JTextArea status = OverviewPanel.text("Cache can be rebuilt. Database and unknown-owner volumes remain protected. Nothing is selected automatically.");
    private final JComboBox<CacheAge> age = new JComboBox<>(CacheAge.values());
    private final JButton prune, refresh, export, reclaim;
    private final JLabel diskSize = label("Docker Desktop disk allocation unavailable", 13, Font.BOLD, INK);
    private final JTextArea diskNote = OverviewPanel.text("The VM disk includes images, volumes and unused internal space. Reclamation can return unused blocks to the laptop.");
    final ResourceTable containers = new ResourceTable(false), volumes = new ResourceTable(true);

    DockerPanel(DockerService service) {
        super(new BorderLayout(0, 14)); setOpaque(false); this.service = service;
        JPanel heading = Theme.vertical(); heading.add(scope); heading.add(Box.createVerticalStrut(14));
        JPanel cards = transparent(new java.awt.GridLayout(1, 2, 14, 0));
        Card cache = new Card(new BorderLayout(0, 6)); cache.add(micro("UNUSED, UNSHARED BUILD CACHE / ALL AGES"), BorderLayout.NORTH); cache.add(cacheSize);
        cache.add(label("Estimate; shared layers and age filters affect savings", 11, Font.PLAIN, MUTED), BorderLayout.SOUTH); cards.add(cache);
        Card data = new Card(new BorderLayout(0, 6)); data.add(micro("VOLUME DATA / INCLUDING EXISTING PROJECTS"), BorderLayout.NORTH); data.add(volumeSize);
        data.add(label("Database contents are preserved by default", 11, Font.PLAIN, MUTED), BorderLayout.SOUTH); cards.add(data);
        cards.setPreferredSize(new Dimension(800, 112)); heading.add(cards); heading.add(Box.createVerticalStrut(12));
        JPanel actions = transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        refresh = button("Refresh Docker", false, this::load); actions.add(refresh); age.setPreferredSize(new Dimension(230, 36)); actions.add(age);
        prune = button("Review build-cache cleanup…", true, this::reviewCache); actions.add(prune);
        export = button("Export audit…", false, this::exportAudit); actions.add(export); heading.add(actions);
        JPanel desktop = transparent(new BorderLayout(12, 6)); desktop.add(diskSize, BorderLayout.NORTH);
        diskNote.setRows(2); diskNote.setFont(BODY.deriveFont(11f)); diskNote.setForeground(MUTED); desktop.add(diskNote);
        reclaim = button("Reclaim Desktop disk space…", false, this::reviewReclaim); desktop.add(reclaim, BorderLayout.EAST);
        heading.add(Box.createVerticalStrut(12)); heading.add(desktop);
        JTabbedPane tabs = new JTabbedPane(); tabs.addTab("Containers", containers); tabs.addTab("Volumes", volumes);
        tabs.setPreferredSize(new Dimension(800, 350));
        status.setRows(3); status.setFont(BODY.deriveFont(12f)); status.setForeground(MUTED);
        JPanel content = new ScrollBody(new BorderLayout(0, 14)); content.add(heading, BorderLayout.NORTH); content.add(tabs); content.add(status, BorderLayout.SOUTH); add(scrollBody(content));
        containers.remove.addActionListener(event -> reviewResources(false)); volumes.remove.addActionListener(event -> reviewResources(true)); setBusy(false);
    }
    void ensureLoaded() { if (snapshot == null && !busy) load(); }
    void setSnapshot(Snapshot value) {
        snapshot = value; scope.setText("CONTEXT " + Models.safe(value.endpoint().context()) + " · ENGINE " + value.engineId().substring(0, Math.min(16, value.engineId().length())));
        cacheSize.setText(Models.size(value.unsharedCacheBytes())); volumeSize.setText(Models.size(value.volumeBytes()));
        long allocated = DockerService.desktopDiskBytes(value);
        diskSize.setText((allocated < 0 ? "Default Docker.raw allocation unavailable" : "Default Docker.raw on laptop: " + Models.size(allocated) + " allocated")
            + " · Engine image layers: " + Models.size(value.imageBytes()));
        containers.setRows(value.containers().stream().map(c -> new Row(c.id(), c.name(), c.bytes(), c.project(), c.state(), c.reason(), c.removable())).toList());
        volumes.setRows(value.volumes().stream().map(v -> new Row(v.name(), v.name(), v.bytes(), v.project(), v.references() < 0 ? "Unknown" : v.references() + " references", v.reason(), v.removable())).toList());
        status.setText(value.containers().size() + " containers · " + value.volumes().size() + " volumes · " + value.cache().size() + " cache records. "
            + "Only the identified kaal-balance-check and kaal-infra-check test resources can be removed here.\n"
            + "Removing containers keeps volumes. Volume removal permanently resets test data and requires zero references. Database and unknown-owner data stays protected.");
        setBusy(busy);
    }
    private void load() {
        if (busy) return; setBusy(true); status.setText("Reading Docker storage metadata…");
        new SwingWorker<Snapshot, Void>() {
            protected Snapshot doInBackground() throws Exception { return service.load(); }
            protected void done() {
                setBusy(false);
                try { setSnapshot(get()); }
                catch (Exception exception) { clearSnapshot(); error(exception); setBusy(false); }
            }
        }.execute();
    }
    private void clearSnapshot() {
        snapshot = null; containers.setRows(List.of()); volumes.setRows(List.of());
        cacheSize.setText("—"); volumeSize.setText("—"); scope.setText("DOCKER UNAVAILABLE · REFRESH TO RETRY");
        diskSize.setText("Docker Desktop disk allocation unavailable");
    }
    private void setBusy(boolean value) {
        busy = value; refresh.setEnabled(!value); age.setEnabled(!value); export.setEnabled(!value && snapshot != null);
        prune.setEnabled(!value && snapshot != null && !snapshot.cache().isEmpty());
        reclaim.setEnabled(!value && snapshot != null && DockerService.desktopEndpoint(snapshot));
        containers.locked = value; volumes.locked = value; containers.updateSelection(); volumes.updateSelection();
    }
    private void reviewCache() {
        if (busy || snapshot == null) return;
        Snapshot reviewed = snapshot; CacheAge selectedAge = (CacheAge) age.getSelectedItem();
        if (!confirm("Review Docker build-cache cleanup", "Context: " + reviewed.endpoint().context() + "\nScope: " + selectedAge
            + "\nUnused unshared cache across all ages: " + Models.size(reviewed.unsharedCacheBytes())
            + "\n\nDocker will remove eligible dangling build cache only. Future builds may take longer and require fresh downloads."
            + "\nContainers, images and project volumes are retained. Actual savings are reported afterward.", "PRUNE CACHE")) return;
        execute(() -> service.pruneCache(reviewed, selectedAge));
    }
    private void reviewReclaim() {
        if (busy || snapshot == null) return;
        try {
            String command = String.join(" ", DockerService.desktopReclaimCommand(snapshot));
            String detail = "Context: " + snapshot.endpoint().context() + "\n\n" + command
                + "\n\nThis opens a visible terminal and runs Docker's small, pinned reclamation helper as a temporary privileged container inside the Desktop VM."
                + " The helper may need a download. It asks the VM to return unused disk blocks. Existing project containers, images and volume data are retained."
                + "\nModern Docker Desktop may already reclaim automatically. Actual savings can be zero and can be limited by filesystem snapshots."
                + "\nRefresh Docker after the terminal finishes to measure Docker.raw again. Disk size settings are not changed."
                + "\n\nDocker instructions: https://docs.docker.com/desktop/troubleshoot-and-support/faqs/linuxfaqs/";
            if (!ReviewDialogs.typed(this, "Review Docker Desktop disk reclamation", detail, "RECLAIM DESKTOP SPACE")) return;
            Snapshot reviewed = snapshot; setBusy(true);
            new SwingWorker<Void, Void>() {
                protected Void doInBackground() throws Exception { service.openDesktopReclaim(reviewed); return null; }
                protected void done() {
                    setBusy(false);
                    try { get(); status.setText("Reclamation terminal opened. Refresh Docker after it finishes; no project resources were selected for deletion."); }
                    catch (Exception exception) { error(exception); }
                }
            }.execute();
        } catch (Exception exception) { error(exception); }
    }
    private void reviewResources(boolean volume) {
        if (busy || snapshot == null) return;
        List<Row> selected = (volume ? volumes : containers).selected(); if (selected.isEmpty()) return;
        Snapshot reviewed = snapshot;
        String details = "Context: " + reviewed.endpoint().context() + "\n\n" + String.join("\n", selected.stream()
            .map(r -> r.name() + " · " + Models.size(Math.max(0, r.bytes())) + " · " + r.project() + "\n  " + r.reason()).toList());
        details += volume ? "\n\nThis permanently deletes test Kafka messages, Redis state or test app memory. Back up anything you need before continuing."
            : "\n\nContainer-local changes will be lost. Persistent volumes and images are retained. The selected test stacks can be recreated from their Compose files.";
        if (!confirm(volume ? "Review test-volume deletion" : "Review test-container removal", details, volume ? "DELETE TEST DATA" : "REMOVE CONTAINERS")) return;
        List<String> keys = selected.stream().map(Row::key).toList();
        execute(() -> volume ? service.removeVolumes(reviewed, keys) : service.removeContainers(reviewed, keys));
    }
    private boolean confirm(String title, String detail, String phrase) {
        JPanel content = transparent(new BorderLayout(0, 12)); JTextArea text = new JTextArea(detail); text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true); text.setFont(BODY);
        JScrollPane scroll = new JScrollPane(text); scroll.setPreferredSize(new Dimension(720, 340)); content.add(scroll);
        JPanel entry = transparent(new BorderLayout(0, 8)); entry.add(label("Type " + phrase + " to proceed", 13, Font.BOLD, RED), BorderLayout.NORTH);
        JTextField typed = new JTextField(); entry.add(typed); content.add(entry, BorderLayout.SOUTH);
        int choice = JOptionPane.showOptionDialog(this, content, title, JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
            null, new Object[]{"Apply reviewed action", "Cancel"}, "Cancel");
        if (choice != 0) return false;
        if (!typed.getText().equals(phrase)) { status.setText("Confirmation did not match. Docker was not changed."); return false; }
        return true;
    }
    private void execute(Callable<Outcome> action) {
        setBusy(true); status.setText("Applying your reviewed Docker action…");
        new SwingWorker<Outcome, Void>() {
            protected Outcome doInBackground() throws Exception { return action.call(); }
            protected void done() {
                setBusy(false);
                try { Outcome outcome = get(); JOptionPane.showMessageDialog(DockerPanel.this,
                    outcome.succeeded() + " completed; " + outcome.failed() + " skipped.\n" + String.join("\n", outcome.messages()), "Docker cleanup results", JOptionPane.INFORMATION_MESSAGE); load(); }
                catch (Exception exception) { clearSnapshot(); error(exception); setBusy(false); }
            }
        }.execute();
    }
    private void exportAudit() {
        if (snapshot == null || busy) return;
        JFileChooser chooser = new JFileChooser(System.getProperty("user.home")); chooser.setSelectedFile(new java.io.File("docker-storage-" + System.currentTimeMillis() + ".csv"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) try { DockerService.export(chooser.getSelectedFile().toPath(), snapshot); status.setText("Docker audit saved."); }
        catch (Exception exception) { error(exception); }
    }
    private void error(Exception exception) {
        Throwable cause = exception.getCause() == null ? exception : exception.getCause(); status.setText(Models.safe(cause.getMessage()));
    }
    record Row(String key, String name, long bytes, String project, String state, String reason, boolean eligible) {}
    final class ResourceTable extends JPanel {
        private List<Row> rows = List.of(); private final Set<String> checked = new HashSet<>(); private boolean locked;
        private final ResourceModel model = new ResourceModel(); final JTable table = new JTable(model);
        final JButton remove; private final JLabel selection = label("Nothing selected", 12, Font.PLAIN, MUTED);
        ResourceTable(boolean volumes) {
            super(new BorderLayout(0, 10)); setOpaque(false); remove = button(volumes ? "Review selected test volumes…" : "Review selected test containers…", false, () -> {});
            FilesPanel.configureTable(table); table.setAutoCreateRowSorter(true); table.getColumnModel().getColumn(0).setMaxWidth(42);
            table.getColumnModel().getColumn(1).setPreferredWidth(260); table.getColumnModel().getColumn(2).setCellRenderer(new FilesPanel.SizeRenderer());
            table.getColumnModel().getColumn(3).setPreferredWidth(160); table.getColumnModel().getColumn(5).setPreferredWidth(380);
            JScrollPane scroll = new JScrollPane(table); scroll.setColumnHeaderView(table.getTableHeader()); add(scroll);
            JPanel footer = transparent(new BorderLayout()); footer.add(selection, BorderLayout.WEST);
            JPanel actions = transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            actions.add(button("Select eligible", false, () -> { if (!locked) { rows.stream().filter(Row::eligible).forEach(r -> checked.add(r.key())); model.fireTableDataChanged(); } }));
            actions.add(button("Clear", false, () -> { checked.clear(); model.fireTableDataChanged(); })); actions.add(remove); footer.add(actions, BorderLayout.EAST); add(footer, BorderLayout.SOUTH);
            model.addTableModelListener(event -> updateSelection()); updateSelection();
        }
        void setRows(List<Row> value) { rows = value; checked.clear(); model.fireTableDataChanged(); }
        List<Row> selected() { return rows.stream().filter(r -> checked.contains(r.key())).toList(); }
        void updateSelection() { selection.setText(checked.size() + " selected · " + rows.size() + " listed"); remove.setEnabled(!locked && !checked.isEmpty()); }
        private final class ResourceModel extends AbstractTableModel {
            private final String[] columns = {"", "NAME", "REPORTED SIZE", "PROJECT", "STATE / REFERENCES", "WHY REMOVE OR KEEP"};
            public int getRowCount() { return rows.size(); } public int getColumnCount() { return columns.length; }
            public String getColumnName(int column) { return columns[column]; }
            public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : column == 2 ? Long.class : String.class; }
            public boolean isCellEditable(int row, int column) { return column == 0 && !locked && rows.get(row).eligible(); }
            public Object getValueAt(int row, int column) {
                Row r = rows.get(row); return switch (column) { case 0 -> checked.contains(r.key()); case 1 -> r.name(); case 2 -> r.bytes(); case 3 -> r.project(); case 4 -> r.state(); default -> r.reason(); };
            }
            public void setValueAt(Object value, int row, int column) { if (!isCellEditable(row, column)) return; if (Boolean.TRUE.equals(value)) checked.add(rows.get(row).key()); else checked.remove(rows.get(row).key()); fireTableCellUpdated(row, column); }
        }
    }
}
