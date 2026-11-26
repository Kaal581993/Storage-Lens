package dev.kaal.storagelens;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

final class CommandRunner {
    record Result(int exit, String output) {}
    static Result run(List<String> command, Duration timeout) throws IOException {
        return run(command, timeout, java.util.Map.of());
    }
    static Result run(List<String> command, Duration timeout, java.util.Map<String, String> environment) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("LC_ALL", "C");
        builder.environment().putAll(environment);
        Process process = builder.start();
        try (var reader = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<byte[]> output = reader.submit(() -> process.getInputStream().readNBytes(8 * 1024 * 1024));
            try {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly(); throw new IOException("Command timed out: " + command.getFirst());
                }
                return new Result(process.exitValue(), new String(output.get(2, TimeUnit.SECONDS), StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); process.destroyForcibly(); throw new IOException("Command interrupted", e);
            } catch (ExecutionException | TimeoutException e) {
                process.destroyForcibly(); throw new IOException("Could not read command output", e);
            }
        } finally { process.destroy(); }
    }
}
