#!/bin/sh
set -e

case "${CONFIGURATION:-Debug}" in
  *Release*|*release*) export KOTLIN_FRAMEWORK_BUILD_TYPE=release ;;
  *) export KOTLIN_FRAMEWORK_BUILD_TYPE=debug ;;
esac
KOTLIN_FRAMEWORK_BUILD_TYPE_ARG="-PKOTLIN_FRAMEWORK_BUILD_TYPE=$KOTLIN_FRAMEWORK_BUILD_TYPE"

COMPOSE_IOS_RESOURCES_ARCHS_ARG=""
if [ "$PLATFORM_NAME" = "iphonesimulator" ]; then
  COMPOSE_IOS_RESOURCES_ARCHS_ARG="-Pcompose.ios.resources.archs=arm64"
  ./gradlew "$KOTLIN_FRAMEWORK_BUILD_TYPE_ARG" $COMPOSE_IOS_RESOURCES_ARCHS_ARG :example:shared:embedAndSignAppleFrameworkForXcode

  # Shared.framework is configured as a static Kotlin/Native framework. The
  # default Xcode task still copies it into the app's Frameworks directory for
  # simulator builds, but static archives are not valid embedded frameworks.
  if [ -n "$TARGET_BUILD_DIR" ]; then
    rm -rf "$TARGET_BUILD_DIR/${FRAMEWORKS_FOLDER_PATH:-Frameworks}/Shared.framework"
  fi
else
  ./gradlew "$KOTLIN_FRAMEWORK_BUILD_TYPE_ARG" :example:shared:mpvKmpEmbedAndSignAppleFrameworkForXcode
fi
