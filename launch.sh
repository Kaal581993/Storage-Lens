#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if [[ -x "$project_dir/dist/StorageLens/bin/StorageLens" ]]; then
  exec "$project_dir/dist/StorageLens/bin/StorageLens" "$@"
fi
if [[ ! -f "$project_dir/target/storage-lens.jar" ]]; then
  printf 'Build first: cd "%s" && ./build.sh\n' "$project_dir" >&2
  exit 1
fi
exec /usr/bin/java -Xmx768m -Dawt.useSystemAAFontSettings=on -Dswing.aatext=true -jar "$project_dir/target/storage-lens.jar" "$@"
