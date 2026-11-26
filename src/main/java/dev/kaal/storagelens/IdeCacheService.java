package dev.kaal.storagelens;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import static dev.kaal.storagelens.Models.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Complete per-instance reviews; no largest-file truncation and no recursive directory deletion. */
@Singleton
public class IdeCacheService {
    static final int REVIEW_FILE_LIMIT = 1_000_000;
    record Directory(Path path, String key, Instant modified) {}
    record Group(IdeCacheLayout.Instance instance, List<Finding> files, List<Directory> directories,
                 long totalBytes, String blocked) {
        long cacheBytes() { return files.stream().mapToLong(Finding::allocated).sum(); }
        boolean eligible() { return blocked == null && !files.isEmpty(); }
    }
    record Plan(Instant captured, List<Group> groups, boolean cancelled) {
        long cacheBytes() { return groups.stream().mapToLong(Group::cacheBytes).sum(); }
        long totalBytes() { return groups.stream().mapToLong(Group::totalBytes).sum(); }
    }
    private final ScanPolicy policy;
    private final CleanupService cleaner;
    private final AuditLog audit;
    private final IdeIdleCheck idle;
    @Inject public IdeCacheService(ScanPolicy policy, CleanupService cleaner, AuditLog audit, IdeActivityGuard idle) {
        this(policy, cleaner, audit, (IdeIdleCheck) idle);
    }
    IdeCacheService(ScanPolicy policy, CleanupService cleaner, AuditLog audit, IdeIdleCheck idle) {
        this.policy = policy; this.cleaner = cleaner; this.audit = audit; this.idle = idle;
    }
    Plan scan(AtomicBoolean cancel, Consumer<String> progress) throws IOException {
        List<Group> groups = new ArrayList<>();
        for (var instance : policy.ideCaches.discover()) {
            if (cancel.get()) break;
            List<Finding> files = new ArrayList<>(); List<Directory> directories = new ArrayList<>();
            long[] total = {0}, visited = {0}; String[] problem = {null};
            if (policy.hasSymlinkAncestor(instance.root())) { groups.add(new Group(instance, List.of(), List.of(), 0, "Linked IDE directory; protected")); continue; }
            try {
                Files.walkFileTree(instance.root(), EnumSet.noneOf(FileVisitOption.class), 256, new SimpleFileVisitor<>() {
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (cancel.get()) return FileVisitResult.TERMINATE;
                        if (dir.equals(instance.root()) || policy.ideCaches.cacheDirectory(dir))
                            directories.add(new Directory(dir, Objects.toString(attrs.fileKey(), ""), attrs.lastModifiedTime().toInstant()));
                        return FileVisitResult.CONTINUE;
                    }
                    public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                        if (cancel.get()) return FileVisitResult.TERMINATE;
                        if (attrs.isRegularFile()) {
                            long allocated = NativeFiles.allocated(path, attrs.size()); total[0] += allocated;
                            if (policy.ideCaches.cacheFile(path) && policy.canAct(path)) {
                                Object links = Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS);
                                if (!(links instanceof Number n) || n.longValue() != 1) { problem[0] = "Linked cache entries detected; kept for manual review"; }
                                else if (files.size() >= REVIEW_FILE_LIMIT) { problem[0] = "Review exceeds one million files; nothing in this instance can be bulk deleted"; return FileVisitResult.TERMINATE; }
                                else files.add(new Finding(path, attrs.size(), allocated, attrs.lastModifiedTime().toInstant(),
                                    Objects.toString(attrs.fileKey(), ""), Kind.CACHE, true, "Rebuildable IDE index/build cache; reindexing or rebuilding follows cleanup"));
                            }
                        } else if (policy.ideCaches.cacheFile(path)) problem[0] = "Links or special files found inside cache areas; protected";
                        if (++visited[0] % 500 == 0) progress.accept(instance.version() + " · " + visited[0] + " files measured");
                        return FileVisitResult.CONTINUE;
                    }
                    public FileVisitResult visitFileFailed(Path path, IOException exception) {
                        if (policy.ideCaches.cacheFile(path) || policy.ideCaches.cacheDirectory(path)) problem[0] = "Some cache entries cannot be inspected; protected";
                        return FileVisitResult.CONTINUE;
                    }
                    public FileVisitResult postVisitDirectory(Path dir, IOException exception) {
                        if (exception != null) problem[0] = "Directory inspection incomplete; protected";
                        return cancel.get() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException exception) { problem[0] = "Incomplete inspection: " + Models.safe(exception.getMessage()); }
            String activity = idle.blocked(instance.root());
            String blocked = cancel.get() ? "Scan stopped; refresh before cleanup" : problem[0] != null ? problem[0] : activity;
            groups.add(new Group(instance, List.copyOf(files), List.copyOf(directories), total[0], blocked));
        }
        return new Plan(Instant.now(), List.copyOf(groups), cancel.get());
    }
    synchronized ActionResult apply(Plan reviewed, List<Path> roots, AtomicBoolean cancel, Consumer<String> progress) throws IOException {
        if (reviewed.cancelled()) throw new IOException("The scan was stopped. Refresh before cleanup.");
        var chosen = new LinkedHashSet<>(roots); List<Group> groups = reviewed.groups().stream().filter(g -> chosen.remove(g.instance().root())).toList();
        if (!chosen.isEmpty() || groups.isEmpty()) throw new IOException("Selection includes an unreviewed IDE instance");
        // Validate every selected instance before the first mutation.
        for (Group group : groups) {
            if (!group.eligible()) throw new IOException("Protected IDE instance: " + group.instance().version());
            if (!group.instance().equals(policy.ideCaches.instance(group.instance().root().resolve("index/probe")))) throw new IOException("Unrecognized IDE root");
            requireIdle(group);
            for (Directory directory : group.directories()) {
                if (cancel.get()) return new ActionResult(0, 0, 0, List.of("Stopped before deletion"));
                var now = Files.readAttributes(directory.path(), BasicFileAttributes.class, NOFOLLOW_LINKS);
                if (!now.isDirectory() || directory.key().isEmpty() || !directory.key().equals(Objects.toString(now.fileKey(), ""))
                    || !directory.modified().equals(now.lastModifiedTime().toInstant()) || policy.hasSymlinkAncestor(directory.path()))
                    throw new IOException("Cache directory changed. Refresh before deleting: " + Models.safe(directory.path().toString()));
            }
            int checked = 0;
            for (Finding file : group.files()) {
                if (cancel.get()) return new ActionResult(0, 0, 0, List.of("Stopped before deletion"));
                if (!file.path().startsWith(group.instance().root()) || !policy.ideCaches.cacheFile(file.path())) throw new IOException("File is outside the reviewed IDE cache areas");
                cleaner.validate(file); if (++checked % 500 == 0) progress.accept("Rechecking " + group.instance().version() + " · " + checked + " files");
            }
        }
        audit.append("REQUEST IDE BULK", groups.stream().map(g -> g.instance().root().toString()).reduce((a, b) -> a + " | " + b).orElse(""));
        int succeeded = 0, failed = 0; long bytes = 0; List<String> messages = new ArrayList<>();
        outer: for (Group group : groups) {
            for (int offset = 0; offset < group.files().size(); offset += 128) {
                if (cancel.get()) { messages.add("Stopped. Completed files were removed; remaining files were kept."); break outer; }
                try { requireIdle(group); }
                catch (IOException exception) { messages.add(exception.getMessage() + ". Remaining files were kept."); break outer; }
                var batch = group.files().subList(offset, Math.min(offset + 128, group.files().size()));
                ActionResult result = cleaner.apply(batch, CleanupService.Mode.DELETE, update -> {});
                succeeded += result.succeeded(); failed += result.failed(); bytes += result.bytes();
                progress.accept(group.instance().version() + " · " + succeeded + " cache files removed");
                if (result.failed() > 0 || result.succeeded() != batch.size()) {
                    messages.addAll(result.messages().stream().filter(s -> s.startsWith("SKIPPED") || s.contains("audit")).limit(10).toList());
                    messages.add("Cleanup stopped after a changed, active or unlogged item. Refresh before retrying."); break outer;
                }
            }
            messages.add(group.instance().version() + ": reviewed index/build-cache files removed; persistent IDE data retained");
        }
        messages.add("Caches will be rebuilt when projects reopen. Keep the IDEs closed until cleanup finishes.");
        return new ActionResult(succeeded, failed, bytes, List.copyOf(messages));
    }
    private void requireIdle(Group group) throws IOException {
        String blocked = idle.blocked(group.instance().root()); if (blocked != null) throw new IOException(blocked);
    }
    static void export(Path destination, Plan plan) throws IOException {
        try (var out = Files.newBufferedWriter(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            out.write("instance,path,allocated_bytes,logical_bytes,modified,reason\n");
            for (Group group : plan.groups()) for (Finding file : group.files()) {
                String[] cells = {group.instance().version(), file.path().toString(), Long.toString(file.allocated()), Long.toString(file.bytes()),
                    file.modified().toString(), group.blocked() == null ? file.note() : group.blocked()};
                out.write(String.join(",", Arrays.stream(cells).map(ReportWriter::cell).toList()) + "\n");
            }
        }
    }
}
