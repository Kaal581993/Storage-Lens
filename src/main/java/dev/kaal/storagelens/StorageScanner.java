package dev.kaal.storagelens;

import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import static dev.kaal.storagelens.Models.*;

@Singleton
public class StorageScanner {
    public static final int CLEANUP_LIMIT = 30_000;
    public static final int LARGE_LIMIT = 500;
    private final ScanPolicy policy;
    public StorageScanner(ScanPolicy policy) { this.policy = policy; }

    public Disk disk(Path root) throws IOException {
        FileStore store = Files.getFileStore(root);
        return new Disk(store.getTotalSpace(), store.getUsableSpace());
    }
    public ScanResult scan(Path requested, AtomicBoolean cancel, Consumer<Progress> progress) throws IOException {
        Path root = requested.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || policy.hasSymlinkAncestor(root))
            throw new IOException("Choose a real, readable directory rather than a symbolic link.");
        if (policy.excluded(root)) throw new IOException("This directory is protected or excluded from storage scanning.");
        return new Session(root, cancel, progress).run();
    }
    private final class Session extends SimpleFileVisitor<Path> {
        private final Path root;
        private final AtomicBoolean cancel;
        private final Consumer<Progress> progress;
        private final Instant now = Instant.now();
        private final PriorityQueue<Finding> cleanup = new PriorityQueue<>(Comparator.comparingLong(Finding::allocated));
        private final PriorityQueue<Finding> large = new PriorityQueue<>(Comparator.comparingLong(Finding::allocated));
        private final Map<Path, long[]> folders = new HashMap<>();
        private final EnumMap<Kind, Long> totals = new EnumMap<>(Kind.class);
        private final List<String> warnings = new ArrayList<>();
        private long files, bytes, allocated, skipped, candidates, lastUpdate;
        Session(Path root, AtomicBoolean cancel, Consumer<Progress> progress) {
            this.root = root; this.cancel = cancel; this.progress = progress;
        }
        ScanResult run() throws IOException {
            // No FOLLOW_LINKS: a scan never crosses a symbolic link.
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 256, this);
            progress.accept(new Progress(files, allocated, skipped, root));
            if (candidates > CLEANUP_LIMIT) warnings.add("Showing the largest " + CLEANUP_LIMIT + " of " + candidates + " cleanup candidates. Scan a smaller directory for the rest.");
            warnings.add(NativeFiles.available() ? "Disk sizes use allocated filesystem blocks and account for sparse files. Hard links, shared extents, compression and snapshots can still change space recovered."
                : "Allocated-size measurement is unavailable; sizes fall back to logical bytes. Rebuild with ./build.sh for the native size reader.");
            if (skipped > 0) warnings.add(skipped + " entries skipped: protected paths, links, inaccessible directories or virtual filesystems.");
            var folderList = folders.entrySet().stream().map(e -> new Folder(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .sorted(Comparator.comparingLong(Folder::bytes).reversed()).toList();
            return new ScanResult(root, Instant.now(), files, bytes, allocated, skipped, cancel.get(),
                sorted(cleanup), sorted(large), folderList, Map.copyOf(totals), List.copyOf(warnings));
        }
        private List<Finding> sorted(Collection<Finding> findings) {
            return findings.stream().sorted(Comparator.comparingLong(Finding::allocated).reversed().thenComparing(f -> f.path().toString())).toList();
        }
        @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
            if (cancel.get() || Thread.currentThread().isInterrupted()) { cancel.set(true); return FileVisitResult.TERMINATE; }
            if (policy.excluded(dir) || dir.getFileName() != null && dir.getFileName().toString().equals(".snapshots")) {
                skipped++; return FileVisitResult.SKIP_SUBTREE;
            }
            update(dir);
            return FileVisitResult.CONTINUE;
        }
        @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            if (cancel.get() || Thread.currentThread().isInterrupted()) { cancel.set(true); return FileVisitResult.TERMINATE; }
            if (!attributes.isRegularFile() || policy.excluded(file)) { skipped++; return FileVisitResult.CONTINUE; }
            long size = attributes.size();
            long diskSize = NativeFiles.allocated(file, size);
            files++; bytes += size; allocated += diskSize;
            Path relative = root.relativize(file);
            Path folder = relative.getNameCount() > 1 ? root.resolve(relative.getName(0)) : root;
            long[] aggregate = folders.computeIfAbsent(folder, p -> new long[2]);
            aggregate[0] += diskSize; aggregate[1]++;
            Kind kind = policy.classify(file, attributes.lastModifiedTime().toInstant(), now);
            Finding finding = null;
            if (kind != Kind.LARGE) {
                finding = finding(file, attributes, diskSize, kind);
                candidates++;
                totals.merge(kind, diskSize, Long::sum);
                retain(cleanup, finding, CLEANUP_LIMIT);
            }
            if (size >= 25L * 1024 * 1024 && (large.size() < LARGE_LIMIT || diskSize > large.peek().allocated()))
                retain(large, finding != null ? finding : finding(file, attributes, diskSize, Kind.LARGE), LARGE_LIMIT);
            update(file);
            return FileVisitResult.CONTINUE;
        }
        private Finding finding(Path file, BasicFileAttributes attributes, long diskSize, Kind kind) {
            return new Finding(file, attributes.size(), diskSize, attributes.lastModifiedTime().toInstant(),
                Objects.toString(attributes.fileKey(), ""), kind, policy.canAct(file), policy.note(file, kind));
        }
        private void retain(PriorityQueue<Finding> queue, Finding item, int limit) {
            if (queue.size() < limit) queue.add(item);
            else if (item.allocated() > queue.peek().allocated()) { queue.poll(); queue.add(item); }
        }
        @Override public FileVisitResult visitFileFailed(Path file, IOException exception) {
            skipped++;
            update(file);
            return cancel.get() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
        }
        @Override public FileVisitResult postVisitDirectory(Path dir, IOException exception) {
            if (exception != null) skipped++;
            return cancel.get() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
        }
        private void update(Path current) {
            long tick = System.nanoTime();
            if (tick - lastUpdate >= 180_000_000L) {
                progress.accept(new Progress(files, allocated, skipped, current)); lastUpdate = tick;
            }
        }
    }
}
