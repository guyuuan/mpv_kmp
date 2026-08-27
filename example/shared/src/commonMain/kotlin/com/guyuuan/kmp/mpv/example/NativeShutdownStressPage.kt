package com.guyuuan.kmp.mpv.example

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.guyuuan.kmp.mpv.MpvComposeView
import com.guyuuan.kmp.mpv.MpvPlayerSnapshot
import com.guyuuan.kmp.mpv.MpvPlayerState
import com.guyuuan.kmp.mpv.MpvVideoOutputReadiness
import com.guyuuan.kmp.mpv.MpvVideoOutputState
import com.guyuuan.kmp.mpv.pip.PipMpvPlayer
import com.guyuuan.kmp.mpv.pip.rememberPipMpvPlayer
import com.guyuuan.kmp.mpv.service.PlaybackMediaType
import com.guyuuan.kmp.mpv.service.PlaybackMetadata
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** Device-only page driven by Android instrumentation and iOS XCUITest. */
@Composable
fun NativeShutdownStressPage(iterations: Int = STRESS_ITERATIONS) {
    var running by remember { mutableStateOf(false) }
    var playerMounted by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    var completedIterations by remember { mutableIntStateOf(0) }
    var failureMessage by remember { mutableStateOf<String?>(null) }

    val status = when {
        failureMessage != null -> "Stress test failed: $failureMessage"
        !running && completedIterations == iterations ->
            "Stress test passed: $completedIterations/$iterations"

        running -> "Stress test running: $completedIterations/$iterations"
        else -> "Stress test ready: 0/$iterations"
    }

    Column(
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.safeContent)
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Native shutdown stress test",
            modifier = Modifier.testTag(STRESS_TITLE_TAG),
        )
        Text(
            text = status,
            modifier = Modifier.testTag(STRESS_STATUS_TAG),
        )
        Button(
            enabled = !running,
            onClick = {
                completedIterations = 0
                generation = 0
                failureMessage = null
                running = true
                playerMounted = true
            },
            modifier = Modifier.testTag(STRESS_START_TAG),
        ) {
            Text("Start stress test")
        }

        if (running && playerMounted) {
            key(generation) {
                NativeShutdownStressIteration(
                    generation = generation,
                    onComplete = {
                        completedIterations += 1
                        playerMounted = false
                    },
                    onFailure = { message ->
                        failureMessage = message
                        running = false
                        playerMounted = false
                    },
                )
            }
        }
    }

    LaunchedEffect(running, playerMounted, completedIterations) {
        if (!running || playerMounted) return@LaunchedEffect
        if (completedIterations >= iterations) {
            running = false
            return@LaunchedEffect
        }

        // The previous composition must be disposed (and close synchronously returned) before a
        // new process-level owner is created on the next composition pass.
        yield()
        generation += 1
        playerMounted = true
    }
}

@Composable
private fun NativeShutdownStressIteration(
    generation: Int,
    onComplete: () -> Unit,
    onFailure: (String) -> Unit,
) {
    val player = rememberPipMpvPlayer()
    val snapshot by player.snapshot.collectAsState()
    val scenario = StressScenario.entries[generation % StressScenario.entries.size]

    Text(
        text = "Iteration ${generation + 1}: ${scenario.label}, state=${snapshot.state}",
        modifier = Modifier.testTag(STRESS_ITERATION_TAG),
    )
    MpvComposeView(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .testTag(STRESS_VIDEO_TAG),
        player = player,
    )

    LaunchedEffect(player, generation) {
        val outputReadiness = player.videoOutput as? MpvVideoOutputReadiness
        if (outputReadiness != null && !outputReadiness.awaitVideoOutputAttached()) {
            player.close()
            onFailure(
                "video output was not attached within ${SURFACE_TIMEOUT_MILLIS}ms " +
                    "at iteration ${generation + 1}"
            )
            return@LaunchedEffect
        }

        // Register before load() so a fast StartFile -> FileLoaded transition cannot be missed
        // merely because the state waiter started too late.
        val loadingWaiter = if (scenario == StressScenario.Loading) {
            async(start = CoroutineStart.UNDISPATCHED) {
                player.snapshot.awaitState(
                    expected = MpvPlayerState.Loading,
                    phase = "waiting-loading",
                    timeoutMillis = NETWORK_LOADING_TIMEOUT_MILLIS,
                    abortStates = TERMINAL_STATES + setOf(
                        MpvPlayerState.Playing,
                        MpvPlayerState.Paused,
                    ),
                )
            }
        } else {
            null
        }
        val loadResult = player.load(
            PlaybackMetadata(
                mediaId = "$STRESS_MEDIA_URL#$generation",
                uri = STRESS_MEDIA_URL,
                title = "Native shutdown stress ${generation + 1}",
                mediaType = PlaybackMediaType.Video,
            )
        )
        if (loadResult < 0) {
            loadingWaiter?.cancel()
            player.close()
            onFailure("load failed with $loadResult at iteration ${generation + 1}")
            return@LaunchedEffect
        }

        val preparationFailure = prepareScenario(player, scenario, loadingWaiter)
        if (preparationFailure != null) {
            player.close()
            onFailure("$preparationFailure at iteration ${generation + 1}")
            return@LaunchedEffect
        }

        val stopResult = player.stop()
        if (stopResult < 0) {
            player.close()
            onFailure(
                "stop command failed with $stopResult, state=${player.snapshot.value.state} " +
                    "at iteration ${generation + 1}"
            )
            return@LaunchedEffect
        }
        player.close()
        onComplete()
    }
}

