@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package com.guyuuan.mpv_kmp.service

import kotlin.io.encoding.Base64

interface PlaybackStateStore {
    fun load(): RestorablePlaybackState?

    fun save(state: RestorablePlaybackState)

    fun clear()
}

/** Lightweight store useful for tests and applications that already persist their own state. */
class InMemoryPlaybackStateStore(
    initialState: RestorablePlaybackState? = null
) : PlaybackStateStore {
    private var state = initialState

    override fun load(): RestorablePlaybackState? = state

    override fun save(state: RestorablePlaybackState) {
        this.state = state
    }

    override fun clear() {
        state = null
    }
}

/** Stable text codec shared by the Android, Apple, and Desktop state stores. */
object PlaybackStateCodec {
    fun encode(state: RestorablePlaybackState): String = buildList {
        add(CODEC_MAGIC)
        add(CODEC_VERSION.toString())
        add(state.currentIndex.toString())
        add(state.positionMillis.toString())
        add(state.speed.toString())
        add(state.repeatMode.name)
        add(state.shuffleEnabled.toString())
        add(state.paused.toString())
        add(state.queue.size.toString())
        state.queue.forEach { metadata ->
            add(metadata.mediaId.encodeString())
            add(metadata.uri.encodeString())
            add(metadata.title.encodeString())
            add(metadata.artist.encodeNullableString())
            add(metadata.albumTitle.encodeNullableString())
            add(metadata.mediaType.name)
            when (val artwork = metadata.artwork) {
                null -> {
                    add("none")
                    add(NULL_VALUE)
                }
                is PlaybackArtwork.Uri -> {
                    add("uri")
                    add(artwork.value.encodeString())
                }
                is PlaybackArtwork.Bytes -> {
                    add("bytes")
                    add(Base64.encode(artwork.toByteArray()))
                }
            }
            add(metadata.extras.size.toString())
            metadata.extras.entries.sortedBy(Map.Entry<String, String>::key).forEach { (key, value) ->
                add(key.encodeString())
                add(value.encodeString())
            }
        }
    }.joinToString("\n")

    fun decode(value: String): RestorablePlaybackState? = runCatching {
        val reader = CodecLineReader(value.lines())
        require(reader.next() == CODEC_MAGIC)
        require(reader.next().toInt() == CODEC_VERSION)
        val currentIndex = reader.next().toInt()
        val positionMillis = reader.next().toLong()
        val speed = reader.next().toFloat()
        val repeatMode = PlaybackRepeatMode.valueOf(reader.next())
        val shuffleEnabled = reader.next().toBooleanStrict()
        val paused = reader.next().toBooleanStrict()
        val queueSize = reader.next().toInt()
        require(queueSize in 1..MAX_COLLECTION_SIZE)
        val queue = List(queueSize) {
            val mediaId = reader.next().decodeString()
            val uri = reader.next().decodeString()
            val title = reader.next().decodeString()
            val artist = reader.next().decodeNullableString()
            val albumTitle = reader.next().decodeNullableString()
            val mediaType = PlaybackMediaType.valueOf(reader.next())
            val artworkType = reader.next()
            val artworkValue = reader.next()
            val artwork = when (artworkType) {
                "none" -> null
                "uri" -> PlaybackArtwork.Uri(artworkValue.decodeString())
                "bytes" -> PlaybackArtwork.Bytes(Base64.decode(artworkValue))
                else -> error("Unknown artwork type $artworkType")
            }
            val extraCount = reader.next().toInt()
            require(extraCount in 0..MAX_COLLECTION_SIZE)
            val extras = buildMap {
                repeat(extraCount) {
                    put(reader.next().decodeString(), reader.next().decodeString())
                }
            }
            PlaybackMetadata(
                mediaId = mediaId,
                uri = uri,
                title = title,
                artist = artist,
                albumTitle = albumTitle,
                artwork = artwork,
                mediaType = mediaType,
                extras = extras
            )
        }
        require(!reader.hasRemainingNonEmptyLines())
        RestorablePlaybackState(
            queue = queue,
            currentIndex = currentIndex,
            positionMillis = positionMillis,
            speed = speed,
            repeatMode = repeatMode,
            shuffleEnabled = shuffleEnabled,
            paused = paused
        )
    }.getOrNull()
}

private class CodecLineReader(private val lines: List<String>) {
    private var index = 0

    fun next(): String = lines.getOrNull(index++) ?: error("Playback state is truncated")

    fun hasRemainingNonEmptyLines(): Boolean = lines.drop(index).any(String::isNotEmpty)
}

private fun String.encodeString(): String = Base64.encode(encodeToByteArray())

private fun String?.encodeNullableString(): String = this?.encodeString() ?: NULL_VALUE

private fun String.decodeString(): String = Base64.decode(this).decodeToString()

private fun String.decodeNullableString(): String? =
    if (this == NULL_VALUE) null else decodeString()

private const val CODEC_MAGIC = "mpv-kmp-playback-state"
private const val CODEC_VERSION = 1
private const val NULL_VALUE = "-"
private const val MAX_COLLECTION_SIZE = 10_000
