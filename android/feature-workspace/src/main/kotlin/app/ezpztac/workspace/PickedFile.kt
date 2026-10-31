package app.ezpztac.workspace

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream

/**
 * A file the person chose in the system's picker, read for importing. The picker hands over an address, not a file: the name is asked for, and the
 * bytes are read here, bounded, because a file from anywhere may be anything.
 */
object PickedFile {
    /** A set of local points is some tens of kilobytes to a few megabytes; anything past this is not one, and is not read into memory. */
    const val MAX_BYTES: Long = 32L * 1024 * 1024

    sealed interface Result {
        class Read(val name: String, val bytes: ByteArray) : Result

        /** [message] is in words for the person. */
        data class Failed(val message: String) : Result
    }

    const val UNREADABLE = "That file could not be read."
    const val TOO_BIG = "That file is too large to be a set of local points."

    fun read(resolver: ContentResolver, uri: Uri, maxBytes: Long = MAX_BYTES, tooBig: String = TOO_BIG): Result {
        val name = nameOf(resolver, uri)
        val bytes = try {
            resolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > maxBytes) return Result.Failed(tooBig)
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        } catch (_: Exception) {
            // Whatever is behind the address, reading a chosen file must not end the app: the grant for it may have been withdrawn since it was chosen, the file
            // may be gone, and a provider is someone else's code (it can throw anything). The person is told in words; nothing from inside is shown.
            return Result.Failed(UNREADABLE)
        } ?: return Result.Failed(UNREADABLE)
        return Result.Read(name, bytes)
    }

    /** The name the file is shown as in the picker, which names the set; the last part of the address when there is none, else a plain one. */
    private fun nameOf(resolver: ContentResolver, uri: Uri): String {
        val shown = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {                                  // a provider that will not say: the name is not worth failing the import for
            null
        }
        return shown?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: DEFAULT_NAME
    }

    private const val BUFFER = 16 * 1024
    private const val DEFAULT_NAME = "points.lps"
}
