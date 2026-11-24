# Changelog

## 0.1.1

- Publish Desktop and iOS native ZIPs under `core`. The Gradle plugin resolves them from the matching `core` version.
- The `ios-native-libs` classifier replaces `ios-arm64-native-libs` and includes both device and simulator libraries.
- Publish libraries before the Gradle plugin in CI.

## 0.1.0

First stable release, prepared for publication.

- Kotlin Multiplatform libmpv player bindings for Android, iOS, and Desktop (JVM).
- Compose integration for player state and platform video rendering.
- Playback service, platform media sessions, interruption handling, and picture-in-picture support.
- Coil artwork loading and a Gradle plugin for packaging Desktop and iOS native libraries.
- Configurable mpv logging and normalized buffering progress in playback snapshots.
- Android consumer keep rules for apps using R8.
- Compose Multiplatform 1.12.1, Kotlin 2.4.21, and Coil 3.6.3; Ktor remains at 3.5.2.
