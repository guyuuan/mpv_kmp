@file:OptIn(
    kotlinx.cinterop.BetaInteropApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class
)

package com.guyuuan.kmp.mpv.service

import co.touchlab.kermit.Logger
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionOptionKey
import platform.AVFAudio.AVAudioSessionInterruptionOptionShouldResume
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOldDeviceUnavailable
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSize
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSThread
import platform.Foundation.create
import platform.MediaPlayer.MPChangePlaybackPositionCommandEvent
import platform.MediaPlayer.MPMediaItemArtwork
import platform.MediaPlayer.MPMediaItemPropertyAlbumTitle
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyArtwork
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoMediaTypeAudio
import platform.MediaPlayer.MPNowPlayingInfoMediaTypeVideo
import platform.MediaPlayer.MPNowPlayingInfoPropertyDefaultPlaybackRate
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyExternalContentIdentifier
import platform.MediaPlayer.MPNowPlayingInfoPropertyMediaType
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackQueueCount
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackQueueIndex
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommand
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandEvent
import platform.MediaPlayer.MPRemoteCommandHandlerStatusCommandFailed
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.MediaPlayer.MPSkipIntervalCommandEvent
import platform.UIKit.UIImage
import platform.UIKit.UIGraphicsImageRenderer
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_sync
import kotlin.math.max
import kotlin.math.min

/** Asynchronously supplies iOS artwork. The completion may be called from any queue. */
@Deprecated(
    message = "Use PlaybackArtworkLoaderFactory on PlaybackCoordinator for URI loading."
)
fun interface IosArtworkLoader {
    fun load(artwork: PlaybackArtwork, completion: (UIImage?) -> Unit)
}

/** Supports in-memory artwork and lets applications provide their own URI loader when needed. */
@Deprecated(
    message = "Use PlaybackArtworkLoaderFactory on PlaybackCoordinator for URI loading."
)
@Suppress("DEPRECATION")
object DefaultIosArtworkLoader : IosArtworkLoader {
    override fun load(artwork: PlaybackArtwork, completion: (UIImage?) -> Unit) {
        val image = when (artwork) {
            is PlaybackArtwork.Bytes -> artwork.toByteArray().toUIImage()
            is PlaybackArtwork.Uri -> null
        }
        completion(image)
    }
}

/**
 * iOS media bridge for background audio, Now Playing, remote commands, interruptions, and route
 * changes. The host app must also enable the Audio/AirPlay/Picture in Picture background mode.
 * New code should provide a [PlaybackArtworkLoaderFactory] to [PlaybackCoordinator]; the resulting
 * [PlaybackArtwork.Bytes] are decoded here without exposing UIKit to common code.
 */
