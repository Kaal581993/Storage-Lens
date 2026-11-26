#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd -- "$project_dir"
[[ -f target/storage-lens.jar ]] || { printf 'Run ./build.sh first.\n' >&2; exit 1; }
[[ ! -e dist/StorageLens ]] || { printf 'dist/StorageLens already exists; keep or rename the previous release before packaging.\n' >&2; exit 1; }
mkdir -p target/app-input dist
cp -- target/storage-lens.jar target/app-input/storage-lens.jar
if [[ ! -f packaging/storage-lens.png ]]; then
  ./scripts/review.sh run java -jar target/storage-lens.jar --icon packaging/storage-lens.png
fi
./scripts/review.sh binary packaging/storage-lens.png
./scripts/review.sh run jpackage --type app-image --name StorageLens --app-version 1.0.0 \
  --vendor 'Kaal' --description 'Storage inspector and reviewed desktop cleaner' \
  --input target/app-input --main-jar storage-lens.jar --dest dist --icon packaging/storage-lens.png \
  --java-options '-Xmx768m' --java-options '-Dawt.useSystemAAFontSettings=on' \
  --add-modules java.base,java.desktop,java.logging,java.management,java.naming,java.sql,jdk.unsupported,jdk.crypto.ec,jdk.zipfs \
  --jlink-options '--strip-debug --no-header-files --no-man-pages'
./scripts/review.sh binary dist/StorageLens/bin/StorageLens
./scripts/review.sh run gcc -std=c11 -O2 -Wall -Wextra -Werror native/launcher.c -o dist/StorageLens/bin/StorageLens
./scripts/review.sh run install -m755 target/native/storage-lens-nvme dist/StorageLens/bin/storage-lens-nvme
./scripts/review.sh binary dist/StorageLens/bin/storage-lens-nvme
./scripts/review.sh binary dist/StorageLens/bin/StorageLens
./scripts/review.sh run dist/StorageLens/bin/StorageLens --help
smoke_dir="$(mktemp -d /tmp/storage-lens-runtime-XXXXXX)"
trap 'rm -f -- "$smoke_dir/read-only-fixture"; rmdir -- "$smoke_dir"' EXIT
printf 'Storage Lens runtime check\n' > "$smoke_dir/read-only-fixture"
./scripts/review.sh run dist/StorageLens/bin/StorageLens --scan "$smoke_dir"
manifest="dist/StorageLens-1.0.0.manifest.txt"
while IFS= read -r -d '' artifact; do
  printf '%s\t%s bytes\t%s\n' "$artifact" "$(stat -c %s -- "$artifact")" "$(sha256sum -- "$artifact" | cut -d' ' -f1)"
done < <(rg --files --hidden --no-ignore -0 dist/StorageLens | sort -z) | tee "$manifest" | tee -a .review/review.log
./scripts/review.sh run tar -czf dist/StorageLens-1.0.0-linux-x64.tar.gz -C dist StorageLens
./scripts/review.sh binary dist/StorageLens/bin/StorageLens dist/StorageLens-1.0.0-linux-x64.tar.gz
./scripts/review.sh run du -sh dist/StorageLens
