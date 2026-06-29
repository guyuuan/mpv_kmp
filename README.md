This is a Kotlin Multiplatform project targeting Android, iOS, and Desktop (JVM).

The project follows the updated Kotlin Multiplatform structure with separate modules for shared code and runnable app entry points:

* [/mpv](./mpv/src) is the reusable multiplatform mpv player library.
* [/mpv/androidNative](./mpv/androidNative/src) contains the Android-only CMake/JNI bridge consumed by `mpv`.
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

The JVM runtime loads native libraries in this order:

1. `-Dmpv.kmp.native.dir=<dir>` provided by the plugin or by the application.
2. Compose Desktop app resources under `mpv-kmp/<platform>`.
3. The `mpv` JAR resources as a fallback.
4. The system `mpv` library as a final fallback.

Set `mpvKmp.desktopNativeDirectoryOverride` when an app wants to use externally built native libraries instead
of the plugin-bundled resources.

### Build and Run iOS Application

To build and run the development version of the iOS app, use the run configuration from the run widget
in your IDE’s toolbar or open the [/iosApp](./iosApp) directory in Xcode and run it from there.

### iOS dylib integration for KMP consumers

KMP consumers should use the Gradle plugin together with the `mpv` dependency. The plugin links the final
iOS framework against `libmpv.dylib` and embeds/signs the bundled mpv and FFmpeg dylibs during the Xcode
framework build phase.

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

Current iOS native libraries are device `iphoneos/arm64` only. Simulator support requires building and
bundling matching simulator mpv/FFmpeg dylibs.

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…
