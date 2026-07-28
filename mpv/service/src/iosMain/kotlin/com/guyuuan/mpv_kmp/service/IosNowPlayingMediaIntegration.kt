@file:OptIn(
    kotlinx.cinterop.BetaInteropApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class
)

package com.guyuuan.mpv_kmp.service

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
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
import platform.Foundation.NSData
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
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
import platform.MediaPlayer.MPNowPlayingPlaybackStatePaused
import platform.MediaPlayer.MPNowPlayingPlaybackStatePlaying
import platform.MediaPlayer.MPNowPlayingPlaybackStateStopped
import platform.MediaPlayer.MPRemoteCommand
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandEvent
import platform.MediaPlayer.MPRemoteCommandHandlerStatusCommandFailed
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.MediaPlayer.MPSkipIntervalCommandEvent
import platform.UIKit.UIImage
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/** Asynchronously supplies iOS artwork. The completion may be called from any queue. */
fun interface IosArtworkLoader {
    fun load(artwork: PlaybackArtwork, completion: (UIImage?) -> Unit)
}

/** Supports in-memory artwork and lets applications provide their own URI loader when needed. */
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
 */
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
    private var lastPublishedSnapshot: PlaybackSnapshot? = null
    private var resumeAfterInterruption = false
    private var audioSessionActive = false
    private var active = false
    private val commandTargets = mutableListOf<CommandTarget>()
    private val notificationObservers = mutableListOf<NSObjectProtocol>()

    override fun activate(commandHandler: MediaCommandHandler) {
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
        currentMetadata = metadata
        currentArtwork = null
        artworkMediaId = metadata?.mediaId
        publishNowPlaying(force = true)

        val artwork = metadata?.artwork ?: return
        val requestedMediaId = metadata.mediaId
        artworkLoader.load(artwork) { image ->
            dispatch_async(dispatch_get_main_queue()) {
                if (active && requestedMediaId == artworkMediaId && image != null) {
                    currentArtwork = MPMediaItemArtwork(image)
                    publishNowPlaying(force = true)
                }
            }
        }
    }

    override fun updatePlaybackState(state: PlaybackSnapshot) {
        val lastPublished = lastPublishedSnapshot
        currentSnapshot = state
        updateCommandAvailability()
        updateAudioSession(state)
        updateNowPlayingPlaybackState(state.status)

        val positionMovedEnough = lastPublished == null ||
            kotlin.math.abs(state.positionMillis - lastPublished.positionMillis) >=
            POSITION_UPDATE_INTERVAL_MS
        val nonPositionStateChanged = lastPublished == null ||
            state.copy(positionMillis = lastPublished.positionMillis) != lastPublished
        publishNowPlaying(force = positionMovedEnough || nonPositionStateChanged)
    }

    override fun deactivate() {
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
        lastPublishedSnapshot = null
        nowPlayingCenter.nowPlayingInfo = null
        nowPlayingCenter.playbackState = MPNowPlayingPlaybackStateStopped
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
        when (snapshot.status) {
            PlaybackStatus.Playing -> {
                if (!activateAudioSession()) commandHandler?.handle(MediaCommand.Pause)
            }
            PlaybackStatus.Stopped,
            PlaybackStatus.Ended,
            PlaybackStatus.Error,
            PlaybackStatus.Disposed -> deactivateAudioSession()
            else -> Unit
        }
    }

    private fun activateAudioSession(): Boolean {
        if (audioSessionActive) return true
        val categorySet = audioSession.setCategory(AVAudioSessionCategoryPlayback, error = null)
        audioSessionActive = categorySet && audioSession.setActive(true, error = null)
        return audioSessionActive
    }

    private fun deactivateAudioSession() {
        if (!audioSessionActive) return
        audioSession.setActive(
            false,
            withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,
            error = null
        )
        audioSessionActive = false
    }

    private fun updateNowPlayingPlaybackState(status: PlaybackStatus) {
        nowPlayingCenter.playbackState = when (status) {
            PlaybackStatus.Playing -> MPNowPlayingPlaybackStatePlaying
            PlaybackStatus.Loading,
            PlaybackStatus.Paused -> MPNowPlayingPlaybackStatePaused
            else -> MPNowPlayingPlaybackStateStopped
        }
    }

    private fun publishNowPlaying(force: Boolean) {
        if (!active || !force) return
        val metadata = currentMetadata
        if (metadata == null) {
            nowPlayingCenter.nowPlayingInfo = null
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
        lastPublishedSnapshot = currentSnapshot
    }

    private data class CommandTarget(
        val command: MPRemoteCommand,
        val token: Any
    )
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

private const val POSITION_UPDATE_INTERVAL_MS = 1_000L
private const val MILLIS_PER_SECOND = 1_000.0
private const val DEFAULT_SKIP_INTERVAL_SECONDS = 15.0
