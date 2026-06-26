#!/bin/sh
set -e

COMPOSE_IOS_RESOURCES_ARCHS_ARG=""
if [ "$PLATFORM_NAME" = "iphonesimulator" ]; then
  COMPOSE_IOS_RESOURCES_ARCHS_ARG="-Pcompose.ios.resources.archs=arm64"
fi

./gradlew :example:shared:embedAndSignAppleFrameworkForXcode $COMPOSE_IOS_RESOURCES_ARCHS_ARG

if [ "$PLATFORM_NAME" != "iphoneos" ]; then
  exit 0
fi

MPV_NATIVE_LIB_DIR="$SRCROOT/lib"
if [ ! -d "$MPV_NATIVE_LIB_DIR" ]; then
  echo "Missing iOS mpv native library directory: $MPV_NATIVE_LIB_DIR" >&2
  exit 1
fi

MPV_FRAMEWORKS_DIR="$TARGET_BUILD_DIR/$FRAMEWORKS_FOLDER_PATH"
mkdir -p "$MPV_FRAMEWORKS_DIR"

validate_dylib_references() {
  dylib="$1"

  if otool -L "$dylib" | grep -E '(/usr/local/lib/lib.*\.dylib|@rpath/lib.*\.[0-9]+.*\.dylib)' >/dev/null; then
    echo "Invalid iOS dylib load path in $(basename "$dylib"):" >&2
    otool -L "$dylib" | grep -E '(/usr/local/lib/lib.*\.dylib|@rpath/lib.*\.[0-9]+.*\.dylib)' >&2
    echo "Rebuild the iOS native libraries so install names use @rpath/libxxx.dylib." >&2
    exit 1
  fi
}

for dylib in "$MPV_FRAMEWORKS_DIR"/lib*.dylib; do
  [ -e "$dylib" ] || continue
  rm -f "$dylib"
done

for dylib in "$MPV_NATIVE_LIB_DIR"/lib*.dylib; do
  [ -e "$dylib" ] || continue
  dylib_name=$(basename "$dylib")
  case "$dylib_name" in
    lib*.*.dylib) continue ;;
  esac
  cp -fL "$dylib" "$MPV_FRAMEWORKS_DIR/$dylib_name"
  chmod +w "$MPV_FRAMEWORKS_DIR/$dylib_name"
done

for dylib in "$MPV_FRAMEWORKS_DIR"/lib*.dylib; do
  [ -e "$dylib" ] || continue
  validate_dylib_references "$dylib"

  if [ "${CODE_SIGNING_ALLOWED:-}" != "NO" ] && [ -n "${EXPANDED_CODE_SIGN_IDENTITY:-}" ]; then
    if ! codesign --force --sign "$EXPANDED_CODE_SIGN_IDENTITY" --timestamp=none --verbose=2 "$dylib"; then
      echo "Failed to code sign dylib: $dylib" >&2
      echo "Signing identity: $EXPANDED_CODE_SIGN_IDENTITY" >&2
      echo "Available code signing identities:" >&2
      security find-identity -v -p codesigning >&2 || true
      exit 1
    fi
  fi
done
