package app.ezpztac.android.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.ezpztac.data.MissionTemplate
import app.ezpztac.data.ThsTemplate
import app.ezpztac.workspace.ExportFile
import java.io.File

/** The bundled AMPS threat file an export is built on, read from the app's assets once. */
class AssetThsTemplate(private val context: Context) : ThsTemplate {
    private val bytes: ByteArray by lazy { context.assets.open(ASSET).use { it.readBytes() } }

    override fun bytes(): ByteArray = bytes

    private companion object {
        const val ASSET = "threat_template.ths"
    }
}

/** What clears exported files off the device: sign-out calls it, because a shared `.ths` holds a crew's threats in the clear. */
fun interface ExportCleaner {
    fun clear()
}

/** The bundled AMPS mission an export is built on, read from the app's assets once. */
class AssetMissionTemplate(private val context: Context) : MissionTemplate {
    private val bytes: ByteArray by lazy { context.assets.open(ASSET).use { it.readBytes() } }

    override fun bytes(): ByteArray = bytes

    private companion object {
        const val ASSET = "msnx_template.msnx"
    }
}

/**
 * Hands exported files to the system share sheet: a mission, and the threat file that travels with it. Each is written to one folder of the app's cache (`exports/`,
 * the only place the share provider may read from) and another app is given a temporary read grant on those URIs, nothing more. Nothing here is sent anywhere by the
 * app: the person chooses where it goes.
 *
 * **A threat file is not kept as long as a mission**: it holds a crew's threats in the clear (the app's own copy is sealed, and a file another app must read cannot be),
 * so it is cleared out after an hour rather than a day, and everything is cleared at sign-out ([clear]). A name is cut down to a plain file name so a crafted route name
 * cannot reach outside the folder.
 */
object ShareExport {
    private const val FOLDER = "exports"
    private const val KEEP_MS = 24L * 60 * 60 * 1000
    private const val KEEP_THREATS_MS = 60L * 60 * 1000

    /** Writes [file] and returns the share intent for it, to be started. [now] is the time, for clearing out old files. */
    fun prepare(context: Context, file: ExportFile, now: Long = System.currentTimeMillis()): Intent = prepare(context, listOf(file), now)

    /** Writes [files] (one is a plain send, several are sent together) and returns the share intent for them. */
    fun prepare(context: Context, files: List<ExportFile>, now: Long = System.currentTimeMillis()): Intent {
        require(files.isNotEmpty()) { "nothing to share" }
        val folder = File(context.cacheDir, FOLDER).apply { mkdirs() }
        folder.listFiles()?.filter { now - it.lastModified() > keepFor(it.name) }?.forEach { it.delete() }
        val uris = files.map { file ->
            val target = File(folder, plainName(file.fileName))
            target.writeBytes(file.bytes)
            FileProvider.getUriForFile(context, "${context.packageName}.exports", target)
        }
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris.single()) }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)) }
        }
        send.apply {
            type = MIME
            clipData = ClipData.newRawUri(files.first().fileName, uris.first()).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Export for AMPS").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Writes the files and opens the share sheet. */
    fun share(context: Context, files: List<ExportFile>) {
        val chooser = prepare(context, files)
        if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    /** Writes the file and opens the share sheet. */
    fun share(context: Context, file: ExportFile) = share(context, listOf(file))

    /** Removes everything exported, now: at sign-out, so what one person shared is not left on the device for the next. */
    fun clear(context: Context) {
        File(context.cacheDir, FOLDER).listFiles()?.forEach { it.delete() }
    }

    private fun keepFor(name: String) = if (name.endsWith(".ths", ignoreCase = true)) KEEP_THREATS_MS else KEEP_MS

    /** A file name with no folder in it, preserving a threat `.ths`; every other export is an AMPS mission `.msnx`. */
    internal fun plainName(name: String): String {
        val last = name.substringAfterLast('/').substringAfterLast('\\').trim().trim('.')
        val threat = last.endsWith(".ths", ignoreCase = true)
        val extension = if (threat) ".ths" else ".msnx"
        val stem = when {
            threat -> last.dropLast(4)
            last.endsWith(".msnx", ignoreCase = true) -> last.dropLast(5)
            else -> last
        }.trim().ifEmpty {
            if (threat) "threats" else "ROUTES"
        }
        return "$stem$extension"
    }

    private const val MIME = "application/octet-stream"
}
