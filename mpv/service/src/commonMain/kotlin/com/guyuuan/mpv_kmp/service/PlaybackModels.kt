package com.guyuuan.mpv_kmp.service

/** The semantic media type exposed to platform media surfaces. */
enum class PlaybackMediaType {
    Unknown,
    Audio,
    Video
}

/** Artwork supplied by the application instead of inferred from a file name. */
sealed interface PlaybackArtwork {
    data class Uri(val value: String) : PlaybackArtwork {
        init {
            require(value.isNotBlank()) { "Artwork URI must not be blank" }
        }
    }

    class Bytes(bytes: ByteArray) : PlaybackArtwork {
        private val content = bytes.copyOf()

        init {
            require(content.isNotEmpty()) { "Artwork bytes must not be empty" }
        }

        fun toByteArray(): ByteArray = content.copyOf()

        override fun equals(other: Any?): Boolean =
            other is Bytes && content.contentEquals(other.content)

        override fun hashCode(): Int = content.contentHashCode()

        override fun toString(): String = "Bytes(size=${content.size})"
    }
}

data class PlaybackMetadata(
    val mediaId: String,
    val uri: String,
    val title: String,
    val artist: String? = null,
    val albumTitle: String? = null,
    val artwork: PlaybackArtwork? = null,
    val mediaType: PlaybackMediaType = PlaybackMediaType.Unknown,
    val extras: Map<String, String> = emptyMap()
) {
    init {
        require(mediaId.isNotBlank()) { "Media ID must not be blank" }
        require(uri.isNotBlank()) { "Media URI must not be blank" }
        require(title.isNotBlank()) { "Media title must not be blank" }
    }
}

enum class PlaybackStatus {
    Idle,
    Loading,
    Playing,
    Paused,
    Stopped,
    Ended,
    Error,
    Disposed
}

enum class PlaybackRepeatMode {
    None,
    One,
    All
}

enum class MediaCommandType {
    Play,
    Pause,
    TogglePlayPause,
    Stop,
    SeekTo,
    SeekBy,
    Next,
    Previous,
    SetSpeed,
    SetVolume,
    SetRepeatMode,
    SetShuffle
}

sealed interface MediaCommand {
    val type: MediaCommandType

    data object Play : MediaCommand {
        override val type = MediaCommandType.Play
    }

    data object Pause : MediaCommand {
        override val type = MediaCommandType.Pause
    }

    data object TogglePlayPause : MediaCommand {
        override val type = MediaCommandType.TogglePlayPause
    }

    data object Stop : MediaCommand {
        override val type = MediaCommandType.Stop
    }

    data class SeekTo(val positionMillis: Long) : MediaCommand {
        override val type = MediaCommandType.SeekTo

        init {
            require(positionMillis >= 0) { "Seek position must not be negative" }
        }
    }

    data class SeekBy(val offsetMillis: Long) : MediaCommand {
        override val type = MediaCommandType.SeekBy
    }

    data object Next : MediaCommand {
        override val type = MediaCommandType.Next
    }

    data object Previous : MediaCommand {
        override val type = MediaCommandType.Previous
    }

    data class SetSpeed(val speed: Float) : MediaCommand {
        override val type = MediaCommandType.SetSpeed

        init {
            require(speed in 0.01f..100f) { "Playback speed must be between 0.01 and 100" }
        }
    }

    data class SetVolume(val volume: Float) : MediaCommand {
        override val type = MediaCommandType.SetVolume

        init {
            require(volume in 0f..100f) { "Volume must be between 0 and 100" }
        }
    }

    data class SetRepeatMode(val repeatMode: PlaybackRepeatMode) : MediaCommand {
        override val type = MediaCommandType.SetRepeatMode
    }

    data class SetShuffle(val enabled: Boolean) : MediaCommand {
        override val type = MediaCommandType.SetShuffle
    }
}

data class PlaybackSnapshot(
    val metadata: PlaybackMetadata? = null,
    val status: PlaybackStatus = PlaybackStatus.Idle,
    val playWhenReady: Boolean = false,
    val positionMillis: Long = 0,
    val durationMillis: Long = 0,
    val speed: Float = 1f,
    val volume: Float = 0f,
    val queueIndex: Int? = null,
    val queueSize: Int = 0,
    val repeatMode: PlaybackRepeatMode = PlaybackRepeatMode.None,
    val shuffleEnabled: Boolean = false,
    val availableCommands: Set<MediaCommandType> = DEFAULT_MEDIA_COMMANDS
) {
    val isPlaying: Boolean
        get() = status == PlaybackStatus.Playing

    init {
        require(positionMillis >= 0) { "Position must not be negative" }
        require(durationMillis >= 0) { "Duration must not be negative" }
        require(queueSize >= 0) { "Queue size must not be negative" }
        require(queueIndex == null || queueIndex in 0 until queueSize) {
            "Queue index must point to an item in the queue"
        }
    }
}

val DEFAULT_MEDIA_COMMANDS: Set<MediaCommandType> = MediaCommandType.entries.toSet()

data class RestorablePlaybackState(
    val queue: List<PlaybackMetadata>,
    val currentIndex: Int,
    val positionMillis: Long,
    val speed: Float,
    val repeatMode: PlaybackRepeatMode,
    val shuffleEnabled: Boolean,
    val paused: Boolean
) {
    init {
        require(queue.isNotEmpty()) { "A restorable queue must not be empty" }
        require(currentIndex in queue.indices) { "Current index must point to a queue item" }
        require(positionMillis >= 0) { "Restored position must not be negative" }
        require(speed in 0.01f..100f) { "Restored speed must be between 0.01 and 100" }
    }
}
