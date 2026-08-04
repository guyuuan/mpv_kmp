package com.guyuuan.kmp.mpv.loader.coil

import coil3.Canvas
import coil3.Image
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.request.transformations
import coil3.size.Size
import com.guyuuan.kmp.mpv.service.AbstractPlaybackArtworkLoader
import com.guyuuan.kmp.mpv.service.PlaybackArtwork
import com.guyuuan.kmp.mpv.service.PlaybackArtworkLoader
import com.guyuuan.kmp.mpv.service.PlaybackArtworkLoaderFactory
import okio.BufferedSource

/**
 * Resolves playback artwork with a Coil [ImageLoader].
 *
 * The supplied image loader is shared by default and is not shut down when this loader closes.
 * Set [shutdownImageLoaderOnClose] only when the image loader is owned by the same coordinator
 * lifecycle. [artworkSize] is forwarded to Coil's request size resolver. Responses larger than
 * [maxArtworkBytes] are rejected before they are fully buffered.
 */
class CoilPlaybackArtworkLoader(
    private val context: PlatformContext,
    private val imageLoader: ImageLoader,
    private val configureRequest: ImageRequest.Builder.() -> Unit = {},
    private val shutdownImageLoaderOnClose: Boolean = false,
    private val maxArtworkBytes: Long = DEFAULT_MAX_ARTWORK_BYTES,
    private val artworkSize: Size? = null
) : AbstractPlaybackArtworkLoader() {

    init {
        require(maxArtworkBytes in 1..MAX_SUPPORTED_ARTWORK_BYTES) {
            "Maximum artwork size must be between 1 and $MAX_SUPPORTED_ARTWORK_BYTES bytes"
        }
    }

    override suspend fun loadBytes(artwork: PlaybackArtwork.Uri): ByteArray? {
        val request = ImageRequest.Builder(context)
            .apply(configureRequest)
            .data(artwork.value)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .transformations(emptyList())
            .target(null)
            .apply {
                artworkSize?.let { requestedSize -> size(requestedSize) }
            }
            .decoderFactory(EncodedArtworkDecoderFactory(maxArtworkBytes))
            .build()
        val result = imageLoader.execute(request) as? SuccessResult ?: return null

        return (result.image as? EncodedArtworkImage)?.toByteArray()
    }

    override fun onClosed() {
        if (shutdownImageLoaderOnClose) {
            imageLoader.shutdown()
        }
    }

    companion object {
        const val DEFAULT_MAX_ARTWORK_BYTES: Long = 10L * 1024L * 1024L
    }
}

/**
 * Creates the one Coil-backed artwork loader owned by each playback coordinator.
 *
 * Passing an [ImageLoader] shares its cache and lifecycle with the application. Passing an
 * image-loader factory creates an image loader that is shut down together with the produced
 * artwork loader.
 */
class CoilPlaybackArtworkLoaderFactory private constructor(
    private val context: PlatformContext,
    private val imageLoaderFactory: () -> ImageLoader,
    private val configureRequest: ImageRequest.Builder.() -> Unit,
    private val shutdownImageLoaderOnClose: Boolean,
    private val maxArtworkBytes: Long,
    private val artworkSize: Size?
) : PlaybackArtworkLoaderFactory {

    init {
        require(maxArtworkBytes in 1..MAX_SUPPORTED_ARTWORK_BYTES) {
            "Maximum artwork size must be between 1 and $MAX_SUPPORTED_ARTWORK_BYTES bytes"
        }
    }

    constructor(
        context: PlatformContext,
        imageLoader: ImageLoader = SingletonImageLoader.get(context),
        maxArtworkBytes: Long = CoilPlaybackArtworkLoader.DEFAULT_MAX_ARTWORK_BYTES,
        artworkSize: Size? = null,
        configureRequest: ImageRequest.Builder.() -> Unit = {}
    ) : this(
        context = context,
        imageLoaderFactory = { imageLoader },
        configureRequest = configureRequest,
        shutdownImageLoaderOnClose = false,
        maxArtworkBytes = maxArtworkBytes,
        artworkSize = artworkSize
    )

    constructor(
        context: PlatformContext,
        imageLoaderFactory: () -> ImageLoader,
        maxArtworkBytes: Long = CoilPlaybackArtworkLoader.DEFAULT_MAX_ARTWORK_BYTES,
        artworkSize: Size? = null,
        configureRequest: ImageRequest.Builder.() -> Unit = {}
    ) : this(
        context = context,
        imageLoaderFactory = imageLoaderFactory,
        configureRequest = configureRequest,
        shutdownImageLoaderOnClose = true,
        maxArtworkBytes = maxArtworkBytes,
        artworkSize = artworkSize
    )

    override fun create(): PlaybackArtworkLoader = CoilPlaybackArtworkLoader(
        context = context,
        imageLoader = imageLoaderFactory(),
        configureRequest = configureRequest,
        shutdownImageLoaderOnClose = shutdownImageLoaderOnClose,
        maxArtworkBytes = maxArtworkBytes,
        artworkSize = artworkSize
    )
}

private class EncodedArtworkDecoderFactory(
    private val maxArtworkBytes: Long
) : Decoder.Factory {
    override fun create(
        result: SourceFetchResult,
        options: Options,
        imageLoader: ImageLoader
    ): Decoder = Decoder {
        val bytes = result.source.source().readByteArrayWithinLimit(maxArtworkBytes)
            ?: return@Decoder null
        if (bytes.isEmpty()) {
            null
        } else {
            DecodeResult(
                image = EncodedArtworkImage(bytes),
                isSampled = false
            )
        }
    }
}

private class EncodedArtworkImage(bytes: ByteArray) : Image {
    private val content = bytes.copyOf()

    override val size: Long = content.size.toLong()
    override val width: Int = -1
    override val height: Int = -1

    // This image only transports encoded bytes out of Coil's pipeline and must not be cached or
    // rendered by an image target.
    override val shareable: Boolean = false

    override fun draw(canvas: Canvas) = Unit

    fun toByteArray(): ByteArray = content.copyOf()
}

internal fun BufferedSource.readByteArrayWithinLimit(maxBytes: Long): ByteArray? {
    require(maxBytes in 1..MAX_SUPPORTED_ARTWORK_BYTES)
    if (request(maxBytes + 1L)) return null
    return readByteArray()
}

private const val MAX_SUPPORTED_ARTWORK_BYTES = 2_147_483_647L
