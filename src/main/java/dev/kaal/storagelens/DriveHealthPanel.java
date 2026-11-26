package dev.kaal.storagelens;

import javax.swing.*;
import java.awt.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.BiConsumer;
import static dev.kaal.storagelens.Theme.*;

final class DriveHealthPanel extends JPanel {
    private final DriveHealthService service;
    private final BiConsumer<DriveHealthService.Health, BigDecimal> changed;
    private final JComboBox<DriveHealthService.Device> devices = new JComboBox<>();
    private final JButton refresh, authorize, save;
    private final JTextField rated = new JTextField(10);
    final JLabel life = label("Unavailable", 29, Font.BOLD, TEAL), written = label("Unavailable", 29, Font.BOLD, INK), remaining = label("Rating needed", 29, Font.BOLD, INK);
    private final JLabel wear = label("SMART data not read", 12, Font.PLAIN, MUTED), counter = label("Host write commands unavailable", 12, Font.PLAIN, MUTED);
    private final JTextArea details = OverviewPanel.text("Select a local NVMe drive and refresh its SMART information.");
    private final JProgressBar endurance = new JProgressBar(0, 100);
    private DriveHealthService.Health health;
    private BigDecimal rating;
    private boolean busy, loadingDevices, attempted;

    DriveHealthPanel(DriveHealthService service, BiConsumer<DriveHealthService.Health, BigDecimal> changed) {
        super(new BorderLayout(0, 18)); setOpaque(false); this.service = service; this.changed = changed;
        JPanel controls = transparent(new FlowLayout(FlowLayout.LEFT, 8, 0)); devices.setPreferredSize(new Dimension(310, 36)); controls.add(devices);
        refresh = button("Refresh SMART", false, () -> read(false)); authorize = button("Authorize SMART read…", true, () -> read(true));
        controls.add(refresh); controls.add(authorize); add(controls, BorderLayout.NORTH);
        JPanel body = new ScrollBody(new BorderLayout(0, 18)); JPanel cards = transparent(new GridLayout(1, 3, 14, 0));
        cards.add(card("ENDURANCE REMAINING", life, wear)); cards.add(card("LIFETIME HOST WRITES", written, counter));
        cards.add(card("RATED WRITES REMAINING", remaining, label("TBW minus host writes · estimate", 11, Font.PLAIN, MUTED)));
        JPanel upper = transparent(new BorderLayout(0, 14)); upper.add(cards); endurance.setForeground(TEAL); endurance.setBackground(MINT); endurance.setBorderPainted(false);
        endurance.setPreferredSize(new Dimension(600, 8)); upper.add(endurance, BorderLayout.SOUTH); body.add(upper, BorderLayout.NORTH);
        Card explanation = new Card(new BorderLayout(0, 16)); explanation.add(label("Understand the numbers", 18, Font.BOLD, INK), BorderLayout.NORTH);
        details.setRows(11); details.setFont(BODY); explanation.add(details);
        JPanel rating = transparent(new FlowLayout(FlowLayout.LEFT, 8, 0)); rating.add(label("Manufacturer rated endurance (TBW)", 12, Font.BOLD, INK)); rating.add(rated);
        rated.setToolTipText("Use the rating for this exact model/capacity from its manufacturer's datasheet; leave blank if unverified");
        save = button("Save rating", false, this::saveRating); rating.add(save); explanation.add(rating, BorderLayout.SOUTH); body.add(explanation); add(scrollBody(body));
        devices.addActionListener(event -> { if (!loadingDevices && !busy) read(false); }); updateButtons();
    }
    private Card card(String title, JLabel value, JLabel caption) {
        Card card = new Card(new BorderLayout(0, 14)); card.add(micro(title), BorderLayout.NORTH); card.add(value);
        JTextArea wrapped = OverviewPanel.text(caption.getText()); wrapped.setFont(caption.getFont()); wrapped.setForeground(caption.getForeground()); wrapped.setRows(2);
        caption.addPropertyChangeListener("text", event -> wrapped.setText(caption.getText())); card.add(wrapped, BorderLayout.SOUTH); return card;
    }
    void ensureLoaded() {
        if (attempted || busy) return; attempted = true; busy = true; updateButtons();
        new SwingWorker<List<DriveHealthService.Device>, Void>() {
            protected List<DriveHealthService.Device> doInBackground() throws Exception { return service.devices(); }
            protected void done() {
                try { loadingDevices = true; devices.removeAllItems(); for (var device : get()) devices.addItem(device); }
                catch (Exception exception) { error(exception); }
                finally { loadingDevices = false; busy = false; updateButtons(); }
                if (devices.getItemCount() > 0) read(false); else details.setText("No NVMe controllers were found in this session. Refresh from the laptop desktop to access its hardware.");
            }
        }.execute();
    }
    void setHealth(DriveHealthService.Health value, BigDecimal ratedTbw) {
        attempted = true;
        if (devices.getItemCount() == 0) { loadingDevices = true; devices.addItem(value.device()); loadingDevices = false; }
        health = value; rating = ratedTbw;
        life.setText(value.remainingPercent() == null ? "Unavailable" : value.remainingPercent() + "%");
        wear.setText(value.used() == null ? "SMART access needed" : value.used() + "% endurance used · vendor estimate");
        written.setText(DriveHealthService.tb(value.writtenBytes())); counter.setText(value.writeCommands() == null ? "Host write commands unavailable" : value.writeCommands() + " host write commands");
        BigDecimal left = value.remainingTbw(rating); remaining.setText(left == null ? rating == null ? "Rating needed" : "SMART needed" : left.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + " TB");
        endurance.setValue(value.remainingPercent() == null ? 0 : value.remainingPercent());
        endurance.setForeground(value.remainingPercent() != null && value.remainingPercent() <= 10 ? RED : TEAL);
        rated.setText(rating == null ? "" : rating.toPlainString());
        String telemetry = value.used() == null ? "SMART unavailable: " + value.message() + ". " + (value.needsPermission() ? "Use Authorize SMART read for a read-only hardware request." : "The device may be hidden or unavailable in this session.")
            : "Available spare: " + value.spare() + "% · Critical warning flags: " + value.warning() + " · Temperature: " + (value.temperatureC() == null ? "Unavailable" : value.temperatureC().toPlainString() + " °C");
        details.setText(value.device() + "\nRead: " + value.captured() + "\n\n" + telemetry
            + "\n\nRemaining endurance is 100 minus SMART Percentage Used, clamped at zero. It is a wear estimate, not a prediction of failure."
            + "\nWrite capacity remaining uses the exact model's manufacturer TBW rating minus lifetime host writes. It is a rated write budget, not guaranteed future writes."
            + "\nThe number or size of future write commands cannot be known. Host-write totals use decimal TB and may be rounded by the controller."
            + "\nDeleting cache frees capacity; it does not restore SSD endurance."
            + "\n\nEnter a verified manufacturer TBW rating below to calculate the write budget. Unknown ratings and unavailable SMART values are never guessed.");
        changed.accept(value, rating); updateButtons();
    }
    private void read(boolean authorizeRead) {
        if (busy) return;
        var device = (DriveHealthService.Device) devices.getSelectedItem();
        if (device == null) { attempted = false; ensureLoaded(); return; }
        busy = true; updateButtons(); details.setText(authorizeRead ? "Waiting for system authentication for a read-only NVMe SMART request…" : "Reading NVMe SMART counters…");
        new SwingWorker<DriveHealthService.Health, Void>() {
            private BigDecimal saved; private String ratingProblem;
            protected DriveHealthService.Health doInBackground() throws Exception {
                try { saved = service.ratedTbw(device.model()); } catch (Exception exception) { ratingProblem = Models.safe(exception.getMessage()); }
                return service.read(device, authorizeRead);
            }
            protected void done() {
                try { setHealth(get(), saved); if (ratingProblem != null) details.append("\nSaved rating could not be read: " + ratingProblem); }
                catch (Exception exception) { error(exception); } finally { busy = false; updateButtons(); }
            }
        }.execute();
    }
    private void saveRating() {
        if (health == null || busy) return;
        try {
            BigDecimal value = rated.getText().isBlank() ? null : new BigDecimal(rated.getText().trim());
            service.saveRatedTbw(health.device().model(), value); setHealth(health, value);
        } catch (Exception exception) { details.setText("Rating could not be saved: " + Models.safe(exception.getMessage()) + "\nEnter a positive TBW number for this exact model, or leave it blank to clear the rating."); }
    }
    private void updateButtons() {
        devices.setEnabled(!busy); refresh.setEnabled(!busy); save.setEnabled(!busy && health != null);
        authorize.setEnabled(!busy && health != null && health.needsPermission());
    }
    private void error(Exception exception) {
        Throwable cause = exception.getCause() == null ? exception : exception.getCause();
        var device = (DriveHealthService.Device) devices.getSelectedItem();
        int errorCode = health != null && health.device().equals(device) && health.needsPermission() ? 13 : -1;
        if (device != null) setHealth(new DriveHealthService.Health(device, java.time.Instant.now(), null, null, null, null, null, null, errorCode,
            Models.safe(cause.getMessage())), null);
        else { health = null; life.setText("Unavailable"); written.setText("Unavailable"); remaining.setText("Unavailable"); endurance.setValue(0); details.setText(Models.safe(cause.getMessage())); }
    }
}
