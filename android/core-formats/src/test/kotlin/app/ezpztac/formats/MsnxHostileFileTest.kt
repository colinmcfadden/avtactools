package app.ezpztac.formats

import app.ezpztac.testing.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A leading XML declaration (and BOM), which a DOCTYPE has to come after. */
private val DECLARATION = Regex("""^\uFEFF?\s*<\?xml[^>]*\?>\s*""")

/**
 * A `.msnx` arrives by email or AirDrop from whoever sent it. These are the files a
 * hostile sender would make, and the damage each could do if the reader trusted them.
 */
class MsnxHostileFileTest {
    private val template: Map<String, ByteArray> = unzip(Fixtures.bytes("msnx/template.msnx"))

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val parts = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) parts[entry.name] = zip.readBytes()
            }
        }
        return parts
    }

    private fun zipOf(parts: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            parts.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
        return out.toByteArray()
    }

    private fun with(part: String, transform: (String) -> String): ByteArray =
        zipOf(template + (part to transform(String(template.getValue(part))).toByteArray()))

    /**
     * The part with `doctype` placed where XML puts one: after the declaration, before the root.
     * Putting it anywhere else makes the document malformed, and a test that expected a refusal
     * would then pass for that reason instead of the one it names.
     */
    private fun withDoctype(part: String, doctype: String, edit: (String) -> String = { it }): ByteArray =
        with(part) { xml -> """<?xml version="1.0"?>""" + doctype + edit(xml.replace(DECLARATION, "")) }

    private fun message(bytes: ByteArray, max: Int = MsnxReader.DEFAULT_MAX_PART_BYTES): String? =
        assertThrows<MsnxException> { MsnxReader.read(bytes, max) }.message

    @Test
    fun `the template, rebuilt by this test, reads (the helpers are sound)`() {
        assertEquals(1, MsnxReader.read(zipOf(template)).routes.size)
    }

    @Test
    fun `an external entity cannot read a file off the device`() {
        val xxe = withDoctype("mission.gpx", """<!DOCTYPE gpx [<!ENTITY secret SYSTEM "file:///etc/passwd">]>""") {
            it.replaceFirst("<name>", "<name>&secret;")
        }
        assertEquals("Failed to parse mission.gpx in this mission file.", message(xxe))
    }

    @Test
    fun `a billion laughs expansion is refused, not expanded`() {
        val laughs = buildString {
            append("""<!DOCTYPE lolz [<!ENTITY lol "lol">""")
            for (i in 1..9) append("""<!ENTITY lol$i "${"&lol${if (i == 1) "" else (i - 1).toString()};".repeat(10)}">""")
            append("]>")
        }
        val bomb = withDoctype("mission/points.xml", laughs) { it.replace("<points>", "<points>&lol9;") }
        assertEquals("Failed to parse points.xml in this mission file.", message(bomb))
    }

    @Test
    fun `a DOCTYPE is refused whatever the case`() {
        val lower = withDoctype("mission/segments.xml", """<!doctype x [<!entity y "z">]>""")
        assertEquals("Failed to parse segments.xml in this mission file.", message(lower))
    }

    @Test
    fun `an archive that expands past the limit is refused rather than filling memory`() {
        // A real part is at most tens of MB; one that keeps going is a zip bomb. Shrink the limit
        // so the test does not have to build gigabytes.
        val message = message(zipOf(template), max = 100 * 1024)
        assertNotNull(message)
        assertTrue(message!!.startsWith("This mission file is too large to open"), message)
    }

    @Test
    fun `a genuine bomb is stopped by the default limit before it is held in memory`() {
        // 300 MB of zeros deflates to about 300 KB: harmless to send, ruinous to unpack.
        val zeros = ByteArray(1024 * 1024)
        val bomb = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("mission/legs.xml"))
                repeat(300) { zip.write(zeros) }
                zip.closeEntry()
            }
        }.toByteArray()
        assertTrue(bomb.size < 1024 * 1024, "the bomb should be small on the wire, was ${bomb.size}")
        assertTrue(message(bomb)!!.startsWith("This mission file is too large to open"))
    }

    @Test
    fun `unwanted parts are never read, so a huge one cannot hurt`() {
        val padded = template + ("Vehicle%20Installations/huge.vidx" to ByteArray(2 * 1024 * 1024))
        assertEquals(1, MsnxReader.read(zipOf(padded), maxPartBytes = 1024 * 1024 * 40).routes.size)
        // ...even with a limit smaller than that part: it is skipped, not loaded.
        assertEquals(1, MsnxReader.read(zipOf(padded), maxPartBytes = template.getValue("mission/legs.xml").size + 10).routes.size)
    }

    @Test
    fun `a missing required part says the file is not a mission`() {
        for (part in listOf("mission.gpx", "mission/points.xml", "mission/legs.xml", "mission/segments.xml")) {
            val bytes = zipOf(template - part)
            assertEquals("This doesn't look like a valid .msnx mission file (missing expected mission data).", message(bytes), part)
        }
    }

    @Test
    fun `a missing vehicles part is fine, there is just no airframe`() {
        val mission = MsnxReader.read(zipOf(template - "mission/vehicles.xml"))
        assertEquals(null, mission.aircraft)
        assertEquals(1, mission.routes.size)
    }

    @Test
    fun `broken XML names the part that failed`() {
        assertEquals("Failed to parse mission.gpx in this mission file.", message(with("mission.gpx") { it.take(it.length / 2) }))
        assertEquals("Failed to parse points.xml in this mission file.", message(with("mission/points.xml") { "<points><point>" }))
    }

    @Test
    fun `a mission with no routes says so`() {
        val none = with("mission.gpx") { it.replace(Regex("<rte>[\\s\\S]*?</rte>"), "") }
        assertEquals("No routes found in this mission file.", message(none))
    }

    @Test
    fun `a doubled XML declaration, which earlier web builds wrote, still opens`() {
        // The web strips a BOM and any leading declarations; so must the app, or files
        // the web exported before the fix would not open on a phone.
        val doubled = with("mission/points.xml") { "﻿<?xml version=\"1.0\"?>\n<?xml version=\"1.0\" encoding=\"utf-8\"?>\n$it" }
        assertEquals(27, MsnxReader.read(doubled).routes.single().points.size)
    }

    @Test
    fun `an entry that is a directory, or a path that tries to climb out, is ignored`() {
        val sneaky = zipOf(template + ("../../etc/passwd" to "x".toByteArray()) + ("mission/../evil.xml" to "x".toByteArray()))
        assertEquals(1, MsnxReader.read(sneaky).routes.size)        // nothing is ever written to disk by the reader
    }
}

