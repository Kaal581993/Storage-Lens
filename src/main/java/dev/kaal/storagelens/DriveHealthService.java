package dev.kaal.storagelens;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.math.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;

/** Micronaut presentation service; the C helper owns hardware access. */
@Singleton
public class DriveHealthService {
    record Device(String controller, String model) { public String toString() { return model + " · " + controller; } }
    record Health(Device device, Instant captured, Integer used, Integer spare, Integer warning,
                  BigDecimal temperatureC, BigInteger writtenBytes, BigInteger writeCommands, int error, String message) {
        Integer remainingPercent() { return used == null ? null : Math.max(0, 100 - used); }
        boolean needsPermission() { return error == 1 || error == 13; }
        BigDecimal remainingTbw(BigDecimal ratedTbw) {
            if (writtenBytes == null || ratedTbw == null) return null;
            return ratedTbw.subtract(new BigDecimal(writtenBytes).movePointLeft(12)).max(BigDecimal.ZERO);
        }
    }
    private static final BigInteger UNIT = BigInteger.valueOf(512_000);
    private final ObjectMapper json = new ObjectMapper();
    private final AuditLog audit;
    public DriveHealthService(AuditLog audit) { this.audit = audit; }
    List<Device> devices() throws IOException {
        var result = CommandRunner.run(List.of(helper().toString(), "--list"), Duration.ofSeconds(10));
        if (result.exit() != 0) throw new IOException("NVMe identity reader could not run");
        JsonNode values = json.readTree(result.output()); if (!values.isArray()) throw new IOException("Invalid NVMe device list");
        List<Device> devices = new ArrayList<>();
        for (JsonNode item : values) {
            String controller = item.path("controller").asText(); validateController(controller);
            devices.add(new Device(controller, Models.safe(item.path("model").asText("Unknown NVMe"))));
        }
        return List.copyOf(devices);
    }
    Health read(Device device, boolean authorize) throws IOException {
        List<String> command = command(helper(), device.controller(), authorize);
        audit.append("NVME HEALTH READ", device.controller() + " | system authentication=" + authorize);
        var result = CommandRunner.run(command, Duration.ofMinutes(3));
        if (!result.output().stripLeading().startsWith("{")) throw new IOException(authorize
            ? "SMART authentication was cancelled or unavailable. No drive data was changed." : "SMART reader unavailable");
        Health health = parse(json.readTree(result.output()));
        if (!health.device().controller().equals(device.controller()) || !health.device().model().equals(device.model()))
            throw new IOException("NVMe identity changed; refresh the drive list");
        return health;
    }
    static Health parse(JsonNode node) throws IOException {
        String controller = node.path("controller").asText(); validateController(controller);
        Device device = new Device(controller, Models.safe(node.path("model").asText()));
        if (!node.path("readable").asBoolean(false)) return new Health(device, Instant.now(), null, null, null, null, null, null,
            node.path("errno").asInt(-1), Models.safe(node.path("error").asText("SMART counters unavailable")));
        int used = bounded(node, "percentage_used", 255), spare = bounded(node, "available_spare", 100), warning = bounded(node, "critical_warning", 255);
        BigInteger units = unsigned128(node.path("data_units_written").asText()), commands = unsigned128(node.path("host_write_commands").asText());
        int kelvin = bounded(node, "temperature_kelvin", 65535);
        return new Health(device, Instant.now(), used, spare, warning, kelvin == 0 ? null : BigDecimal.valueOf(kelvin).subtract(new BigDecimal("273.15")),
            units.signum() == 0 ? null : units.multiply(UNIT), commands.signum() == 0 ? null : commands, 0, "SMART read completed");
    }
    private static int bounded(JsonNode node, String key, int maximum) throws IOException {
        if (!node.path(key).isIntegralNumber() || !node.path(key).canConvertToInt()) throw new IOException("Missing SMART counter: " + key);
        int value = node.path(key).intValue(); if (value < 0 || value > maximum) throw new IOException("Invalid SMART counter: " + key); return value;
    }
    private static BigInteger unsigned128(String value) throws IOException {
        if (!value.matches("[0-9]{1,39}")) throw new IOException("Invalid SMART write counter");
        BigInteger parsed = new BigInteger(value); if (parsed.bitLength() > 128) throw new IOException("SMART counter exceeds 128 bits"); return parsed;
    }
    static List<String> command(Path helper, String controller, boolean authorize) throws IOException {
        validateController(controller);
        return authorize ? List.of("/usr/bin/pkexec", helper.toString(), "--read", controller) : List.of(helper.toString(), "--read", controller);
    }
    private static void validateController(String controller) throws IOException {
        if (!controller.matches("nvme[0-9]{1,6}")) throw new IOException("Invalid NVMe controller name");
    }
    static Path helper() throws IOException {
        try {
            Path location = Path.of(DriveHealthService.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path parent = location.getParent();
            for (Path path : List.of(parent.resolve("native/storage-lens-nvme"), parent.resolve("../../bin/storage-lens-nvme").normalize()))
                if (ManagedCacheService.nativeExecutable(path)) return path.toAbsolutePath();
        } catch (Exception exception) { throw new IOException("Cannot locate the native NVMe reader", exception); }
        throw new IOException("Native NVMe reader unavailable; rebuild and reinstall Storage Lens");
    }
    BigDecimal ratedTbw(String model) throws IOException {
        String value = loadRatings().getProperty(model);
        if (value == null) return null;
        try { BigDecimal number = new BigDecimal(value); if (number.signum() > 0) return number; }
        catch (NumberFormatException ignored) {}
        throw new IOException("Invalid saved endurance rating; enter the manufacturer's TBW again");
    }
    void saveRatedTbw(String model, BigDecimal value) throws IOException {
        if (model.isBlank() || model.length() > 128 || value != null && (value.signum() <= 0 || value.compareTo(new BigDecimal("1000000")) > 0)) throw new IOException("Enter a positive manufacturer TBW rating");
        Properties ratings = loadRatings(); if (value == null) ratings.remove(model); else ratings.setProperty(model, value.toPlainString());
        Path file = ratingFile(); Path directory = file.getParent();
        if (new ScanPolicy().hasSymlinkAncestor(directory)) throw new IOException("Drive preferences path contains a symbolic link");
        Files.createDirectories(directory); Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        audit.append("NVME RATING", model + " | user-entered TBW=" + value);
        try (var out = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { ratings.store(out, "User-entered manufacturer endurance ratings in decimal TBW"); }
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    }
    private static Path ratingFile() { return Path.of(System.getProperty("user.home"), ".local/share/storage-lens/drive-endurance.properties"); }
    private static Properties loadRatings() throws IOException {
        Properties ratings = new Properties(); Path file = ratingFile();
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 64 * 1024) throw new IOException("Invalid drive-endurance preferences");
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { ratings.load(input); }
        }
        return ratings;
    }
    static String tb(BigInteger bytes) { return bytes == null ? "Unavailable" : new BigDecimal(bytes).movePointLeft(12).setScale(2, RoundingMode.HALF_UP).toPlainString() + " TB"; }
}
