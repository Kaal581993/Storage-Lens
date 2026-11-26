package dev.kaal.storagelens;

import jakarta.inject.Singleton;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import static dev.kaal.storagelens.Models.*;

@Singleton
public class ScanPolicy {
    private final Path home;
    private final Path cache;
    private final Path trash;
    private final Path downloads;
    final IdeCacheLayout ideCaches;
    private final List<Path> protectedRoots;
    private final List<Path> managedRoots;
    private static final List<String> PARTIAL_SUFFIXES = List.of(".part", ".partial", ".crdownload", ".download", ".zmdownload", ".aria2", ".filepart");
    private static final List<Path> SYSTEM_EXCLUDED = List.of("/proc", "/sys", "/dev", "/run", "/lost+found", "/.snapshots", "/timeshift", "/mnt", "/media", "/var/lib/docker", "/var/lib/containers").stream().map(Path::of).toList();
    private static final Set<String> KNOWN_CACHE = Set.of("thumbnails", "mesa_shader_cache", "mesa_shader_cache_db", "mesa_shader_cache_sf", "fontconfig", "ksycoca6", "ksycoca5", "chromium", "google-chrome", "mozilla", "firefox", "brave", "brave-browser", "typescript", "yarn", "npm", "go-build", "ccache", "sccache");
    // These caches may contain offline models, installed tools or user-created assets.
    private static final List<String> PERSISTENT_CACHE = List.of("huggingface", "torch", "whisper", "ollama", "lm-studio", "models", "unsloth", "triton", "ms-playwright", "puppeteer", "selenium", "uv", "pip", "pypoetry", "netlify-cli", "review");

    public ScanPolicy() { this(Path.of(System.getProperty("user.home"))); }
    public ScanPolicy(Path home) {
        this.home = home.toAbsolutePath().normalize();
        this.cache = this.home.resolve(".cache");
        this.trash = this.home.resolve(".local/share/Trash");
        this.downloads = this.home.resolve("Downloads");
        this.ideCaches = new IdeCacheLayout(this.home);
        this.managedRoots = List.of(this.home.resolve(".docker"), this.home.resolve(".local/share/containers"),
            this.home.resolve(".local/share/libvirt"), this.home.resolve(".local/share/flatpak"));
        this.protectedRoots = List.of(this.home.resolve(".ssh"), this.home.resolve(".gnupg"),
            this.home.resolve(".aws"), this.home.resolve(".codex"), this.home.resolve(".agents"),
            this.home.resolve(".config"), this.home.resolve(".password-store"),
            this.home.resolve(".local/share/storage-lens"), this.home.resolve("storage-lens"));
    }
    public Path home() { return home; }
    public Path trash() { return trash; }
    public boolean excluded(Path path) {
        Path p = path.toAbsolutePath().normalize();
        for (Path protectedPath : protectedRoots) if (p.startsWith(protectedPath)) return true;
        // Never traverse virtual filesystems, mounted media or system snapshots automatically.
        for (Path systemPath : SYSTEM_EXCLUDED) if (p.startsWith(systemPath)) return true;
        return false;
    }
    public boolean canAct(Path path) {
        Path p = path.toAbsolutePath().normalize();
        // uv owns its cache locking. Hugging Face and IDE persistence are explicitly preserved.
        if (p.startsWith(cache.resolve("uv")) || p.startsWith(cache.resolve("huggingface"))
            || p.startsWith(home.resolve(".local/share/JetBrains"))) return false;
        if (ideCaches.managed(p) && !ideCaches.cacheFile(p)) return false;
        return p.startsWith(home) && !p.equals(home) && !excluded(p)
            && managedRoots.stream().noneMatch(p::startsWith)
            && !p.startsWith(trash.resolve("info")) && !p.getFileName().toString().equals(".git-credentials")
            && !p.getFileName().toString().equals(".npmrc")
            && !p.getFileName().toString().equals(".env") && !p.toString().contains("/.git/");
    }
    public Kind classify(Path path, Instant modified, Instant now) {
        Path p = path.toAbsolutePath().normalize();
        if (p.startsWith(trash.resolve("files"))) return Kind.TRASH;
        if (!p.startsWith(home)) return p.startsWith(Path.of("/var/cache")) ? Kind.SYSTEM : Kind.LARGE;
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean old7 = modified.isBefore(now.minus(Duration.ofDays(7)));
        if (old7 && PARTIAL_SUFFIXES.stream().anyMatch(name::endsWith)) return Kind.PARTIAL;
        if (p.startsWith(downloads) && modified.isBefore(now.minus(Duration.ofDays(90)))) return Kind.OLD_DOWNLOAD;
        if (p.startsWith(cache) && old7) {
            Path relative = cache.relativize(p);
            String owner = relative.getName(0).toString().toLowerCase(Locale.ROOT);
            // Only an explicit allowlist is eligible; .cache alone is not evidence of disposability.
            boolean known = KNOWN_CACHE.contains(owner);
            if (known && PERSISTENT_CACHE.stream().noneMatch(owner::contains)) return Kind.CACHE;
        }
        if (p.startsWith(home.resolve(".npm/_cacache")) && old7) return Kind.CACHE;
        return Kind.LARGE;
    }
    public String note(Path path, Kind kind) {
        if (path.startsWith(cache.resolve("huggingface"))) return "Hugging Face models excluded from cleanup at your request";
        if (path.startsWith(cache.resolve("uv"))) return "Managed uv cache; use Bulk caches so uv can honor its locks";
        if (ideCaches.instance(path) != null) return ideCaches.cacheFile(path)
            ? "Rebuildable IDE index/build cache; use Bulk caches and close this IDE first"
            : "Preserved IDE state, Local History, plugins or VFS; excluded from cleanup";
        if (managedRoots.stream().anyMatch(path::startsWith)) return "Managed application/container storage; use the owning app's storage tools";
        if (kind == Kind.CACHE) return kind.explanation + "; unchanged 7+ days";
        if (kind == Kind.LARGE && path.startsWith(cache)) return "Cache location may contain models or tools; kept for manual review";
        return kind.explanation;
    }
    /** Refuse cleanup through symlinks, including a parent replaced after scanning. */
    public boolean hasSymlinkAncestor(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        for (Path part = absolute; part != null; part = part.getParent()) {
            if (Files.isSymbolicLink(part)) return true;
        }
        return false;
    }
}