/** The same files on an XML runtime with no feature flags (Android's): only the text check can refuse them. */
class MsnxWithoutParserHardeningTest {
    private val template: Map<String, ByteArray> = unzipFixture()

    @Test
    fun `a DOCTYPE is still refused when the parser cannot be configured`() {
        val previous = MsnxReader.hardenXmlFactory
        MsnxReader.hardenXmlFactory = false
        try {
            // Well-formed apart from the DOCTYPE, so nothing but the text check can refuse it:
            // without that check this document parses.
            val gpx = String(template.getValue("mission.gpx")).replace(DECLARATION, "")
            val xxe = zip(template + ("mission.gpx" to ("""<?xml version="1.0"?><!DOCTYPE gpx [<!ENTITY secret "x">]>""" + gpx).toByteArray()))
            val error = assertThrows<MsnxException> { MsnxReader.read(xxe) }
            assertEquals("Failed to parse mission.gpx in this mission file.", error.message)
            // ...and an ordinary mission still reads, so the seam did not simply break the parser.
            assertEquals(1, MsnxReader.read(zip(template)).routes.size)
        } finally {
            MsnxReader.hardenXmlFactory = previous
        }
    }

    private fun unzipFixture(): Map<String, ByteArray> {
        val parts = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(Fixtures.bytes("msnx/template.msnx"))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) parts[entry.name] = zip.readBytes()
            }
        }
        return parts
    }

    private fun zip(parts: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> parts.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return out.toByteArray()
    }
}
