# Storage Lens

A desktop storage inspector and reviewed cleaner for this Garuda Linux laptop. Java 21 and Swing provide the desktop interface; Micronaut manages the local scan, cleanup, inventory, drive-health and audit services. C components read allocated filesystem blocks and NVMe SMART counters. Sparse disk images do not inflate the disk-use rankings. The application does not run a web server or send scan results anywhere.

## Open the application

Use **Storage Lens** on the desktop or in the application menu, or run:

```bash
/home/kaal/storage-lens/launch.sh
```

The packaged application in `dist/StorageLens/` includes its own Java runtime. Keep that directory intact. The JAR fallback uses the installed Java 21 runtime. Do not run the desktop cleaner with sudo.

Use **Storage Lens Widget** on the desktop/application menu, the dashboard's **Widget** button, or `./launch.sh --widget` for the compact floating toolbar. It shows free space, refreshes every 30 seconds and opens the dashboard, scan, cleanup and Docker screens. Starting only the widget does not scan the home folder. A system-tray icon and menu are added when the desktop supports them. Closing the dashboard keeps it available through the tray or visible widget; **Quit** ends the application. No login autostart is installed.

## Use it

1. **Scan home** starts a read-only scan. **Choose folder** inspects a particular directory, including hidden directories. **Full disk** reads accessible locations across the internal filesystem. Stop a scan to keep partial results.
2. **Overview** shows available disk space, candidate sizes and immediate folder sizes. Click a folder to scan inside it. Files directly in the scan root appear under that root's name.
3. **Cleanup** lists suggestions with their exact paths, modification dates and review reasons. Search or filter the results, then select individual files or use **Select visible**. Previously checked files remain selected when filtered out; the review dialog lists every selected file.
   **Review cache cleanup…** prepares a bulk batch of all eligible cache files retained from the current scan, including files hidden by filters. It excludes downloads, personal files and protected caches. Review the full paths and type `DELETE` to permanently remove that batch. Close the owning applications first. This action uses the same per-file identity, link and audit checks as individual cleanup. The 30,000-candidate retention limit described below still applies; scan a smaller cache directory when that limit is reached.
4. **Large files** shows up to 500 regular files with a logical size of at least 25 MiB, ranked by allocated disk size. **On disk** and **File size** are shown separately. Size does not establish whether a personal file is unnecessary. Double-click a row to open its containing folder.
5. **Move to Trash** writes standard desktop Trash metadata so the file manager can restore the files. Moving files to Trash does not free disk space. **Delete permanently** shows the selected paths and requires typing `DELETE` before removal.
6. **Installed apps** lists pacman packages and Flatpak apps by installed size. Filter for **Orphan dependencies** to find dependencies no longer required by another installed package. Their usefulness still needs your review. Usage of other apps is shown as unknown; no launch history is invented.
7. **Review uninstall** displays the exact command and requires the package/application ID. It opens an interactive terminal. The package manager shows the proposed transaction and asks for confirmation before removal. Refresh the app list after the terminal finishes.
8. **Export report** saves a new CSV containing scan results, folder aggregates and package sizes. Exports never overwrite an existing file. **Activity** shows the local append-only action log.
9. **Docker** reads the selected local Docker context and explains which containers and volumes are protected or eligible. It offers reviewed build-cache cleanup and selected isolated test-resource removal. Export its assessment separately with **Export audit…**.
10. **Bulk caches** measures every recognized JetBrains version, uv and downloaded package archives. It is also reachable from **Cleanup → JetBrains / uv cleanup…**. Hugging Face is excluded from deletion.
11. **Drive health** shows the NVMe model, estimated endurance remaining, lifetime host writes and a remaining rated write budget. The dashboard includes a drive-health summary and opens this page.

## Bulk cache cleanup

