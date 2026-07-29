@file:OptIn(
    kotlinx.cinterop.BetaInteropApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class
)

package com.guyuuan.mpv_kmp.pip

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import cnames.structs.opaqueCMSampleBuffer
import com.guyuuan.mpv_kmp.IosMpvVideoOutput
import com.guyuuan.mpv_kmp.IosRenderContextSupport
import com.guyuuan.mpv_kmp.MpvPlayer
import com.guyuuan.mpv_kmp.MpvPlayerCapability
import com.guyuuan.mpv_kmp.MpvPlayerSnapshot
import com.guyuuan.mpv_kmp.MpvPlayerState
import com.guyuuan.mpv_kmp.data.MpvAudioTrack
import com.guyuuan.mpv_kmp.data.MpvDecoderInfo
import com.guyuuan.mpv_kmp.data.MpvSubtitleTrack
import com.guyuuan.mpv_kmp.service.IosNowPlayingMediaIntegration
import com.guyuuan.mpv_kmp.service.IosPlaybackStateStore
import com.guyuuan.mpv_kmp.service.MediaCommand
import com.guyuuan.mpv_kmp.service.PlaybackCoordinator
import com.guyuuan.mpv_kmp.service.PlaybackMediaType
import com.guyuuan.mpv_kmp.service.PlaybackMetadata
import com.guyuuan.mpv_kmp.service.PlaybackSnapshot
import com.guyuuan.mpv_kmp.service.PlaybackStatus
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CValue
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import platform.AVFoundation.*
import platform.AVKit.*
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.CoreMedia.CMTimeRange
import platform.CoreMedia.CMTimeRangeMake
import platform.CoreMedia.CMVideoDimensions
import platform.CoreMedia.kCMTimePositiveInfinity
import platform.CoreMedia.kCMTimeZero
import platform.Foundation.NSArray
import platform.Foundation.NSBundle
import platform.Foundation.NSError
import platform.Foundation.NSThread
import platform.Foundation.containsObject
import platform.UIKit.UIColor
import platform.UIKit.UIScreen
import platform.UIKit.UIView
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSEC_PER_SEC
import platform.darwin.NSObject
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time
import kotlin.concurrent.Volatile
import kotlin.math.roundToInt

/**
 * iOS owns playback at application scope: the coordinator is the only owner of the process-wide
 * libmpv instance, while each Compose screen only owns its AVKit video-output connection.
 */
@Composable
actual fun rememberPipMpvPlayer(): PipMpvPlayer {
    val coordinator = remember { IosPipPlaybackOwner.coordinator }
    val videoOutput = remember(coordinator) {
        IosSampleBufferPictureInPictureOutput(coordinator)
    }
    val mediaPlayer = remember(coordinator, videoOutput) {
        IosPlaybackCoordinatorMpvPlayer(
            coordinator = coordinator,
            videoOutput = videoOutput
        )
    }
    val player = remember(mediaPlayer, videoOutput) {
        PipMpvPlayer(
            delegate = mediaPlayer,
            pictureInPicture = videoOutput,
            videoOutput = videoOutput,
            release = {
                mediaPlayer.close()
                videoOutput.close()
            }
        )
    }

    DisposableEffect(player) {
        onDispose(player::close)
    }
    return player
}

private object IosPipPlaybackOwner {
    val coordinator: PlaybackCoordinator by lazy {
        PlaybackCoordinator(
            mediaIntegration = IosNowPlayingMediaIntegration(),
            stateStore = IosPlaybackStateStore()
        ).also(PlaybackCoordinator::start)
    }
}

