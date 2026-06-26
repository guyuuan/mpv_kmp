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
  dylib_name=$(basename "$dylib")
  install_name_tool -id "@rpath/$dylib_name" "$dylib" 2>/dev/null || true

  for dep in "$MPV_FRAMEWORKS_DIR"/lib*.dylib; do
    [ -e "$dep" ] || continue
    dep_name=$(basename "$dep")
    install_name_tool -change "/usr/local/lib/$dep_name" "@rpath/$dep_name" "$dylib" 2>/dev/null || true
  done

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
