#!/bin/bash
set -e
cmd="${AR_REAL:-llvm-ar}"
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
exec "$cmd" "${args[@]}"