The dedicated **Bulk caches** review covers current and older IntelliJ IDEA, Community, CLion and other recognized JetBrains product versions. Standard Linux, older home-based system directories, Snap and Flatpak cache locations are recognized. Only files under `index`, `shared-indexes`, `compiler` and `compile-server` are included, at every age. **LocalHistory**, persistent VFS records under `caches`, plugins, settings, projects, model stores and unrecognized directories are preserved. A package archive named `intellij-*.pkg.tar.zst` in `/var/cache/pacman/pkg` is a downloaded installer archive; it does not establish another installed IDE instance.

Close the IDEs, open **Bulk caches**, select the closed instances and use **Review selected IDE caches…**. The review lists each root, file count and allocated size and requires `DELETE IDE CACHES`. Later project loading can take longer while indexes and build caches are recreated. [JetBrains documents cache recreation and Local History separately](https://www.jetbrains.com/help/idea/invalidate-caches.html).

This review does not use the ordinary scanner's 30,000-candidate cap. It exports every reviewed file. A one-million-file limit per IDE blocks the entire instance rather than silently omitting files. All selected instances are revalidated before the first deletion. Process checks, directory identity/modification checks, per-file identity checks, no-link checks, and audit recording run before removal. Activity is checked between batches, and cleanup stops on cancellation, a changed file, an active IDE or a logging failure. Completed deletions cannot be undone; unattempted files remain. If IDE activity cannot be verified in an isolated session, deletion is blocked. Run the application in the normal laptop desktop session.

The **uv / system package cache** tab uses existing native maintenance tools:

- **Prune unused uv cache…** uses the existing native `uv cache prune` command with its own locking, offline mode and a five-second lock wait. It removes unused entries, including cached environments no longer attached to projects. **Clear all uv cache…** is a separately reviewed full clear. Both are limited to the verified standard `~/.cache/uv` cache. They start no language runtime and do not target project directories or Hugging Face. Downloads or rebuilds may be needed later. The [uv cache documentation](https://docs.astral.sh/uv/concepts/cache/) explains why managed caches should use the native tool rather than manual directory removal. Both the native report and the observed filesystem free-space change are shown; hardlinks, compression and snapshots can make those numbers differ.
- **Review package cache…** previews `paccache --dryrun --keep 3` and opens a visible terminal for `sudo -- paccache --remove --keep 3` after `CLEAN PACKAGE CACHE` is entered. This retains the newest three downloaded versions per package and does not uninstall installed programs. Older cached versions will no longer be available for an offline rollback. The terminal requires system authentication; refresh after it completes. [paccache documents these options](https://man.archlinux.org/man/paccache.8).

On 24 November, the actual uv prune reported **97,888 files / 11.6 GiB removed**; the immediately observed net filesystem free-space increase was **2,202,058,752 bytes (about 2.05 GiB)**. The uv folder then measured **72 KiB**. The read-only review found **3.7 GiB** of index/build-cache candidates across four recognized JetBrains versions, and the package-cache dry run reported **196 candidates / 5.17 GiB**. Those latter two reviews have not deleted files. Hugging Face was left untouched. See [the current local assessment](dist/storage-cleanup-review-20261124.txt) and [the full IDE review](dist/ide-cache-review-20261124.csv).

## NVMe endurance and writes

The native C helper accepts only `--list` or `--read nvmeN`. It opens the controller read only and issues the SMART/Health log read. It has no format, write, firmware, arbitrary-device-path or arbitrary-command mode. The application never elevates the desktop cleaner. On a normal desktop, **Authorize SMART read…** uses the system authentication dialog to run only this read-only helper when hardware access is denied. No permanent permission or setuid change is installed. Drive serial numbers are not read or logged.

- **Endurance remaining** is `max(0, 100 - SMART Percentage Used)`. This is the controller vendor's estimated wear, not a predicted failure date. Percentage Used may exceed 100.
- **Lifetime host writes** converts the unsigned 128-bit Data Units Written counter to bytes using 512,000 bytes per unit and displays decimal TB. The actual host-write-command count is shown separately. Zero unsupported counters and unavailable SMART data display as unavailable.
- **Rated writes remaining** is `max(0, exact-model manufacturer TBW - lifetime host-write TB)`. Enter a verified TBW rating for the exact model/capacity and save it locally. An unknown rating is not guessed. This is an estimated rated budget; the number or size of future write commands cannot be known. Removing caches frees capacity without reversing wear.

The calculation follows the [NVM Express SMART definitions](https://nvmexpress.org/wp-content/uploads/NVM-Express-Base-Specification-Revision-2.1-2024.08.05-Ratified.pdf). The laptop identifies as **CT1000E100SSD8**. Its controller device node is hidden from this workspace, so actual endurance and lifetime-write values could not be read here. The installed app must read them in the laptop session. No manufacturer rating has been assumed or saved by this work.

## Docker cleanup

Docker operations use its local Unix-socket Engine API through the system `/usr/bin/curl`; Docker Desktop must be running and the current user must have socket access. Internet access is not needed for the assessment itself. Remote Docker endpoints are refused. Context and Engine identity are rechecked before every reviewed action. Failed refreshes clear the assessment and disable cleanup until a fresh assessment succeeds.

- **Review build-cache cleanup…** defaults to dangling cache unused for at least seven days. The two-day and all-dangling-cache options are available explicitly. This calls Docker's build-cache prune endpoint with `all=false`; it retains containers, images and volumes. Later builds may take longer and require downloads. The cards show an all-ages estimate; shared layers, the age filter and Docker's virtual disk affect real savings. The result displays Docker's reported reclaimed bytes.
- Only stopped containers in the verified **kaal-balance-check** and **kaal-infra-check** Compose projects are eligible. Their full IDs, labels and stopped state are rechecked. Removal uses `force=false` and `v=false`, preserving named and anonymous volumes. Container-local changes are lost; the test stacks can be recreated from their Compose files.
- Only the corresponding test volumes for `api-primary`, `api-secondary`, `kafka-data` and `redis-data` are eligible, after their container references reach zero. The name, project, creation identity and reference count are checked again before deletion. This permanently resets those test stacks' data and requires typing `DELETE TEST DATA`.
- Existing project databases, active containers and resources with unknown owners remain protected, including `oracle_data`, `mysql_data`, `mongo_data`, the older `kaal_kafka*_data` volumes and the anonymous volume used by `PG-vector-db`. An unreferenced volume is not automatically disposable. There is no bulk system/volume prune, image deletion or direct modification of `Docker.raw`.

**Reclaim Desktop disk space…** compares the allocated blocks in the default `Docker.raw` against Engine-reported image layers and opens the reviewed reclamation command in a visible terminal. It requires `RECLAIM DESKTOP SPACE`, rechecks the context and Engine identity, verifies Docker Desktop, and pins Docker's small native helper to the reviewed Linux x86_64 image digest. The command uses `--rm --privileged --pid=host` inside the Desktop VM, a fixed local socket and no host-directory mounts. It requests return of unused VM blocks while retaining existing containers, images and volume data. The helper may need to be downloaded. Modern Docker Desktop may already reclaim unused blocks automatically; actual savings may be zero and filesystem snapshots can retain blocks. Refresh Docker after the terminal finishes. The app never deletes `Docker.raw` or reduces its maximum size. See [Docker's Linux disk-space guidance](https://docs.docker.com/desktop/troubleshoot-and-support/faqs/linuxfaqs/).

Nothing is checked automatically. Cache cleanup requires `PRUNE CACHE`; container removal requires `REMOVE CONTAINERS`. Requests must be audited before mutation. The 24 November audit found **91.0 GiB** of VM allocation, **11.8 GiB** of Engine image layers, **720 MiB** of volume data and **zero build-cache records**. These figures overlap and are not a promise of reclaimable space. All 11 current volumes are referenced and protected; the nine existing containers are preserved. This work did not remove Docker objects or execute the VM reclamation helper. The refreshed [Docker audit](dist/docker-storage-review-20261124.csv) and [storage assessment](dist/storage-cleanup-review-20261124.txt) supersede the historical 23 November snapshot. Refresh in the app before acting. The assessment covers `desktop-linux`, not any other Docker daemon on the laptop.

Docker's documented [build-cache prune API](https://docs.docker.com/reference/api/engine/version/v1.57/operations/BuildPrune/) and [container removal API](https://docs.docker.com/reference/api/engine/version/v1.57/operations/ContainerDelete/) define the action scopes. Broad [system prune](https://docs.docker.com/reference/cli/docker/system/prune/) can remove unrelated stopped resources, so it is not used here.

## How suggestions work

- Known rebuildable user caches must be unmodified for at least seven days. Suggestions include thumbnail, shader, font, browser and selected build caches, plus npm's content cache. Close the owning application before cleaning a cache.
- Partial-download suffixes include `.part`, `.partial`, `.crdownload`, `.download`, `.zmdownload`, `.aria2` and `.filepart`. An item must be unmodified for at least seven days; it can still be resumable, so confirm it is abandoned.
- Downloads unmodified for 90 days are labeled **Older download**, with an explicit reminder that age does not prove they are unused.
- Existing regular files in the desktop Trash are listed for permanent-removal review.
- Model stores, unknown cache directories, tool installations, dependency stores and personal files are not automatically classified as disposable. Inspect their folder sizes and large files manually.
- System package caches are visible during a full-disk scan and remain read only in the file browser. Their dedicated Bulk caches action uses the package manager's maintenance tool in an authenticated terminal.

## Boundaries and size estimates

Scanning reads metadata rather than file contents, never follows symbolic links, and skips protected settings/credential directories, virtual filesystems, system snapshots, mounted-media roots, and container data. Inaccessible paths are counted and reported. This means a scan is an accessible-file inventory, not a claim to account for every byte on the drive. Scanning a chosen directory is available for narrower investigations.

Only regular files inside the current user's home directory can be changed by filesystem cleanup. Protected locations, Trash metadata and credential files are excluded. Managed Docker, container, libvirt and Flatpak stores remain visible and read only in the file browser; the Docker page uses Docker's own maintenance API. Every file action checks size, modification time and file identity again; changed, missing, linked and hard-linked files are refused. Cleanup opens directory handles without following symlinks and fails closed if the filesystem does not provide secure directory streams. The app does not recursively delete directories. An audit request must be recorded before changing a file.

The ordinary file browser keeps the largest 30,000 cleanup candidates and 500 large files to bound memory consumption. A warning appears when more cleanup candidates exist; scan a smaller directory to inspect the remainder. The dedicated Bulk caches IDE review has its separate complete-review limit described above. Category totals can therefore exceed the files retained for selection. Disk sizes use `lstat().st_blocks × 512` and account for sparse files. Logical sizes are retained separately in file rows and exports. Hard links, Btrfs shared extents, compression and snapshots still affect actual storage recovered. Folder aggregates and file rows overlap and should not be added together. Installed package sizes also overlap with shared files and dependencies. If the native reader cannot load, the app states that it is falling back to logical sizes.

Protected system components and required dependencies cannot be uninstalled through this application. Package removal uses interactive `pacman -Rns` or `flatpak uninstall`; it never uses a shell or disables the package manager's confirmation. Portable applications and installations outside those managers are visible as files and folders, not as invented package entries.

## Build, test and package

```bash
cd /home/kaal/storage-lens
./build.sh
./package.sh
./install.sh
```

The build compiles the C/JNI filesystem reader and C NVMe reader with GCC, checks the native SMART decoder using explicit synthetic counters, uses Java dependencies already cached on this laptop, and runs JUnit safety and desktop component tests. For a fresh machine with an empty Maven cache, populate dependencies using `mvn --batch-mode --no-transfer-progress -DskipTests package` with network access, then run `./build.sh` to compile the native components and run the full tests. Packaging uses the installed JDK's `jpackage` and `jlink`; it refuses to overwrite a previous `dist/StorageLens` release. Move that previous release aside to package a later build.

The release archive is `dist/StorageLens-1.0.0-linux-x64.tar.gz`. Its runtime and executable require a compatible Linux x86_64 system, desktop session and system GUI libraries; it is not a Windows/macOS build. It has no additional language runtime or model service.

The C launcher starts the bundled JVM directly. The runtime includes the ZIP filesystem provider used by Micronaut's service discovery. Release packaging checks the actual executable with a temporary read-only scan before producing the archive.

```bash
./launch.sh --scan /home/kaal --report /tmp/storage-report.csv
./launch.sh --inventory
./launch.sh --docker-audit /tmp/docker-storage-review.csv
./launch.sh --preview /tmp/storage-previews --root /home/kaal/Downloads
./launch.sh --help
```

These command-line modes only inspect and export. There is no command-line deletion/uninstall mode. Preview mode paints the actual Swing screens from a real scan and works without desktop access; it does not prove that a desktop window opened.

## Verification on this laptop

All 76 JUnit checks passed, including safe deletion, changed-file rejection, symlink and hard-link protection, standard Trash restore metadata, sparse-file allocation against Linux `stat`, package metadata parsing, command argument validation, filtering/selection and desktop rendering. IDE checks include a complete review exceeding 30,000 files, all-age cleanup across versions, preservation of persistent data, changes after review, activity before and between deletion batches, cancellation and audit failures. Native-tool checks validate exact uv scope, locking and package-cache commands. NVMe checks cover 128-bit counters, units, unavailable values and rated-budget calculations. The dashboard regression checks repeated resize/reflow at widths from 900 to 1920 pixels, horizontal containment and visible nonoverlapping sections. Docker tests cover context/Engine changes, containers started after review, referenced or replaced volumes, protected databases, required audit logging and exact action scopes using a fake Engine; reclamation checks use a fake terminal without executing a privileged container. Widget callbacks are also covered. The bundled executable completed a read-only scan and loaded the installed-package inventory.

The earlier home scan and desktop previews are in `dist/release-scan.csv`, `dist/release-preview/` and `dist/feature-preview-20261123/`. Those are historical snapshots. Current headless renders of the actual dashboard, complete IDE review, native-cache controls, Docker page and Drive health are in `dist/cache-health-preview-20261124/`, including narrow and short windows. Candidate sizes are amounts to review, not promised recoverable space. The actual uv cleanup is recorded above. No personal project file, Hugging Face model, installed package, Docker container or volume was removed by this work.

Internet access was retried successfully. The live Docker read-only audit was refreshed through its local socket, but a socket connection from the application process remains restricted by this workspace. Docker mutations were verified against a fake Engine only. This workspace also isolates process visibility, hides NVMe device nodes and blocks connections to desktop display servers, so live window operation, IDE idleness and actual SMART counters could not be verified here. Open the installed desktop/menu shortcuts from the laptop session. Quit and reopen Storage Lens to load the update; closing the dashboard can leave the old process in the tray. `tools/DesktopSmoke.java` is available for a future graphical-session check. `tools/CacheAndHealthReview.java` obtains the Micronaut services, creates full read-only assessments and renders the actual updated components without starting cleanup or requesting authentication.

## Review artifacts

`scripts/review.sh` displays every edited source/configuration file and its incremental diff, and appends them to `.review/review.log`. Build/test commands and results are recorded in the same log. Binary paths, sizes and SHA-256 checksums are recorded as artifacts are created; `dist/StorageLens-1.0.0.manifest.txt` lists all packaged files. No secret values are included in these logs.

To watch the build review on the desktop:

```bash
konsole -e tail -n 100 -F /home/kaal/storage-lens/.review/review.log
```

Runtime requests, outcomes and skipped-file reasons are appended to `~/.local/share/storage-lens/activity.log` with private permissions. Only paths and action metadata are logged. Removing the app launcher does not remove personal data or these logs.

The service container follows [Micronaut's ApplicationContext API](https://docs.micronaut.io/4.9.5/api/io/micronaut/context/ApplicationContext.html); this project pins the locally available Micronaut 4.10.36 dependency.
