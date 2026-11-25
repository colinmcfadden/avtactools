package app.ezpztac.data

import app.ezpztac.formats.FileKind
import app.ezpztac.formats.FileKinds
import app.ezpztac.formats.FormatException
import app.ezpztac.formats.LpsReader
import app.ezpztac.formats.MsnxReader
import app.ezpztac.model.Mission
import app.ezpztac.model.RoutePoint
import app.ezpztac.model.Threat
import app.ezpztac.network.DOCUMENT_UPLOAD_MAX_BYTES
import javax.inject.Inject

/** What a file somebody sent is, and what opening it would do: the answer a screen puts to the person before anything is imported. */
public sealed interface FilePreview {
    /** A threat file: [threats] would be added to the picture. */
    public class Threats(public val threats: List<Threat>) : FilePreview

    /** A local-points file: [count] points would be saved as a set called [setName]. */
    public data class Points(val setName: String, val count: Int) : FilePreview

    /** An AMPS mission: its [routes] (with points to draw) and [namedPoints] would come in as a new set of routes. [aircraft] is the designation AMPS planned it for, if it says. */
    public class Mission(
        public val mission: app.ezpztac.model.Mission,
        public val routes: Int,
        public val namedPoints: Int,
        public val aircraft: String?,
    ) : FilePreview

    /**
     * A document (docs/DOCUMENTS.md §9.1): uploaded as it is, and kept as a PDF. [type] is what its content says it is (`pdf`, `docx`, `xlsx`, `pptx`, `doc`,
     * `xls`, `ppt`, or `office` for a legacy file its name does not settle); [name] what it would be called, its file's name less its extension. Nothing of it is
     * read here but enough to say what it is: the server checks it.
     */
    public data class Document(val type: String, val name: String) : FilePreview

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
        FileKind.MISSION -> try {
            missionPreview(MsnxReader.read(bytes))
        } catch (e: FormatException) {
            FilePreview.Problem(e.message ?: "This doesn't look like an AMPS mission (.msnx) file.")
        }
        FileKind.DOCUMENT ->
            if (bytes.size > DOCUMENT_UPLOAD_MAX_BYTES) {
                FilePreview.Problem(DocumentRepository.TOO_LARGE)
            } else {
                FilePreview.Document(FileKinds.documentType(name, bytes) ?: "pdf", documentName(name))
            }
        FileKind.UNKNOWN -> FilePreview.Problem(
            "EZ/PZ opens AMPS local points (.LPS), threat (.ths) and mission (.msnx) files, and PDF, Word, Excel and PowerPoint documents. This is none of them.",
        )
    }

    /** What bringing [mission] in would do: only a route with a point has anything to draw, so only those count. */
    internal fun missionPreview(mission: Mission): FilePreview {
        val withPoints = mission.routes.filter { it.points.isNotEmpty() }
        if (withPoints.isEmpty()) return FilePreview.Problem("This mission has no routes with points to bring in.")
        return FilePreview.Mission(
            mission, routes = withPoints.size,
            namedPoints = withPoints.sumOf { r -> r.points.count { it.kind == RoutePoint.KIND_AMPS } },
            aircraft = mission.aircraft?.designation,
        )
    }

    internal companion object {
        const val DEFAULT_SET_NAME = "LOCAL POINTS"

        /** A document's name from its file's (the web's and the server's rule): the name less its extension, at most 100 characters, else `DOCUMENT`. */
        fun documentName(fileName: String): String {
            val base = fileName.substringAfterLast('/').substringAfterLast('\\')
            val at = base.lastIndexOf('.')
            val name = (if (at > 0) base.substring(0, at) else base).trim()
            return (if (name.codePointCount(0, name.length) > 100) name.substring(0, name.offsetByCodePoints(0, 100)) else name).ifEmpty { "DOCUMENT" }
        }
    }
}
