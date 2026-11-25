package app.ezpztac.formats

import app.ezpztac.testing.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** What a file sent to the app is: by its content first, by its name only when the content does not say. */
class FileKindTest {
    private val threats = Fixtures.bytes("sqlite/threats.ths")
    private val points = Fixtures.bytes("sqlite/local-points.lps")
    private val mission = Fixtures.bytes("msnx/sketch-export.msnx")
    private val noTables = Fixtures.bytes("sqlite/values.db")

    @Test
    fun `a threat file, a points file and a mission are told by what is in them`() {
        assertEquals(FileKind.THREATS, FileKinds.detect("anything", threats))
        assertEquals(FileKind.LOCAL_POINTS, FileKinds.detect("anything", points))
        assertEquals(FileKind.MISSION, FileKinds.detect("anything", mission))
    }

    @Test
    fun `the content outranks a name that says otherwise`() {
        assertEquals(FileKind.THREATS, FileKinds.detect("points.lps", threats))
        assertEquals(FileKind.LOCAL_POINTS, FileKinds.detect("threats.ths", points))
        assertEquals(FileKind.MISSION, FileKinds.detect("threats.ths", mission))
    }

    @Test
    fun `an empty threat file is still a threat file`() {
        assertEquals(FileKind.THREATS, FileKinds.detect(null, Fixtures.bytes("sqlite/threats-empty.ths")))
    }

    @Test
    fun `a database with neither table takes the kind its name claims, so the reader can refuse it in words`() {
        assertEquals(FileKind.THREATS, FileKinds.detect("mine.THS", noTables))
        assertEquals(FileKind.LOCAL_POINTS, FileKinds.detect("mine.LPS", noTables))
        assertEquals(FileKind.UNKNOWN, FileKinds.detect("mine.db", noTables))
        assertEquals(FileKind.UNKNOWN, FileKinds.detect(null, noTables))
    }

    @Test
    fun `a database with both tables goes by its name, and is a threat file when the name does not say`() {
        val both = MiniSqlite.build(
            MiniSqlite.Table("THREATS", "CREATE TABLE THREATS (ID INTEGER PRIMARY KEY)", rows = emptyList()),
            MiniSqlite.Table("Points", "CREATE TABLE Points (ID TEXT)", rows = emptyList()),
        )
        assertEquals(FileKind.LOCAL_POINTS, FileKinds.detect("x.lps", both))
        assertEquals(FileKind.THREATS, FileKinds.detect("x.ths", both))
        assertEquals(FileKind.THREATS, FileKinds.detect("x", both))
        assertEquals(FileKind.THREATS, FileKinds.detect("x.msnx", both))                 // a name that names neither table does not pick one
    }

    @Test
    fun `table names are matched without regard to case`() {
        val lower = MiniSqlite.build(MiniSqlite.Table("points", "CREATE TABLE points (ID TEXT)", rows = emptyList()))
        assertEquals(FileKind.LOCAL_POINTS, FileKinds.detect(null, lower))
    }

    @Test
    fun `a file that is not a database or a zip is told by its name alone`() {
        val text = "hello".toByteArray()
        assertEquals(FileKind.THREATS, FileKinds.detect("a.ths", text))
        assertEquals(FileKind.LOCAL_POINTS, FileKinds.detect("a.lps", text))
        assertEquals(FileKind.MISSION, FileKinds.detect("a.msnx", text))
        assertEquals(FileKind.UNKNOWN, FileKinds.detect("a.txt", text))
        assertEquals(FileKind.UNKNOWN, FileKinds.detect(null, ByteArray(0)))
    }

    @Test
    fun `a file cut short, or damaged inside, is never an exception`() {
        for (cut in listOf(0, 1, 15, 16, 99, 100, 512, threats.size / 2)) {
            val kind = FileKinds.detect("x.ths", threats.copyOf(cut))
            assertEquals(FileKind.THREATS, kind, "cut at $cut")                         // the name is all that is left to go by
        }
        val scrambled = threats.copyOf().also { for (i in 100 until it.size step 7) it[i] = 0x7F }
        FileKinds.detect(null, scrambled)                                               // whatever it says, it says it and does not throw
    }

