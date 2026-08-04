This is a Kotlin Multiplatform project targeting Android, iOS, and Desktop (JVM).

The project follows the updated Kotlin Multiplatform structure with separate modules for shared code and runnable app entry points:

* [/mpv/core](./mpv/core/src) contains the reusable multiplatform libmpv interop layer.
* [/mpv/core/androidNative](./mpv/core/androidNative/src) contains the Android-only CMake/JNI bridge consumed by `mpv/core`.
* [/mpv/compose](./mpv/compose) contains the Compose player state and platform video rendering controls.
* [/mpv/service](./mpv/service) adds application-level playback ownership, platform media sessions, interruption handling, and playback restoration.
* [/mpv/service-coil](./mpv/service-coil) resolves playback artwork URIs with Coil on Android, iOS, and Desktop (JVM).
* [/example/shared](./example/shared/src) contains shared Compose UI for the sample app and exports the iOS framework.
* [/example/androidApp](./example/androidApp/src) contains the Android application entry point and Android app configuration.
* [/example/desktopApp](./example/desktopApp/src) contains the Desktop (JVM) application entry point and desktop packaging configuration.
* [/iosApp](./iosApp/iosApp) contains the iOS application that consumes the framework produced by `example:shared`.

### Build and Run Android Application

To build and run the development version of the Android app, use the run configuration from the run widget
in your IDE’s toolbar or build it directly from the terminal:
- on macOS/Linux
  ```shell
  ./gradlew :example:androidApp:assembleDebug
  ```
- on Windows
  ```shell
  .\gradlew.bat :example:androidApp:assembleDebug
  ```

### Build and Run Desktop (JVM) Application

To build and run the development version of the desktop app, use the run configuration from the run widget
in your IDE’s toolbar or run it directly from the terminal:
- on macOS/Linux
  ```shell
  ./gradlew :example:desktopApp:run
  ```
- on Windows
  ```shell
  .\gradlew.bat :example:desktopApp:run
  ```

### Desktop native library integration for Compose Multiplatform consumers

Compose Desktop consumers should apply the Gradle plugin in the desktop application module. The plugin extracts
the current host's mpv native libraries, passes the directory to `run`, and adds the same files to Compose Desktop
application resources for native distributions.

```kotlin
plugins {
    id("com.guyuuan.mpv-kmp")
}

dependencies {
    implementation("com.guyuuan.mpv_kmp:mpv:<version>")
}
```

The Gradle plugin JAR and the regular JVM library JAR are code-only. Native libraries are published separately as
`jvm-<os>-<arch>-native-libs` ZIP classifiers; the plugin resolves only the current build host's classifier from the
same dependency repositories used by the application. This avoids downloading every desktop platform for each
consumer.

The JVM runtime loads native libraries in this order:

1. `-Dmpv.kmp.native.dir=<dir>` provided by the plugin or by the application.
2. Compose Desktop app resources under `mpv-kmp/<platform>`.
3. Resources from legacy `mpv` JARs as a compatibility fallback.
4. The system `mpv` library as a final fallback.

Set `mpvKmp.desktopNativeDirectoryOverride` when an app wants to use externally built native libraries instead
of the plugin-bundled resources.

### Logging

The library writes diagnostic output through [Kermit](https://kermit.touchlab.co/). Kermit's default
platform logger works without application-side initialization. Applications that need custom filtering or log
writers can add Kermit as a direct dependency and configure its global `Logger` before creating a player.

### Build and Run iOS Application

To build and run the development version of the iOS app, use the run configuration from the run widget
in your IDE’s toolbar or open the [/iosApp](./iosApp) directory in Xcode and run it from there.

### iOS dylib integration for KMP consumers

KMP consumers should use the Gradle plugin together with the `mpv` dependency. The plugin resolves the separate
`ios-native-libs` ZIP, links the final iOS framework against `libmpv.dylib`, and embeds/signs the mpv and FFmpeg
dylibs during the Xcode framework build phase. The plugin's main JAR therefore does not make Android- or
desktop-only consumers download iOS binaries.

```kotlin
plugins {
    id("com.guyuuan.mpv-kmp")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("com.guyuuan.mpv_kmp:mpv:<version>")
        }
    }
}
```

Use the plugin task from the iOS app target's Kotlin framework Run Script phase:

```sh
cd "$SRCROOT/.."
./gradlew :shared:mpvKmpEmbedAndSignAppleFrameworkForXcode
```

The task expects the usual Xcode build environment variables such as `PLATFORM_NAME`,
`TARGET_BUILD_DIR`, `FRAMEWORKS_FOLDER_PATH`, `CONFIGURATION`, and the code-signing identity. It copies
the Kotlin framework and the required `lib*.dylib` files into `App.app/Frameworks` and signs them when
code signing is enabled.

The published iOS native libraries include `iphoneos/arm64` and `iphonesimulator/arm64`. Rebuild the simulator
bundle with:

```sh
./buildscripts/buildall.sh --platform ios --arch arm64-simulator mpv
```

### Native library size and Android ABI packaging

Release native builds use size optimization, link-time optimization, and symbol stripping. FFmpeg is built without
debug data, command-line programs, documentation, `libavdevice`, or device I/O support; playback formats, network
protocols, decoders, and filters otherwise remain available. The final resource-copy step strips each shared library
again and removes duplicate Windows mpv DLL names.

The Android library AAR still exposes both supported ABIs (`arm64-v8a` and `x86_64`). The example application
produces one APK per ABI and keeps ABI splitting enabled for Android App Bundles, so an installed APK does not carry
both native-library sets. Applications consuming the library should use the same `splits.abi` configuration when
publishing standalone APKs; Play-generated APKs from an AAB are ABI-specific.

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…

## License

This project is licensed under the [Apache License 2.0](./LICENSE).
