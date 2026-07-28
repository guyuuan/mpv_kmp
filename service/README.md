# 跨平台后台媒体播放方案调研

调研日期：2026-07-27

## 结论

Android 之外没有与 `MediaSessionService` 完全同构的统一 API。各平台实际提供的是下列能力的不同组合：

1. 播放器和进程的后台存活；
2. 系统媒体信息展示；
3. 锁屏、耳机、键盘和系统面板的播放控制；
4. 音频焦点、中断和输出设备变化处理；
5. 播放状态持久化与恢复。

在当前项目中，不建议在 `commonMain` 抽象一个名为 `Service` 的平台对象。更合适的方案是让公共层提供与生命周期无关的播放器所有者和媒体会话协调器，各平台分别实现自己的后台策略与系统媒体桥接。

## 当前项目范围

项目目前包含 Android、iOS 和 Desktop JVM 目标：

- [`mpv/core/build.gradle.kts`](../mpv/core/build.gradle.kts) 定义 Android、`iosArm64`、`iosSimulatorArm64` 和 JVM 目标；
- [`example/desktopApp/build.gradle.kts`](../example/desktopApp/build.gradle.kts) 生成 macOS DMG、Windows MSI 和 Linux DEB；
- JVM 原生资源当前包含 macOS 和 Windows，Linux 可继续使用现有的系统 `libmpv` 回退路径。

因此，本报告覆盖 Android、iOS、macOS、Windows 和 Linux。tvOS、visionOS、watchOS 和 Web 不在当前项目目标内。

## 平台方案对比

| 平台 | 播放执行主体 | 后台播放机制 | 系统媒体集成 | 与 `MediaSessionService` 的关系 |
| --- | --- | --- | --- | --- |
| Android | 前台 `Service` 中的播放器 | Media3 `MediaSessionService` 或 `MediaLibraryService` | `MediaSession`、媒体通知、系统控制器 | 原生基准方案 |
| iOS | 应用进程中的播放器所有者 | `AVAudioSession.Category.playback` + Background Modes 中的 Audio/AirPlay/PiP | `MPNowPlayingInfoCenter` + `MPRemoteCommandCenter` | 没有 Service；由系统授予正在播放音频的应用后台执行资格 |
| macOS | 常驻的桌面应用进程 | 一般不需要后台 Service；窗口关闭后必须保持应用进程运行 | `MPNowPlayingInfoCenter` + `MPRemoteCommandCenter` | 没有 Service；系统媒体 API 只负责状态和控制，不负责进程存活 |
| Windows | 常驻的 Win32/JVM 应用进程 | 应用保持运行，可选托盘或独立用户态辅助进程 | WinRT `SystemMediaTransportControls`（SMTC） | 没有等价 Service；SMTC 不负责保活 |
| Linux | 常驻的 JVM 应用进程 | 应用保持运行，可选托盘；只有明确需要守护进程时才考虑 `systemd --user` | 会话 D-Bus 上的 MPRIS 2 | 没有等价 Service；MPRIS 不负责保活 |

## Android

Android Media3 官方建议把 `Player` 和 `MediaSession` 一起放进 `MediaSessionService`。该服务可与系统媒体控件、蓝牙设备、Android Auto、Wear OS 或应用内 `MediaController` 连接，并根据会话状态维护媒体通知。它也支持在服务或设备重启后实现播放恢复。

本项目使用 libmpv，并没有实现 `androidx.media3.common.Player`，因此不能直接把现有 `MpvPlayer` 交给 `MediaSession.Builder`。推荐增加一个 Android 专用适配器：

```text
MpvMediaSessionService
  -> MediaSession
    -> MpvMedia3Player : SimpleBasePlayer
      -> MpvPlayer / Mpv
```

`SimpleBasePlayer` 用于把 libmpv 的状态和命令转换为 Media3 `Player`：

