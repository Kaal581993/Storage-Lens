#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd -- "$project_dir"
[[ -f target/storage-lens.jar ]] || { printf 'Run ./build.sh first.\n' >&2; exit 1; }
if [[ ! -f packaging/storage-lens.png ]]; then
  ./scripts/review.sh run java -jar target/storage-lens.jar --icon packaging/storage-lens.png
  ./scripts/review.sh binary packaging/storage-lens.png
fi
desktop_entry="$project_dir/packaging/storage-lens.desktop"
cat > "$desktop_entry" <<EOF
[Desktop Entry]
Version=1.0
Type=Application
Name=Storage Lens
GenericName=Laptop Storage Cleaner
Comment=Inspect caches, incomplete downloads, large files and installed applications
Exec="$project_dir/launch.sh"
Icon=$project_dir/packaging/storage-lens.png
Terminal=false
Categories=Utility;FileTools;
Keywords=storage;disk;cache;cleaner;downloads;uninstall;
StartupNotify=true
StartupWMClass=dev-kaal-storagelens-StorageLens
Actions=Widget;

[Desktop Action Widget]
Name=Open toolbar widget
Exec="$project_dir/launch.sh" --widget
EOF
widget_entry="$project_dir/packaging/storage-lens-widget.desktop"
cat > "$widget_entry" <<EOF
[Desktop Entry]
Version=1.0
Type=Application
Name=Storage Lens Widget
GenericName=Storage Toolbar
Comment=Show free space and open reviewed cache and Docker cleanup
Exec="$project_dir/launch.sh" --widget
Icon=$project_dir/packaging/storage-lens.png
Terminal=false
Categories=Utility;FileTools;
Keywords=storage;disk;cache;docker;widget;toolbar;
StartupNotify=false
StartupWMClass=dev-kaal-storagelens-StorageLens
EOF
./scripts/review.sh "$desktop_entry" "$widget_entry"
./scripts/review.sh run desktop-file-validate "$desktop_entry" "$widget_entry"
install -Dm644 "$desktop_entry" "$HOME/.local/share/applications/storage-lens.desktop"
install -Dm755 "$desktop_entry" "$HOME/Desktop/Storage Lens.desktop"
install -Dm644 "$widget_entry" "$HOME/.local/share/applications/storage-lens-widget.desktop"
install -Dm755 "$widget_entry" "$HOME/Desktop/Storage Lens Widget.desktop"
./scripts/review.sh "$HOME/.local/share/applications/storage-lens.desktop" "$HOME/Desktop/Storage Lens.desktop" \
  "$HOME/.local/share/applications/storage-lens-widget.desktop" "$HOME/Desktop/Storage Lens Widget.desktop"
if command -v update-desktop-database >/dev/null 2>&1; then
  ./scripts/review.sh run update-desktop-database "$HOME/.local/share/applications"
fi
printf 'Installed Storage Lens and its toolbar widget in the application menu and on the desktop.\n' | tee -a .review/review.log