private class IosPlaybackCoordinatorMpvPlayer(
    private val coordinator: PlaybackCoordinator,
    override val videoOutput: IosSampleBufferPictureInPictureOutput
) : MpvPlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutableSnapshot = MutableStateFlow(coordinator.snapshot.value.toPlayerSnapshot())
    override val snapshot: StateFlow<MpvPlayerSnapshot> = mutableSnapshot.asStateFlow()

    override val capabilities: Set<MpvPlayerCapability> = setOf(
        MpvPlayerCapability.TrackSelection,
        MpvPlayerCapability.ExternalSubtitle
    )
    override val decoderInfoFlow: Flow<MpvDecoderInfo> = emptyFlow()
    private var closed = false

    init {
        scope.launch {
            coordinator.snapshot.collect { snapshot ->
                mutableSnapshot.value = snapshot.toPlayerSnapshot()
                videoOutput.updatePlaybackSnapshot(snapshot)
            }
        }
    }

    override fun load(uri: String): Int {
        val title = uri.substringAfterLast('/').substringBefore('?').ifBlank { uri }
        return coordinator.load(
            PlaybackMetadata(
                mediaId = uri,
                uri = uri,
                title = title,
                mediaType = PlaybackMediaType.Video
            )
        )
    }

    override fun play(): Int = coordinator.execute(MediaCommand.Play)
    override fun pause(): Int = coordinator.execute(MediaCommand.Pause)
    override fun stop(): Int = coordinator.execute(MediaCommand.Stop)

    override fun seek(positionSeconds: Double): Int = coordinator.execute(
        MediaCommand.SeekTo(
            (positionSeconds.coerceAtLeast(0.0) * MILLIS_PER_SECOND).toLong()
        )
    )

    override fun setVolume(volume: Double): Int = coordinator.execute(
        MediaCommand.SetVolume(volume.toFloat().coerceIn(0f, MAX_MPV_VOLUME))
    )

    override fun setSpeed(speed: Float): Int =
        coordinator.execute(MediaCommand.SetSpeed(speed))

    override fun getSubtitleList(): List<MpvSubtitleTrack> =
        coordinator.player.getSubtitleList()

    override fun setSubtitle(id: Int?): Int = coordinator.player.setSubtitle(id)

    override fun getAudioTrackList(): List<MpvAudioTrack> =
        coordinator.player.getAudioTrackList()

    override fun setAudioTrack(id: Int?): Int = coordinator.player.setAudioTrack(id)

    override fun addExternalSubtitle(uri: String): Int =
        coordinator.player.addExternalSubtitle(uri)

    override fun addExternalSubtitleFile(path: String): Int =
        coordinator.player.addExternalSubtitleFile(path)

    fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        mutableSnapshot.value = mutableSnapshot.value.copy(state = MpvPlayerState.Disposed)
    }
}

