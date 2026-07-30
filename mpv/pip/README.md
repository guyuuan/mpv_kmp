# mpv/pip

`mpv/pip` 是需要后台播放和画中画时使用的 Compose 播放入口。它公开与
`mpv/compose` 相同的 `MpvPlayer`/`MpvComposeView` 能力，并增加跨平台
`PictureInPictureController`。

```kotlin
commonMain.dependencies {
    implementation(projects.mpv.pip)
}
```

```kotlin
val player = rememberPipMpvPlayer()
val snapshot by player.snapshot.collectAsState()

LaunchedEffect(snapshot.isPlaying) {
    player.pictureInPicture.setEligible(snapshot.isPlaying)
}

MpvComposeView(player = player)
```

不需要后台播放或 PiP 的应用应只依赖 `mpv/compose`，并使用
`rememberMpvPlayer()`。两种入口都实现同一个 `MpvPlayer` 接口：

- `LocalMpvPlayer` 在 Compose 生命周期内直接拥有唯一的 libmpv 实例；
- Android 和 iOS `PipMpvPlayer` 都通过公共 `PlaybackCoordinatorMpvPlayer` 直接连接
  应用级 `PlaybackCoordinator`，Compose 页面销毁只释放 UI 观察和视频输出，不会销毁
  播放器；
- Android 的 `AndroidPlaybackCoordinatorOwner` 由 Compose 和
  `MpvMediaSessionService` 共用，MediaController 只负责启动并维持系统 MediaSession，
  播放命令和 `SurfaceView` 直接连接同一个 coordinator；
- iOS 的应用级 owner 组合
  `IosNowPlayingMediaIntegration`、`AVSampleBufferDisplayLayer` 与
  `AVPictureInPictureController`。PiP 播放、暂停和跳转会回到同一个 coordinator，
  不会创建第二个播放器。

## Android 宿主配置

`mpv/service` 的 Manifest 已包含媒体播放前台服务和权限。宿主 Activity 还必须声明：

```xml
<activity
    android:name=".MainActivity"
    android:supportsPictureInPicture="true"
    android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout" />
```

Activity 必须继承 `ComponentActivity`。模块使用 `androidx.core:core-pip` 的
`VideoPlaybackPictureInPicture` 跟踪播放器 View，并在播放期间启用 Android 12+
自动进入 PiP。用户显式触发时，以及 Android 15+ 发出系统进入动画事件时，`state`
会先变为 `PictureInPictureState.Entering`，宿主可提前隐藏播放器覆盖层；进入完成后
再变为 `Active`。`requestStart()` 会等待播放器 View 完成当前布局、刷新
`sourceRectHint` 后再进入，避免全屏画面先消失再出现小窗。Android 不提供直接退出 PiP
的 API。

需要自定义状态存储、命令集合或封面加载器时，应在 `Application.onCreate` 中、首次调用
`rememberPipMpvPlayer()` 之前配置进程级 owner：

```kotlin
import androidx.media3.common.util.UnstableApi

@OptIn(UnstableApi::class)
override fun onCreate() {
    super.onCreate()
    AndroidPlaybackCoordinatorOwner.configure { context, mediaIntegration ->
        PlaybackCoordinator(
            mediaIntegration = mediaIntegration,
            stateStore = AndroidPlaybackStateStore(context),
            artworkLoaderFactory = customArtworkLoaderFactory
        )
    }
}
```

## iOS 宿主配置

在 Xcode Target 的 **Signing & Capabilities** 中添加 **Background Modes**，并勾选
**Audio, AirPlay, and Picture in Picture**。对应的 `Info.plist` 配置是：

```xml
<key>UIBackgroundModes</key>
<array>
    <string>audio</string>
</array>
```

iOS 自定义播放器 PiP 由 `AVSampleBufferDisplayLayer` 提供视频源。`mpv/pip` 会让
libmpv 使用软件输出生成 BGRA sample buffer，并按视频源尺寸等比缩放到最长边 1280，
再由 AVKit 在应用内和 PiP 窗口中统一做 aspect-fit。这个输出比 `mpv/compose` 默认的
OpenGL ES 路径更消耗 CPU；不需要 PiP 时应继续只依赖 `mpv/compose`。
