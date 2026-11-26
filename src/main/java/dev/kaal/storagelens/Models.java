package dev.kaal.storagelens;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class Models {
    private Models() {}

    public enum Kind {
        CACHE("Cache", "Rebuildable cache; close the owning app before cleaning"),
        PARTIAL("Incomplete download", "No changes for at least 7 days; confirm the download is abandoned"),
        OLD_DOWNLOAD("Older download", "Unmodified for 90+ days; age does not prove it is unused"),
        TRASH("Trash", "Already in Trash; permanent removal cannot be undone"),
        LARGE("Large file", "Review this personal file before removing"),
        SYSTEM("System cache", "Read only; use the package manager to maintain system files");
        public final String label;
        public final String explanation;
        Kind(String label, String explanation) { this.label = label; this.explanation = explanation; }
    }

    /** Identity captured during a read-only scan; no file content is read. */
    public record Finding(Path path, long bytes, long allocated, Instant modified, String fileKey,
                          Kind kind, boolean actionable, String note) {
        public Finding(Path path, long bytes, Instant modified, String fileKey, Kind kind, boolean actionable, String note) {
            this(path, bytes, bytes, modified, fileKey, kind, actionable, note);
        }
    }
    public record Folder(Path path, long bytes, long files) {}
    public record Disk(long total, long usable) { public long used() { return total - usable; } }
    public record Progress(long files, long bytes, long skipped, Path current) {}
    public record ScanResult(Path root, Instant finished, long files, long bytes, long allocated, long skipped,
                             boolean cancelled, List<Finding> cleanup, List<Finding> large,
                             List<Folder> folders, Map<Kind, Long> totals, List<String> warnings) {
        public long reviewBytes() { return cleanup.stream().filter(Finding::actionable).mapToLong(Finding::allocated).sum(); }
    }
    public record InstalledApp(String name, String version, long bytes, String source,
                               String status, String description, boolean removable) {}
    public record AppInventory(List<InstalledApp> apps, List<String> warnings) {}
    public record ActionResult(int succeeded, int failed, long bytes, List<String> messages) {}

    public static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KiB", "MiB", "GiB", "TiB", "PiB"};
        double value = bytes;
        int index = -1;
        do { value /= 1024; index++; } while (value >= 1024 && index < units.length - 1);
        return String.format(Locale.ROOT, value >= 100 ? "%.0f %s" : "%.1f %s", value, units[index]);
    }

    public static String display(Path path) {
        String home = System.getProperty("user.home");
        String value = path.toString();
        return value.equals(home) ? "~" : value.startsWith(home + "/") ? "~" + value.substring(home.length()) : value;
    }
    public static String safe(String text) {
        return text == null ? "" : text.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ");
    }
}
