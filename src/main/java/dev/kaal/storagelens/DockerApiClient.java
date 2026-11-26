package dev.kaal.storagelens;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import static dev.kaal.storagelens.DockerModels.*;

interface DockerApi {
    Endpoint activeEndpoint() throws IOException;
    JsonNode call(Endpoint endpoint, String method, String path) throws IOException;
}

/** Docker access stays on the local Unix socket and never invokes a shell. */
@Singleton
public class DockerApiClient implements DockerApi {
    private final ObjectMapper json = new ObjectMapper();
    public Endpoint activeEndpoint() throws IOException {
        Path configuration = Path.of(System.getenv().getOrDefault("DOCKER_CONFIG", System.getProperty("user.home") + "/.docker"));
        String context = System.getenv("DOCKER_CONTEXT"), host = System.getenv("DOCKER_HOST");
        if (context == null || context.isBlank()) {
            if (host != null && !host.isBlank()) return endpoint("DOCKER_HOST", host);
            Path config = configuration.resolve("config.json");
            context = Files.isRegularFile(config) ? read(config).path("currentContext").asText("default") : "default";
        }
        if (context.equals("default")) return endpoint(context, "unix:///var/run/docker.sock");
        Path metadata = configuration.resolve("contexts/meta");
        if (Files.isDirectory(metadata)) try (var directories = Files.list(metadata)) {
            for (Path directory : directories.toList()) {
                Path file = directory.resolve("meta.json");
                if (!Files.isRegularFile(file)) continue;
                JsonNode node = read(file);
                if (node.path("Name").asText().equals(context))
                    return endpoint(context, node.path("Endpoints").path("docker").path("Host").asText());
            }
        }
        throw new IOException("Cannot find the selected local Docker context: " + Models.safe(context));
    }
    private JsonNode read(Path file) throws IOException {
        if (Files.size(file) > 1024 * 1024) throw new IOException("Docker context metadata is unexpectedly large");
        return json.readTree(Files.readString(file));
    }
    private Endpoint endpoint(String context, String host) throws IOException {
        if (!host.startsWith("unix:///")) throw new IOException("Storage Lens supports local Docker Unix sockets. Select a local Docker context first.");
        return new Endpoint(Models.safe(context), Path.of(host.substring("unix://".length())).toAbsolutePath().normalize());
    }
    public JsonNode call(Endpoint endpoint, String method, String path) throws IOException {
        if (!List.of("GET", "POST", "DELETE").contains(method) || !path.startsWith("/") || path.contains("\r") || path.contains("\n"))
            throw new IOException("Invalid Docker request");
        if (!Files.isExecutable(Path.of("/usr/bin/curl"))) throw new IOException("Install the system curl package to use Docker storage controls");
        var response = CommandRunner.run(List.of("/usr/bin/curl", "--silent", "--show-error", "--max-time", "90",
            "--unix-socket", endpoint.socket().toString(), "--request", method,
            "--write-out", "\n%{http_code}", "http://localhost" + path), Duration.ofSeconds(95));
        if (response.exit() != 0) throw new IOException("Cannot reach Docker context " + endpoint.context() + ". Check Docker Desktop and permission to access its socket.");
        int split = response.output().lastIndexOf('\n');
        if (split < 0) throw new IOException("Incomplete Docker response");
        int status;
        try { status = Integer.parseInt(response.output().substring(split + 1).trim()); }
        catch (NumberFormatException exception) { throw new IOException("Invalid Docker response status"); }
        String body = response.output().substring(0, split).trim();
        JsonNode value = body.isEmpty() ? json.createObjectNode() : json.readTree(body);
        if (status < 200 || status >= 300) {
            String message = Models.safe(value.path("message").asText("Request refused"));
            throw new IOException("Docker " + status + ": " + message.substring(0, Math.min(message.length(), 500)));
        }
        return value;
    }
}
