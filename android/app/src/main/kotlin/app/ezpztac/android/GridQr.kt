package app.ezpztac.android

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * A shared grid's QR code (`docs/GRID_SHARING.md`, option 2), as the web draws its own with `qrcode`: the whole text, not only the link, so a
 * phone with no signal still reads the grid; level M; a quiet zone of two modules; UTF-8, so a label in any script reads back as it was.
 */
internal object GridQr {
    private val HINTS = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.MARGIN to 2,
    )

    /** The code's modules, one cell each, quiet zone included; null for text a QR code cannot hold. */
    fun matrix(text: String): BitMatrix? = try {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, HINTS)
    } catch (_: WriterException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** The code as a bitmap of one pixel a module, black on white whatever the theme (a camera reads it best so), to be drawn scaled up sharp. */
    fun image(text: String): ImageBitmap? {
        val modules = matrix(text) ?: return null
        val pixels = IntArray(modules.width * modules.height) { i ->
            if (modules[i % modules.width, i / modules.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        return Bitmap.createBitmap(pixels, modules.width, modules.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}
