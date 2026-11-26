package dev.kaal.storagelens;

import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static dev.kaal.storagelens.Models.*;

@Singleton
public class UninstallService {
    private final AuditLog audit;
    public UninstallService(AuditLog audit) { this.audit = audit; }
    public List<String> command(InstalledApp app) {
        if (!app.removable() || !app.name().matches("[a-zA-Z0-9@_+][a-zA-Z0-9@_.+:-]*"))
            throw new IllegalArgumentException("This entry cannot be uninstalled from Storage Lens");
        if (app.source().equals("pacman")) {
            if (AppInventoryService.protectedPackage(app.name())) throw new IllegalArgumentException("Protected system package");
            // Pacman will show the dependency transaction and ask for confirmation.
            return List.of("/usr/bin/sudo", "/usr/bin/pacman", "-Rns", "--", app.name());
        }
        if (app.source().equals("Flatpak user") || app.source().equals("Flatpak system"))
            return List.of("/usr/bin/flatpak", "uninstall", app.source().endsWith("user") ? "--user" : "--system", "--", app.name());
        throw new IllegalArgumentException("Unsupported package manager");
    }
    public void openTerminal(InstalledApp app) throws IOException {
        List<String> command = command(app);
        List<String> launch = new ArrayList<>();
        if (Files.isExecutable(Path.of("/usr/bin/konsole")))
            launch.addAll(List.of("/usr/bin/konsole", "--separate", "--hold", "-p", "tabtitle=Storage Lens — review uninstall", "-e"));
        else if (Files.isExecutable(Path.of("/usr/bin/xterm"))) launch.addAll(List.of("/usr/bin/xterm", "-hold", "-e"));
        else throw new IOException("No supported terminal. Copy the reviewed command and run it in a terminal.");
        launch.addAll(command);
        audit.append("OPEN UNINSTALL TERMINAL", String.join(" ", command));
        new ProcessBuilder(launch).start();
    }
}
