package com.guyuuan.kmp.mpv.service

import co.touchlab.kermit.Logger
import com.guyuuan.kmp.mpv.Mpv
import com.guyuuan.kmp.mpv.MpvEventType
import com.guyuuan.kmp.mpv.config.MpvConfig
import com.guyuuan.kmp.mpv.data.MpvEvent
import com.guyuuan.kmp.mpv.props.MpvAudioProperties
import com.guyuuan.kmp.mpv.props.MpvPlaybackProperties
import com.guyuuan.kmp.mpv.util.PlatformLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Application-level owner for an [Mpv] and its platform media integration.
 *
 * UI code may retain and observe [player], but only the platform owner that created this
 * coordinator should call [close]. This keeps playback alive when a Compose page leaves the
 * composition.
 */
class PlaybackCoordinator(
    mpv: Mpv? = null,
    private val mediaIntegration: PlatformMediaIntegration = NoopPlatformMediaIntegration,
    private val stateStore: PlaybackStateStore? = null,
    availableCommands: Set<MediaCommandType> = DEFAULT_MEDIA_COMMANDS,
    artworkLoaderFactory: PlaybackArtworkLoaderFactory? = null,
    private val mpvConfig: MpvConfig = MpvConfig(),
    private val navigationHandler: PlaybackNavigationHandler? = null
) : MediaCommandHandler {
    private enum class PendingQueueLoadPhase {
        AwaitingStart,
        Loading
    }

    private data class PendingQueueLoad(
        var playWhenReady: Boolean,
        var phase: PendingQueueLoadPhase = PendingQueueLoadPhase.AwaitingStart
    )

    private val ownsSharedMpv: Boolean = mpv == null
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val artworkLoader: PlaybackArtworkLoader? = artworkLoaderFactory?.create()
    val player: Mpv = mpv ?: Mpv(mpvConfig)

    private val mutableSnapshot = MutableStateFlow(
        PlaybackSnapshot(availableCommands = availableCommands.toSet())
    )
    val snapshot: StateFlow<PlaybackSnapshot> = mutableSnapshot.asStateFlow()

    /** Immutable semantic queue corresponding to the playlist currently owned by libmpv. */
    val queueItems: List<PlaybackMetadata>
        get() = queue

    private var started = false
    private var closed = false
    private var playerInitialized = false
    private var hasActiveFile = false
    private var pauseProperty = false
    private var stopRequested = false
    private val pendingQueueLock = PlatformLock()
    private var pendingQueueLoad: PendingQueueLoad? = null
    private var queue: List<PlaybackMetadata> = emptyList()
    private var persistenceJob: Job? = null
    private var restoring = false

    private val eventListener: (MpvEvent) -> Unit = { event -> handleMpvEvent(event) }

    val isStarted: Boolean
        get() = started

    val isClosed: Boolean
        get() = closed

    /** Initializes libmpv and connects the platform media surface. Safe to call more than once. */
    fun start(): Boolean {
        check(!closed) { "PlaybackCoordinator is already closed" }
        if (started) return snapshot.value.status != PlaybackStatus.Error

        mediaIntegration.activate(this)
        playerInitialized = player.initialize()
        if (playerInitialized) {
            player.setCoroutineScope(scope)
            player.addEventListener(eventListener)
            COORDINATOR_OBSERVED_PROPERTIES.forEach(player::observeProperty)
        }
        started = true

        val status = if (playerInitialized) PlaybackStatus.Idle else PlaybackStatus.Error
        publishSnapshot(snapshot.value.copy(status = status))
        publishMetadata(snapshot.value.metadata)
        return status != PlaybackStatus.Error
    }

    /** Loads a URI together with the semantic metadata needed by system media surfaces. */
    fun load(metadata: PlaybackMetadata): Int = setQueue(listOf(metadata))

    /** Replaces the libmpv playlist and its semantic metadata in one operation. */
    fun setQueue(
        items: List<PlaybackMetadata>, currentIndex: Int = 0, playWhenReady: Boolean = true
    ): Int {
        ensureUsable()
        require(items.isNotEmpty()) { "Playback queue must not be empty" }
        require(currentIndex in items.indices) { "Current index must point to a queue item" }

        queue = items.toList()
        pendingQueueLock.withLock {
            pendingQueueLoad = PendingQueueLoad(playWhenReady)
        }
        var result = player.pause()
        if (result >= 0) pauseProperty = true
        result = result.firstErrorOr(player.playlistClear())
        result = result.firstErrorOr(player.load(items[currentIndex].uri))
        items.take(currentIndex).forEachIndexed { index, item ->
            result = result.firstErrorOr(player.addToPlaylist(item.uri, index))
        }
        result = result.firstErrorOr(
            player.addToPlaylist(
                *items.drop(currentIndex + 1).map { it.uri }.toTypedArray()
            )
        )
        val metadata = items[currentIndex]
        publishSnapshot(
            snapshot.value.copy(
                metadata = metadata,
                playWhenReady = playWhenReady,
                queueIndex = currentIndex,
                queueSize = items.size
            )
        )
        publishMetadata(metadata)
        if (result < 0) {
            clearPendingQueueLoad()
            publishSnapshot(snapshot.value.copy(status = PlaybackStatus.Error))
        }
        return result
    }

    fun updateMetadata(metadata: PlaybackMetadata?) {
        ensureUsable()
        val index = snapshot.value.queueIndex
        if (metadata != null && index != null && index in queue.indices) {
            queue = queue.toMutableList().also { it[index] = metadata }
        }
        publishSnapshot(snapshot.value.copy(metadata = metadata))
        publishMetadata(metadata)
    }

    fun updateQueuePosition(index: Int?, size: Int) {
        ensureUsable()
        val previousMetadata = snapshot.value.metadata
        val metadata = index?.let(queue::getOrNull) ?: snapshot.value.metadata
        publishSnapshot(
            snapshot.value.copy(metadata = metadata, queueIndex = index, queueSize = size)
        )
        if (metadata != previousMetadata) publishMetadata(metadata)
    }

    fun updateAvailableCommands(commands: Set<MediaCommandType>) {
        ensureUsable()
        publishSnapshot(snapshot.value.copy(availableCommands = commands.toSet()))
    }

    override fun handle(command: MediaCommand) {
        execute(command)
    }

    /** Executes a system or application command and returns the underlying libmpv result code. */
    fun execute(command: MediaCommand): Int {
        ensureUsable()
        if (command.type !in snapshot.value.availableCommands) return 0

        val requestedPlayWhenReady = when (command) {
            MediaCommand.Play -> true
            MediaCommand.Pause, MediaCommand.Stop -> false
            MediaCommand.TogglePlayPause -> !snapshot.value.playWhenReady
            else -> null
        }

        val result = when (command) {
            MediaCommand.Play -> {
                updatePendingQueuePlayWhenReady(true)
                player.play()
            }

            MediaCommand.Pause -> {
                updatePendingQueuePlayWhenReady(false)
                player.pause()
            }

            MediaCommand.TogglePlayPause -> {
                updatePendingQueuePlayWhenReady(requestedPlayWhenReady == true)
                if (requestedPlayWhenReady == true) player.play() else player.pause()
            }

            MediaCommand.Stop -> {
                clearPendingQueueLoad()
                stopRequested = true
                player.stop()
            }

            is MediaCommand.SeekTo -> player.seekTo(command.positionMillis / MILLIS_PER_SECOND)
            is MediaCommand.SeekBy -> {
                val position =
                    (snapshot.value.positionMillis + command.offsetMillis).coerceAtLeast(0)
                player.seekTo(position / MILLIS_PER_SECOND)
            }

            MediaCommand.Next -> navigationHandler?.let {
                it.onNext()
                COMMAND_ACCEPTED
            } ?: player.playlistNext()

            MediaCommand.Previous -> navigationHandler?.let {
                it.onPrevious()
                COMMAND_ACCEPTED
            } ?: player.playlistPrev()
            is MediaCommand.SetSpeed -> player.setSpeed(command.speed)
            is MediaCommand.SetVolume -> player.setVolume(command.volume.toDouble())
            is MediaCommand.SetRepeatMode -> applyRepeatMode(command.repeatMode)
            is MediaCommand.SetShuffle -> applyShuffle(command.enabled)
        }

        if (result >= 0) {
            if (requestedPlayWhenReady != null) {
                publishSnapshot(
                    snapshot.value.copy(playWhenReady = requestedPlayWhenReady)
                )
            }
        }

        if (result < 0) {
            if (command == MediaCommand.Play ||
                command == MediaCommand.Pause ||
                command == MediaCommand.TogglePlayPause
            ) {
                clearPendingQueueLoad()
            }
            if (command == MediaCommand.Stop) stopRequested = false
            publishSnapshot(snapshot.value.copy(status = PlaybackStatus.Error))
        }
        return result
    }

    /** Saves a restorable snapshot immediately, if a state store and queue are available. */
    fun persistPlaybackState(): Boolean {
        val state = captureRestorableState() ?: return false
        val store = stateStore ?: return false
        return runCatching { store.save(state) }.isSuccess
    }

    fun clearSavedPlaybackState() {
        stateStore?.let { store -> runCatching(store::clear) }
    }

    /** Restores a previously saved queue without auto-playing unless explicitly requested. */
    fun restoreSavedPlayback(resumePlayback: Boolean = false): Boolean {
        ensureUsable()
        val store = stateStore ?: return false
        val state = runCatching(store::load).getOrNull() ?: return false
        return restore(state, resumePlayback)
    }

    fun restore(state: RestorablePlaybackState, resumePlayback: Boolean = false): Boolean {
        ensureUsable()
        restoring = true
        return try {
            var result = setQueue(
                items = state.queue,
                currentIndex = state.currentIndex,
                playWhenReady = resumePlayback && !state.paused
            )
            result = result.firstErrorOr(player.seekTo(state.positionMillis / MILLIS_PER_SECOND))
            result = result.firstErrorOr(player.setSpeed(state.speed))
            result = result.firstErrorOr(applyRepeatMode(state.repeatMode))
            result = result.firstErrorOr(applyShuffle(state.shuffleEnabled))
            if (!resumePlayback || state.paused) {
                result = result.firstErrorOr(player.pause())
            }
            if (result < 0) publishSnapshot(snapshot.value.copy(status = PlaybackStatus.Error))
            result >= 0
        } finally {
            restoring = false
            schedulePersistence()
        }
    }

    /**
     * Disconnects system callbacks and terminates the owned player. Safe to call repeatedly.
     */
    fun close() {
        if (closed) return
        persistenceJob?.cancel()
        persistPlaybackState()
        closed = true
        var cleanupFailure: Throwable? = null
        fun cleanup(action: () -> Unit) {
            try {
                action()
            } catch (error: Throwable) {
                if (cleanupFailure == null) cleanupFailure = error
            }
        }

        if (playerInitialized) {
            cleanup { player.removeEventListener(eventListener) }
            COORDINATOR_OBSERVED_PROPERTIES.forEach { property ->
                cleanup { player.removePropertyObservation(property) }
            }
        }
        hasActiveFile = false
        stopRequested = false
        clearPendingQueueLoad()
        val disposed = snapshot.value.copy(
            metadata = null,
            status = PlaybackStatus.Disposed,
            playWhenReady = false,
            positionMillis = 0,
            durationMillis = 0
        )
        mutableSnapshot.value = disposed
        cleanup { mediaIntegration.updatePlaybackState(disposed) }
        cleanup { artworkLoader?.clear() }
        cleanup { mediaIntegration.updateMetadata(null) }
        cleanup { artworkLoader?.close() }
        cleanup { mediaIntegration.deactivate() }
        cleanup {
            if (ownsSharedMpv) {
                Mpv.release()
            } else {
                player.terminate()
            }
        }
        playerInitialized = false
        scope.cancel()
        cleanupFailure?.let { throw it }
    }

    private fun handleMpvEvent(event: MpvEvent) {
        if (closed) return
        if (event.error < 0) {
            hasActiveFile = false
            stopRequested = false
            resetPendingQueueLoadIfLoading()
            publishSnapshot(snapshot.value.copy(status = PlaybackStatus.Error))
            return
        }

        var next = snapshot.value
        when (event.type) {
            MpvEventType.PropertyChange -> {
                next = when (event.name) {
                    MpvPlaybackProperties.PAUSE -> {
                        pauseProperty = event.value.toMpvBoolean()
                        next.copy(
                            status = if (hasActiveFile) {
                                if (pauseProperty) PlaybackStatus.Paused else PlaybackStatus.Playing
                            } else {
                                next.status
                            },
                            playWhenReady = pendingQueuePlayWhenReady() ?: !pauseProperty
                        )
                    }

                    MpvPlaybackProperties.SPEED -> next.copy(
                        speed = event.value?.toFloatOrNull() ?: 1f
                    )

                    MpvPlaybackProperties.TIME_POSITION -> next.copy(positionMillis = event.value.toNonNegativeMillis())

                    MpvPlaybackProperties.DURATION -> next.copy(durationMillis = event.value.toNonNegativeMillis())

                    MpvAudioProperties.VOLUME -> next.copy(
                        volume = event.value?.toFloatOrNull()?.coerceAtLeast(0f) ?: 0f
                    )

                    VIDEO_DISPLAY_WIDTH -> next.copy(
                        videoWidth = event.value.toNonNegativeDimension()
                    )

                    VIDEO_DISPLAY_HEIGHT -> next.copy(
                        videoHeight = event.value.toNonNegativeDimension()
                    )

                    PLAYLIST_POSITION -> {
                        val index = event.value?.toDoubleOrNull()?.toInt()
                        if (index != null && index in queue.indices) {
                            val metadata = queue[index]
                            if (metadata != next.metadata) publishMetadata(metadata)
                            next.copy(
                                metadata = metadata, queueIndex = index, queueSize = queue.size
                            )
                        } else {
                            next
                        }
                    }

                    LOOP_FILE -> next.copy(
                        repeatMode = if (event.value.toMpvBoolean()) {
                            PlaybackRepeatMode.One
                        } else if (next.repeatMode == PlaybackRepeatMode.One) {
                            PlaybackRepeatMode.None
                        } else {
                            next.repeatMode
                        }
                    )

                    LOOP_PLAYLIST -> next.copy(
                        repeatMode = if (event.value.toMpvBoolean()) {
                            PlaybackRepeatMode.All
                        } else if (next.repeatMode == PlaybackRepeatMode.All) {
                            PlaybackRepeatMode.None
                        } else {
                            next.repeatMode
                        }
                    )

                    else -> next
                }
            }

            MpvEventType.Pause -> {
                pauseProperty = true
                next = next.copy(
                    status = if (hasActiveFile) PlaybackStatus.Paused else next.status,
                    playWhenReady = pendingQueuePlayWhenReady() ?: false
                )
            }

            MpvEventType.Unpause -> {
                pauseProperty = false
                next = next.copy(
                    status = if (hasActiveFile) PlaybackStatus.Playing else next.status,
                    playWhenReady = pendingQueuePlayWhenReady() ?: true
                )
            }

            MpvEventType.StartFile -> {
                hasActiveFile = false
                stopRequested = false
                markPendingQueueLoading()
                next = next.copy(
                    status = PlaybackStatus.Loading,
                    positionMillis = 0,
                    durationMillis = 0,
                    videoWidth = 0,
                    videoHeight = 0
                )
            }

            MpvEventType.FileLoaded -> {
                hasActiveFile = true
                val pendingPlayWhenReady = consumePendingQueueLoad()
                if (pendingPlayWhenReady != null) {
                    if (pendingPlayWhenReady) {
                        val playResult = player.play()
                        if (playResult < 0) {
                            next = next.copy(status = PlaybackStatus.Error)
                        } else {
                            pauseProperty = false
                            next = next.copy(
                                status = PlaybackStatus.Playing,
                                playWhenReady = true
                            )
                        }
                    } else {
                        pauseProperty = true
                        next = next.copy(
                            status = PlaybackStatus.Paused,
                            playWhenReady = false
                        )
                    }
                } else {
                    next = next.copy(
                        status = if (pauseProperty) {
                            PlaybackStatus.Paused
                        } else {
                            PlaybackStatus.Playing
                        }
                    )
                }
            }

            MpvEventType.PlaybackRestart -> {
                hasActiveFile = true
                next = next.copy(
                    status = if (pauseProperty) PlaybackStatus.Paused else PlaybackStatus.Playing
                )
            }

            MpvEventType.EndFile -> {
                hasActiveFile = false
                resetPendingQueueLoadIfLoading()
                next = next.copy(
                    status = if (stopRequested) PlaybackStatus.Stopped else PlaybackStatus.Ended
                )
                stopRequested = false
            }

            MpvEventType.Idle -> {
                resetPendingQueueLoadIfLoading()
                if (!hasActiveFile && next.status == PlaybackStatus.Loading) {
                    next = next.copy(status = PlaybackStatus.Idle)
                }
            }

            MpvEventType.Shutdown -> {
                hasActiveFile = false
                stopRequested = false
                clearPendingQueueLoad()
                next = next.copy(
                    status = PlaybackStatus.Disposed, playWhenReady = false
                )
            }

            else -> Unit
        }
        if (next != snapshot.value) publishSnapshot(next)
    }

    private fun publishSnapshot(value: PlaybackSnapshot) {
        mutableSnapshot.value = value
        if (started && !closed) {
            mediaIntegration.updatePlaybackState(value)
        }
        schedulePersistence()
    }

    private fun updatePendingQueuePlayWhenReady(playWhenReady: Boolean) {
        pendingQueueLock.withLock {
            pendingQueueLoad?.playWhenReady = playWhenReady
        }
    }

    private fun pendingQueuePlayWhenReady(): Boolean? = pendingQueueLock.withLock {
        pendingQueueLoad?.playWhenReady
    }

    private fun markPendingQueueLoading() {
        pendingQueueLock.withLock {
            pendingQueueLoad?.phase = PendingQueueLoadPhase.Loading
        }
    }

    private fun consumePendingQueueLoad(): Boolean? = pendingQueueLock.withLock {
        val pendingLoad = pendingQueueLoad?.takeIf {
            it.phase == PendingQueueLoadPhase.Loading
        } ?: return@withLock null
        pendingQueueLoad = null
        pendingLoad.playWhenReady
    }

    private fun resetPendingQueueLoadIfLoading() {
        pendingQueueLock.withLock {
            pendingQueueLoad?.takeIf {
                it.phase == PendingQueueLoadPhase.Loading
            }?.phase = PendingQueueLoadPhase.AwaitingStart
        }
    }

    private fun clearPendingQueueLoad() {
        pendingQueueLock.withLock {
            pendingQueueLoad = null
        }
    }

    private fun publishMetadata(metadata: PlaybackMetadata?) {
        artworkLoader?.clear()
        mediaIntegration.updateMetadata(metadata)
        if (metadata != null) {
            artworkLoader?.load(metadata) { resolved ->
                if (!closed) mediaIntegration.updateMetadata(resolved)
            }
        }
    }

    private fun applyRepeatMode(repeatMode: PlaybackRepeatMode): Int {
        var result = player.setProperty(
            LOOP_FILE, if (repeatMode == PlaybackRepeatMode.One) MPV_INFINITE else MPV_DISABLED
        )
        result = result.firstErrorOr(
            player.setProperty(
                LOOP_PLAYLIST,
                if (repeatMode == PlaybackRepeatMode.All) MPV_INFINITE else MPV_DISABLED
            )
        )
        if (result >= 0) publishSnapshot(snapshot.value.copy(repeatMode = repeatMode))
        return result
    }

    private fun applyShuffle(enabled: Boolean): Int {
        val result = player.commandString(if (enabled) "playlist-shuffle" else "playlist-unshuffle")
        if (result >= 0) publishSnapshot(snapshot.value.copy(shuffleEnabled = enabled))
        return result
    }

    private fun captureRestorableState(): RestorablePlaybackState? {
        val current = snapshot.value
        val currentIndex = current.queueIndex ?: return null
        if (queue.isEmpty() || currentIndex !in queue.indices) return null
        return RestorablePlaybackState(
            queue = queue,
            currentIndex = currentIndex,
            positionMillis = current.positionMillis,
            speed = current.speed,
            repeatMode = current.repeatMode,
            shuffleEnabled = current.shuffleEnabled,
            paused = !current.playWhenReady
        )
    }

    private fun schedulePersistence() {
        val store = stateStore ?: return
        if (!started || closed || restoring) return
        val state = captureRestorableState() ?: return
        persistenceJob?.cancel()
        persistenceJob = scope.launch {
            delay(PERSISTENCE_DEBOUNCE_MILLIS)
            runCatching { store.save(state) }
        }
    }

    private fun ensureUsable() {
        check(started) { "PlaybackCoordinator has not been started" }
        check(!closed) { "PlaybackCoordinator is already closed" }
    }
}

