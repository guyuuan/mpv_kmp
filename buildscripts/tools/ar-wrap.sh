#!/bin/bash
set -e
cmd="${AR_REAL:-llvm-ar}"
args=()
for a in "$@"; do
  if [ "$a" = "-T" ]; then
    continue
  fi
  if [[ "$a" == *"T"* ]]; then
    a="${a//T/}"
    if [ -z "$a" ] || [ "$a" = "-" ]; then
      continue
    fi
  fi
  args+=("$a")
done
exec "$cmd" "${args[@]}"
