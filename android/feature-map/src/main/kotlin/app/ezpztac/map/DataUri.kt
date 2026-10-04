package app.ezpztac.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64

/** The picture in a `data:image/png;base64,` URI the server sent, or null when it is not one or will not decode. */
internal fun decodeDataUri(dataUri: String): Bitmap? {
    val payload = dataUri.substringAfter("base64,", missingDelimiterValue = "")
    if (payload.isEmpty()) return null
    return runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
}