@Suppress("DEPRECATION")
class IosNowPlayingMediaIntegration(
    private val artworkLoader: IosArtworkLoader = DefaultIosArtworkLoader
) : PlatformMediaIntegration {
    private val audioSession = AVAudioSession.sharedInstance()
    private val nowPlayingCenter = MPNowPlayingInfoCenter.defaultCenter()
    private val commandCenter = MPRemoteCommandCenter.sharedCommandCenter()
    private val notificationCenter = NSNotificationCenter.defaultCenter

    private var commandHandler: MediaCommandHandler? = null
    private var currentMetadata: PlaybackMetadata? = null
    private var currentSnapshot = PlaybackSnapshot()
    private var currentArtwork: MPMediaItemArtwork? = null
    private var artworkMediaId: String? = null
    private var publishedMetadataMediaId: String? = null
    private var lastPublishedSnapshot: PlaybackSnapshot? = null
    private var resumeAfterInterruption = false
    private var audioSessionCategoryConfigured = false
    private var audioSessionActive = false
    private var active = false
    private val commandTargets = mutableListOf<CommandTarget>()
    private val notificationObservers = mutableListOf<NSObjectProtocol>()

    override fun activate(commandHandler: MediaCommandHandler) {
        runOnMainSynchronously {
            activateOnMain(commandHandler)
        }
    }

    private fun activateOnMain(commandHandler: MediaCommandHandler) {
        if (active) {
            this.commandHandler = commandHandler
            return
        }
        active = true
        this.commandHandler = commandHandler
        registerRemoteCommands()
        registerNotifications()
        updateCommandAvailability()
        publishNowPlaying(force = true)
    }

    override fun updateMetadata(metadata: PlaybackMetadata?) {
        runOnMain {
            updateMetadataOnMain(metadata)
        }
    }

    private fun updateMetadataOnMain(metadata: PlaybackMetadata?) {
        if (!active) return
        currentMetadata = metadata
        currentArtwork = null
        artworkMediaId = metadata?.mediaId
        publishNowPlaying(force = true)

        val artwork = metadata?.artwork ?: return
        val requestedMediaId = metadata.mediaId
        artworkLoader.load(artwork) { image ->
            runOnMain {
                if (active && requestedMediaId == artworkMediaId && image != null) {
                    currentArtwork = image.toMediaItemArtwork()
                    publishNowPlaying(force = true)
                }
            }
        }
    }

    override fun updatePlaybackState(state: PlaybackSnapshot) {
        runOnMain {
            updatePlaybackStateOnMain(state)
        }
    }

    private fun updatePlaybackStateOnMain(state: PlaybackSnapshot) {
        if (!active) return
        val lastPublished = lastPublishedSnapshot
        currentSnapshot = state
        updateCommandAvailability()
        updateAudioSession(state)

        val positionMovedEnough = lastPublished == null ||
            kotlin.math.abs(state.positionMillis - lastPublished.positionMillis) >=
            POSITION_UPDATE_INTERVAL_MS
        val nonPositionStateChanged = lastPublished == null ||
            state.copy(positionMillis = lastPublished.positionMillis) != lastPublished
        publishNowPlaying(force = positionMovedEnough || nonPositionStateChanged)
    }

    override fun deactivate() {
        runOnMainSynchronously(::deactivateOnMain)
    }

    private fun deactivateOnMain() {
        if (!active) return
        active = false
        commandTargets.forEach { target -> target.command.removeTarget(target.token) }
        commandTargets.clear()
        notificationObservers.forEach(notificationCenter::removeObserver)
        notificationObservers.clear()
        commandHandler = null
        resumeAfterInterruption = false
        currentMetadata = null
        currentArtwork = null
        artworkMediaId = null
        publishedMetadataMediaId = null
        lastPublishedSnapshot = null
        nowPlayingCenter.nowPlayingInfo = null
        deactivateAudioSession()
    }

    private fun registerRemoteCommands() {
        addCommand(commandCenter.playCommand, MediaCommandType.Play) { MediaCommand.Play }
        addCommand(commandCenter.pauseCommand, MediaCommandType.Pause) { MediaCommand.Pause }
        addCommand(
            commandCenter.togglePlayPauseCommand,
            MediaCommandType.TogglePlayPause
        ) { MediaCommand.TogglePlayPause }
        addCommand(commandCenter.stopCommand, MediaCommandType.Stop) { MediaCommand.Stop }
        addCommand(commandCenter.nextTrackCommand, MediaCommandType.Next) { MediaCommand.Next }
        addCommand(commandCenter.previousTrackCommand, MediaCommandType.Previous) {
            MediaCommand.Previous
        }
        commandCenter.skipForwardCommand.preferredIntervals = listOf(DEFAULT_SKIP_INTERVAL_SECONDS)
        commandCenter.skipBackwardCommand.preferredIntervals = listOf(DEFAULT_SKIP_INTERVAL_SECONDS)
        addCommand(commandCenter.skipForwardCommand, MediaCommandType.SeekBy) { event ->
            val seconds = (event as? MPSkipIntervalCommandEvent)?.interval
                ?: DEFAULT_SKIP_INTERVAL_SECONDS
            MediaCommand.SeekBy((seconds * MILLIS_PER_SECOND).toLong())
        }
        addCommand(commandCenter.skipBackwardCommand, MediaCommandType.SeekBy) { event ->
            val seconds = (event as? MPSkipIntervalCommandEvent)?.interval
                ?: DEFAULT_SKIP_INTERVAL_SECONDS
            MediaCommand.SeekBy((-seconds * MILLIS_PER_SECOND).toLong())
        }
        addCommand(commandCenter.changePlaybackPositionCommand, MediaCommandType.SeekTo) { event ->
            val position = (event as? MPChangePlaybackPositionCommandEvent)?.positionTime
                ?: return@addCommand null
            MediaCommand.SeekTo((position.coerceAtLeast(0.0) * MILLIS_PER_SECOND).toLong())
        }
    }

    private fun addCommand(
        command: MPRemoteCommand,
        type: MediaCommandType,
        commandFactory: (MPRemoteCommandEvent?) -> MediaCommand?
    ) {
        val token = command.addTargetWithHandler { event ->
            val handler = commandHandler
            val mediaCommand = commandFactory(event)
            if (!active || handler == null || type !in currentSnapshot.availableCommands ||
                mediaCommand == null
            ) {
                MPRemoteCommandHandlerStatusCommandFailed
            } else {
                handler.handle(mediaCommand)
                MPRemoteCommandHandlerStatusSuccess
            }
        }
        commandTargets += CommandTarget(command, token)
    }

    private fun registerNotifications() {
        notificationObservers += notificationCenter.addObserverForName(
            name = AVAudioSessionInterruptionNotification,
            `object` = audioSession,
            queue = NSOperationQueue.mainQueue,
            usingBlock = ::handleInterruption
        )
        notificationObservers += notificationCenter.addObserverForName(
            name = AVAudioSessionRouteChangeNotification,
            `object` = audioSession,
            queue = NSOperationQueue.mainQueue,
            usingBlock = ::handleRouteChange
        )
    }

    private fun handleInterruption(notification: NSNotification?) {
        val type = notification?.unsignedLong(AVAudioSessionInterruptionTypeKey) ?: return
        when (type) {
            AVAudioSessionInterruptionTypeBegan -> {
                resumeAfterInterruption = currentSnapshot.status == PlaybackStatus.Playing
                commandHandler?.handle(MediaCommand.Pause)
            }
            AVAudioSessionInterruptionTypeEnded -> {
                val options = notification.unsignedLong(AVAudioSessionInterruptionOptionKey) ?: 0uL
                val shouldResume =
                    options and AVAudioSessionInterruptionOptionShouldResume != 0uL
                if (resumeAfterInterruption && shouldResume && activateAudioSession()) {
                    commandHandler?.handle(MediaCommand.Play)
                }
                resumeAfterInterruption = false
            }
        }
    }

    private fun handleRouteChange(notification: NSNotification?) {
        val reason = notification?.unsignedLong(AVAudioSessionRouteChangeReasonKey) ?: return
        if (reason == AVAudioSessionRouteChangeReasonOldDeviceUnavailable &&
            currentSnapshot.status == PlaybackStatus.Playing
        ) {
            resumeAfterInterruption = false
            commandHandler?.handle(MediaCommand.Pause)
        }
    }

    private fun updateCommandAvailability() {
        val available = currentSnapshot.availableCommands
        commandCenter.playCommand.enabled = MediaCommandType.Play in available
        commandCenter.pauseCommand.enabled = MediaCommandType.Pause in available
        commandCenter.togglePlayPauseCommand.enabled = MediaCommandType.TogglePlayPause in available
        commandCenter.stopCommand.enabled = MediaCommandType.Stop in available
        commandCenter.nextTrackCommand.enabled = MediaCommandType.Next in available
        commandCenter.previousTrackCommand.enabled = MediaCommandType.Previous in available
        commandCenter.skipForwardCommand.enabled = MediaCommandType.SeekBy in available
        commandCenter.skipBackwardCommand.enabled = MediaCommandType.SeekBy in available
        commandCenter.changePlaybackPositionCommand.enabled = MediaCommandType.SeekTo in available
    }

    private fun updateAudioSession(snapshot: PlaybackSnapshot) {
        if (snapshot.status == PlaybackStatus.Playing) {
            if (!activateAudioSession()) commandHandler?.handle(MediaCommand.Pause)
        } else {
            deactivateAudioSession()
        }
    }

    private fun configureAudioSessionCategory(): Boolean {
        if (audioSessionCategoryConfigured) return true
        return memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            val configured = audioSession.setCategory(
                category = AVAudioSessionCategoryPlayback,
                error = error.ptr
            )
            if (!configured) logAudioSessionFailure("configure playback category", error.value)
            audioSessionCategoryConfigured = configured
            configured
        }
    }

    private fun activateAudioSession(): Boolean {
        if (audioSessionActive) return true
        if (!configureAudioSessionCategory()) return false
        return memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            val activated = audioSession.setActive(true, error = error.ptr)
            if (!activated) logAudioSessionFailure("activate", error.value)
            if (activated) {
                Logger.i(tag = "IosNowPlayingMediaIntegration") {
                    "AVAudioSession is active for Now Playing"
                }
            }
            audioSessionActive = activated
            activated
        }
    }

    private fun deactivateAudioSession() {
        if (!audioSessionActive) return
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            val deactivated = audioSession.setActive(
                false,
                withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,
                error = error.ptr
            )
            if (!deactivated) logAudioSessionFailure("deactivate", error.value)
            if (deactivated) audioSessionActive = false
        }
    }

    private fun logAudioSessionFailure(operation: String, error: NSError?) {
        Logger.e(tag = "IosNowPlayingMediaIntegration") {
            "Failed to $operation AVAudioSession: " +
                (error?.localizedDescription ?: "unknown error")
        }
    }

    private fun publishNowPlaying(force: Boolean) {
        if (!active || !force) return
        val metadata = currentMetadata
        if (metadata == null) {
            nowPlayingCenter.nowPlayingInfo = null
            publishedMetadataMediaId = null
            return
        }

        val info = mutableMapOf<Any?, Any>(
            MPMediaItemPropertyTitle to metadata.title,
            MPNowPlayingInfoPropertyExternalContentIdentifier to metadata.mediaId,
            MPNowPlayingInfoPropertyElapsedPlaybackTime to
                currentSnapshot.positionMillis / MILLIS_PER_SECOND,
            MPNowPlayingInfoPropertyPlaybackRate to
                if (currentSnapshot.status == PlaybackStatus.Playing) {
                    currentSnapshot.speed.toDouble()
                } else {
                    0.0
                },
            MPNowPlayingInfoPropertyDefaultPlaybackRate to 1.0
        )
        metadata.artist?.let { info[MPMediaItemPropertyArtist] = it }
        metadata.albumTitle?.let { info[MPMediaItemPropertyAlbumTitle] = it }
        currentArtwork?.let { info[MPMediaItemPropertyArtwork] = it }
        if (currentSnapshot.durationMillis > 0) {
            info[MPMediaItemPropertyPlaybackDuration] =
                currentSnapshot.durationMillis / MILLIS_PER_SECOND
        }
        when (metadata.mediaType) {
            PlaybackMediaType.Audio ->
                info[MPNowPlayingInfoPropertyMediaType] = MPNowPlayingInfoMediaTypeAudio
            PlaybackMediaType.Video ->
                info[MPNowPlayingInfoPropertyMediaType] = MPNowPlayingInfoMediaTypeVideo
            PlaybackMediaType.Unknown -> Unit
        }
        currentSnapshot.queueIndex?.let {
            info[MPNowPlayingInfoPropertyPlaybackQueueIndex] = it
        }
        if (currentSnapshot.queueSize > 0) {
            info[MPNowPlayingInfoPropertyPlaybackQueueCount] = currentSnapshot.queueSize
        }
        nowPlayingCenter.nowPlayingInfo = info
        if (publishedMetadataMediaId != metadata.mediaId) {
            Logger.i(tag = "IosNowPlayingMediaIntegration") {
                "Published Now Playing metadata with ${info.size} fields"
            }
            publishedMetadataMediaId = metadata.mediaId
        }
        lastPublishedSnapshot = currentSnapshot
    }

    private data class CommandTarget(
        val command: MPRemoteCommand,
        val token: Any
    )
}

