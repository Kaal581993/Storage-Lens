package dev.kaal.storagelens;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Small C stat bridge for actual block allocation, including sparse disk images. */
public final class NativeFiles {
    private static final boolean AVAILABLE = load();
    private NativeFiles() {}
    private static boolean load() {
        String resource = "/native/linux-x86_64/libstorage_lens.so";
        try (var input = NativeFiles.class.getResourceAsStream(resource)) {
            if (input == null || !System.getProperty("os.name").equals("Linux")) return false;
            byte[] binary = input.readNBytes(128 * 1024);
            if (binary.length == 0 || input.read() != -1) return false;
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(binary));
            Path directory = Files.createTempDirectory("storage-lens-native-"); directory.toFile().deleteOnExit();
            Path library = directory.resolve("libstorage_lens-" + digest.substring(0, 16) + ".so");
            Files.write(library, binary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE); library.toFile().deleteOnExit();
            System.load(library.toAbsolutePath().toString()); return true;
        } catch (Exception | UnsatisfiedLinkError exception) { return false; }
    }
    private static native long allocated0(byte[] path);
    public static boolean available() { return AVAILABLE; }
    public static long allocated(Path path, long logical) {
        if (!AVAILABLE) return logical;
        long measured = allocated0(path.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8));
        return measured < 0 ? logical : measured;
    }
}
