package dev.kaal.storagelens;

import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;
import static dev.kaal.storagelens.Models.*;

@Singleton
public class AppInventoryService {
    private static final Set<String> ESSENTIAL = Set.of("base", "base-devel", "filesystem", "glibc", "bash", "coreutils", "sudo", "pacman", "systemd", "systemd-libs", "util-linux", "util-linux-libs", "dbus", "dbus-broker", "dbus-broker-units", "networkmanager", "openssh", "grub", "efibootmgr", "mkinitcpio", "dracut", "cryptsetup", "btrfs-progs", "linux-firmware", "polkit", "sddm", "plasma-desktop", "plasma-workspace", "kwin", "xorg-server", "xorg-xwayland", "pipewire", "wireplumber", "mesa", "nss", "ca-certificates");
    public static boolean protectedPackage(String name) {
        return ESSENTIAL.contains(name) || List.of("linux", "garuda-", "nvidia", "amd-ucode", "intel-ucode", "grub", "systemd", "pacman", "qt6-", "lib32-").stream().anyMatch(name::startsWith);
    }
    public AppInventory load() {
        List<InstalledApp> apps = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Path database = Path.of("/var/lib/pacman/local");
        if (Files.isDirectory(database)) {
            Set<String> orphans = new HashSet<>();
            try {
                var query = CommandRunner.run(List.of("/usr/bin/pacman", "-Qtdq"), Duration.ofSeconds(25));
                if (query.exit() == 0) orphans.addAll(query.output().lines().toList());
                else if (query.exit() != 1) warnings.add("Orphan dependency query failed; usage remains unknown.");
            } catch (IOException e) { warnings.add("Cannot query orphan dependencies: " + e.getMessage()); }
            try (var entries = Files.list(database)) {
                for (Path entry : entries.toList()) {
                    Path desc = entry.resolve("desc");
                    if (!Files.isRegularFile(desc, LinkOption.NOFOLLOW_LINKS)) continue;
                    try { apps.add(parsePacman(Files.readString(desc), orphans)); }
                    catch (IOException | IllegalArgumentException e) { warnings.add("Could not read package metadata: " + entry.getFileName()); }
                }
            } catch (IOException e) { warnings.add("Cannot read installed package database: " + e.getMessage()); }
        } else warnings.add("Pacman is not available. This build supports Arch/Garuda packages and Flatpak applications.");
        if (Files.isExecutable(Path.of("/usr/bin/flatpak"))) {
            try {
                var query = CommandRunner.run(List.of("/usr/bin/flatpak", "list", "--app", "--columns=application,name,version,size,installation"), Duration.ofSeconds(25));
                if (query.exit() == 0) {
                    for (String row : query.output().lines().toList()) {
                        String[] columns = row.split("\\t", -1);
                        if (columns.length < 5) continue;
                        String source = columns[4].trim().equals("user") ? "Flatpak user" : "Flatpak system";
                        apps.add(new InstalledApp(columns[0].trim(), columns[2].trim(), parseSize(columns[3]), source,
                            "Usage unknown", columns[1].trim(), true));
                    }
                } else warnings.add("Flatpak inventory failed: " + Models.safe(query.output()));
            } catch (IOException e) { warnings.add("Cannot read Flatpak applications: " + e.getMessage()); }
        }
        apps.sort(Comparator.comparingLong(InstalledApp::bytes).reversed());
        warnings.add("Installed size comes from the package manager. Shared files and dependencies mean uninstall savings can differ.");
        warnings.add("Orphan means no installed package requires this dependency. It may still be useful to you. App launch history is not available.");
        return new AppInventory(List.copyOf(apps), List.copyOf(warnings));
    }
    static InstalledApp parsePacman(String text, Set<String> orphans) {
        Map<String, String> fields = new HashMap<>();
        String key = null;
        for (String line : text.lines().toList()) {
            if (line.startsWith("%") && line.endsWith("%")) key = line.substring(1, line.length() - 1);
            else if (key != null && !line.isBlank()) { fields.putIfAbsent(key, line); key = null; }
        }
        String name = fields.getOrDefault("NAME", "");
        if (!name.matches("[a-zA-Z0-9@_+][a-zA-Z0-9@_.+:-]*")) throw new IllegalArgumentException("Invalid package name");
        // ALPM omits REASON for explicit installs; value 1 marks a dependency.
        boolean explicit = fields.getOrDefault("REASON", "0").equals("0");
        boolean orphan = orphans.contains(name);
        boolean essential = protectedPackage(name);
        String status = essential ? "Protected system component" : orphan ? "Orphan dependency · review" : explicit ? "Explicit install · usage unknown" : "Required dependency";
        return new InstalledApp(name, fields.getOrDefault("VERSION", ""), Long.parseLong(fields.getOrDefault("SIZE", "0")),
            "pacman", status, fields.getOrDefault("DESC", ""), !essential && (explicit || orphan));
    }
    static long parseSize(String text) {
        Matcher matcher = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*([KMGT]?i?B)", Pattern.CASE_INSENSITIVE).matcher(text.replace(",", ""));
        if (!matcher.find()) return 0;
        double value = Double.parseDouble(matcher.group(1));
        String unit = matcher.group(2).toUpperCase(Locale.ROOT);
        int power = "KMGT".indexOf(unit.charAt(0)) + 1;
        return (long) (value * Math.pow(unit.contains("I") ? 1024 : 1000, Math.max(0, power)));
    }
}
