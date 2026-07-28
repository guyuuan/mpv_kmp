# mpv/compose

`mpv/compose` provides the Compose-facing player state and video rendering controls on top of
`mpv/core`.

Add the module to a Kotlin Multiplatform UI source set:

```kotlin
commonMain.dependencies {
    implementation(projects.mpv.compose)
}
```

Use `rememberMpvPlayer()` for UI-owned playback, or pass an application-owned `Mpv` instance
such as `PlaybackCoordinator.player` to `MpvComposeView` when playback must outlive the current
composition.

```kotlin
val player = rememberMpvPlayer()

MpvComposeView(
    modifier = Modifier.fillMaxSize(),
    state = player
)
```
