package dev.kaal.storagelens;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.kaal.storagelens.DockerModels.*;

class DockerServiceTest {
    @TempDir Path temp;
    static final String TEST_ID = "a".repeat(64), DB_ID = "b".repeat(64);
    final ObjectMapper json = new ObjectMapper();
    FakeApi api; DockerService service;
    @BeforeEach void setup() throws Exception {
        api = new FakeApi(); service = new DockerService(api, new AuditLog(temp.resolve("audit/activity.log")));
    }
    final class FakeApi implements DockerApi {
        Endpoint endpoint = new Endpoint("desktop-linux", Path.of("/tmp/test-docker.sock"));
        String engine = "engine-one";
        String operatingSystem = "Test Linux";
        final List<String> writes = new ArrayList<>();
        ObjectNode data;
        FakeApi() throws Exception {
            data = (ObjectNode) json.readTree("""
                {"LayersSize":8000,"Containers":[
                  {"Id":"%s","Names":["/kaal-infra-check-redis-1"],"Image":"redis:8.2-alpine","State":"exited","SizeRw":500,
                   "Labels":{"com.docker.compose.project":"kaal-infra-check"},"Mounts":[]},
                  {"Id":"%s","Names":["/PG-vector-db"],"Image":"pgvector/pgvector:pg17","State":"exited","SizeRw":900,
                   "Labels":{},"Mounts":[{"Type":"volume","Name":"real-db","Destination":"/var/lib/postgresql/data"}]}],
                 "Volumes":[
                   {"Name":"kaal-infra-check_redis-data","CreatedAt":"2026-11-10T00:00:00Z","UsageData":{"Size":400,"RefCount":0},
                    "Labels":{"com.docker.compose.project":"kaal-infra-check","com.docker.compose.volume":"redis-data"}},
                   {"Name":"real-db","CreatedAt":"2026-10-01T00:00:00Z","UsageData":{"Size":500000,"RefCount":1},"Labels":{}}],
                 "BuildCache":[{"ID":"cache-old","Size":1000,"InUse":false,"Shared":false,"LastUsedAt":"2026-11-01T00:00:00Z"},
                               {"ID":"shared","Size":3000,"InUse":false,"Shared":true,"LastUsedAt":"2026-11-01T00:00:00Z"}]}
                """.formatted(TEST_ID, DB_ID));
        }
        public Endpoint activeEndpoint() { return endpoint; }
        public JsonNode call(Endpoint target, String method, String path) throws IOException {
            assertEquals(endpoint, target);
            if (method.equals("GET") && path.equals("/info")) return json.createObjectNode().put("ID", engine).put("OperatingSystem", operatingSystem);
            if (method.equals("GET") && path.equals("/system/df")) return data;
            writes.add(method + " " + path);
            if (method.equals("POST") && path.startsWith("/build/prune?")) return json.createObjectNode().put("SpaceReclaimed", 1234);
            if (method.equals("DELETE")) return json.createObjectNode();
            throw new IOException("Unexpected request");
        }
    }
    @Test void reportProtectsDatabaseAndAccountsForSharedCache() throws Exception {
        Snapshot snapshot = service.load();
        assertEquals(4000, snapshot.cacheBytes()); assertEquals(1000, snapshot.unsharedCacheBytes());
        assertTrue(snapshot.containers().stream().filter(c -> c.id().equals(TEST_ID)).findFirst().orElseThrow().removable());
        assertFalse(snapshot.containers().stream().filter(c -> c.id().equals(DB_ID)).findFirst().orElseThrow().removable());
        assertFalse(snapshot.volumes().stream().filter(v -> v.name().equals("real-db")).findFirst().orElseThrow().removable());
        assertTrue(api.writes.isEmpty());
    }
    @Test void cacheCleanupTargetsOnlyBuildCacheAndKeepsRecentEntries() throws Exception {
        Outcome outcome = service.pruneCache(service.load(), CacheAge.WEEK);
        assertEquals(1234, outcome.reclaimed()); assertEquals(1, api.writes.size());
        String request = api.writes.getFirst(); assertTrue(request.startsWith("POST /build/prune?all=false&filters="));
        String filters = URLDecoder.decode(request.substring(request.indexOf("filters=") + 8), StandardCharsets.UTF_8);
        assertEquals("168h", json.readTree(filters).path("until").path(0).asText());
    }
    @Test void desktopReclaimPinsTheSocketAndHelperAndDoesNotDeleteProjectObjects() throws Exception {
        api.endpoint = new Endpoint("desktop-linux", Path.of(System.getProperty("user.home"), ".docker/desktop/docker.sock")); api.operatingSystem = "Docker Desktop";
        List<List<String>> terminals = new ArrayList<>();
        var maintenance = new DockerService(api, new AuditLog(temp.resolve("audit/maintenance.log")), terminals::add);
        maintenance.openDesktopReclaim(maintenance.load()); assertEquals(1, terminals.size());
        var command = terminals.getFirst(); assertTrue(command.containsAll(List.of("--host", "unix://" + api.endpoint.socket(), "--rm", "--privileged", "--pid=host")));
        assertTrue(command.getLast().startsWith("docker/desktop-reclaim-space@sha256:")); assertFalse(command.contains("prune")); assertFalse(command.contains("--volume"));
        assertTrue(api.writes.isEmpty());
    }
    @Test void desktopReclaimRejectsTheSystemDaemonAndChangedContext() throws Exception {
        var maintenance = new DockerService(api, new AuditLog(temp.resolve("audit/maintenance.log")), command -> fail("Unexpected terminal"));
        assertThrows(IOException.class, () -> maintenance.openDesktopReclaim(maintenance.load()));
        api.endpoint = new Endpoint("desktop-linux", Path.of(System.getProperty("user.home"), ".docker/desktop/docker.sock")); api.operatingSystem = "Docker Desktop";
        var reviewed = maintenance.load(); api.endpoint = new Endpoint("default", Path.of("/var/run/docker.sock"));
        assertThrows(IOException.class, () -> maintenance.openDesktopReclaim(reviewed)); assertTrue(api.writes.isEmpty());
    }
    @Test void desktopReclaimCannotOpenItsTerminalWithoutTheRequestLog() throws Exception {
        api.endpoint = new Endpoint("desktop-linux", Path.of(System.getProperty("user.home"), ".docker/desktop/docker.sock")); api.operatingSystem = "Docker Desktop";
        Path bad = java.nio.file.Files.writeString(temp.resolve("not-a-directory"), "x");
        var maintenance = new DockerService(api, new AuditLog(bad.resolve("audit")), command -> fail("Unlogged maintenance"));
        var reviewed = maintenance.load(); assertThrows(IOException.class, () -> maintenance.openDesktopReclaim(reviewed)); assertTrue(api.writes.isEmpty());
    }
    @Test void removingTestContainerNeverRemovesItsVolumesOrForcesAStop() throws Exception {
        assertEquals(1, service.removeContainers(service.load(), List.of(TEST_ID, TEST_ID)).succeeded());
        assertEquals(List.of("DELETE /containers/" + TEST_ID + "?v=false&force=false"), api.writes);
    }
    @Test void protectedContainerCannotBeSelectedThroughTheBackend() throws Exception {
        Snapshot snapshot = service.load();
        assertThrows(IOException.class, () -> service.removeContainers(snapshot, List.of(DB_ID)));
        assertTrue(api.writes.isEmpty());
    }
    @Test void containerStartedAfterReviewIsPreserved() throws Exception {
        Snapshot snapshot = service.load(); ((ObjectNode) api.data.path("Containers").path(0)).put("State", "running");
        assertThrows(IOException.class, () -> service.removeContainers(snapshot, List.of(TEST_ID))); assertTrue(api.writes.isEmpty());
    }
    @Test void newlyReferencedVolumeIsPreserved() throws Exception {
        Snapshot snapshot = service.load(); ((ObjectNode) api.data.path("Volumes").path(0).path("UsageData")).put("RefCount", 1);
        assertThrows(IOException.class, () -> service.removeVolumes(snapshot, List.of("kaal-infra-check_redis-data"))); assertTrue(api.writes.isEmpty());
    }
    @Test void sameNameReplacementVolumeIsPreserved() throws Exception {
        Snapshot snapshot = service.load(); ((ObjectNode) api.data.path("Volumes").path(0)).put("CreatedAt", "2026-11-23T00:00:00Z");
        assertThrows(IOException.class, () -> service.removeVolumes(snapshot, List.of("kaal-infra-check_redis-data"))); assertTrue(api.writes.isEmpty());
    }
    @Test void onlyUnreferencedIdentifiedTestVolumesCanBeRemoved() throws Exception {
        Snapshot snapshot = service.load();
        assertThrows(IOException.class, () -> service.removeVolumes(snapshot, List.of("real-db")));
        assertEquals(1, service.removeVolumes(snapshot, List.of("kaal-infra-check_redis-data")).succeeded());
        assertEquals(List.of("DELETE /volumes/kaal-infra-check_redis-data?force=false"), api.writes);
    }
    @Test void contextAndEngineChangesBlockEveryMutation() throws Exception {
        Snapshot snapshot = service.load(); api.endpoint = new Endpoint("default", Path.of("/tmp/other-docker.sock"));
        assertThrows(IOException.class, () -> service.pruneCache(snapshot, CacheAge.ALL));
        api.endpoint = snapshot.endpoint(); api.engine = "engine-two";
        assertThrows(IOException.class, () -> service.pruneCache(snapshot, CacheAge.ALL)); assertTrue(api.writes.isEmpty());
    }
    @Test void auditFailurePreventsDockerMutation() throws Exception {
        Path bad = java.nio.file.Files.writeString(temp.resolve("file"), "fixture");
        DockerService blocked = new DockerService(api, new AuditLog(bad.resolve("activity.log")));
        assertThrows(IOException.class, () -> blocked.pruneCache(service.load(), CacheAge.ALL)); assertTrue(api.writes.isEmpty());
    }
    @Test void exportNeverOverwritesAndContainsProtectionReasons() throws Exception {
        Path report = temp.resolve("docker.csv"); Snapshot snapshot = service.load(); DockerService.export(report, snapshot);
        assertTrue(java.nio.file.Files.readString(report).contains("protected"));
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> DockerService.export(report, snapshot)); assertTrue(api.writes.isEmpty());
    }
}
