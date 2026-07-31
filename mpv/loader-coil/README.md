# mpv/service-coil

`mpv/service-coil` 使用 Coil 3 实现 `AbstractPlaybackArtworkLoader`，将
`PlaybackArtwork.Uri` 解析成平台媒体中心可以消费的编码图片字节。模块支持 Android、
iOS 与 Desktop (JVM)，并通过 Coil Ktor3 网络组件提供 HTTP/HTTPS 加载能力。

将模块加入 KMP 源集：

```kotlin
commonMain.dependencies {
    implementation(projects.mpv.serviceCoil)
}
```

推荐在应用级创建并复用同一个 Coil `ImageLoader`：

```kotlin
val imageLoader = ImageLoader.Builder(platformContext).build()
configurePipPlayback {
    artworkLoaderFactory = CoilPlaybackArtworkLoaderFactory(
        context = platformContext,
        imageLoader = imageLoader
    )
}
```

该配置适用于依赖 `mpv/pip` 的 Android 与 iOS 应用，并且必须在播放器或 Android 媒体
服务首次初始化前执行。直接使用 `mpv/service` 时，也可以把同一个 factory 传给
`PlaybackCoordinator` 构造函数。

Android 的 `platformContext` 应使用 application context；iOS 和 Desktop 可在对应平台
源集使用 `PlatformContext.INSTANCE`。如果希望 Coil `ImageLoader` 与每个
`PlaybackCoordinator` 同生命周期，可传入创建函数：

```kotlin
CoilPlaybackArtworkLoaderFactory(
    context = platformContext,
    imageLoaderFactory = {
        ImageLoader.Builder(platformContext).build()
    }
)
```

Factory 的 `create()` 每次只创建一个 `CoilPlaybackArtworkLoader`。传入共享
`imageLoader` 时，loader 关闭不会关闭它；传入 `imageLoaderFactory` 时，创建出的
`ImageLoader` 会在 loader 关闭时一并释放。

可以通过 `artworkSize` 指定 Coil 封面请求的目标宽高；未指定时不会覆盖
`configureRequest` 中设置的尺寸，并由 Coil 的请求配置决定最终值。显式传入 `artworkSize`
时，它的优先级高于 `configureRequest`。默认还会拒绝超过 10 MiB 的响应，并在读取过程中
达到字节限制后立即停止继续缓冲：

```kotlin
CoilPlaybackArtworkLoaderFactory(
    context = platformContext,
    imageLoader = imageLoader,
    artworkSize = Size(512, 512),
    maxArtworkBytes = 5L * 1024L * 1024L
)
```

`artworkSize` 会写入 `ImageRequest.sizeResolver`，供 Coil Fetcher/Decoder 使用。该模块的
内置 Decoder 仍返回 Fetcher 提供的原始编码字节，不会额外进行 Bitmap 缩放和重新压缩。

模块为封面请求安装专用 Coil Decoder，直接读取 Fetcher 提供的原始编码数据，不会先解码
Bitmap 再重复压缩。该请求会禁用 Coil 内存缓存及图像变换，避免共享 `ImageLoader` 中的
普通 Bitmap 绕过字节 Decoder；Coil 的磁盘、网络及其余请求策略仍由调用方创建的
`ImageLoader` 和 `configureRequest` 控制。

## 验证

```shell
./gradlew --no-daemon --no-configuration-cache \
  :mpv:service-coil:jvmTest \
  :mpv:service-coil:compileAndroidMain \
  :mpv:service-coil:compileKotlinIosSimulatorArm64 \
  :mpv:service-coil:assemble
```
