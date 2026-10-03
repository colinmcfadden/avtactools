package app.ezpztac.data

import app.ezpztac.formats.FormatException
import app.ezpztac.formats.ThsReader
import app.ezpztac.model.Threat
import app.ezpztac.model.ThreatEntry
import java.time.Instant
import javax.inject.Inject

/**
 * Threats in and out of AMPS `.ths` files, the only way they leave the device (`docs/NATIVE_APPS_PLAN.md`, "Threats on the device": never except by an explicit
 * export). Reading is [ThsReader], which takes a file it does not trust; writing is [ThsWriter].
 */
public class ThreatTransfer @Inject constructor(private val writer: ThsWriter) {
    /** What reading a `.ths` came to. */
    public sealed interface Imported {
        public data class Threats(val threats: List<Threat>) : Imported

        /** The file is not one, or holds nothing usable; [message] says so in the web's words. */
        public data class Refused(val message: String) : Imported
    }

    public fun import(bytes: ByteArray): Imported {
        val threats = try {
            ThsReader.read(bytes)
        } catch (e: FormatException) {
            return Imported.Refused(e.message ?: "This doesn't look like an AMPS .ths threat file.")
        }
        if (threats.size > MAX_IMPORT) return Imported.Refused("This file holds ${withCommas(threats.size)} threats; the most it can import is ${withCommas(MAX_IMPORT)}.")
        return Imported.Threats(threats)
    }

    /**
     * The `.ths` for [entries], hidden ones too (what is on the map is the person's view; the file is the picture), named [baseName].ths, which is what an AMPS
     * mission export is named for, so the two travel together.
     */
    public fun export(entries: List<ThreatEntry>, baseName: String? = null, now: Instant = Instant.now()): ExportResult {
        if (entries.isEmpty()) return ExportResult.Refused("There are no threats to export.")
        val bytes = writer.build(entries.map { it.threat }, now)
        return ExportResult.Ready(fileName(baseName), bytes, warning = null)
    }

    public companion object {
        /** More than this in one file is not a threat picture, and would be held in memory and in the sealed file. */
        public const val MAX_IMPORT: Int = 1_000

        /** `NAME.ths`, with the `.msnx` or `.ths` the name came with taken off, as the web does (`threats` with no name). */
        public fun fileName(baseName: String?): String =
            ExportNames.stem((baseName ?: "").replace(Regex("""\.(msnx|ths)$""", RegexOption.IGNORE_CASE), ""), "threats") + ".ths"

        private fun withCommas(n: Int): String = n.toString().reversed().chunked(3).joinToString(",").reversed()
    }
}
