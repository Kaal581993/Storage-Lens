package dev.kaal.storagelens;

import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import static java.nio.file.StandardOpenOption.*;

@Singleton
public class AuditLog {
    private final Path path;
    public AuditLog() { this(Path.of(System.getProperty("user.home"), ".local/share/storage-lens/activity.log")); }
    public AuditLog(Path path) { this.path = path; }
    public Path path() { return path; }
    public synchronized void append(String event, String detail) throws IOException {
        Files.createDirectories(path.getParent());
        if (Files.isSymbolicLink(path)) throw new IOException("Activity log cannot be a symbolic link");
        try { Files.setPosixFilePermissions(path.getParent(), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")); }
        catch (UnsupportedOperationException ignored) {}
        String line = Instant.now() + "\t" + Models.safe(event) + "\t" + Models.safe(detail).replace('\t', ' ') + System.lineSeparator();
        Files.writeString(path, line, CREATE, WRITE, APPEND, LinkOption.NOFOLLOW_LINKS);
        try { Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException ignored) {}
    }
    public String recent() throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return "No activity yet. Scanning does not remove files.";
        // Read at most the last 96 KiB so a long-lived append-only log cannot fill memory.
        try (var channel = Files.newByteChannel(path, READ, LinkOption.NOFOLLOW_LINKS)) {
            channel.position(Math.max(0, channel.size() - 96 * 1024));
            var buffer = java.nio.ByteBuffer.allocate(96 * 1024);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {}
            buffer.flip();
            return java.nio.charset.StandardCharsets.UTF_8.decode(buffer).toString();
        }
    }
}