private fun runOnMain(block: () -> Unit) {
    if (NSThread.isMainThread) {
        block()
    } else {
        dispatch_async(dispatch_get_main_queue(), block)
    }
}

private fun runOnMainSynchronously(block: () -> Unit) {
    if (NSThread.isMainThread) {
        block()
    } else {
        dispatch_sync(dispatch_get_main_queue(), block)
    }
}

private fun NSNotification.unsignedLong(key: String?): ULong? =
    (userInfo?.get(key) as? NSNumber)?.unsignedIntegerValue

private fun ByteArray.toUIImage(): UIImage? {
    if (isEmpty()) return null
    val data = usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
    return UIImage(data)
}

private fun UIImage.toMediaItemArtwork(): MPMediaItemArtwork {
    val boundsSide = size.useContents { min(width, height) }.coerceAtLeast(1.0)
    val squareBounds = CGSizeMake(boundsSide, boundsSide)
    return MPMediaItemArtwork(boundsSize = squareBounds) { requestedSize ->
        val requestedSide = requestedSize.useContents { max(width, height) }
            .takeIf { it > 0.0 }
            ?: boundsSide
        centerCropped(CGSizeMake(requestedSide, requestedSide))
    }
}

private fun UIImage.centerCropped(targetSize: CValue<CGSize>): UIImage {
    val (sourceWidth, sourceHeight) = size.useContents { width to height }
    val (targetWidth, targetHeight) = targetSize.useContents { width to height }
    if (sourceWidth <= 0.0 || sourceHeight <= 0.0 ||
        targetWidth <= 0.0 || targetHeight <= 0.0
    ) {
        return this
    }

    val scale = max(targetWidth / sourceWidth, targetHeight / sourceHeight)
    val scaledWidth = sourceWidth * scale
    val scaledHeight = sourceHeight * scale
    val drawRect = CGRectMake(
        x = (targetWidth - scaledWidth) / 2.0,
        y = (targetHeight - scaledHeight) / 2.0,
        width = scaledWidth,
        height = scaledHeight
    )
    return UIGraphicsImageRenderer(targetSize).imageWithActions {
        drawInRect(drawRect)
    }
}

private const val POSITION_UPDATE_INTERVAL_MS = 1_000L
private const val MILLIS_PER_SECOND = 1_000.0
private const val DEFAULT_SKIP_INTERVAL_SECONDS = 15.0
