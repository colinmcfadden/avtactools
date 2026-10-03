package app.ezpztac.data

import app.ezpztac.formats.FileKind
import app.ezpztac.formats.FileKinds
import app.ezpztac.formats.FormatException
import app.ezpztac.formats.LpsReader
import app.ezpztac.model.Threat
import javax.inject.Inject

/** What a file somebody sent is, and what opening it would do: the answer a screen puts to the person before anything is imported. */
public sealed interface FilePreview {
    /** A threat file: [threats] would be added to the picture. */
    public class Threats(public val threats: List<Threat>) : FilePreview

    /** A local-points file: [count] points would be saved as a set called [setName]. */
    public data class Points(val setName: String, val count: Int) : FilePreview

    /** An AMPS mission. */
    public data object Mission : FilePreview

    /** Not something that can be opened; [message] says why, in words for the person. */
    public data class Problem(val message: String) : FilePreview
}

/**
 * Looks inside a file that came from another app, without importing it. The file is whoever's it is, so each reader is the one that takes a file it does not
 * trust and every refusal is its own words; nothing from inside a failure is shown.
 */
public class FileInspector @Inject constructor(private val threats: ThreatTransfer) {
    public fun inspect(name: String, bytes: ByteArray): FilePreview = when (FileKinds.detect(name, bytes)) {
        FileKind.THREATS -> when (val read = threats.import(bytes)) {
            is ThreatTransfer.Imported.Refused -> FilePreview.Problem(read.message)
            is ThreatTransfer.Imported.Threats ->
                if (read.threats.isEmpty()) FilePreview.Problem("That .ths file contains no threats.") else FilePreview.Threats(read.threats)
        }
        FileKind.LOCAL_POINTS -> try {
            val set = LpsReader.read(bytes, name)
            FilePreview.Points(set.name.trim().ifEmpty { DEFAULT_SET_NAME }, set.points.size)
        } catch (e: FormatException) {
            FilePreview.Problem(e.message ?: "This doesn't look like an .LPS local points file.")
        }
        FileKind.MISSION -> FilePreview.Mission
        FileKind.UNKNOWN -> FilePreview.Problem("EZ/PZ opens AMPS local points (.LPS), threat (.ths) and mission (.msnx) files. This is none of them.")
    }

    private companion object {
        const val DEFAULT_SET_NAME = "LOCAL POINTS"
    }
}
