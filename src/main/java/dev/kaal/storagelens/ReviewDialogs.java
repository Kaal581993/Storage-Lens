package dev.kaal.storagelens;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import static dev.kaal.storagelens.Models.*;
import static dev.kaal.storagelens.Theme.*;

final class ReviewDialogs {
    private ReviewDialogs() {}
    static boolean typed(Component owner, String title, String detail, String phrase) {
        JPanel content = transparent(new BorderLayout(0, 12)); JTextArea text = new JTextArea(detail);
        text.setEditable(false); text.setLineWrap(true); text.setWrapStyleWord(true); text.setFont(BODY);
        JScrollPane scroll = new JScrollPane(text); scroll.setPreferredSize(new Dimension(760, 340)); content.add(scroll);
        JPanel entry = transparent(new BorderLayout(0, 8)); entry.add(label("Type " + phrase + " to continue", 13, Font.BOLD, RED), BorderLayout.NORTH);
        JTextField typed = new JTextField(); entry.add(typed); content.add(entry, BorderLayout.SOUTH);
        int choice = JOptionPane.showOptionDialog(owner, content, title, JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
            null, new Object[]{"Apply reviewed action", "Cancel"}, "Cancel");
        return choice == 0 && typed.getText().equals(phrase);
    }
    static boolean cleanup(Component owner, List<Finding> selected, CleanupService.Mode mode) {
        if (selected.isEmpty()) return false;
        boolean permanent = mode == CleanupService.Mode.DELETE;
        long bytes = selected.stream().mapToLong(Finding::allocated).sum();
        JPanel panel = transparent(new BorderLayout(0, 14));
        String summary = selected.size() + " selected files · " + Models.size(bytes) + " allocated on disk\n\n"
            + (permanent ? "Permanent deletion cannot be undone. Close apps and confirm that these files are no longer needed.\nSnapshots or compression can affect the space recovered."
                : "These files will move to the desktop Trash, where you can restore them.\nMoving to Trash does not free disk space; empty it later after review.");
        panel.add(OverviewPanel.text(summary), BorderLayout.NORTH);
        JTextArea paths = new JTextArea(); paths.setEditable(false); paths.setFont(MONO);
        paths.setText(String.join("\n", selected.stream().map(f -> Models.size(f.allocated()) + " on disk / " + Models.size(f.bytes()) + " file size  " + Models.safe(f.path().toString()) + "\n    " + f.note()).toList()));
        paths.setCaretPosition(0); JScrollPane scroll = new JScrollPane(paths); scroll.setPreferredSize(new Dimension(780, 330)); panel.add(scroll, BorderLayout.CENTER);
        JTextField confirmation = new JTextField();
        if (permanent) {
            JPanel bottom = transparent(new BorderLayout(0, 8));
            bottom.add(label("Type DELETE to confirm permanent removal of all listed files", 13, Font.BOLD, RED), BorderLayout.NORTH);
            bottom.add(confirmation, BorderLayout.CENTER); panel.add(bottom, BorderLayout.SOUTH);
        }
        Object[] options = {permanent ? "Delete permanently" : "Move to Trash", "Cancel"};
        int choice = JOptionPane.showOptionDialog(owner, panel, permanent ? "Review permanent deletion" : "Review move to Trash",
            JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
        if (choice != 0) return false;
        if (permanent && !confirmation.getText().equals("DELETE")) {
            JOptionPane.showMessageDialog(owner, "Confirmation did not match. No files were changed."); return false;
        }
        return true;
    }
    static boolean uninstall(Component owner, InstalledApp app, List<String> command) {
        JPanel panel = transparent(new BorderLayout(0, 16));
        JTextArea summary = OverviewPanel.text(app.name() + "  " + app.version() + "\n" + Models.size(app.bytes()) + " installed · " + app.status()
            + "\n\n" + app.description() + "\n\nA terminal will open. Review the complete dependency transaction there before answering the package manager's confirmation."
            + "\nRemoving a program does not prove its user data or caches were removed. Save your work and close the application first.");
        summary.setPreferredSize(new Dimension(690, 210)); panel.add(summary, BorderLayout.NORTH);
        JTextArea cmd = new JTextArea(String.join(" ", command)); cmd.setEditable(false); cmd.setFont(MONO); cmd.setRows(2); cmd.setLineWrap(true); panel.add(new JScrollPane(cmd), BorderLayout.CENTER);
        JPanel bottom = transparent(new BorderLayout(0, 8));
        bottom.add(label("Type the package / application ID to continue", 13, Font.BOLD, INK), BorderLayout.NORTH);
        JTextField confirmation = new JTextField(); bottom.add(confirmation, BorderLayout.CENTER); panel.add(bottom, BorderLayout.SOUTH);
        int choice = JOptionPane.showOptionDialog(owner, panel, "Review uninstall", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
            null, new Object[]{"Open uninstall terminal", "Cancel"}, "Cancel");
        return choice == 0 && confirmation.getText().equals(app.name());
    }
}