private class IosSampleBufferPictureInPictureOutput(
    private val coordinator: PlaybackCoordinator
) : IosMpvVideoOutput, PictureInPictureController {

    private val renderPlayer = coordinator.player as? IosRenderContextSupport
    private val mutableAvailability = MutableStateFlow(resolveAvailability(renderPlayer))
    override val availability: StateFlow<PictureInPictureAvailability> =
        mutableAvailability.asStateFlow()

    private val mutableState = MutableStateFlow(PictureInPictureState.Inactive)
    override val state: StateFlow<PictureInPictureState> = mutableState.asStateFlow()
    private val pictureInPictureDelegate =
        IosPictureInPictureDelegate(coordinator, mutableState)

    private var hostView: IosSampleBufferVideoView? = null
    private var displayLayer: AVSampleBufferDisplayLayer? = null
    private var pictureInPictureController: AVPictureInPictureController? = null
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var lastRenderedSize: Pair<Int, Int>? = null
    private var eligible = false
    private var renderContextReady = false
    private var renderCallbackRegistered = false
    private var loggedFirstFrame = false

    @Volatile
    private var renderPending = false

    @Volatile
    private var forceRenderPending = false

    @Volatile
    private var closed = false

    override fun createView(): UIView {
        val view = IosSampleBufferVideoView(this)
        hostView = view
        displayLayer = view.videoLayer
        configurePictureInPictureController(view.videoLayer)
        ensureRenderContext()
        scheduleRender(force = true)
        scheduleRenderTick()
        return view
    }

    override fun updateView(view: UIView) {
        if (view === hostView) scheduleRender()
    }

    override fun releaseView(view: UIView) {
        if (view === hostView) close()
    }

    override fun setEligible(eligible: Boolean) {
        if (closed) return
        this.eligible = eligible
        runOnMain {
            pictureInPictureController?.canStartPictureInPictureAutomaticallyFromInline = eligible
            pictureInPictureController?.invalidatePlaybackState()
        }
    }

    override fun setAspectRatio(width: Int, height: Int) {
        if (closed || width <= 0 || height <= 0) return
        if (sourceWidth == width && sourceHeight == height) return
        sourceWidth = width
        sourceHeight = height
        scheduleRender(force = true)
    }

    override fun requestStart(): Boolean {
        val controller = pictureInPictureController ?: return false
        if (closed || !eligible ||
            availability.value != PictureInPictureAvailability.Available ||
            !controller.pictureInPicturePossible
        ) {
            return false
        }
        runOnMain(controller::startPictureInPicture)
        return true
    }

    override fun requestStop(): Boolean {
        val controller = pictureInPictureController ?: return false
        if (closed || !controller.pictureInPictureActive) return false
        runOnMain(controller::stopPictureInPicture)
        return true
    }

    override fun close() {
        if (closed) return
        closed = true
        renderPending = false
        forceRenderPending = false

        val controller = pictureInPictureController
        if (controller?.pictureInPictureActive == true) {
            controller.stopPictureInPicture()
        }
        controller?.delegate = null
        controller?.contentSource = null
        pictureInPictureController = null

        displayLayer?.flushAndRemoveImage()
        displayLayer?.removeFromSuperlayer()
        displayLayer = null
        hostView = null

        if (renderContextReady) {
            renderPlayer?.freeRenderContext()
            renderContextReady = false
        }
        renderCallbackRegistered = false
        mutableState.value = PictureInPictureState.Inactive
    }

    fun updatePlaybackSnapshot(snapshot: PlaybackSnapshot) {
        if (snapshot.videoWidth > 0 && snapshot.videoHeight > 0) {
            setAspectRatio(snapshot.videoWidth, snapshot.videoHeight)
        }
        pictureInPictureController?.invalidatePlaybackState()
    }

    fun requestLayoutRender() {
        scheduleRender(force = true)
    }

    private fun configurePictureInPictureController(layer: AVSampleBufferDisplayLayer) {
        if (availability.value != PictureInPictureAvailability.Available) return
        val contentSource = AVPictureInPictureControllerContentSource.create(
            sampleBufferDisplayLayer = layer,
            playbackDelegate = pictureInPictureDelegate
        )
        pictureInPictureController = AVPictureInPictureController(contentSource).also {
            it.delegate = pictureInPictureDelegate
            it.canStartPictureInPictureAutomaticallyFromInline = eligible
        }
    }

    private fun ensureRenderContext(): Boolean {
        if (closed || renderPlayer == null) return false
        if (!renderContextReady) {
            renderContextReady = renderPlayer.createSampleBufferRenderContext()
            if (!renderContextReady) {
                mutableAvailability.value =
                    PictureInPictureAvailability.UnsupportedVideoOutput
                return false
            }
        }
        if (!renderCallbackRegistered) {
            renderPlayer.setRenderCallback { scheduleRender() }
            renderCallbackRegistered = true
        }
        return true
    }

    private fun scheduleRender(force: Boolean = false) {
        if (closed) return
        if (force) forceRenderPending = true
        if (renderPending) return
        renderPending = true
        dispatch_async(dispatch_get_main_queue()) {
            if (closed) {
                renderPending = false
                return@dispatch_async
            }
            val shouldForce = forceRenderPending
            forceRenderPending = false
            renderPending = false
            renderFrame(shouldForce)
        }
    }

    private fun scheduleRenderTick() {
        if (closed) return
        dispatch_after(
            dispatch_time(DISPATCH_TIME_NOW, (NSEC_PER_SEC / RENDER_TICK_RATE).toLong()),
            dispatch_get_main_queue()
        ) {
            if (!closed) {
                scheduleRender()
                scheduleRenderTick()
            }
        }
    }

    private fun renderFrame(force: Boolean) {
        val layer = displayLayer ?: return
        val renderer = renderPlayer ?: return
        if (!ensureRenderContext()) return
        if (layer.requiresFlushToResumeDecoding) {
            layer.flush()
        }
        if (!layer.readyForMoreMediaData) return
        val hasFrameUpdate = renderer.updateRenderContext()
        if (!force && !hasFrameUpdate) return

        val targetSize = resolveRenderSize() ?: return
        if (lastRenderedSize != targetSize) {
            layer.flushAndRemoveImage()
            lastRenderedSize = targetSize
        }

        val sampleBuffer: CPointer<opaqueCMSampleBuffer> =
            renderer.renderSampleBuffer(targetSize.first, targetSize.second) ?: return
        layer.enqueueSampleBuffer(sampleBuffer)
        renderer.releaseSampleBuffer(sampleBuffer)

        if (!loggedFirstFrame) {
            loggedFirstFrame = true
            println(
                "IosPictureInPicture: rendered first sample buffer " +
                    "${targetSize.first}x${targetSize.second}"
            )
        }
    }

    private fun resolveRenderSize(): Pair<Int, Int>? {
        var width = sourceWidth
        var height = sourceHeight
        if (width <= 0 || height <= 0) {
            val view = hostView ?: return null
            val scale = UIScreen.mainScreen.scale
            view.bounds.useContents {
                width = (size.width * scale).roundToInt()
                height = (size.height * scale).roundToInt()
            }
        }
        if (width <= 0 || height <= 0) return null

        val longestEdge = maxOf(width, height)
        if (longestEdge <= MAX_RENDER_EDGE) return width to height
        val scale = MAX_RENDER_EDGE.toDouble() / longestEdge
        return (width * scale).roundToInt().coerceAtLeast(1) to
            (height * scale).roundToInt().coerceAtLeast(1)
    }

    private fun runOnMain(block: () -> Unit) {
        if (NSThread.isMainThread) {
            block()
        } else {
            dispatch_async(dispatch_get_main_queue(), block)
        }
    }

    private companion object {
        fun resolveAvailability(
            renderPlayer: IosRenderContextSupport?
        ): PictureInPictureAvailability = when {
            renderPlayer == null -> PictureInPictureAvailability.UnsupportedVideoOutput
            !AVPictureInPictureController.isPictureInPictureSupported() ->
                PictureInPictureAvailability.MissingSystemFeature
            !hostSupportsBackgroundPlayback() ->
                PictureInPictureAvailability.MissingHostCapability
            else -> PictureInPictureAvailability.Available
        }

        private fun hostSupportsBackgroundPlayback(): Boolean {
            val modes = NSBundle.mainBundle
                .objectForInfoDictionaryKey("UIBackgroundModes") as? NSArray
            return modes?.containsObject("audio") == true
        }
    }
}

