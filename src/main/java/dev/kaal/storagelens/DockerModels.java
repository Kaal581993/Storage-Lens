package dev.kaal.storagelens;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

final class DockerModels {
    private DockerModels() {}
    record Endpoint(String context, Path socket) {}
    record Mount(String type, String name, String destination) {}
    record Container(String id, String name, String image, String state, String project,
                     long bytes, boolean removable, String reason, List<Mount> mounts) {}
    record Volume(String name, long bytes, int references, String project, String created,
                  boolean removable, String reason) {}
    record Cache(String id, long bytes, boolean inUse, boolean shared, Instant lastUsed) {}
    record Snapshot(Endpoint endpoint, String engineId, Instant captured, long imageBytes,
                    List<Container> containers, List<Volume> volumes, List<Cache> cache) {
        long cacheBytes() { return cache.stream().mapToLong(Cache::bytes).sum(); }
        long unsharedCacheBytes() { return cache.stream().filter(c -> !c.inUse() && !c.shared()).mapToLong(Cache::bytes).sum(); }
        long volumeBytes() { return volumes.stream().mapToLong(v -> Math.max(0, v.bytes())).sum(); }
    }
    record Outcome(int succeeded, int failed, long reclaimed, List<String> messages) {}
    enum CacheAge {
        WEEK("Unused for at least 7 days", "168h"), TWO_DAYS("Unused for at least 2 days", "48h"), ALL("All dangling build cache", "");
        final String label, filter;
        CacheAge(String label, String filter) { this.label = label; this.filter = filter; }
        public String toString() { return label; }
    }
}
