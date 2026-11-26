package dev.kaal.storagelens;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static dev.kaal.storagelens.DockerModels.*;

@Singleton
public class DockerService {
    private static final Set<String> TEST_PROJECTS = Set.of("kaal-balance-check", "kaal-infra-check");
    private static final Set<String> TEST_VOLUME_ROLES = Set.of("api-primary", "api-secondary", "kafka-data", "redis-data");
    private final DockerApi api;
    private final AuditLog audit;
    interface Terminal { void open(List<String> command) throws IOException; }
    private final Terminal terminal;
    private static final String RECLAIM_IMAGE = "docker/desktop-reclaim-space@sha256:8b26e45c51632fd78131f3bddca76122bebc1dd8376f16dd26f769c7f33121fd";
    @Inject public DockerService(DockerApiClient api, AuditLog audit) { this((DockerApi) api, audit); }
    DockerService(DockerApi api, AuditLog audit) { this(api, audit, command -> { new ProcessBuilder(command).start(); }); }
    DockerService(DockerApi api, AuditLog audit, Terminal terminal) { this.api = api; this.audit = audit; this.terminal = terminal; }

    Snapshot load() throws IOException { return fetch(api.activeEndpoint()); }
    private Snapshot fetch(Endpoint endpoint) throws IOException {
        JsonNode info = api.call(endpoint, "GET", "/info");
        String id = info.path("ID").asText();
        if (id.isBlank()) throw new IOException("The selected socket did not identify a Docker Engine");
        return parse(endpoint, id, api.call(endpoint, "GET", "/system/df"));
    }
    static Snapshot parse(Endpoint endpoint, String engineId, JsonNode data) {
        List<Container> containers = new ArrayList<>(); List<Volume> volumes = new ArrayList<>(); List<Cache> cache = new ArrayList<>();
        for (JsonNode item : data.path("Containers")) {
            String id = item.path("Id").asText(), name = clean(item.path("Names").path(0).asText(id)).replaceFirst("^/", "");
            String project = clean(item.path("Labels").path("com.docker.compose.project").asText());
            String state = clean(item.path("State").asText());
            boolean removable = TEST_PROJECTS.contains(project) && name.startsWith(project + "-")
                && id.matches("[a-f0-9]{64}") && Set.of("exited", "created", "dead").contains(state);
            String reason = removable ? "Isolated test stack; container can be recreated. Its volumes will be retained."
                : Set.of("running", "paused", "restarting").contains(state) ? "Active container; protected"
                : "Existing project or unverified owner; preserve container and data";
            List<Mount> mounts = new ArrayList<>();
            for (JsonNode mount : item.path("Mounts")) mounts.add(new Mount(clean(mount.path("Type").asText()),
                clean(mount.path("Name").asText()), clean(mount.path("Destination").asText())));
            containers.add(new Container(id, name, clean(item.path("Image").asText()), state, project,
                Math.max(0, item.path("SizeRw").asLong()), removable, reason, List.copyOf(mounts)));
        }
        for (JsonNode item : data.path("Volumes")) {
            String name = clean(item.path("Name").asText());
            String project = clean(item.path("Labels").path("com.docker.compose.project").asText());
            String role = clean(item.path("Labels").path("com.docker.compose.volume").asText());
            String created = item.path("CreatedAt").asText();
            int references = item.path("UsageData").path("RefCount").asInt(-1);
            boolean test = TEST_PROJECTS.contains(project) && TEST_VOLUME_ROLES.contains(role) && name.equals(project + "_" + role);
            boolean removable = test && references == 0 && !created.isBlank()
                && containers.stream().noneMatch(c -> c.mounts().stream().anyMatch(m -> name.equals(m.name())));
            String reason = !test ? "Database, existing project data or unknown owner; protected"
                : references != 0 ? "Test data still referenced by containers; remove those containers first"
                : created.isBlank() ? "Creation identity unavailable; protected"
                : removable ? "Unreferenced isolated test data; deletion resets this test stack permanently"
                : "A container still mounts this volume; protected";
            volumes.add(new Volume(name, item.path("UsageData").path("Size").asLong(-1), references, project, created, removable, reason));
        }
        for (JsonNode item : data.path("BuildCache")) {
            Instant used;
            try { used = Instant.parse(item.path("LastUsedAt").asText()); } catch (Exception exception) { used = Instant.MAX; }
            cache.add(new Cache(clean(item.path("ID").asText()), Math.max(0, item.path("Size").asLong()),
                item.path("InUse").asBoolean(true), item.path("Shared").asBoolean(true), used));
        }
        containers.sort(Comparator.comparingLong(Container::bytes).reversed()); volumes.sort(Comparator.comparingLong(Volume::bytes).reversed());
        return new Snapshot(endpoint, engineId, Instant.now(), Math.max(0, data.path("LayersSize").asLong()),
            List.copyOf(containers), List.copyOf(volumes), List.copyOf(cache));
    }
    private static String clean(String value) { return Models.safe(value).replace('\t', ' '); }
    private Snapshot recheck(Snapshot expected) throws IOException {
        if (!expected.endpoint().equals(api.activeEndpoint())) throw new IOException("Docker context changed. Refresh Docker and review again.");
        Snapshot fresh = fetch(expected.endpoint());
        if (!expected.engineId().equals(fresh.engineId())) throw new IOException("Docker Engine changed. Refresh Docker and review again.");
        return fresh;
    }
    static boolean desktopEndpoint(Snapshot snapshot) {
        return snapshot.endpoint().context().equals("desktop-linux") && snapshot.endpoint().socket().equals(
            Path.of(System.getProperty("user.home"), ".docker/desktop/docker.sock").toAbsolutePath().normalize());
    }
    static long desktopDiskBytes(Snapshot snapshot) {
        if (!desktopEndpoint(snapshot) || !NativeFiles.available()) return -1;
        Path disk = Path.of(System.getProperty("user.home"), ".docker/desktop/vms/0/data/Docker.raw");
        try { return Files.isRegularFile(disk, LinkOption.NOFOLLOW_LINKS) ? NativeFiles.allocated(disk, -1) : -1; }
        catch (RuntimeException exception) { return -1; }
    }
    static List<String> desktopReclaimCommand(Snapshot snapshot) throws IOException {
        if (!desktopEndpoint(snapshot)) throw new IOException("Disk reclamation is available only for the local desktop-linux Docker context");
        return List.of("/usr/bin/env", "-u", "DOCKER_CONTEXT", "-u", "DOCKER_HOST", "/usr/bin/docker", "--host", "unix://" + snapshot.endpoint().socket(),
            "run", "--rm", "--privileged", "--pid=host", RECLAIM_IMAGE);
    }
    synchronized void openDesktopReclaim(Snapshot reviewed) throws IOException {
        recheck(reviewed);
        if (!api.call(reviewed.endpoint(), "GET", "/info").path("OperatingSystem").asText().contains("Docker Desktop"))
            throw new IOException("The selected daemon is not Docker Desktop; disk reclamation was refused");
        List<String> command = new ArrayList<>(List.of("/usr/bin/konsole", "--hold", "-e")); command.addAll(desktopReclaimCommand(reviewed));
        audit.append("REQUEST DOCKER DESKTOP RECLAIM TERMINAL", reviewed.endpoint().context() + " | " + RECLAIM_IMAGE);
        terminal.open(command);
    }
    synchronized Outcome pruneCache(Snapshot reviewed, CacheAge age) throws IOException {
        recheck(reviewed);
        String filters = age.filter.isEmpty() ? "{}" : new ObjectMapper().writeValueAsString(Map.of("until", List.of(age.filter)));
        String path = "/build/prune?all=false&filters=" + URLEncoder.encode(filters, StandardCharsets.UTF_8);
        audit.append("DOCKER REQUEST CACHE", reviewed.endpoint().context() + " | " + age.label);
        JsonNode result = api.call(reviewed.endpoint(), "POST", path);
        long reclaimed = Math.max(0, result.path("SpaceReclaimed").asLong());
        List<String> messages = new ArrayList<>(); messages.add("Docker reports " + Models.size(reclaimed) + " reclaimed. Later builds may take longer or need downloads.");
        try { audit.append("DOCKER SUCCESS CACHE", Long.toString(reclaimed)); }
        catch (IOException exception) { messages.add("Cleanup completed; completion log could not be written."); }
        return new Outcome(1, 0, reclaimed, List.copyOf(messages));
    }
    synchronized Outcome removeContainers(Snapshot reviewed, List<String> selected) throws IOException {
        Snapshot fresh = recheck(reviewed); Map<String, Container> old = new HashMap<>(), current = new HashMap<>();
        reviewed.containers().forEach(c -> old.put(c.id(), c)); fresh.containers().forEach(c -> current.put(c.id(), c));
        List<String> ids = selected.stream().distinct().toList();
        if (ids.isEmpty() || ids.size() > 1000) throw new IOException("Select a bounded list of test containers first");
        for (String id : ids) {
            Container before = old.get(id), now = current.get(id);
            if (before == null || now == null || !before.removable() || !now.removable() || !before.project().equals(now.project()))
                throw new IOException("Selection changed or includes a protected container. Refresh and review again.");
        }
        return remove(reviewed.endpoint(), ids, "CONTAINER", id -> "/containers/" + id + "?v=false&force=false");
    }
    synchronized Outcome removeVolumes(Snapshot reviewed, List<String> selected) throws IOException {
        Snapshot fresh = recheck(reviewed); Map<String, Volume> old = new HashMap<>(), current = new HashMap<>();
        reviewed.volumes().forEach(v -> old.put(v.name(), v)); fresh.volumes().forEach(v -> current.put(v.name(), v));
        List<String> names = selected.stream().distinct().toList();
        if (names.isEmpty() || names.size() > 1000) throw new IOException("Select a bounded list of test volumes first");
        for (String name : names) {
            Volume before = old.get(name), now = current.get(name);
            if (before == null || now == null || !before.removable() || !now.removable() || !before.created().equals(now.created()))
                throw new IOException("A volume is protected, referenced or changed. Refresh and review again.");
        }
        return remove(reviewed.endpoint(), names, "VOLUME", name -> "/volumes/" + URLEncoder.encode(name, StandardCharsets.UTF_8) + "?force=false");
    }
    private Outcome remove(Endpoint endpoint, List<String> selected, String kind, java.util.function.Function<String, String> route) {
        int succeeded = 0, failed = 0; List<String> messages = new ArrayList<>();
        for (String name : selected) {
            try {
                audit.append("DOCKER REQUEST " + kind, endpoint.context() + " | " + name);
                api.call(endpoint, "DELETE", route.apply(name)); succeeded++; messages.add("Removed " + kind.toLowerCase(Locale.ROOT) + " " + name);
                try { audit.append("DOCKER SUCCESS " + kind, name); }
                catch (IOException exception) { messages.add("Removal completed; logging failed. Remaining items were not attempted."); break; }
            } catch (IOException exception) {
                failed++; messages.add("Kept " + name + ": " + Models.safe(exception.getMessage()));
                try { audit.append("DOCKER SKIPPED " + kind, name + " | " + exception.getMessage()); } catch (IOException ignored) {}
            }
        }
        return new Outcome(succeeded, failed, 0, List.copyOf(messages));
    }
    static void export(Path path, Snapshot snapshot) throws IOException {
        try (var out = Files.newBufferedWriter(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            out.write("kind,name,size_bytes,references,project,review_reason\n");
            for (Container c : snapshot.containers()) row(out, "container", c.name(), Long.toString(c.bytes()), "", c.project(), c.reason());
            for (Volume v : snapshot.volumes()) row(out, "volume", v.name(), Long.toString(v.bytes()), Integer.toString(v.references()), v.project(), v.reason());
            row(out, "cache", "unused_unshared", Long.toString(snapshot.unsharedCacheBytes()), "", snapshot.endpoint().context(), "Estimate; shared layers, selected age and disk trimming affect actual savings");
        }
    }
    private static void row(java.io.Writer out, String... cells) throws IOException {
        out.write(String.join(",", Arrays.stream(cells).map(ReportWriter::cell).toList())); out.write('\n');
    }
}