private class IosPictureInPictureDelegate(
    private val coordinator: PlaybackCoordinator,
    private val state: MutableStateFlow<PictureInPictureState>
) : NSObject(),
    AVPictureInPictureControllerDelegateProtocol,
    AVPictureInPictureSampleBufferPlaybackDelegateProtocol {

    override fun pictureInPictureController(
        pictureInPictureController: AVPictureInPictureController,
        setPlaying: Boolean
    ) {
        coordinator.execute(if (setPlaying) MediaCommand.Play else MediaCommand.Pause)
        pictureInPictureController.invalidatePlaybackState()
    }

    override fun pictureInPictureControllerTimeRangeForPlayback(
        pictureInPictureController: AVPictureInPictureController
    ): CValue<CMTimeRange> {
        val durationMillis = coordinator.snapshot.value.durationMillis
        val duration = if (durationMillis > 0) {
            CMTimeMakeWithSeconds(
                durationMillis / MILLIS_PER_SECOND,
                PREFERRED_TIME_SCALE
            )
        } else {
            kCMTimePositiveInfinity.readValue()
        }
        return CMTimeRangeMake(kCMTimeZero.readValue(), duration)
    }

    override fun pictureInPictureControllerIsPlaybackPaused(
        pictureInPictureController: AVPictureInPictureController
    ): Boolean = !coordinator.snapshot.value.isPlaying

    override fun pictureInPictureController(
        pictureInPictureController: AVPictureInPictureController,
        didTransitionToRenderSize: CValue<CMVideoDimensions>
    ) = Unit

    override fun pictureInPictureController(
        pictureInPictureController: AVPictureInPictureController,
        skipByInterval: CValue<CMTime>,
        completionHandler: () -> Unit
    ) {
        val intervalSeconds = CMTimeGetSeconds(skipByInterval)
        val currentSeconds =
            coordinator.snapshot.value.positionMillis / MILLIS_PER_SECOND
        coordinator.execute(
            MediaCommand.SeekTo(
                ((currentSeconds + intervalSeconds).coerceAtLeast(0.0) * MILLIS_PER_SECOND).toLong()
            )
        )
        completionHandler()
    }

    override fun pictureInPictureControllerShouldProhibitBackgroundAudioPlayback(
        pictureInPictureController: AVPictureInPictureController
    ): Boolean = false

    override fun pictureInPictureControllerDidStartPictureInPicture(
        pictureInPictureController: AVPictureInPictureController
    ) {
        state.value = PictureInPictureState.Active
    }

    override fun pictureInPictureControllerDidStopPictureInPicture(
        pictureInPictureController: AVPictureInPictureController
    ) {
        state.value = PictureInPictureState.Inactive
    }

    override fun pictureInPictureController(
        pictureInPictureController: AVPictureInPictureController,
        failedToStartPictureInPictureWithError: NSError
    ) {
        state.value = PictureInPictureState.Inactive
        println(
            "IosPictureInPicture: failed to start: " +
                failedToStartPictureInPictureWithError.localizedDescription
        )
    }

    override fun pictureInPictureController(
        pictureInPictureController: AVPictureInPictureController,
        restoreUserInterfaceForPictureInPictureStopWithCompletionHandler:
            (Boolean) -> Unit
    ) {
        restoreUserInterfaceForPictureInPictureStopWithCompletionHandler(true)
    }
}

