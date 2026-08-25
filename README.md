# mpv_kmp

Kotlin Multiplatform bindings for [mpv](https://mpv.io/) (libmpv), targeting **Android, iOS, and Desktop (JVM)**.

## Modules

| Module | Description |
| --- | --- |
| `mpv/core` | Reusable multiplatform libmpv interop layer; the Android target packages a Kotlin/Native JNI bridge for `arm64-v8a` and `x86_64` directly in the AAR. |
| `mpv/compose` | Compose player state and platform video rendering controls. |
| `mpv/service` | Application-level playback ownership, platform media sessions, and interruption handling. |
| `mpv/pip` | Picture-in-picture integration. |
| `mpv/loader-coil` | Playback artwork URI resolution with Coil on all platforms. |
| `mpv-gradle-plugin` | Gradle plugin that wires native mpv/FFmpeg libraries into Desktop and iOS consumers. |

Sample apps live in `example/` (shared Compose UI, Android, Desktop) and `iosApp/` (iOS).

## Getting started

- **Android**: `./gradlew :example:androidApp:assembleDebug`
- **Desktop (JVM)**: `./gradlew :example:desktopApp:run`
- **iOS**: open `iosApp` in Xcode and run from there

(On Windows use `.\gradlew.bat …`.)

## Using the library

```kotlin
plugins {
    id("com.guyuuan.kmp.mpv") version "<version>"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.guyuuan.kmp.mpv:core:<version>")
        }
    }
}
```

**Desktop**: the plugin extracts the host's mpv native libraries, passes the directory to `run`, and bundles them into Compose Desktop app resources. Runtime search order: `-Dmpv.kmp.native.dir` → Compose resources (`mpv-kmp/<platform>`) → legacy `mpv` JARs → system mpv. Override with `mpvKmp.desktopNativeDirectoryOverride`.

**iOS**: the plugin resolves the `ios-native-libs` ZIP, links the framework against `libmpv.dylib`, and embeds/signs the mpv & FFmpeg dylibs. Add to the Kotlin framework Run Script phase:

```sh
./gradlew :shared:mpvKmpEmbedAndSignAppleFrameworkForXcode
```

## Maven Central

Published under group `com.guyuuan.kmp.mpv`:

| Module | Artifact |
| --- | --- |
| Core player bindings | `com.guyuuan.kmp.mpv:core` |
| Compose integration | `com.guyuuan.kmp.mpv:compose` |
| Playback service | `com.guyuuan.kmp.mpv:service` |
| Picture-in-picture | `com.guyuuan.kmp.mpv:pip` |
| Coil artwork loader | `com.guyuuan.kmp.mpv:loader-coil` |
| Gradle plugin | `com.guyuuan.kmp.mpv:mpv-gradle-plugin` |

Kotlin APIs use the `com.guyuuan.kmp.mpv` package; platform artifacts (`core-android`, `core-jvm`, `core-iosarm64`, …) are resolved automatically from the root module metadata.

## Logging

Diagnostics go through [Kermit](https://kermit.touchlab.co/). The default platform logger works without setup; add Kermit as a direct dependency to configure custom log writers before creating a player.

## Notes

- Release native builds use size optimization, LTO, and symbol stripping; FFmpeg omits debug data, CLI programs, docs, `libavdevice`, and device I/O support.
- The Android AAR ships `arm64-v8a` and `x86_64`; use `splits.abi` (as the example does) so each APK carries only one ABI set.
- iOS native libraries include `iphoneos/arm64` and `iphonesimulator/arm64`; rebuild the simulator bundle with `./buildscripts/buildall.sh --platform ios --arch arm64-simulator mpv`.

## License

[Apache License 2.0](./LICENSE)
