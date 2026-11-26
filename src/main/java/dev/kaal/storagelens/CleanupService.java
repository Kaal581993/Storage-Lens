package dev.kaal.storagelens;

import jakarta.inject.Singleton;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import static dev.kaal.storagelens.Models.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Operates only on selected regular files, using directory handles that do not follow links. */
@Singleton
public class CleanupService {
    public enum Mode { TRASH, DELETE }
    private final ScanPolicy policy;
    private final AuditLog audit;
    private final IdeIdleCheck ideIdle;
    @Inject public CleanupService(ScanPolicy policy, AuditLog audit, IdeActivityGuard idle) { this(policy, audit, (IdeIdleCheck) idle); }
    public CleanupService(ScanPolicy policy, AuditLog audit) { this(policy, audit, new IdeActivityGuard()); }
    CleanupService(ScanPolicy policy, AuditLog audit, IdeIdleCheck idle) { this.policy = policy; this.audit = audit; this.ideIdle = idle; }

    public ActionResult apply(List<Finding> selected, Mode mode, Consumer<String> update) {
        int succeeded = 0, failed = 0;
        long bytes = 0;
        List<String> messages = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        for (Finding finding : selected) {
            if (!seen.add(finding.path())) continue;
            try {
                // Audit must be writable before any change is allowed.
                audit.append("REQUEST " + mode, finding.path().toString());
                act(finding, mode);
                succeeded++; bytes += finding.allocated();
                String message = mode + " " + Models.safe(finding.path().toString());
                messages.add(message); update.accept(message);
                try { audit.append("SUCCESS " + mode, finding.path().toString()); }
                catch (IOException e) { messages.add("Action completed; completion audit could not be written: " + e.getMessage()); break; }
            } catch (Exception exception) {
                failed++;
                String message = "SKIPPED " + finding.path() + " — " + exception.getMessage();
                messages.add(message); update.accept(Models.safe(message));
                try { audit.append("SKIPPED", message); } catch (IOException ignored) {}
            }
        }
        return new ActionResult(succeeded, failed, bytes, List.copyOf(messages));
    }

    private void act(Finding finding, Mode mode) throws IOException {
        Path file = finding.path().toAbsolutePath().normalize();
        verifyScope(finding);
        if (mode == Mode.TRASH && file.startsWith(policy.trash())) throw new IOException("This file is already in Trash");
        try (var parent = openSecure(file.getParent())) {
            verify(parent, file.getFileName(), finding);
            if (mode == Mode.DELETE) parent.deleteFile(file.getFileName());
            else moveToTrash(parent, file, finding);
        }
    }
    void validate(Finding finding) throws IOException {
        verifyScope(finding);
        try (var parent = openSecure(finding.path().getParent())) { verify(parent, finding.path().getFileName(), finding); }
    }
    private void verifyScope(Finding finding) throws IOException {
        Path file = finding.path().toAbsolutePath().normalize();
        if (!finding.actionable() || !policy.canAct(file)) throw new IOException("Protected location or read-only finding");
        var instance = policy.ideCaches.instance(file);
        if (instance != null) {
            String blocked = ideIdle.blocked(instance.root());
            if (blocked != null) throw new IOException(blocked);
        }
    }
    private void verify(SecureDirectoryStream<Path> parent, Path name, Finding finding) throws IOException {
        BasicFileAttributes current = parent.getFileAttributeView(name, BasicFileAttributeView.class, NOFOLLOW_LINKS).readAttributes();
        if (!current.isRegularFile() || current.size() != finding.bytes()
            || !current.lastModifiedTime().toInstant().equals(finding.modified())
            || finding.fileKey().isEmpty() || !Objects.toString(current.fileKey(), "").equals(finding.fileKey()))
            throw new IOException("File changed since the scan; rescan before cleaning");
        // Conservative: do not promise reclaimed space from another hard-linked name.
        Object links = Files.getAttribute(finding.path(), "unix:nlink", NOFOLLOW_LINKS);
        if (links instanceof Number count && count.longValue() > 1) throw new IOException("Hard-linked file: kept for manual review");
    }
    private void moveToTrash(SecureDirectoryStream<Path> source, Path original, Finding finding) throws IOException {
        Path trash = policy.trash();
        if (policy.hasSymlinkAncestor(trash)) throw new IOException("Trash location contains a symbolic link");
        Files.createDirectories(trash.resolve("files"));
        Files.createDirectories(trash.resolve("info"));
        // A move across filesystems is refused; no fallback copies consume additional space.
        if (!Files.getFileStore(original).equals(Files.getFileStore(trash)))
            throw new IOException("Trash is on a different filesystem; use the file manager");
        String token = "storage-lens-" + UUID.randomUUID();
        Path destinationName = Path.of(token);
        Path infoName = Path.of(token + ".trashinfo");
        String encoded = original.toUri().getRawPath();
        String info = "[Trash Info]\nPath=" + encoded + "\nDeletionDate=" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")) + "\n";
        try (var destination = openSecure(trash.resolve("files")); var metadata = openSecure(trash.resolve("info"))) {
            try (var out = metadata.newByteChannel(infoName, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, NOFOLLOW_LINKS))) {
                ByteBuffer data = StandardCharsets.UTF_8.encode(info);
                while (data.hasRemaining()) out.write(data);
            }
            try {
                verify(source, original.getFileName(), finding);
                source.move(original.getFileName(), destination, destinationName);
            } catch (IOException e) {
                metadata.deleteFile(infoName);
                throw e;
            }
        }
    }
    private SecureDirectoryStream<Path> openSecure(Path directory) throws IOException {
        Path absolute = directory.toAbsolutePath().normalize();
        DirectoryStream<Path> initial = Files.newDirectoryStream(absolute.getRoot());
        if (!(initial instanceof SecureDirectoryStream<Path> current)) {
            initial.close(); throw new IOException("This filesystem does not support safe cleanup; use the file manager");
        }
        try {
            for (Path component : absolute) {
                SecureDirectoryStream<Path> next = current.newDirectoryStream(component, NOFOLLOW_LINKS);
                current.close(); current = next;
            }
            return current;
        } catch (IOException | RuntimeException e) { current.close(); throw e; }
    }
}