private const val MILLIS_PER_SECOND = 1000.0
private const val COMMAND_ACCEPTED = 0
private const val PERSISTENCE_DEBOUNCE_MILLIS = 1_000L
private const val PLAYLIST_POSITION = "playlist-pos"
private const val LOOP_FILE = "loop-file"
private const val LOOP_PLAYLIST = "loop-playlist"
private const val VIDEO_DISPLAY_WIDTH = "video-out-params/dw"
private const val VIDEO_DISPLAY_HEIGHT = "video-out-params/dh"
private const val MPV_INFINITE = "inf"
private const val MPV_DISABLED = "no"

private val COORDINATOR_OBSERVED_PROPERTIES = listOf(
    MpvAudioProperties.VOLUME,
    MpvPlaybackProperties.PAUSE,
    MpvPlaybackProperties.SPEED,
    MpvPlaybackProperties.TIME_POSITION,
    MpvPlaybackProperties.DURATION,
    VIDEO_DISPLAY_WIDTH,
    VIDEO_DISPLAY_HEIGHT,
    PLAYLIST_POSITION,
    LOOP_FILE,
    LOOP_PLAYLIST
)

private fun Int.firstErrorOr(next: Int): Int = if (this < 0) this else next

private fun String?.toMpvBoolean(): Boolean = this == "yes" || this == "true"

private fun String?.toNonNegativeMillis(): Long =
    ((this?.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0) * MILLIS_PER_SECOND).toLong()

private fun String?.toNonNegativeDimension(): Int =
    (this?.toDoubleOrNull() ?: 0.0).toInt().coerceAtLeast(0)
