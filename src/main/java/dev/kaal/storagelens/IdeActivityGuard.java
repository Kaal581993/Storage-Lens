package dev.kaal.storagelens;

import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

interface IdeIdleCheck { String blocked(Path systemRoot); }

/** Inspects process metadata only; no process is stopped and no arguments are logged. */
@Singleton
public class IdeActivityGuard implements IdeIdleCheck {
    record Activity(long pid, String selector, Path systemRoot) {}
    record View(boolean visible, List<Activity> processes) {}
    private View previous;
    private long checked;

    @Override public synchronized String blocked(Path root) {
        if (previous == null || System.nanoTime() - checked > 250_000_000L) {
            previous = inspect(); checked = System.nanoTime();
        }
        String reason = reason(previous, root); if (reason != null) return reason;
        Path pidFile = root.resolve(".pid");
        try {
            if (Files.exists(pidFile, NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(pidFile, NOFOLLOW_LINKS) || Files.size(pidFile) > 64) return "IDE process marker cannot be verified";
                String value = Files.readString(pidFile).trim();
                if (!value.matches("[0-9]+")) return "IDE process marker is unrecognized; close the IDE and refresh";
                Optional<ProcessHandle> process = ProcessHandle.of(Long.parseLong(value));
                if (process.isPresent() && process.get().isAlive()) {
                    // A later process with a reused PID does not make an old IDE instance active.
                    Optional<Instant> start = process.get().info().startInstant();
                    if (start.isEmpty() || !start.get().isAfter(Files.getLastModifiedTime(pidFile, NOFOLLOW_LINKS).toInstant()))
                        return "Recorded IDE process " + value + " is still alive; close this IDE before cleanup";
                }
            }
        } catch (IOException | RuntimeException exception) { return "IDE activity cannot be verified; close the IDE and refresh from the laptop desktop"; }
        return null;
    }
    static String reason(View view, Path root) {
        if (!view.visible()) return "IDE activity cannot be verified here; open Storage Lens from the laptop desktop";
        String version = root.getFileName().toString().equals("system") ? root.getParent().getFileName().toString().replaceFirst("^\\.", "") : root.getFileName().toString();
        for (Activity process : view.processes()) {
            boolean matches = process.selector().equals(version) || process.systemRoot() != null &&
                (process.systemRoot().equals(root) || process.systemRoot().getFileName().equals(root.getFileName()))
                || process.systemRoot() == null && process.selector().isEmpty();
            if (matches) return "IDE or build process " + process.pid() + " is using this instance; close it and refresh";
        }
        return null;
    }
    private static View inspect() {
        List<Activity> found = new ArrayList<>();
        try {
            String init = Files.readString(Path.of("/proc/1/comm")).trim();
            if (!Set.of("systemd", "init").contains(init) || Files.exists(Path.of("/.dockerenv"))) return new View(false, List.of());
            String user = System.getProperty("user.name");
            try (var processes = ProcessHandle.allProcesses()) {
                for (ProcessHandle process : processes.toList()) {
                    if (process.pid() == ProcessHandle.current().pid() || !process.isAlive()) continue;
                    var info = process.info();
                    String owner = info.user().orElseGet(() -> {
                        try { return Files.getOwner(Path.of("/proc", Long.toString(process.pid()))).getName(); }
                        catch (IOException exception) { return ""; }
                    });
                    if (owner.isEmpty() && process.isAlive()) return new View(false, List.of());
                    if (!owner.equals(user)) continue;
                    String command = info.command().orElse("");
                    Optional<String[]> arguments = info.arguments();
                    if (command.isEmpty()) { if (process.isAlive()) return new View(false, List.of()); else continue; }
                    if (arguments.isEmpty()) {
                        String name = Path.of(command).getFileName().toString();
                        if (name.equals("java") || identify(process.pid(), command, new String[0]) != null) return new View(false, List.of());
                        continue;
                    }
                    Activity activity = identify(process.pid(), command, arguments.get());
                    if (activity != null) found.add(activity);
                }
            }
            return new View(true, List.copyOf(found));
        } catch (IOException | RuntimeException exception) { return new View(false, List.of()); }
    }
    static Activity identify(long pid, String command, String[] arguments) {
        String all = String.join(" ", arguments), executable = Path.of(command).getFileName().toString().toLowerCase(Locale.ROOT);
        boolean ide = all.contains("com.intellij.idea.Main") || all.contains("-Didea.paths.selector=") || all.contains("-Didea.platform.prefix=")
            || all.contains("org.jetbrains.jps.") || all.contains("org.jetbrains.kotlin.daemon.KotlinCompileDaemon")
            || Set.of("idea", "idea64", "clion", "webstorm", "rider", "goland", "rustrover", "datagrip", "phpstorm").contains(executable);
        if (!ide) return null;
        String selector = ""; Path root = null;
        for (String argument : arguments) {
            if (argument.startsWith("-Didea.paths.selector=")) selector = argument.substring("-Didea.paths.selector=".length());
            if (argument.startsWith("-Didea.system.path=")) {
                try { root = Path.of(argument.substring("-Didea.system.path=".length())).toAbsolutePath().normalize(); }
                catch (RuntimeException ignored) { selector = ""; }
            }
        }
        return new Activity(pid, selector, root);
    }
}
