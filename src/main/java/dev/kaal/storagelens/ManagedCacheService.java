package dev.kaal.storagelens;

import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Delegates managed caches to their existing native tools; never installs or executes a language runtime. */
@Singleton
public class ManagedCacheService {
    enum UvAction { PRUNE, CLEAN }
    record UvReview(Path path, String identity, long bytes, Path executable, String unavailable) {}
    interface Runner { CommandRunner.Result run(List<String> command, Duration timeout, Map<String, String> environment) throws IOException; }
    private final ScanPolicy policy;
    private final AuditLog audit;
    private final Runner runner;
    public ManagedCacheService(ScanPolicy policy, AuditLog audit) { this(policy, audit, CommandRunner::run); }
    ManagedCacheService(ScanPolicy policy, AuditLog audit, Runner runner) { this.policy = policy; this.audit = audit; this.runner = runner; }
    UvReview inspectUv() throws IOException {
        Path root = policy.home().resolve(".cache/uv");
        Path executable = Path.of("/usr/bin/uv");
        if (!nativeExecutable(executable)) executable = policy.home().resolve(".local/bin/uv");
        if (!Files.isDirectory(root, NOFOLLOW_LINKS) || policy.hasSymlinkAncestor(root)) return new UvReview(root, "", 0, executable, "No real standard uv cache directory found");
        String tag = "Signature: 8a477f597d28d172789f06886806bc55";
        Path marker = root.resolve("CACHEDIR.TAG");
        if (!Files.isRegularFile(marker, NOFOLLOW_LINKS) || Files.size(marker) > 1024 || !Files.readString(marker).startsWith(tag))
            return new UvReview(root, "", 0, executable, "Cache signature unavailable; protected");
        long[] size = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) { if (attrs.isRegularFile()) size[0] += NativeFiles.allocated(path, attrs.size()); return FileVisitResult.CONTINUE; }
        });
        return new UvReview(root, Objects.toString(Files.readAttributes(root, BasicFileAttributes.class, NOFOLLOW_LINKS).fileKey(), ""), size[0], executable,
            nativeExecutable(executable) ? null : "Native uv executable is unavailable");
    }
    synchronized String cleanUv(UvReview reviewed, UvAction action) throws IOException {
        Path expected = policy.home().resolve(".cache/uv");
        if (reviewed.unavailable() != null || !expected.equals(reviewed.path()) || policy.hasSymlinkAncestor(expected)
            || !Set.of(Path.of("/usr/bin/uv"), policy.home().resolve(".local/bin/uv")).contains(reviewed.executable())
            || !nativeExecutable(reviewed.executable()) || reviewed.identity().isBlank()
            || !reviewed.identity().equals(Objects.toString(Files.readAttributes(expected, BasicFileAttributes.class, NOFOLLOW_LINKS).fileKey(), "")))
            throw new IOException("The uv cache or tool changed. Refresh and review again.");
        List<String> command = uvCommand(reviewed, action);
        audit.append("REQUEST UV CACHE " + action, expected.toString());
        long before = Files.getFileStore(expected).getUsableSpace();
        var result = runner.run(command, Duration.ofMinutes(3), Map.of("UV_LOCK_TIMEOUT", "5"));
        if (result.exit() != 0) throw new IOException("uv kept the cache or could not finish: " + Models.safe(result.output()).substring(0, Math.min(1200, result.output().length())));
        long delta = Files.getFileStore(expected).getUsableSpace() - before;
        String logging = "";
        try { audit.append("SUCCESS UV CACHE " + action, expected.toString()); }
        catch (IOException exception) { logging = "\nCleanup completed; completion logging failed."; }
        return Models.safe(result.output()) + "\nFilesystem free-space change: " + (delta < 0 ? "-" : "+") + Models.size(Math.abs(delta))
            + " (also affected by other disk activity).\nReported cache size can exceed physical recovery because of hardlinks, compression and snapshots." + logging;
    }
    static List<String> uvCommand(UvReview review, UvAction action) {
        return List.of(review.executable().toString(), "cache", action == UvAction.PRUNE ? "prune" : "clean", "--cache-dir", review.path().toString(),
            "--offline", "--no-config", "--no-python-downloads", "--color", "never", "--no-progress");
    }
    String packagePreview() throws IOException {
        if (!Files.isExecutable(Path.of("/usr/bin/paccache"))) return "paccache is unavailable; system package caches remain read only";
        var result = runner.run(List.of("/usr/bin/paccache", "--dryrun", "--keep", "3"), Duration.ofSeconds(45), Map.of());
        if (result.exit() != 0) throw new IOException("Package cache preview failed: " + Models.safe(result.output()));
        return Models.safe(result.output());
    }
    static List<String> packageCommand() { return List.of("/usr/bin/sudo", "--", "/usr/bin/paccache", "--remove", "--keep", "3"); }
    void openPackageCleanup() throws IOException {
        if (!Files.isExecutable(Path.of("/usr/bin/paccache")) || !Files.isExecutable(Path.of("/usr/bin/konsole"))) throw new IOException("The package-cache terminal is unavailable");
        audit.append("REQUEST PACKAGE CACHE TERMINAL", String.join(" ", packageCommand()));
        List<String> command = new ArrayList<>(List.of("/usr/bin/konsole", "--hold", "-e")); command.addAll(packageCommand());
        new ProcessBuilder(command).start();
    }
    static boolean nativeExecutable(Path path) {
        if (!Files.isExecutable(path)) return false;
        try (var input = Files.newInputStream(path)) { return Arrays.equals(input.readNBytes(4), new byte[]{0x7f, 'E', 'L', 'F'}); }
        catch (IOException exception) { return false; }
    }
}
