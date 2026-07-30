# mpv/service

`mpv/service` 在 `mpv/core` 之上统一播放器所有权、媒体语义、系统命令和播放恢复，同时保留各平台不同的后台机制。设计依据见仓库中的[跨平台后台媒体播放方案调研](../../service/README.md)。

## 公共层

将模块加入 KMP 源集：

```kotlin
commonMain.dependencies {
    implementation(projects.mpv.service)
}
```

`PlaybackCoordinator` 必须由 Service、应用级对象或桌面应用生命周期持有，不能在播放页面离开组合时销毁。`rememberMpvPlayer()` 与 `MpvComposeView` 位于 `mpv/compose`，适合不需要后台播放的页面；Android 后台播放和 PiP UI 应依赖 `mpv/pip`，通过 `MediaController` 连接 Service，不能直接持有 `coordinator.player`：

```kotlin
val coordinator = PlaybackCoordinator(
    mediaIntegration = platformMediaIntegration,
    stateStore = platformStateStore,
    artworkLoaderFactory = PlaybackArtworkLoaderFactory {
        object : AbstractPlaybackArtworkLoader() {
            override suspend fun loadBytes(artwork: PlaybackArtwork.Uri): ByteArray? =
                httpClient.get(artwork.value)
                    .body<ByteArray>()
                    .takeIf(ByteArray::isNotEmpty)
        }
    }
)

check(coordinator.start())
coordinator.restoreSavedPlayback()
coordinator.setQueue(
    items = listOf(
        PlaybackMetadata(
            mediaId = "episode-42",
            uri = mediaUri,
            title = "Episode 42",
            artist = "Example",
            mediaType = PlaybackMediaType.Audio
        )
    )
)
```

`PlaybackArtworkLoaderFactory` 为每个 `PlaybackCoordinator` 创建且只创建一个 loader。
`AbstractPlaybackArtworkLoader` 位于 `commonMain`，统一实现异步调度、取消旧请求、过期结果
过滤和异常回退，应用只需实现 `loadBytes()`。它不缓存历史封面：切换媒体时旧请求和引用
立即释放，返回的编码图片字节只发布给平台媒体集成，不会替换公开 snapshot 或持久化队列
中的原始 URI。模块本身不绑定 HTTP 客户端；应用既可以在 `commonMain` 复用网络实现，
也可以在 `androidMain`、`iosMain` 或 `jvmMain` 中处理鉴权和私有 URI。返回 `null`、空数组
或抛出普通加载异常时继续使用原始 URI。

`DesktopPlaybackStateStore` 使用对应系统的用户数据目录和原子文件替换，不受 Java Preferences 单值大小限制。

在真正退出平台级所有者时调用 `coordinator.close()`。队列、索引、位置、速度、循环、随机和暂停状态会先写入配置的 `PlaybackStateStore`；定期状态变化也会以 1 秒防抖保存。默认恢复不会自动播放，只有用户明确允许时才调用 `restoreSavedPlayback(resumePlayback = true)`。

## Android

模块清单已声明前台媒体播放权限和 `MpvMediaSessionService`。`AndroidMediaSessionIntegration` 实现公共层的 `PlatformMediaIntegration`，负责将 coordinator 状态发布给 Media3，并把系统命令路由回 coordinator。默认 Service 持有 libmpv、`MediaSession`、`SimpleBasePlayer` 适配器、音频焦点和耳机断开监听，并使用 `AndroidPlaybackStateStore` 恢复上次队列。

应用可直接通过 `SessionToken`/`MediaController` 连接该 Service，也可继承它并覆盖 `createPlaybackCoordinator(mediaIntegration)` 注入自己的配置；传入的 `mediaIntegration` 必须继续交给新 coordinator。系统或控制器提供队列时，`MpvMedia3Player` 会将所有 `MediaItem` 转成 `PlaybackMetadata`，再统一交给 coordinator。

Compose 应用可直接依赖 `mpv/pip` 并使用 `rememberPipMpvPlayer()`。返回的
`PipMpvPlayer` 只持有 MediaController 连接；视频 `SurfaceView` 经 Media3 传给 Service，
不会在 UI 进程生命周期内再创建第二个播放器。

Android 端要求：

- 运行时使用 Media3 控制器启动并连接播放会话；
- Android 13 及以上按应用场景请求通知权限；
- 自定义 Service 时在应用 Manifest 中声明子类，并避免同时启用基类和子类两个媒体会话服务。

## iOS

应用级所有者应组合：

```kotlin
PlaybackCoordinator(
    mediaIntegration = IosNowPlayingMediaIntegration(),
    stateStore = IosPlaybackStateStore(),
    artworkLoaderFactory = customArtworkLoaderFactory
)
```

`IosNowPlayingMediaIntegration` 管理 `AVAudioSession`、Now Playing、远程命令、音频中断和输出路由变化。公共 loader 将网络或私有 URI 解析成字节后，iOS 集成负责转换为 `UIImage`。原有 `IosArtworkLoader` 构造参数暂时保留用于源码兼容，但已弃用；新代码应统一向 `PlaybackCoordinator` 注入 `PlaybackArtworkLoaderFactory`。

宿主还必须在 Xcode 的 Background Modes 中启用 **Audio, AirPlay, and Picture in Picture**。iOS 被终止后不会继续运行；重启时应先展示恢复入口，再由用户操作决定是否播放。

## Desktop JVM

桌面端在应用级作用域创建集成和状态存储：

```kotlin
val config = DesktopMediaIntegrationConfig(
    applicationId = "sample_player",
    identity = "Sample Player",
    desktopEntry = "sample-player",
    nativeWindowHandle = windowsHwnd
)
val coordinator = PlaybackCoordinator(
    mediaIntegration = createDesktopMediaIntegration(config),
    stateStore = DesktopPlaybackStateStore(config.applicationId)
)
```

- macOS：JAR 自动携带 x86-64 与 arm64 MediaPlayer 动态桥，支持媒体键、Now Playing 和异步封面 URI；关闭窗口后继续播放时，宿主必须保持应用进程存活。
- Windows：JAR 在 Windows 构建机上用 MSVC/C++/WinRT 编译 x86-64 SMTC 桥。应传入真实 Win32 `HWND`；未传时仅尝试当前前台窗口。构建需从具备 Windows SDK 和 C++/WinRT 头文件的 Visual Studio Developer 环境运行 Gradle；非标准 SDK 路径可通过 `mpvKmp.cppWinRtIncludeDir` Gradle 属性指定。
- Linux：通过会话 D-Bus 注册 MPRIS 2，发布属性变化和 seek 信号；公共 loader 返回的字节会写入临时图片文件并以 `mpris:artUrl` 发布，切换封面或停用集成时删除；宿主需要保持用户态应用进程运行。

macOS/Windows 若需使用外部构建的桥，可设置 `-Dmpv.kmp.service.native.dir=<directory>`。真正退出桌面应用时调用 `close()`；“关闭窗口”和“退出应用”应由宿主定义为不同操作。

## 验证

```shell
./gradlew --no-daemon --no-configuration-cache \
  :mpv:service:jvmTest \
  :mpv:service:compileAndroidMain \
  :mpv:service:compileKotlinIosSimulatorArm64 \
  :mpv:service:assemble
```

Windows SMTC 原生桥必须额外在 Windows x86-64 + Visual Studio/Windows SDK 环境运行 `:mpv:service:compileWindowsX8664SmtcBridge`；该任务在非 Windows 主机上会跳过。
