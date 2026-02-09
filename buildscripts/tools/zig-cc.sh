#!/bin/bash
set -e
if [ -z "$ZIG_TARGET" ]; then
  echo "ZIG_TARGET not set" >&2
  exit 1
fi
exec zig cc -target "$ZIG_TARGET" "$@"
