package app.ezpztac.android.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.ezpztac.data.MissionTemplate
import app.ezpztac.data.ThsTemplate
import app.ezpztac.workspace.ExportFile
import java.io.File

/** The bundled AMPS mission an export is built on, read from the app's assets once. */
class AssetMissionTemplate(private val context: Context) : MissionTemplate {
    private val bytes: ByteArray by lazy { context.assets.open(ASSET).use { it.readBytes() } }

    override fun bytes(): ByteArray = bytes

    private companion object {
        const val ASSET = "msnx_template.msnx"
    }
}

/** The backend's cleaned AMPS threat database, bundled once and copied before each export. */
class AssetThsTemplate(private val context: Context) : ThsTemplate {
    private val bytes: ByteArray by lazy { context.assets.open(ASSET).use { it.readBytes() } }

    override fun bytes(): ByteArray = bytes

    private companion object {
        const val ASSET = "threat_template.ths"
    }
}

/**
 * Hands an exported mission to the system share sheet. The file is written to one folder of the app's cache (`exports/`, the only place the share provider may read from) and
 * another app is given a temporary read grant on that one file's URI, nothing more. Nothing here is sent anywhere by the app: the person chooses where it goes.
 *
 * Files left in the cache are removed after a day, and a name is cut down to a plain file name so a crafted route name cannot reach outside the folder.
 */
object ShareExport {
    private const val FOLDER = "exports"
    private const val KEEP_MS = 24L * 60 * 60 * 1000

    /** Writes [file] and returns the share intent for it, to be started. [now] is the time, for clearing out old files. */
    fun prepare(context: Context, file: ExportFile, now: Long = System.currentTimeMillis()): Intent {
        val folder = File(context.cacheDir, FOLDER).apply { mkdirs() }
        folder.listFiles()?.filter { now - it.lastModified() > KEEP_MS }?.forEach { it.delete() }
        val target = File(folder, plainName(file.fileName))
        target.writeBytes(file.bytes)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", target)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(target.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Export for AMPS").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Writes the file and opens the share sheet. */
    fun share(context: Context, file: ExportFile) {
        val chooser = prepare(context, file)
        if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

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
