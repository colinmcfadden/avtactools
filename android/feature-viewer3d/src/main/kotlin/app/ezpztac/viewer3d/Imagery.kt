package app.ezpztac.viewer3d

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.ezpztac.scene3d.ImageryTileId
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The satellite imagery's tiles: one tile's bytes (a JPEG), or an exception saying whether to try again ([ImageryRefused] says not to). */
fun interface ImageryTiles {
    suspend fun tile(id: ImageryTileId): ByteArray
}

/** A tile that asking again will not bring: there is no token, the token was refused, or there is no such tile. */
class ImageryRefused(message: String) : Exception(message)

/**
 * Mapbox's satellite imagery, as the web's 3D view lays it on (`MAPBOX_IMAGERY`: `satellite-v9`, 512-point tiles at `@2x`, 1024 pixels), with
 * the 2D map's public token ([token]: `/api/config`'s, kept on the device), over the app's HTTP client and a disk cache of its own, as long as
 * Mapbox says (the same tiles for everyone, rarely changed). The token goes in the URL, which is Mapbox's way; it is a public `pk.` one.
 * A 429 or a server error, or no connection, is worth another try; anything else Mapbox refuses is not. Each answer is read whole, and no
 * larger than [MAX_BYTES], on OkHttp's own thread, never the caller's (AGENTS.md §14).
 */
class MapboxImagery(private val http: OkHttpClient, private val base: String = MAPBOX, private val token: () -> String?) : ImageryTiles {
    override suspend fun tile(id: ImageryTileId): ByteArray {
        val key = token()?.takeIf { it.startsWith("pk.") } ?: throw ImageryRefused("No Mapbox token")
        val request = Request.Builder()
            .url("$base/styles/v1/mapbox/satellite-v9/tiles/512/${id.level}/${id.x}/${id.y}@2x?access_token=$key")
            .build()
        val call = http.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = runCatching { read(response) }
                        result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
                    }
                },
            )
        }
    }

    private fun read(response: Response): ByteArray = response.use {
        when {
            it.code == 429 || it.code >= 500 -> throw IOException("Mapbox answered ${it.code}")
            !it.isSuccessful -> throw ImageryRefused("Mapbox answered ${it.code}")
        }
        val body = it.body ?: throw IOException("No body")
        if (body.contentLength() > MAX_BYTES) throw ImageryRefused("A tile of ${body.contentLength()} bytes")
        val source = body.source()
        if (source.request(MAX_BYTES + 1)) throw ImageryRefused("A tile past $MAX_BYTES bytes")
        source.readByteArray()
    }

    companion object {
        const val MAPBOX = "https://api.mapbox.com"

        /** Mapbox's satellite tiles are a few hundred kilobytes. */
        const val MAX_BYTES: Long = 8L * 1024 * 1024
    }
}

/** An imagery tile decoded and waiting for the GPU: what it will take there, and a way to let go of it. */
interface ImageryImage {
    val bytesOnGpu: Long

    fun release()
}

/** Turns a tile's bytes into an [ImageryImage], or null for bytes that are no image. */
fun interface ImageryDecoder {
    fun decode(bytes: ByteArray): ImageryImage?
}

/** A decoded tile as a bitmap, two bytes a pixel. */
class BitmapImage(val bitmap: Bitmap) : ImageryImage {
    // Two bytes a pixel, and a third again for its mipmaps.
    override val bytesOnGpu: Long get() = bitmap.width.toLong() * bitmap.height * 2 * 4 / 3

    override fun release() {
        bitmap.recycle()
    }
}

/** BitmapFactory, as RGB 565: the imagery has no transparency, and half the bytes of ARGB do for a photograph. */
object BitmapDecoder : ImageryDecoder {
    override fun decode(bytes: ByteArray): ImageryImage? {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let(::BitmapImage)
    }
}
