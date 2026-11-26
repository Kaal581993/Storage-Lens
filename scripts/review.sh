#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
review_dir="$project_dir/.review"
mkdir -p "$review_dir/snapshots"
review_log="$review_dir/review.log"
if [[ "${1:-}" == run ]]; then
  shift
  printf '\n[%s] COMMAND ' "$(date -Iseconds)" | tee -a "$review_log"
  printf '%q ' "$@" | tee -a "$review_log"
  printf '\n' | tee -a "$review_log"
  "$@" 2>&1 | tee -a "$review_log"
  exit "${PIPESTATUS[0]}"
fi
if [[ "${1:-}" == binary ]]; then
  shift
  for artifact in "$@"; do
    printf '\n[%s] BINARY %s\n' "$(date -Iseconds)" "$artifact" | tee -a "$review_log"
    stat --printf='Size: %s bytes\n' -- "$artifact" | tee -a "$review_log"
    sha256sum -- "$artifact" | tee -a "$review_log"
  done
  exit 0
fi
for source_file in "$@"; do
  [[ -f "$source_file" ]] || { printf 'Missing file: %s\n' "$source_file"; exit 1; }
  source_key="$(printf '%s' "$source_file" | sha256sum | cut -d' ' -f1)"
  previous="$review_dir/snapshots/$source_key"
  printf '\n[%s] SOURCE %s\n' "$(date -Iseconds)" "$source_file" | tee -a "$review_log"
  cat -- "$source_file" | tee -a "$review_log"
  printf '\nINCREMENTAL DIFF %s\n' "$source_file" | tee -a "$review_log"
  diff -u --label "previous/$source_file" --label "current/$source_file" "${previous:-/dev/null}" "$source_file" 2>/dev/null | tee -a "$review_log" || true
  if [[ ! -e "$previous" ]]; then
    diff -u --label /dev/null --label "$source_file" /dev/null "$source_file" | tee -a "$review_log" || true
  fi
  cp -- "$source_file" "$previous"
done
