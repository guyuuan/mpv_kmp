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

需要向系统媒体中心发布标题、作者及网络封面时，使用完整元数据加载：

```kotlin
player.load(
    PlaybackMetadata(
        mediaId = "episode-1",
        uri = videoUrl,
        title = "Episode 1",
        artist = "Example",
        artwork = PlaybackArtwork.Uri(artworkUrl),
        mediaType = PlaybackMediaType.Video
    )
)
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
再变为 `Active`。用户点击 PiP 按钮时可直接调用 `player.enterPictureInPicture()`；
Android 会等待播放器 View 完成当前布局、刷新
`sourceRectHint` 后再进入，避免全屏画面先消失再出现小窗。Android 不提供直接退出 PiP
的 API。

需要自定义命令集合或封面加载器时，应在 `Application.onCreate` 中、首次启动
`MpvMediaSessionService` 或调用 `rememberPipMpvPlayer()` 之前配置进程级 owner。配置保存
`PlaybackArtworkLoaderFactory`，Coordinator 初始化时只创建并持有一个 loader：

```kotlin
override fun onCreate() {
    super.onCreate()
    configurePipPlayback {
        artworkLoaderFactory = CoilPlaybackArtworkLoaderFactory(
            context = applicationContext,
            imageLoader = applicationImageLoader
        )
    }
}
```

`configurePipPlayback` 是跨平台的一次性入口；播放器或媒体服务初始化后再调用、或重复配置
都会抛出异常。绝大多数应用只需设置 `artworkLoaderFactory`。需要替换 Coordinator 创建
逻辑时，可设置 `coordinatorFactory`，并优先使用
`PipPlaybackCoordinatorEnvironment.createDefault()` 保留平台媒体集成、默认状态存储、
命令集合与封面加载器。

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

iOS 使用相同的 `configurePipPlayback` 配置入口。应在首次创建 Compose 播放页面前从
应用启动代码安装配置；例如使用 `mpv/service-coil` 时传入 iOS 的
`PlatformContext.INSTANCE` 和应用级 `ImageLoader`。配置随后由 iOS 应用级 owner 消费，
不会因 Compose 页面重建而创建新的 Coordinator 或 loader。
