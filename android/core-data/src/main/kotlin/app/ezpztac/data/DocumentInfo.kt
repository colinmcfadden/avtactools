package app.ezpztac.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * What a document's JSON says of it (docs/DOCUMENTS.md §2), a Library record's and a pack item's alike: its PDF and page text by their SHA-256, the PDF's size
 * and pages, and what was uploaded. The server writes it, but a pack item is one any member's client could have touched, so it is read loosely and a hash that
 * is not 64 lower-case hex digits is no file (it names a file on this device: `DocumentFiles`).
 */
public data class DocumentInfo(
    val pdf: String,
    val pdfBytes: Long,
    val pages: Int,
    /** The page text's hash, or null for a scan (nothing to search). */
    val text: String?,
    /** What was uploaded: `pdf`, `docx`, `xlsx`, `pptx`, `doc`, `xls`, `ppt`, or what a newer server adds. */
    val sourceType: String?,
    val sourceName: String?,
) {
    /** `14 pages · from Word`, `1 page · PDF`, or what of it is known (the web's `documentDetail`). */
    val detail: String get() = listOfNotNull(pageWords(pages), sourceWords(sourceType)).joinToString(" · ")

    public companion object {
        private val HASH = Regex("^[0-9a-f]{64}$")
        private val SOURCES = mapOf(
            "pdf" to "PDF", "doc" to "from Word", "docx" to "from Word", "xls" to "from Excel", "xlsx" to "from Excel",
            "ppt" to "from PowerPoint", "pptx" to "from PowerPoint",
        )

        /** A hash a file on this device may be named by. */
        public fun isHash(value: String?): Boolean = value != null && HASH.matches(value)

        /** The document [data] describes, or null when it names no PDF this device could keep. */
        public fun of(data: JsonElement?): DocumentInfo? {
            val doc = data as? JsonObject ?: return null
            val pdf = doc["pdf"] as? JsonObject ?: return null
            val pdfHash = text(pdf["sha256"])?.takeIf(::isHash) ?: return null
            val textHash = (doc["text"] as? JsonObject)?.let { text(it["sha256"]) }?.takeIf(::isHash)
            val source = doc["source"] as? JsonObject
            return DocumentInfo(
                pdf = pdfHash,
                pdfBytes = (pdf["bytes"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 } ?: 0,
                pages = (pdf["pages"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 } ?: 0,
                text = textHash,
                sourceType = text(source?.get("type")),
                sourceName = text(source?.get("name")),
            )
        }

        /** `14 pages`, `1 page`, `1,203 pages`; nothing for a count that is not one. */
        public fun pageWords(pages: Int): String? = if (pages > 0) "${withCommas(pages)} ${if (pages == 1) "page" else "pages"}" else null

        /** What a document was made from, in the web's words; nothing for a type this version does not know. */
        public fun sourceWords(type: String?): String? = SOURCES[type]

        private fun text(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        private fun withCommas(n: Int): String = n.toString().reversed().chunked(3).joinToString(",").reversed()
    }
}
