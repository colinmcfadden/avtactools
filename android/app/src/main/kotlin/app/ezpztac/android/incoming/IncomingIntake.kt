package app.ezpztac.android.incoming

import android.content.Context
import android.content.Intent
import app.ezpztac.data.IncomingFiles
import app.ezpztac.workspace.IncomingViewModel
import app.ezpztac.workspace.PickedFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Reads what another app handed to this one into [IncomingFiles], where it waits for the person to say what to do with it. **Nothing is imported
 * here.** The files are read at once, while the sender's permission to read them is still in force (it ends with the task that was given it), and
 * bounded: a file from anywhere may be anything, and a read that cannot finish must not end the app.
 */
class IncomingIntake @Inject constructor(
    @ApplicationContext private val context: Context,
    private val incoming: IncomingFiles,
) {
    /** The most one file may be; a test lowers it, so the bound is tried without a file that size. */
    internal var maxBytes: Long = PickedFile.MAX_BYTES

    /** The address this app's own files are shared under: never taken back in. */
    private val ownAuthority = "${context.packageName}.exports"

    /** Whether [intent] was asking to open files. */
    internal fun carriesFiles(intent: Intent?): Boolean = !IncomingIntents.find(intent, ownAuthority).isEmpty

    /** Reads what [intent] carries, off the main thread. Every failure is a file the person is told about, in the app's words. */
    suspend fun accept(intent: Intent?) {
        val found = IncomingIntents.find(intent, ownAuthority)
        if (found.isEmpty) return
        val resolver = context.contentResolver
        for (uri in found.uris) {
            val read = try {
                withContext(Dispatchers.IO) { PickedFile.read(resolver, uri, maxBytes = maxBytes, tooBig = TOO_BIG) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                PickedFile.Result.Failed(PickedFile.UNREADABLE)
            }
            when (read) {
                is PickedFile.Result.Read -> if (!incoming.offer(read.name, read.bytes)) incoming.refuse(read.name, FULL)
                is PickedFile.Result.Failed -> incoming.refuse(IncomingIntents.label(uri), read.message)
            }
        }
        for (name in found.refused) incoming.refuse(name, NOT_SHARED)
        if (found.leftOut > 0) {
            incoming.refuse("${found.leftOut} more", "Only the first ${IncomingIntents.MAX_FILES} files are taken at a time. Share the rest again once these are dealt with.")
        }
    }

    companion object {
        const val TOO_BIG = IncomingViewModel.TOO_BIG
        const val FULL = IncomingViewModel.FULL
        const val NOT_SHARED = "EZ/PZ only opens files another app shares with it. Open it from Files or a mail, or share it to EZ/PZ."
    }
}