    @Test
    fun `a zip header is a mission even with no name`() {
        assertEquals(FileKind.MISSION, FileKinds.detect(null, byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0)))
        assertEquals(FileKind.UNKNOWN, FileKinds.detect(null, byteArrayOf(0x50, 0x4B, 0x05, 0x06)))   // an empty zip's end record is not a mission
    }

    // -- Documents (docs/DOCUMENTS.md §9.1), told as the web's detectImport.js tells them --------------------------------------

    /** A real zip of [names], each holding a few bytes; [stored] writes them uncompressed, so a name's text is in the file as it is. */
    private fun zipOf(vararg names: String, stored: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for (name in names) {
                val body = "<x/>".toByteArray()
                val entry = ZipEntry(name)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = body.size.toLong()
                    entry.crc = CRC32().apply { update(body) }.value
                }
                zip.putNextEntry(entry)
                zip.write(body)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val docx = zipOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml")
    private val xlsx = zipOf("[Content_Types].xml", "xl/workbook.xml", "xl/worksheets/sheet1.xml")
    private val pptx = zipOf("[Content_Types].xml", "ppt/presentation.xml", "ppt/slides/slide1.xml")
    private val ole = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()) + ByteArray(504)

    private fun pdfAt(offset: Int): ByteArray = ByteArray(offset) { 0x20 } + "%PDF-1.7\n%\u00e2\u00e3\n1 0 obj".toByteArray(Charsets.ISO_8859_1)

    @Test
    fun `a Word, Excel or PowerPoint zip is a document, asked for its parts before it is taken for a mission`() {
        assertEquals("docx", FileKinds.documentType("anything", docx))
        assertEquals("xlsx", FileKinds.documentType(null, xlsx))
        assertEquals("pptx", FileKinds.documentType("a.msnx", pptx))                                   // the parts outrank the name
        for (office in listOf(docx, xlsx, pptx)) assertEquals(FileKind.DOCUMENT, FileKinds.detect("x.msnx", office))
    }

    @Test
    fun `a zip without Office's parts is a mission, whatever it is called`() {
        assertNull(FileKinds.documentType("mission.msnx", mission))
        assertEquals(FileKind.MISSION, FileKinds.detect("report.docx", mission))
        // Both parts are wanted: the content types alone, or Word's part alone, are no Office file.
        assertEquals(FileKind.MISSION, FileKinds.detect("a.docx", zipOf("word/document.xml", "word/styles.xml")))
        assertEquals(FileKind.MISSION, FileKinds.detect("a.docx", zipOf("[Content_Types].xml", "docProps/app.xml")))
        // A name is matched whole and as it is written: a part in a folder, or in another case, is not Office's.
        assertEquals(FileKind.MISSION, FileKinds.detect(null, zipOf("x/[Content_Types].xml", "word/document.xml")))
        assertEquals(FileKind.MISSION, FileKinds.detect(null, zipOf("[content_types].xml", "word/document.xml")))
    }

    @Test
    fun `a zip with a PDF's mark near its start is still a zip`() {
        val stored = zipOf("%PDF-1.4.txt", "Points.xml", stored = true)
        assertNull(FileKinds.documentType(null, stored))
        assertEquals(FileKind.MISSION, FileKinds.detect(null, stored))
    }

    @Test
    fun `an OLE file is a legacy Office document, its type from its name where the name settles it`() {
        assertEquals("doc", FileKinds.documentType("ANNEX.DOC", ole))
        assertEquals("xls", FileKinds.documentType("sheet.xls", ole))
        assertEquals("ppt", FileKinds.documentType("brief.Ppt", ole))
        assertEquals("office", FileKinds.documentType("annex.docx", ole))                               // an encrypted .docx is an OLE file
        assertEquals("office", FileKinds.documentType("annex", ole))
        assertEquals("office", FileKinds.documentType(null, ole))
        assertEquals(FileKind.DOCUMENT, FileKinds.detect("points.lps", ole))
        assertEquals("doc", FileKinds.documentType("a.doc", ole + pdfAt(10)))                         // the OLE mark comes first
    }

    @Test
    fun `a PDF is told by its mark anywhere in its first kilobyte`() {
        assertEquals("pdf", FileKinds.documentType(null, pdfAt(0)))
        assertEquals("pdf", FileKinds.documentType("scan", pdfAt(300)))
        assertEquals("pdf", FileKinds.documentType(null, pdfAt(1019)))                                 // its five bytes end at 1,024
        assertNull(FileKinds.documentType(null, pdfAt(1020)))
        assertEquals(FileKind.DOCUMENT, FileKinds.detect("x.ths", pdfAt(40)))
        assertNull(FileKinds.documentType(null, "%PDF".toByteArray()))
    }

    @Test
    fun `a name alone never makes a file a document, since only the server reads one`() {
        val text = "hello".toByteArray()
        for (name in listOf("a.pdf", "a.docx", "a.xlsx", "a.pptx", "a.doc", "a.xls", "a.ppt")) {
            assertNull(FileKinds.documentType(name, text), name)
            assertEquals(FileKind.UNKNOWN, FileKinds.detect(name, text), name)
            assertNull(FileKinds.byName(name), name)
        }
    }

    @Test
    fun `a database is never a document`() {
        assertNull(FileKinds.documentType("x.pdf", threats))
        assertNull(FileKinds.documentType(null, points))
    }

    @Test
    fun `an Office zip whose directory is damaged or cut short is a mission, never an exception`() {
        val end = lastEnd(docx)
        assertEquals(docx.size - 22, end, "no comment: the end record is the zip's last 22 bytes")
        for (cut in listOf(4, 30, docx.size / 2, docx.size - 23, docx.size - 1)) {
            assertEquals(FileKind.MISSION, FileKinds.detect("a.docx", docx.copyOf(cut)), "cut at $cut")
        }
        // The directory's size made larger than the bytes before the end record, then every byte of the end record in turn.
        assertEquals(FileKind.MISSION, FileKinds.detect(null, docx.copyOf().also { it.putInt(end + 12, 0x7FFFFFFF) }))
        assertEquals(FileKind.MISSION, FileKinds.detect(null, docx.copyOf().also { it.putInt(end + 12, 8) }))
        for (at in end until docx.size) {
            for (value in listOf(0x00, 0x7F, 0xFF)) {
                val damaged = docx.copyOf().also { it[at] = value.toByte() }
                FileKinds.detect(null, damaged)                                               // whatever it says, it says it and does not throw
            }
        }
        val scrambled = docx.copyOf().also { for (i in 4 until it.size step 5) it[i] = 0x7F }
        assertEquals(FileKind.MISSION, FileKinds.detect(null, scrambled))
    }

    @Test
    fun `a directory is read as the server reads it, Zip64's end record included`() {
        // Zip64's counts, as a writer that always writes them leaves them: the end record says 0xFFFF and names the Zip64 record.
        val at = lastEnd(docx)
        val ends = ByteBuffer.wrap(docx).order(ByteOrder.LITTLE_ENDIAN)
        val entries = ends.getShort(at + 10).toLong() and 0xFFFF
        val size = ends.getInt(at + 12).toLong() and 0xFFFFFFFFL
        val offset = ends.getInt(at + 16).toLong() and 0xFFFFFFFFL
        val zip64 = ByteBuffer.allocate(56 + 20 + 22).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x06064b50); putLong(44); putShort(45); putShort(45); putInt(0); putInt(0)
            putLong(entries); putLong(entries); putLong(size); putLong(offset)
            putInt(0x07064b50); putInt(0); putLong(at.toLong()); putInt(1)
            putInt(0x06054b50); putShort(0); putShort(0); putShort(-1); putShort(-1); putInt(-1); putInt(-1); putShort(0)
        }.array()
        assertEquals("docx", FileKinds.documentType(null, docx.copyOf(at) + zip64))
        // A locator that is not there is no directory.
        assertNull(FileKinds.documentType(null, docx.copyOf(at) + zip64.copyOf().also { it[56] = 0 }))
        // A comment after the end record, as a zip may carry: the last end record in the zip's last 64 KB is the one.
        val commented = docx.copyOf().also { it[docx.size - 2] = 5 } + "notes".toByteArray()
        assertEquals("docx", FileKinds.documentType(null, commented))
    }

    @Test
    fun `an Office zip past the server's 20,000 entries is not one it takes`() {
        val parts = arrayOf("[Content_Types].xml", "word/document.xml")
        assertEquals("docx", FileKinds.documentType(null, zipOf(*parts, *Array(19_998) { "f/$it" })))
        assertNull(FileKinds.documentType(null, zipOf(*parts, *Array(19_999) { "f/$it" })))
    }

    /** Where a zip's end record starts. */
    private fun lastEnd(bytes: ByteArray): Int = (bytes.size - 4 downTo 0).first {
        bytes[it] == 0x50.toByte() && bytes[it + 1] == 0x4B.toByte() && bytes[it + 2] == 0x05.toByte() && bytes[it + 3] == 0x06.toByte()
    }

    private fun ByteArray.putInt(at: Int, value: Int) {
        ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(at, value)
    }

    @Test
    fun `only the extension counts for the name`() {
        assertEquals(null, FileKinds.byName("threats.ths.txt"))
        assertEquals(null, FileKinds.byName("ths"))
        assertEquals(FileKind.THREATS, FileKinds.byName("A B.Ths"))
        assertEquals(null, FileKinds.byName(null))
    }
}
