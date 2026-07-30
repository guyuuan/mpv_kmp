package com.guyuuan.mpv_kmp.service

import com.guyuuan.mpv_kmp.util.PlatformLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Resolves artwork for the one media item currently published by a [PlaybackCoordinator].
 *
 * A coordinator owns one loader created by [PlaybackArtworkLoaderFactory]. Calling [load] replaces
 * the previous request; [clear] releases the current request when metadata is removed or already
 * contains bytes. Implementations must not retain artwork from earlier media items.
 */
interface PlaybackArtworkLoader {
    fun load(
        metadata: PlaybackMetadata,
        onLoaded: (PlaybackMetadata) -> Unit
    )

    fun clear()

    fun close()
}

/**
 * Common request lifecycle for URI artwork.
 *
 * Subclasses only implement [loadBytes]. This class owns asynchronous dispatch, cancellation,
 * stale-result filtering, empty-result handling, and exception fallback. Loaded bytes are copied
 * into [PlaybackArtwork.Bytes], delivered once, and are not cached.
 */
abstract class AbstractPlaybackArtworkLoader(
    dispatcher: CoroutineDispatcher = Dispatchers.IO
) : PlaybackArtworkLoader {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val stateLock = PlatformLock()
    private var activeRequest: ArtworkRequest? = null
    private var loadingJob: Job? = null
    private var closed = false

    final override fun load(
        metadata: PlaybackMetadata,
        onLoaded: (PlaybackMetadata) -> Unit
    ) {
        val artwork = metadata.artwork as? PlaybackArtwork.Uri
        stateLock.withLock {
            check(!closed) { "PlaybackArtworkLoader is already closed" }
            cancelCurrentRequestLocked()
            if (artwork == null) return@withLock

            val request = ArtworkRequest(metadata, artwork)
            activeRequest = request
            loadingJob = scope.launch {
                resolve(request, onLoaded)
            }
        }
    }

    final override fun clear() {
        stateLock.withLock {
            if (closed) return@withLock
            cancelCurrentRequestLocked()
        }
    }

    final override fun close() {
        val shouldClose = stateLock.withLock {
            if (closed) {
                false
            } else {
                cancelCurrentRequestLocked()
                closed = true
                true
            }
        }
        if (!shouldClose) return
        scope.cancel()
        onClosed()
    }

    protected abstract suspend fun loadBytes(artwork: PlaybackArtwork.Uri): ByteArray?

    /** Releases subclass-owned resources. Called once after the current request is cancelled. */
    protected open fun onClosed() = Unit

    private suspend fun resolve(
        request: ArtworkRequest,
        onLoaded: (PlaybackMetadata) -> Unit
    ) {
        try {
            val bytes = try {
                loadBytes(request.artwork)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            currentCoroutineContext().ensureActive()
            if (bytes == null || bytes.isEmpty()) return

            val resolved = request.metadata.copy(artwork = PlaybackArtwork.Bytes(bytes))
            currentCoroutineContext().ensureActive()
            stateLock.withLock {
                if (activeRequest !== request) return@withLock
                onLoaded(resolved)
                if (activeRequest === request) {
                    activeRequest = null
                    loadingJob = null
                }
            }
        } finally {
            stateLock.withLock {
                if (activeRequest === request) {
                    activeRequest = null
                    loadingJob = null
                }
            }
        }
    }

    private fun cancelCurrentRequestLocked() {
        activeRequest = null
        loadingJob?.cancel()
        loadingJob = null
    }
}

/** Creates the single artwork loader owned by a [PlaybackCoordinator]. */
fun interface PlaybackArtworkLoaderFactory {
    fun create(): PlaybackArtworkLoader
}

private class ArtworkRequest(
    val metadata: PlaybackMetadata,
    val artwork: PlaybackArtwork.Uri
)
