package dev.kaal.storagelens;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import static dev.kaal.storagelens.Models.*;
import static dev.kaal.storagelens.Theme.*;

final class FilesPanel extends JPanel {
    private final FileModel model = new FileModel();
    final JTable table = new JTable(model);
    private final TableRowSorter<FileModel> sorter = new TableRowSorter<>(model);
    final JComboBox<String> category = new JComboBox<>();
    final JTextField search = new JTextField(22);
    final JLabel selection = label("No files selected", 12, Font.PLAIN, MUTED);
    final JLabel count = micro("RUN A SCAN TO SEE FILES");
    final JButton trash, delete;
    final JButton bulkCaches;
    private final Consumer<java.nio.file.Path> reveal;
    private boolean busy;
    private Runnable managedCaches = () -> {};

    FilesPanel(boolean cleanup, Consumer<List<Finding>> onTrash, Consumer<List<Finding>> onDelete, Consumer<java.nio.file.Path> reveal) {
        super(new BorderLayout(0, 16)); setOpaque(false); this.reveal = reveal;
        JPanel toolbar = transparent(new BorderLayout(0, 10));
        JPanel filters = transparent(new FlowLayout(FlowLayout.LEFT, 10, 0));
        category.addItem(cleanup ? "All candidates" : "All large files");
        for (Kind kind : Kind.values()) category.addItem(kind.label);
        category.setPreferredSize(new Dimension(195, 36));
        search.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LINE), new javax.swing.border.EmptyBorder(9, 12, 9, 12)));
        search.setToolTipText("Search file name, path, category or review note");
        search.getAccessibleContext().setAccessibleName("Search files");
        filters.add(category); filters.add(label("Search", 12, Font.PLAIN, MUTED)); filters.add(search);
        toolbar.add(filters, BorderLayout.NORTH);
        JButton select = button("Select visible", false, this::selectVisible);
        JButton clear = button("Clear", false, () -> { model.checked.clear(); model.fireTableDataChanged(); selectionChanged(); });
        JPanel selectButtons = transparent(new FlowLayout(FlowLayout.LEFT, 8, 0)); selectButtons.add(select); selectButtons.add(clear);
        bulkCaches = cleanup ? button("Review cache cleanup…", true, () -> onDelete.accept(cacheBatch())) : null;
        if (bulkCaches != null) {
            bulkCaches.setToolTipText("Review all eligible caches retained from this scan, including files hidden by filters");
            selectButtons.add(bulkCaches);
            selectButtons.add(button("JetBrains / uv cleanup…", false, () -> managedCaches.run()));
        }
        toolbar.add(selectButtons, BorderLayout.SOUTH);
        add(toolbar, BorderLayout.NORTH);
        configureTable(table); table.setRowSorter(sorter); table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.getColumnModel().getColumn(0).setMaxWidth(42);
        table.getColumnModel().getColumn(1).setPreferredWidth(340);
        table.getColumnModel().getColumn(2).setPreferredWidth(94);
        table.getColumnModel().getColumn(2).setCellRenderer(new SizeRenderer());
        table.getColumnModel().getColumn(3).setPreferredWidth(94);
        table.getColumnModel().getColumn(3).setCellRenderer(new SizeRenderer());
        table.getColumnModel().getColumn(4).setPreferredWidth(145);
        table.getColumnModel().getColumn(5).setPreferredWidth(100);
        table.getColumnModel().getColumn(6).setPreferredWidth(240);
        JScrollPane scroll = new JScrollPane(table); scroll.setBorder(BorderFactory.createLineBorder(LINE));
        scroll.setColumnHeaderView(table.getTableHeader());
        scroll.getViewport().setBackground(PAPER); add(scroll, BorderLayout.CENTER);
        JPanel footer = transparent(new BorderLayout());
        JPanel footerText = vertical(); footerText.add(selection); footerText.add(Box.createVerticalStrut(5)); footerText.add(count);
        footer.add(footerText, BorderLayout.WEST);
        JPanel actions = transparent(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.add(button("Show in folder", false, this::revealSelection));
        trash = button("Move to Trash…", false, () -> onTrash.accept(selected()));
        delete = button("Delete permanently…", true, () -> onDelete.accept(selected()));
        actions.add(trash); actions.add(delete); footer.add(actions, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
        category.addActionListener(e -> filter());
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filter(); }
            public void removeUpdate(DocumentEvent e) { filter(); }
            public void changedUpdate(DocumentEvent e) { filter(); }
        });
        model.addTableModelListener(e -> selectionChanged());
        table.addMouseListener(new MouseAdapter() { @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) revealSelection(); } });
        selectionChanged();
    }
    void setFiles(List<Finding> files) { model.rows = files; model.checked.clear(); model.fireTableDataChanged(); filter(); }
    void onManagedCaches(Runnable action) { managedCaches = Objects.requireNonNull(action); }
    List<Finding> files() { return model.rows; }
    List<Finding> cacheBatch() { return model.rows.stream().filter(f -> f.kind() == Kind.CACHE && f.actionable()).toList(); }
    List<Finding> selected() { return model.rows.stream().filter(f -> model.checked.contains(f.path())).toList(); }
    void setBusy(boolean value) { busy = value; selectionChanged(); }
    void choose(Kind kind) { category.setSelectedItem(kind.label); }
    void selectVisible() {
        if (busy) return;
        for (int row = 0; row < table.getRowCount(); row++) {
            Finding finding = model.rows.get(table.convertRowIndexToModel(row));
            if (finding.actionable()) model.checked.add(finding.path());
        }
        model.fireTableDataChanged(); selectionChanged();
    }
    private void filter() {
        String text = search.getText().trim().toLowerCase(Locale.ROOT);
        int type = category.getSelectedIndex();
        sorter.setRowFilter(new RowFilter<>() {
            @Override public boolean include(Entry<? extends FileModel, ? extends Integer> entry) {
                Finding f = model.rows.get(entry.getIdentifier());
                return (type == 0 || f.kind() == Kind.values()[type - 1])
                    && (text.isEmpty() || (f.path() + " " + f.note() + " " + f.kind().label).toLowerCase(Locale.ROOT).contains(text));
            }
        });
        count.setText(table.getRowCount() + " VISIBLE / " + model.rows.size() + " FILES · HIDDEN CHECKED FILES INCLUDED");
    }
    private void selectionChanged() {
        List<Finding> selected = selected();
        selection.setText(selected.isEmpty() ? "No files selected" : selected.size() + " selected · " + Models.size(selected.stream().mapToLong(Finding::allocated).sum()) + " on disk");
        trash.setEnabled(!busy && selected.stream().anyMatch(f -> f.kind() != Kind.TRASH));
        delete.setEnabled(!busy && !selected.isEmpty());
        if (bulkCaches != null) bulkCaches.setEnabled(!busy && !cacheBatch().isEmpty());
    }
    private void revealSelection() {
        int row = table.getSelectedRow();
        if (row >= 0) reveal.accept(model.rows.get(table.convertRowIndexToModel(row)).path().getParent());
    }
    static void configureTable(JTable table) {
        table.setRowHeight(43); table.setShowVerticalLines(false); table.setShowHorizontalLines(true);
        table.setIntercellSpacing(new Dimension(12, 1)); table.setFillsViewportHeight(true);
        table.getTableHeader().setBackground(BG); table.getTableHeader().setForeground(MUTED);
        table.getTableHeader().setFont(BODY.deriveFont(Font.BOLD, 11));
        table.getTableHeader().setPreferredSize(new Dimension(0, 38));
        table.getTableHeader().setReorderingAllowed(false);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        DefaultTableCellRenderer text = new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int col) {
                super.getTableCellRendererComponent(t, Models.safe(Objects.toString(value, "")), selected, focus, row, col);
                putClientProperty("html.disable", Boolean.TRUE); setToolTipText(Models.safe(Objects.toString(value, ""))); return this;
            }
        };
        table.setDefaultRenderer(Object.class, text);
    }
    static final class SizeRenderer extends DefaultTableCellRenderer {
        SizeRenderer() { setHorizontalAlignment(SwingConstants.RIGHT); setFont(MONO); }
        @Override protected void setValue(Object value) { setText(value instanceof Number n && n.longValue() >= 0 ? Models.size(n.longValue()) : "Unknown"); }
    }
    private final class FileModel extends AbstractTableModel {
        List<Finding> rows = List.of();
        Set<java.nio.file.Path> checked = new HashSet<>();
        final String[] columns = {"", "FILE / PATH", "ON DISK", "FILE SIZE", "CATEGORY", "MODIFIED", "REVIEW NOTE"};
        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return columns.length; }
        public String getColumnName(int col) { return columns[col]; }
        public Class<?> getColumnClass(int col) { return col == 0 ? Boolean.class : col == 2 || col == 3 ? Long.class : String.class; }
        public boolean isCellEditable(int row, int col) { return col == 0 && !busy && rows.get(row).actionable(); }
        public Object getValueAt(int row, int col) {
            Finding f = rows.get(row);
            return switch (col) {
                case 0 -> checked.contains(f.path()); case 1 -> display(f.path()); case 2 -> f.allocated(); case 3 -> f.bytes();
                case 4 -> f.kind().label; case 5 -> DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault()).format(f.modified());
                default -> (f.actionable() ? "" : "Read only · ") + f.note();
            };
        }
        public void setValueAt(Object value, int row, int col) {
            if (!isCellEditable(row, col)) return;
            if (Boolean.TRUE.equals(value)) checked.add(rows.get(row).path()); else checked.remove(rows.get(row).path());
            fireTableCellUpdated(row, col);
        }
    }
}
