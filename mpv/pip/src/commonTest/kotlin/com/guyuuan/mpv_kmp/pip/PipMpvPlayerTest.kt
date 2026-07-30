package com.guyuuan.mpv_kmp.pip

import com.guyuuan.mpv_kmp.MpvPlayer
import com.guyuuan.mpv_kmp.MpvPlayerCapability
import com.guyuuan.mpv_kmp.MpvPlayerSnapshot
import com.guyuuan.mpv_kmp.MpvPlayerState
import com.guyuuan.mpv_kmp.MpvVideoOutput
import com.guyuuan.mpv_kmp.data.MpvDecoderInfo
import com.guyuuan.mpv_kmp.service.PlaybackSnapshot
import com.guyuuan.mpv_kmp.service.PlaybackStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

class PipMpvPlayerTest {
    @Test
    fun exposesPipCapabilityOnlyWhenPlatformIsAvailable() {
        val available = FakePictureInPictureController(
            PictureInPictureAvailability.Available
        )
        val unsupported = FakePictureInPictureController(
            PictureInPictureAvailability.UnsupportedVideoOutput
        )

        assertTrue(
            MpvPlayerCapability.PictureInPicture in
                PipMpvPlayer(FakePlayer, available).capabilities
        )
        assertFalse(
            MpvPlayerCapability.PictureInPicture in
                PipMpvPlayer(FakePlayer, unsupported).capabilities
        )
    }

    @Test
    fun closeReleasesConnectionOnlyOnce() {
        val controller = FakePictureInPictureController(
            PictureInPictureAvailability.Available
        )
        var releaseCount = 0
        val player = PipMpvPlayer(FakePlayer, controller) {
            releaseCount++
        }

        player.close()
        player.close()

        assertEquals(1, releaseCount)
    }

    @Test
    fun userCanRequestPictureInPictureThroughPlayer() {
        val controller = FakePictureInPictureController(
            availability = PictureInPictureAvailability.Available,
            requestStartResult = true
        )
        val player = PipMpvPlayer(FakePlayer, controller)

        assertTrue(player.enterPictureInPicture())
        assertEquals(1, controller.requestStartCount)

        player.close()

        assertFalse(player.enterPictureInPicture())
        assertEquals(1, controller.requestStartCount)
    }

    @Test
    fun platformCanReplaceDelegatesVideoOutput() {
        val controller = FakePictureInPictureController(
            PictureInPictureAvailability.Available
        )
        val pipVideoOutput = object : MpvVideoOutput {}

        val player = PipMpvPlayer(
            delegate = FakePlayer,
            pictureInPicture = controller,
            videoOutput = pipVideoOutput
        )

        assertSame(pipVideoOutput, player.videoOutput)
    }

    @Test
    fun mapsCoordinatorSnapshotToSharedPlayerSnapshot() {
        val snapshot = PlaybackSnapshot(
            status = PlaybackStatus.Playing,
            positionMillis = 12_500,
            durationMillis = 90_000,
            volume = 42f,
            speed = 1.5f
        )

        assertEquals(
            MpvPlayerSnapshot(
                state = MpvPlayerState.Playing,
                positionSeconds = 12.5,
                durationSeconds = 90.0,
                volume = 42f,
                speed = 1.5f
            ),
            snapshot.toMpvPlayerSnapshot()
        )
    }

    private object FakePlayer : MpvPlayer {
        override val snapshot: StateFlow<MpvPlayerSnapshot> =
            MutableStateFlow(MpvPlayerSnapshot())
        override val capabilities: Set<MpvPlayerCapability> = emptySet()
        override val videoOutput: MpvVideoOutput = object : MpvVideoOutput {}
        override val decoderInfoFlow: Flow<MpvDecoderInfo> = emptyFlow()

        override fun load(uri: String): Int = 0
        override fun play(): Int = 0
        override fun pause(): Int = 0
        override fun stop(): Int = 0
        override fun seek(positionSeconds: Double): Int = 0
        override fun setVolume(volume: Double): Int = 0
        override fun setSpeed(speed: Float): Int = 0
    }

    private class FakePictureInPictureController(
        availability: PictureInPictureAvailability,
        private val requestStartResult: Boolean = false
    ) : PictureInPictureController {
        override val availability: StateFlow<PictureInPictureAvailability> =
            MutableStateFlow(availability)
        override val state: StateFlow<PictureInPictureState> =
            MutableStateFlow(PictureInPictureState.Inactive)
        var requestStartCount = 0
            private set

        override fun setEligible(eligible: Boolean) = Unit
        override fun setAspectRatio(width: Int, height: Int) = Unit
        override fun requestStart(): Boolean {
            requestStartCount++
            return requestStartResult
        }
        override fun requestStop(): Boolean = false
        override fun close() = Unit
    }
}