private suspend fun prepareScenario(
    player: PipMpvPlayer,
    scenario: StressScenario,
    loadingWaiter: Deferred<AwaitStateResult>?,
): String? =
    when (scenario) {
        StressScenario.Loading -> {
            val loading = checkNotNull(loadingWaiter).await()
            loading.failureMessage()?.let { return it }
            val currentState = player.snapshot.value.state
            if (currentState == MpvPlayerState.Loading) {
                null
            } else {
                "waiting-loading reached Loading but advanced to $currentState before shutdown"
            }
        }

        StressScenario.Playing -> {
            player.snapshot.awaitState(
                expected = MpvPlayerState.Playing,
                phase = "waiting-playing",
                timeoutMillis = NETWORK_PLAYING_TIMEOUT_MILLIS,
            ).failureMessage()
        }

        StressScenario.Paused -> {
            val playing = player.snapshot.awaitState(
                expected = MpvPlayerState.Playing,
                phase = "waiting-playing-before-pause",
                timeoutMillis = NETWORK_PLAYING_TIMEOUT_MILLIS,
            )
            playing.failureMessage()?.let { return it }

            val pauseResult = player.pause()
            if (pauseResult < 0) {
                return "pause command failed with $pauseResult, state=${player.snapshot.value.state}"
            }
            player.snapshot.awaitState(
                expected = MpvPlayerState.Paused,
                phase = "waiting-paused",
                timeoutMillis = PAUSE_TIMEOUT_MILLIS,
            ).failureMessage()
        }
    }.also { failure ->
        if (failure == null && scenario != StressScenario.Loading) {
            delay(PLAYBACK_SETTLE_MILLIS.milliseconds)
        }
    }

private suspend fun kotlinx.coroutines.flow.StateFlow<MpvPlayerSnapshot>.awaitState(
    expected: MpvPlayerState,
    phase: String,
    timeoutMillis: Long,
    abortStates: Set<MpvPlayerState> = TERMINAL_STATES,
): AwaitStateResult {
    val started = TimeSource.Monotonic.markNow()
    val snapshot = withTimeoutOrNull(timeoutMillis.milliseconds) {
        first { value ->
            value.state == expected || value.state in abortStates
        }
    }
    val elapsedMillis = started.elapsedNow().inWholeMilliseconds
    return when {
        snapshot == null -> AwaitStateResult.TimedOut(
            phase = phase,
            expected = expected,
            lastState = value.state,
            elapsedMillis = elapsedMillis,
        )

        snapshot.state == expected -> AwaitStateResult.Reached
        else -> AwaitStateResult.Terminal(
            phase = phase,
            expected = expected,
            state = snapshot.state,
            elapsedMillis = elapsedMillis,
        )
    }
}

private suspend fun MpvVideoOutputReadiness.awaitVideoOutputAttached(): Boolean =
    withTimeoutOrNull(SURFACE_TIMEOUT_MILLIS.milliseconds) {
        videoOutputState.first { it == MpvVideoOutputState.Attached }
        true
    } ?: false

private sealed interface AwaitStateResult {
    data object Reached : AwaitStateResult

    data class Terminal(
        val phase: String,
        val expected: MpvPlayerState,
        val state: MpvPlayerState,
        val elapsedMillis: Long,
    ) : AwaitStateResult

    data class TimedOut(
        val phase: String,
        val expected: MpvPlayerState,
        val lastState: MpvPlayerState,
        val elapsedMillis: Long,
    ) : AwaitStateResult
}

private fun AwaitStateResult.failureMessage(): String? = when (this) {
    AwaitStateResult.Reached -> null
    is AwaitStateResult.Terminal ->
        "$phase reached terminal state=$state while waiting for $expected after ${elapsedMillis}ms"

    is AwaitStateResult.TimedOut ->
        "$phase timed out waiting for $expected after ${elapsedMillis}ms, lastState=$lastState"
}

private val TERMINAL_STATES = setOf(
    MpvPlayerState.Error,
    MpvPlayerState.Stopped,
    MpvPlayerState.Ended,
    MpvPlayerState.Disposed,
)

private enum class StressScenario(val label: String) {
    Loading("loading"),
    Playing("playing"),
    Paused("paused"),
}

const val STRESS_START_TAG = "native_shutdown_stress_start"
const val STRESS_STATUS_TAG = "native_shutdown_stress_status"
const val STRESS_TITLE_TAG = "native_shutdown_stress_title"
const val STRESS_ITERATION_TAG = "native_shutdown_stress_iteration"
const val STRESS_VIDEO_TAG = "native_shutdown_stress_video"

private const val STRESS_ITERATIONS = 100
private const val SURFACE_TIMEOUT_MILLIS = 5_000L
private const val NETWORK_LOADING_TIMEOUT_MILLIS = 10_000L
private const val NETWORK_PLAYING_TIMEOUT_MILLIS = 30_000L
private const val PAUSE_TIMEOUT_MILLIS = 5_000L
private const val PLAYBACK_SETTLE_MILLIS = 1000L
private const val STRESS_MEDIA_URL =
    "https://developer.mozilla.org/shared-assets/videos/flower.mp4"
