package com.guyuuan.mpv_kmp.loader.coil

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.request.ImageRequest
import coil3.size.Size
import com.guyuuan.mpv_kmp.service.PlaybackArtwork
import com.guyuuan.mpv_kmp.service.PlaybackMetadata
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Buffer
import okio.Path.Companion.toOkioPath

class CoilPlaybackArtworkLoaderTest {

    @Test
    fun loadsNetworkArtworkAndReturnsOriginalEncodedBytes() = runBlocking {
        val requestedSize = Size(512, 512)
        val loaded = loadNetworkArtwork(artworkSize = requestedSize)

        assertContentEquals(Base64.getDecoder().decode(PNG_BASE64), loaded.bytes)
        assertEquals(requestedSize, loaded.request.sizeResolver.size())
    }

    @Test
    fun preservesSizeConfiguredOnRequestWhenArtworkSizeIsNotSpecified() = runBlocking {
        val requestedSize = Size(320, 180)

        val loaded = loadNetworkArtwork(
            configureRequest = { size(requestedSize) }
        )

        assertEquals(requestedSize, loaded.request.sizeResolver.size())
    }

    @Test
    fun factoryOnlyShutsDownOwnedImageLoaders() {
        val shared = trackingImageLoader()
        val sharedLoader = CoilPlaybackArtworkLoaderFactory(
            context = PlatformContext.INSTANCE,
            imageLoader = shared
        ).create()

        sharedLoader.close()

        assertEquals(0, shared.shutdownCount)
        shared.shutdown()

        val owned = trackingImageLoader()
        val ownedLoader = CoilPlaybackArtworkLoaderFactory(
            context = PlatformContext.INSTANCE,
            imageLoaderFactory = { owned }
        ).create()

        ownedLoader.close()
        ownedLoader.close()

        assertEquals(1, owned.shutdownCount)
    }

    @Test
    fun boundedReaderAcceptsOnlyResponsesWithinConfiguredLimit() {
        val accepted = byteArrayOf(1, 2, 3, 4)

        assertContentEquals(
            accepted,
            Buffer().write(accepted).readByteArrayWithinLimit(accepted.size.toLong())
        )
        assertNull(
            Buffer().write(accepted).readByteArrayWithinLimit(accepted.size - 1L)
        )
    }

    private fun trackingImageLoader(): TrackingImageLoader {
        val delegate = ImageLoader.Builder(PlatformContext.INSTANCE)
            .memoryCache(null)
            .diskCache(null)
            .build()
        return TrackingImageLoader(delegate)
    }

    private suspend fun loadNetworkArtwork(
        artworkSize: Size? = null,
        configureRequest: ImageRequest.Builder.() -> Unit = {}
    ): LoadedNetworkArtwork {
        val artworkBytes = Base64.getDecoder().decode(PNG_BASE64)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/cover.png") { exchange ->
                exchange.responseHeaders.add("Content-Type", "image/png")
                exchange.sendResponseHeaders(200, artworkBytes.size.toLong())
                exchange.responseBody.use { it.write(artworkBytes) }
            }
            start()
        }
        val cacheDirectory = Files.createTempDirectory("mpv-service-coil-test")
        val diskCache = DiskCache.Builder()
            .directory(cacheDirectory.toOkioPath())
            .build()
        val imageLoader = ImageLoader.Builder(PlatformContext.INSTANCE)
            .diskCache(diskCache)
            .build()
        var startedRequest: ImageRequest? = null
        val loader = CoilPlaybackArtworkLoader(
            context = PlatformContext.INSTANCE,
            imageLoader = imageLoader,
            artworkSize = artworkSize,
            configureRequest = {
                configureRequest()
                listener(onStart = { request -> startedRequest = request })
            }
        )
        val artworkUri = "http://127.0.0.1:${server.address.port}/cover.png"

        return try {
            val loaded = CompletableDeferred<PlaybackMetadata>()
            loader.load(
                metadata = PlaybackMetadata(
                    mediaId = "network-artwork",
                    uri = "file:///media.mp3",
                    title = "Network artwork",
                    artwork = PlaybackArtwork.Uri(artworkUri)
                ),
                onLoaded = loaded::complete
            )

            val resolved = withTimeout(10_000) { loaded.await() }
            LoadedNetworkArtwork(
                bytes = (resolved.artwork as PlaybackArtwork.Bytes).toByteArray(),
                request = checkNotNull(startedRequest)
            )
        } finally {
            loader.close()
            imageLoader.shutdown()
            server.stop(0)
            cacheDirectory.toFile().deleteRecursively()
        }
    }

    private class TrackingImageLoader(
        private val delegate: ImageLoader
    ) : ImageLoader by delegate {
        var shutdownCount = 0
            private set

        override fun shutdown() {
            shutdownCount += 1
            delegate.shutdown()
        }
    }

    private data class LoadedNetworkArtwork(
        val bytes: ByteArray,
        val request: ImageRequest
    )

    private companion object {
        const val PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    }
}
