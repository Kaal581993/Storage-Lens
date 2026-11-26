package dev.kaal.storagelens;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import static dev.kaal.storagelens.Theme.*;

final class BulkCachePanel extends JPanel {
    private final IdeCacheService ide;
    private final ManagedCacheService managed;
    private final Consumer<Boolean> busyChanged;
    private final Runnable changed;
    private final AtomicBoolean cancel = new AtomicBoolean();
    private final JLabel cacheBytes = label("—", 25, Font.BOLD, TEAL), totalBytes = label("—", 25, Font.BOLD, INK);
    private final JTextArea status = OverviewPanel.text("Refresh to measure each IDE version and managed cache. Hugging Face is excluded.");
    private final JTextArea uvSummary = OverviewPanel.text("Reading uv cache…"), packageSummary = OverviewPanel.text("Reading package-cache preview…");
    private final JButton refresh, stop, remove, select, clear, export, pruneUv, cleanUv, packages;
    private final Set<Path> checked = new HashSet<>();
    private final CacheModel model = new CacheModel();
    final JTable table = new JTable(model);
    private IdeCacheService.Plan plan;
    private ManagedCacheService.UvReview uv;
    private boolean busy, externalBusy, cancellable, attempted;

    BulkCachePanel(IdeCacheService ide, ManagedCacheService managed, Runnable docker, Consumer<Boolean> busyChanged, Runnable changed) {
        super(new BorderLayout(0, 14)); setOpaque(false); this.ide = ide; this.managed = managed; this.busyChanged = busyChanged; this.changed = changed;
        JPanel top = transparent(new BorderLayout(0, 12)); JPanel cards = transparent(new GridLayout(1, 2, 14, 0));
        Card caches = new Card(new BorderLayout(0, 7)); caches.add(micro("REBUILDABLE IDE INDEXES / ALL AGES"), BorderLayout.NORTH); caches.add(cacheBytes);
        caches.add(label("Closed IDEs only · startup may reindex and rebuild", 11, Font.PLAIN, MUTED), BorderLayout.SOUTH); cards.add(caches);
        Card total = new Card(new BorderLayout(0, 7)); total.add(micro("IDE SYSTEM DATA / INCLUDING PRESERVED FILES"), BorderLayout.NORTH); total.add(totalBytes);
        total.add(label("Local History, VFS, plugins and settings stay intact", 11, Font.PLAIN, MUTED), BorderLayout.SOUTH); cards.add(total); top.add(cards);
        JPanel tools = transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        refresh = button("Refresh caches", false, this::load); stop = button("Stop", false, () -> cancel.set(true));
        export = button("Export full IDE review…", false, this::export);
        tools.add(refresh); tools.add(stop); tools.add(export); tools.add(button("Open Docker cleanup", false, docker)); top.add(tools, BorderLayout.SOUTH); add(top, BorderLayout.NORTH);
        JTabbedPane tabs = new JTabbedPane(); JPanel versions = transparent(new BorderLayout(0, 10));
        FilesPanel.configureTable(table); table.setAutoCreateRowSorter(true); table.getColumnModel().getColumn(0).setMaxWidth(42);
        table.getColumnModel().getColumn(2).setCellRenderer(new FilesPanel.SizeRenderer()); table.getColumnModel().getColumn(3).setCellRenderer(new FilesPanel.SizeRenderer());
        table.getColumnModel().getColumn(1).setPreferredWidth(180); table.getColumnModel().getColumn(5).setPreferredWidth(430);
        JScrollPane scroll = new JScrollPane(table); scroll.setColumnHeaderView(table.getTableHeader()); versions.add(scroll);
        JPanel actions = transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        select = button("Select closed IDEs", false, () -> { if (!locked() && plan != null) plan.groups().stream().filter(IdeCacheService.Group::eligible).forEach(g -> checked.add(g.instance().root())); model.fireTableDataChanged(); updateButtons(); });
        clear = button("Clear", false, () -> { checked.clear(); model.fireTableDataChanged(); updateButtons(); });
        remove = button("Review selected IDE caches…", true, this::reviewIde); actions.add(select); actions.add(clear); actions.add(remove); versions.add(actions, BorderLayout.SOUTH); tabs.addTab("JetBrains / IntelliJ versions", versions);
        JPanel nativeCaches = transparent(new BorderLayout(0, 12)); JPanel details = new ScrollBody(new GridLayout(2, 1, 0, 14));
        Card uvCard = new Card(new BorderLayout(0, 12)); uvCard.add(label("uv — managed download and build cache", 17, Font.BOLD, INK), BorderLayout.NORTH);
        uvSummary.setRows(5); uvCard.add(uvSummary); JPanel uvButtons = transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        pruneUv = button("Prune unused uv cache…", false, () -> reviewUv(ManagedCacheService.UvAction.PRUNE));
        cleanUv = button("Clear all uv cache…", true, () -> reviewUv(ManagedCacheService.UvAction.CLEAN)); uvButtons.add(pruneUv); uvButtons.add(cleanUv); uvCard.add(uvButtons, BorderLayout.SOUTH); details.add(uvCard);
        Card packageCard = new Card(new BorderLayout(0, 12)); packageCard.add(label("Pacman — downloaded package archives", 17, Font.BOLD, INK), BorderLayout.NORTH);
        packageSummary.setRows(4); packageCard.add(packageSummary); packages = button("Review package cache…", false, this::reviewPackages); packageCard.add(packages, BorderLayout.SOUTH); details.add(packageCard);
        nativeCaches.add(scrollBody(details)); tabs.addTab("uv / system package cache", nativeCaches); add(tabs);
        status.setRows(3); status.setFont(BODY.deriveFont(12f)); add(status, BorderLayout.SOUTH); updateButtons();
    }
    private boolean locked() { return busy || externalBusy; }
    void setExternalBusy(boolean value) { externalBusy = value; updateButtons(); if (!value && isVisible() && plan == null && !busy) ensureLoaded(); }
    void ensureLoaded() { if (plan == null && !attempted && !locked()) load(); }
    private void busy(boolean value, boolean canStop) { busy = value; cancellable = canStop; busyChanged.accept(value); updateButtons(); }
    private void updateButtons() {
        boolean locked = locked(); refresh.setEnabled(!locked); stop.setEnabled(busy && cancellable); export.setEnabled(!locked && plan != null);
        select.setEnabled(!locked && plan != null); clear.setEnabled(!locked); remove.setEnabled(!locked && !checked.isEmpty());
        boolean hasUv = !locked && uv != null && uv.unavailable() == null;
        pruneUv.setEnabled(hasUv); cleanUv.setEnabled(hasUv); packages.setEnabled(!locked);
    }
    void setPlan(IdeCacheService.Plan value) {
        plan = value; checked.clear(); model.fireTableDataChanged(); cacheBytes.setText(Models.size(value.cacheBytes())); totalBytes.setText(Models.size(value.totalBytes()));
        status.setText(value.groups().size() + " IDE versions measured. Select only closed instances. No 30,000-file truncation is used here.\n"
            + "Only index, shared-indexes, compiler and compile-server files are included. Local History, VFS, projects, plugins, settings, models and Hugging Face are preserved."); updateButtons();
    }
    void setManagedReview(ManagedCacheService.UvReview value, String preview) {
        uv = value;
        uvSummary.setText(uv == null ? "Scan stopped. Refresh before cleanup." : Models.display(uv.path()) + "\n" + Models.size(uv.bytes()) + " measured cache files\n"
            + (uv.unavailable() == null ? "Uses uv's native cache lock; no language runtime is started.\nPrune removes unused entries, including cached environments no longer attached to projects.\nFull clear can require future downloads and rebuilds. Hardlinks or snapshots can reduce actual space recovered." : uv.unavailable()));
        packageSummary.setText((preview == null ? "Refresh for a preview." : preview.trim())
            + "\nKeeps the newest three cached versions. Installed programs stay installed.\nThe terminal needs your system authentication; refresh afterward."); updateButtons();
    }
    private void load() {
        if (locked()) return; attempted = true; cancel.set(false); busy(true, true); status.setText("Measuring IDE versions and uv cache; no files are being changed…");
        new SwingWorker<IdeCacheService.Plan, String>() {
            private ManagedCacheService.UvReview measuredUv; private String preview;
            protected IdeCacheService.Plan doInBackground() throws Exception {
                var measured = ide.scan(cancel, this::publish);
                if (!cancel.get()) { measuredUv = managed.inspectUv(); preview = managed.packagePreview(); }
                return measured;
            }
            protected void process(List<String> updates) { status.setText(updates.getLast()); }
            protected void done() {
                try {
                    setPlan(get()); setManagedReview(measuredUv, preview);
                } catch (Exception exception) { plan = null; uv = null; checked.clear(); model.fireTableDataChanged(); error(exception); }
                finally { busy(false, false); }
            }
        }.execute();
    }
    private void reviewIde() {
        if (locked() || plan == null) return;
        var selected = plan.groups().stream().filter(g -> checked.contains(g.instance().root())).toList();
        if (selected.isEmpty()) return;
        String detail = String.join("\n\n", selected.stream().map(g -> g.instance().version() + "\n" + g.instance().root()
            + "\n" + g.files().size() + " index/build-cache files · " + Models.size(g.cacheBytes())).toList());
        detail += "\n\nAll ages are included. Exact file identities are checked again. This permanently deletes only reviewed rebuildable files.\n"
            + "Local History, VFS, plugins, settings and project sources are retained. Empty cache directories are retained.\nKeep all selected IDEs and their build processes closed until completion. Reindexing/rebuilding can make the next start slower.";
        if (!ReviewDialogs.typed(this, "Review bulk IDE cache deletion", detail, "DELETE IDE CACHES")) return;
        var reviewed = plan; var roots = selected.stream().map(g -> g.instance().root()).toList();
        cancel.set(false); busy(true, true);
        new SwingWorker<Models.ActionResult, String>() {
            protected Models.ActionResult doInBackground() throws Exception { return ide.apply(reviewed, roots, cancel, this::publish); }
            protected void process(List<String> updates) { status.setText(updates.getLast()); }
            protected void done() {
                try { var result = get(); result(result.succeeded() + " files deleted; " + result.failed() + " skipped.\n" + Models.size(result.bytes()) + " allocated size; actual recovery can differ.\n" + String.join("\n", result.messages())); }
                catch (Exception exception) { error(exception); }
                finally { plan = null; busy(false, false); changed.run(); load(); }
            }
        }.execute();
    }
    private void reviewUv(ManagedCacheService.UvAction action) {
        if (locked() || uv == null) return;
        String detail = String.join(" ", ManagedCacheService.uvCommand(uv, action)) + "\n\nCache: " + uv.path() + "\nMeasured size: " + Models.size(uv.bytes())
            + "\n\n" + (action == ManagedCacheService.UvAction.PRUNE ? "Remove unused entries and cached centralized environments using uv's own checks. Cached environments can be recreated when needed." : "Clear all uv cache entries. Later installs/builds may need fresh downloads. This reduces offline reuse; do not clear while relying on cache-linked environments.")
            + "\nExisting project folders, model stores and Hugging Face are excluded. No force flag is used. If uv reports the cache is busy, retry after those jobs finish.";
        if (!ReviewDialogs.typed(this, "Review uv cache cleanup", detail, action == ManagedCacheService.UvAction.PRUNE ? "PRUNE UV CACHE" : "CLEAR UV CACHE")) return;
        var reviewed = uv; runNative(() -> managed.cleanUv(reviewed, action));
    }
    private void reviewPackages() {
        if (locked()) return;
        String detail = packageSummary.getText() + "\n\n" + String.join(" ", ManagedCacheService.packageCommand())
            + "\n\nA terminal opens for system authentication. This removes old downloaded package archives, retaining three cached versions. It does not uninstall applications. Older rollback archives will no longer be available locally.";
        if (!ReviewDialogs.typed(this, "Review system package-cache cleanup", detail, "CLEAN PACKAGE CACHE")) return;
        try { managed.openPackageCleanup(); status.setText("Package-cache terminal opened. Finish authentication there, then refresh caches to measure the result."); }
        catch (Exception exception) { error(exception); }
    }
    private void runNative(Callable<String> action) {
        busy(true, false); status.setText("Waiting for the cache tool's lock and reviewed cleanup…");
        new SwingWorker<String, Void>() {
            protected String doInBackground() throws Exception { return action.call(); }
            protected void done() {
                try { result(get()); } catch (Exception exception) { error(exception); }
                finally { plan = null; uv = null; busy(false, false); changed.run(); load(); }
            }
        }.execute();
    }
    private void export() {
        if (locked() || plan == null) return;
        JFileChooser chooser = new JFileChooser(System.getProperty("user.home")); chooser.setSelectedFile(new java.io.File("ide-cache-review-" + System.currentTimeMillis() + ".csv"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) try { IdeCacheService.export(chooser.getSelectedFile().toPath(), plan); status.setText("Complete IDE cache review exported."); }
        catch (Exception exception) { error(exception); }
    }
    private void result(String text) {
        JTextArea detail = new JTextArea(text); detail.setEditable(false); detail.setLineWrap(true); detail.setWrapStyleWord(true); detail.setFont(BODY);
        JScrollPane scroll = new JScrollPane(detail); scroll.setPreferredSize(new Dimension(750, 350)); JOptionPane.showMessageDialog(this, scroll, "Cache cleanup result", JOptionPane.INFORMATION_MESSAGE);
    }
    private void error(Exception exception) { Throwable cause = exception.getCause() == null ? exception : exception.getCause(); status.setText(Models.safe(cause.getMessage())); }
    private final class CacheModel extends AbstractTableModel {
        String[] columns = {"", "IDE VERSION", "REBUILDABLE", "SYSTEM DATA", "FILES", "STATUS / PRESERVATION"};
        public int getRowCount() { return plan == null ? 0 : plan.groups().size(); } public int getColumnCount() { return columns.length; }
        public String getColumnName(int c) { return columns[c]; }
        public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : c == 2 || c == 3 ? Long.class : c == 4 ? Integer.class : String.class; }
        public boolean isCellEditable(int r, int c) { return c == 0 && !locked() && plan.groups().get(r).eligible(); }
        public Object getValueAt(int r, int c) {
            var g = plan.groups().get(r); return switch (c) { case 0 -> checked.contains(g.instance().root()); case 1 -> g.instance().version();
                case 2 -> g.cacheBytes(); case 3 -> g.totalBytes(); case 4 -> g.files().size(); default -> g.blocked() != null ? g.blocked() : g.files().isEmpty() ? "No rebuildable files found" : "Closed IDE · persistent IDE data preserved"; };
        }
        public void setValueAt(Object value, int r, int c) { if (!isCellEditable(r, c)) return; Path root = plan.groups().get(r).instance().root(); if (Boolean.TRUE.equals(value)) checked.add(root); else checked.remove(root); fireTableCellUpdated(r, c); updateButtons(); }
    }
}
