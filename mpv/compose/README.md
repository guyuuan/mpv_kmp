# mpv/compose

`mpv/compose` provides the Compose-facing player state and video rendering controls on top of
`mpv/core`.

Add the module to a Kotlin Multiplatform UI source set:

```kotlin
commonMain.dependencies {
    implementation(projects.mpv.compose)
}
```

Use `rememberMpvPlayer()` for UI-owned playback. This returns the local `MpvPlayer`
implementation and releases its process-wide libmpv instance when the composition leaves.
Applications that need background playback or Picture-in-Picture should depend on `mpv/pip`
instead; its Android implementation connects to the Service-owned player through Media3.

```kotlin
val player = rememberMpvPlayer()

MpvComposeView(
    modifier = Modifier.fillMaxSize(),
    player = player
)
```

`MpvPlayer` is a platform-neutral command and state interface. Collect `player.snapshot` for
position, duration, volume, speed, and playback state. The raw-`Mpv` `MpvComposeView(state = ...)`
overload remains available for low-level integrations, but it does not provide ownership or
MediaSession behavior.
