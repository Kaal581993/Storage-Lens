package dev.kaal.storagelens;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.*;
import java.awt.*;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import static dev.kaal.storagelens.Models.*;
import static dev.kaal.storagelens.Theme.*;

final class AppsPanel extends JPanel {
    private List<InstalledApp> apps = List.of();
    private final AppModel model = new AppModel();
    final JTable table = new JTable(model);
    final JComboBox<String> filter = new JComboBox<>(new String[]{"All installed packages", "Orphan dependencies", "Reviewable installs", "Protected / required"});
    final JTextField search = new JTextField(23);
    private final JLabel count = micro("READING PACKAGE METADATA…");
    private final JTextArea note = new JTextArea("Usage is unknown until you review it. Orphans are dependencies no longer required by an installed package.");
    final JButton uninstall;
    private final TableRowSorter<AppModel> sorter = new TableRowSorter<>(model);
    AppsPanel(Consumer<InstalledApp> review, Runnable refresh) {
        super(new BorderLayout(0, 16)); setOpaque(false);
        JPanel toolbar = transparent(new BorderLayout());
        JPanel filters = transparent(new FlowLayout(FlowLayout.LEFT, 10, 0));
        filter.setPreferredSize(new Dimension(210, 36));
        search.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(LINE), new javax.swing.border.EmptyBorder(9, 12, 9, 12)));
        search.getAccessibleContext().setAccessibleName("Search installed packages");
        filters.add(filter); filters.add(label("Search", 12, Font.PLAIN, MUTED)); filters.add(search);
        toolbar.add(filters, BorderLayout.WEST); toolbar.add(button("Refresh apps", false, refresh), BorderLayout.EAST);
        add(toolbar, BorderLayout.NORTH);
        FilesPanel.configureTable(table); table.setRowSorter(sorter); table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(240);
        table.getColumnModel().getColumn(1).setPreferredWidth(95); table.getColumnModel().getColumn(1).setCellRenderer(new FilesPanel.SizeRenderer());
        table.getColumnModel().getColumn(2).setPreferredWidth(110);
        table.getColumnModel().getColumn(3).setPreferredWidth(265);
        table.getColumnModel().getColumn(4).setPreferredWidth(250);
        JScrollPane scroll = new JScrollPane(table); scroll.setColumnHeaderView(table.getTableHeader()); scroll.setBorder(BorderFactory.createLineBorder(LINE)); add(scroll, BorderLayout.CENTER);
        JPanel footer = transparent(new BorderLayout(20, 8));
        JPanel details = vertical(); details.add(count); details.add(Box.createVerticalStrut(9));
        note.setEditable(false); note.setLineWrap(true); note.setWrapStyleWord(true); note.setOpaque(false); note.setFont(BODY.deriveFont(12f)); note.setForeground(MUTED);
        note.setRows(3); details.add(note); footer.add(details, BorderLayout.CENTER);
        uninstall = button("Review uninstall…", true, () -> { InstalledApp app = selected(); if (app != null) review.accept(app); });
        uninstall.setEnabled(false); JPanel action = transparent(new FlowLayout(FlowLayout.RIGHT, 0, 0)); action.add(uninstall); footer.add(action, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
        filter.addActionListener(e -> applyFilter());
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { applyFilter(); }
            public void removeUpdate(DocumentEvent e) { applyFilter(); }
            public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });
        table.getSelectionModel().addListSelectionListener(e -> { InstalledApp app = selected(); uninstall.setEnabled(app != null && app.removable()); });
    }
    void setInventory(AppInventory inventory) {
        apps = inventory.apps(); model.fireTableDataChanged();
        note.setText(String.join("\n", inventory.warnings())); applyFilter();
    }
    private InstalledApp selected() {
        int row = table.getSelectedRow();
        return row < 0 || row >= table.getRowCount() ? null : apps.get(table.convertRowIndexToModel(row));
    }
    private void applyFilter() {
        String query = search.getText().trim().toLowerCase(Locale.ROOT);
        int mode = filter.getSelectedIndex();
        sorter.setRowFilter(new RowFilter<>() {
            public boolean include(Entry<? extends AppModel, ? extends Integer> row) {
                InstalledApp app = apps.get(row.getIdentifier());
                boolean include = switch (mode) { case 1 -> app.status().startsWith("Orphan"); case 2 -> app.removable(); case 3 -> !app.removable(); default -> true; };
                return include && (query.isEmpty() || (app.name() + " " + app.description() + " " + app.source()).toLowerCase(Locale.ROOT).contains(query));
            }
        });
        count.setText(table.getRowCount() + " VISIBLE / " + apps.size() + " INSTALLED · PACKAGE MANAGER SIZES");
    }
    private final class AppModel extends AbstractTableModel {
        String[] columns = {"PACKAGE / APPLICATION", "SIZE", "SOURCE", "REVIEW STATUS", "DESCRIPTION"};
        public int getRowCount() { return apps.size(); }
        public int getColumnCount() { return columns.length; }
        public String getColumnName(int c) { return columns[c]; }
        public Class<?> getColumnClass(int c) { return c == 1 ? Long.class : String.class; }
        public Object getValueAt(int r, int c) {
            InstalledApp a = apps.get(r);
            return switch (c) { case 0 -> a.name(); case 1 -> a.bytes(); case 2 -> a.source(); case 3 -> a.status(); default -> a.description(); };
        }
    }
}
