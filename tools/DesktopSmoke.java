package dev.kaal.storagelens.smoke;

import dev.kaal.storagelens.StorageLens;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.nio.file.Path;

/** Opens the real app in an isolated test display; never invokes cleanup actions. */
public final class DesktopSmoke {
    public static void main(String[] args) {
        Path screenshot = Path.of(args[0]);
        StorageLens.main(new String[0]);
        SwingUtilities.invokeLater(() -> {
            Timer check = new Timer(3500, event -> {
                try {
                    JFrame frame = null;
                    for (Frame candidate : Frame.getFrames())
                        if (candidate instanceof JFrame value && value.getTitle().startsWith("Storage Lens") && value.isShowing()) frame = value;
                    if (frame == null) throw new IllegalStateException("No visible Storage Lens window");
                    for (String page : new String[]{"Cleanup", "Large files", "Installed apps", "Overview"}) {
                        JButton navigation = button(frame, page);
                        if (navigation == null) throw new IllegalStateException("Missing navigation: " + page);
                        navigation.doClick();
                    }
                    frame.validate();
                    var capture = new Robot().createScreenCapture(frame.getBounds());
                    ImageIO.write(capture, "png", screenshot.toFile());
                    System.out.println("GUI PASS: real window opened; all four screens navigated; screenshot " + screenshot);
                    System.exit(0);
                } catch (Exception | AWTError failure) { failure.printStackTrace(); System.exit(1); }
            });
            check.setRepeats(false); check.start();
        });
    }
    private static JButton button(Container parent, String text) {
        for (Component component : parent.getComponents()) {
            if (component instanceof JButton value && value.getText().equals(text)) return value;
            if (component instanceof Container nested) {
                JButton found = button(nested, text); if (found != null) return found;
            }
        }
        return null;
    }
}
