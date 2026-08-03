#!/bin/bash
set -e
if [ -n "${AR_REAL:-}" ]; then
  cmd=("$AR_REAL")
elif command -v llvm-ar >/dev/null 2>&1; then
  cmd=(llvm-ar)
elif command -v zig >/dev/null 2>&1; then
  cmd=(zig ar)
elif command -v ar >/dev/null 2>&1; then
  cmd=(ar)
else
  echo "No compatible archiver found; install llvm-ar or Zig, or set AR_REAL." >&2
  exit 1
fi
args=()
processed_flags=0
for a in "$@"; do
  if [ "$a" = "-T" ]; then
    continue
  fi
  if [ $processed_flags -eq 0 ] && [[ "$a" =~ ^[A-Za-z]+$ ]]; then
    args+=("${a//T/}")
    processed_flags=1
    continue
  fi
  args+=("$a")
done
exec "${cmd[@]}" "${args[@]}"
