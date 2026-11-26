package dev.kaal.storagelens;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Only known IDE system directories and rebuildable child areas are eligible. */
final class IdeCacheLayout {
    static final Set<String> AREAS = Set.of("index", "shared-indexes", "compiler", "compile-server");
    private static final Pattern VERSION = Pattern.compile("(?:IntelliJIdea|IdeaIC|CLion|WebStorm|PhpStorm|DataGrip|Rider|GoLand|RustRover|RubyMine|AndroidStudio)20[0-9]{2}\\.[0-9]+(?:[.\\-][A-Za-z0-9_\\-.]+)?");
    record Instance(String version, Path root) {}
    private final Path home;
    private final List<Path> bases;
    IdeCacheLayout(Path home) {
        this.home = home.toAbsolutePath().normalize();
        List<Path> paths = new ArrayList<>(); paths.add(this.home.resolve(".cache/JetBrains"));
        for (String edition : List.of("community", "ultimate")) paths.add(this.home.resolve("snap/intellij-idea-" + edition + "/common/.cache/JetBrains"));
        for (String edition : List.of("Community", "Ultimate")) paths.add(this.home.resolve(".var/app/com.jetbrains.IntelliJ-IDEA-" + edition + "/cache/JetBrains"));
        bases = List.copyOf(paths);
    }
    List<Instance> discover() throws IOException {
        List<Instance> found = new ArrayList<>();
        for (Path base : bases) if (Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) try (var children = Files.list(base)) {
            children.filter(p -> VERSION.matcher(p.getFileName().toString()).matches())
                .filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).forEach(p -> found.add(new Instance(p.getFileName().toString(), p)));
        }
        try (var children = Files.list(home)) {
            children.filter(p -> p.getFileName().toString().startsWith(".") && VERSION.matcher(p.getFileName().toString().substring(1)).matches())
                .map(p -> new Instance(p.getFileName().toString().substring(1), p.resolve("system")))
                .filter(i -> Files.isDirectory(i.root(), LinkOption.NOFOLLOW_LINKS)).forEach(found::add);
        }
        return found.stream().sorted(Comparator.comparing(Instance::version).thenComparing(i -> i.root().toString())).toList();
    }
    Instance instance(Path file) {
        Path p = file.toAbsolutePath().normalize();
        for (Path base : bases) if (p.startsWith(base) && p.getNameCount() > base.getNameCount()) {
            String owner = base.relativize(p).getName(0).toString();
            if (VERSION.matcher(owner).matches()) return new Instance(owner, base.resolve(owner));
        }
        if (p.startsWith(home) && p.getNameCount() > home.getNameCount()) {
            String owner = home.relativize(p).getName(0).toString();
            Path system = home.resolve(owner).resolve("system");
            if (owner.startsWith(".") && VERSION.matcher(owner.substring(1)).matches() && p.startsWith(system)) return new Instance(owner.substring(1), system);
        }
        return null;
    }
    boolean cacheFile(Path path) {
        Instance owner = instance(path); if (owner == null) return false;
        Path relative = owner.root().relativize(path.toAbsolutePath().normalize());
        return relative.getNameCount() >= 2 && AREAS.contains(relative.getName(0).toString());
    }
    boolean managed(Path path) { return bases.stream().anyMatch(path::startsWith) || instance(path) != null; }
    boolean cacheDirectory(Path path) {
        Instance owner = instance(path); if (owner == null || owner.root().equals(path)) return false;
        return AREAS.contains(owner.root().relativize(path).getName(0).toString());
    }
}