private class IosSampleBufferVideoView(
    private val output: IosSampleBufferPictureInPictureOutput
) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    val videoLayer = AVSampleBufferDisplayLayer().apply {
        videoGravity = AVLayerVideoGravityResizeAspect
    }

    init {
        backgroundColor = UIColor.blackColor
        clipsToBounds = true
        layer.addSublayer(videoLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        videoLayer.frame = bounds
        output.requestLayoutRender()
    }
}

private fun PlaybackSnapshot.toPlayerSnapshot(): MpvPlayerSnapshot = MpvPlayerSnapshot(
    state = when (status) {
        PlaybackStatus.Idle -> MpvPlayerState.Idle
        PlaybackStatus.Loading -> MpvPlayerState.Loading
        PlaybackStatus.Playing -> MpvPlayerState.Playing
        PlaybackStatus.Paused -> MpvPlayerState.Paused
        PlaybackStatus.Stopped -> MpvPlayerState.Stopped
        PlaybackStatus.Ended -> MpvPlayerState.Ended
        PlaybackStatus.Error -> MpvPlayerState.Error
        PlaybackStatus.Disposed -> MpvPlayerState.Disposed
    },
    positionSeconds = positionMillis / MILLIS_PER_SECOND,
    durationSeconds = durationMillis / MILLIS_PER_SECOND,
    volume = volume,
    speed = speed
)

private const val MILLIS_PER_SECOND = 1_000.0
private const val MAX_MPV_VOLUME = 100f
private const val PREFERRED_TIME_SCALE = 1_000
private const val MAX_RENDER_EDGE = 1_280
private const val RENDER_TICK_RATE = 30uL