- `Playing`、`Paused`、`Loading`、`Ended` 映射到 Media3 播放状态；
- `play()`、`pause()`、`stop()`、`seekTo()`、`playlistNext()`、`playlistPrev()` 映射为可用命令；
- `timePos`、`duration` 和 `speed` 映射为时间线状态；
- libmpv 主动产生状态变化时调用 `invalidateState()`；
- 自行处理 libmpv 的音频焦点、耳机断开和 `AudioManager` 中断策略，不能假定仅创建 `MediaSessionService` 就已覆盖这些行为。

参考：

- [Android：使用 MediaSessionService 进行后台播放](https://developer.android.com/media/media3/session/background-playback)
- [Android：Media3 Player 与自定义 SimpleBasePlayer](https://developer.android.com/media/media3/session/player)

## iOS

iOS 不允许应用创建 Android 式的任意常驻后台服务。媒体应用的等价组合是：

1. 将 `AVAudioSession` 类别配置为 `.playback`，在真正开始播放时激活；
2. 在 Xcode 的 Background Modes 中启用 Audio, AirPlay, and Picture in Picture；
3. 使用 `MPNowPlayingInfoCenter` 发布标题、作者、封面、时长、进度和播放速率；
4. 使用 `MPRemoteCommandCenter` 接收播放、暂停、上一首、下一首和跳转命令；
5. 监听音频中断与输出路由变化，在电话、Siri、耳机断开等情况下暂停或恢复。

这里的“后台运行”只服务于有效的媒体播放场景，并不赋予应用无限制保活能力。播放结束或长时间暂停后，应假设应用可能被挂起或终止，并持久化队列、媒体标识、进度、速度和暂停状态。

在 KMP 中可以在 `iosMain` 直接调用 Apple Framework，也可以让 Swift 宿主实现一个很薄的桥。播放器应由应用级对象持有，而不是由某个 Compose 页面持有。

参考：

- [Apple：配置媒体播放、音频会话和后台模式](https://developer.apple.com/documentation/avfoundation/configuring-your-app-for-media-playback)
- [Apple：MPNowPlayingInfoCenter](https://developer.apple.com/documentation/mediaplayer/mpnowplayinginfocenter)
- [Apple：MPRemoteCommandCenter](https://developer.apple.com/documentation/mediaplayer/mpremotecommandcenter)

## macOS

macOS 桌面应用在窗口不可见时仍可继续运行，所以通常不需要额外的播放 Service。需要分别完成两件事：

- 应用生命周期：关闭播放窗口时保持应用进程运行，可通过菜单栏/托盘入口重新打开；真正退出应用时才释放播放器；
- 系统媒体集成：使用 `MPNowPlayingInfoCenter` 和 `MPRemoteCommandCenter` 接入控制中心、媒体键和外部控制。

macOS 还要求在开始、暂停或停止时更新 `MPNowPlayingInfoCenter.playbackState`，否则远程控制可能不能按预期工作。

当前 Desktop 是 Kotlin/JVM，不能像 Kotlin/Native 一样直接导入 Objective-C Framework。建议用一个很小的 Objective-C/Swift 动态库封装 MediaPlayer Framework，再通过 JNA/JNI 调用；该桥只处理系统集成，libmpv 仍由现有 JVM 实现持有。

不建议为了播放而引入 LaunchDaemon。只有需要登录后自动启动、UI 与播放进程彻底隔离时，才考虑 LaunchAgent/XPC，并通过 IPC 控制播放器。

参考：

- [Apple：MPNowPlayingInfoCenter](https://developer.apple.com/documentation/mediaplayer/mpnowplayinginfocenter)
- [Apple：macOS playbackState](https://developer.apple.com/documentation/mediaplayer/mpnowplayinginfocenter/playbackstate)
- [Apple：处理外部播放器事件](https://developer.apple.com/documentation/mediaplayer/handling-external-player-events-notifications)

## Windows

Windows 10/11 的标准系统媒体入口是 `SystemMediaTransportControls`。由于本项目使用 libmpv 而不是 WinRT `MediaPlayer`，需要手动：

- 启用 Play、Pause、Next、Previous 等受支持按钮；
- 监听 `ButtonPressed` 并转发到 `MpvPlayer`；
- 通过 `DisplayUpdater` 更新标题、作者、封面和媒体类型；
- 更新播放状态与 `SystemMediaTransportControlsTimelineProperties`；
- 对普通 Win32 窗口使用 `ISystemMediaTransportControlsInterop::GetForWindow(HWND)` 获取 SMTC。

Desktop JVM 侧可通过 JNA/JNI 访问 COM/WinRT；建议把 COM 初始化、线程模型、事件回调和资源释放封装在原生桥中。

SMTC 只提供系统媒体界面和命令，不会让已退出的程序继续播放。若用户关闭窗口后仍要播放，应将关闭行为改为隐藏到托盘或保持无窗口进程。Windows Service 运行在非交互式服务会话中，不适合作为面向当前登录用户的媒体播放与 SMTC 宿主；只有确实需要 UI/播放进程隔离时，才使用用户态辅助进程和 IPC。

参考：

- [Microsoft：集成 System Media Transport Controls](https://learn.microsoft.com/en-us/windows/apps/develop/media-playback/integrate-with-systemmediatransportcontrols)
- [Microsoft：手动控制 SMTC](https://learn.microsoft.com/en-us/windows/apps/develop/media-playback/system-media-transport-controls)
- [Microsoft：ISystemMediaTransportControlsInterop::GetForWindow](https://learn.microsoft.com/en-us/windows/win32/api/systemmediatransportcontrolsinterop/nf-systemmediatransportcontrolsinterop-isystemmediatransportcontrolsinterop-getforwindow)

## Linux

Linux 桌面环境通常通过 MPRIS 2 在用户会话 D-Bus 上发现和控制媒体播放器。应用需要注册类似下面的总线名和对象：

```text
org.mpris.MediaPlayer2.<applicationName>
  /org/mpris/MediaPlayer2
    org.mpris.MediaPlayer2
    org.mpris.MediaPlayer2.Player
```

至少实现以下内容即可覆盖常见桌面环境：

- `Play`、`Pause`、`PlayPause`、`Stop`、`Next`、`Previous`、`Seek`、`SetPosition`；
- `PlaybackStatus`、`Metadata`、`Position`、`Rate`、`Volume`；
- `CanPlay`、`CanPause`、`CanSeek`、`CanGoNext`、`CanGoPrevious`；
- 状态变化时发送 `PropertiesChanged`，非连续跳转时发送 `Seeked`。

JVM 端可以使用成熟的 D-Bus Java 库实现 MPRIS。MPRIS 只是会话级媒体协议，不负责让进程存活；窗口关闭后继续播放仍需要应用保留进程。`systemd --user` 适合明确设计成播放器守护进程的场景，不是普通桌面播放器接入媒体键的前置条件。

参考：

- [freedesktop.org：MPRIS 2 Player Interface](https://specifications.freedesktop.org/mpris/latest/Player_Interface.html)
- [freedesktop.org：MPRIS 2 规范](https://specifications.freedesktop.org/mpris/latest/)

## 适合本项目的公共架构

### 1. 先调整播放器所有权

当前 [`rememberMpvPlayer()`](../mpv/core/src/commonMain/kotlin/com/guyuuan/mpv_kmp/MpvPlayer.kt) 在 `DisposableEffect.onDispose` 中调用 `MpvPlayer.dispose()`，随后执行 `mpv.terminate()`。这意味着播放器的生命期与 Compose 页面绑定，页面离开后无法继续后台播放。

第一步应把播放器提升到平台级所有者：

- Android：`MediaSessionService` 持有；
- iOS：应用级 playback coordinator 持有；
- Desktop：application singleton 持有；
- Compose UI 只绑定状态和发送命令，不拥有或销毁播放器。

### 2. 公共层抽象“媒体集成”，不抽象系统 Service

建议的职责关系：

```text
Compose UI / platform controllers
              |
              v
      PlaybackCoordinator
       |              |
       v              v
   MpvPlayer    PlatformMediaIntegration
                       |
          +------------+------------+------------+
          |            |            |            |
       Android        Apple       Windows       Linux
       Media3       Now Playing      SMTC        MPRIS
```

公共接口只描述跨平台共有能力，例如：

```kotlin
interface PlatformMediaIntegration {
    fun activate(commandHandler: MediaCommandHandler)
    fun updateMetadata(metadata: PlaybackMetadata)
    fun updatePlaybackState(state: PlaybackSnapshot)
    fun deactivate()
}
```

`expect/actual` 可以划分 Android、iOS 和 JVM；JVM `actual` 再按运行系统选择 macOS、Windows 或 Linux 实现。避免把 `Context`、`AVAudioSession`、`HWND`、D-Bus 连接等平台类型泄漏到 `commonMain`。

### 3. 补齐公共媒体模型

现有 `MpvPlayer` 已有 `state`、`timePos`、`duration`、`volume` 和 `speed`，也有播放、暂停、停止、跳转和播放列表命令，但系统媒体集成还缺少由应用提供的语义元数据：

- 稳定的媒体 ID 和 URI；
- 标题、作者、专辑；
- 封面 URI 或字节数据；
- 队列索引和队列长度；
- 媒体类型；
- 可用命令集合。

这些信息不应从文件名猜测，应由上层应用在加载媒体时传给 `PlaybackCoordinator`。

### 4. 状态同步原则

- `MpvPlayer` 是真实播放状态的唯一来源；
- 系统命令统一回到 coordinator，再调用播放器；
- 系统面板状态由播放器事件驱动更新，不能在按键回调中乐观地永久修改；
- 时间进度按平台需要节流，跳转后立即推送；
- artwork 异步加载时保留 media ID，避免旧请求覆盖新媒体；
- 停止或销毁时注销系统回调、清空媒体信息并释放原生资源。

### 5. 播放恢复

持久化最少状态：播放队列、当前索引、媒体 ID/URI、位置、速度、循环模式、暂停状态和必要元数据。

- Android 接到 Media3 playback resumption 回调后恢复；
- iOS 在应用重新启动后由用户操作恢复，不假设被终止后仍有后台进程；
- Desktop 可在下次启动时恢复，或在独立用户态播放进程仍存活时通过 IPC 重连。

## 推荐实施顺序

1. 将 `MpvPlayer` 的所有权从 Compose 页面移到平台级 coordinator，并保持现有 UI API 可用；
2. 定义公共 metadata、snapshot、command handler 和 `PlatformMediaIntegration`；
3. Android 实现 `SimpleBasePlayer` 适配器和 `MediaSessionService`；
4. iOS 实现后台音频、Now Playing、Remote Command 和中断处理；
5. macOS 实现 MediaPlayer Framework 原生桥；
6. Windows 实现 WinRT SMTC 原生桥；
7. Linux 实现 MPRIS；
8. 最后统一加入播放恢复和跨平台行为测试。

## 验收清单

- UI 进入后台或播放页面离开后，符合平台规则地继续播放；
- 锁屏、控制中心、任务栏/桌面面板显示正确元数据、进度和状态；
- 耳机、蓝牙、键盘媒体键可以控制播放；
- 来电、音频焦点丢失、输出设备断开时行为正确；
- 系统命令与应用内 UI 不产生双向循环或状态抖动；
- 关闭窗口、退出应用、进程终止三种行为在 Desktop 上定义清楚；
- 进程被系统终止后不会假装仍在播放，并能按平台能力恢复；
- 多次创建/销毁集成对象不会留下重复回调、D-Bus 名称或原生资源。

## 最终建议

对 `mpv_kmp` 来说，最稳妥的设计不是寻找一个跨平台 `MediaSessionService`，而是统一“播放器所有权、命令和状态”，保留“后台执行与系统媒体接入”的平台差异。Android 使用真正的 Service；iOS 使用系统认可的后台音频会话；桌面端保持用户态应用进程，并分别接入 macOS Now Playing、Windows SMTC 和 Linux MPRIS。
