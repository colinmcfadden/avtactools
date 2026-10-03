package app.ezpztac.android.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.ezpztac.testing.Fixtures
import app.ezpztac.workspace.ExportFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** Handing an exported mission to the share sheet: where it is written, what the other app is given, and what is cleared out. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShareExportTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val bytes = byteArrayOf(80, 75, 3, 4, 1, 2, 3)

    /** The provider remembers where the cache folder was for as long as the process lives; each test has a folder of its own, so it is made to forget. */
    @Before
    fun forgetTheProvidersFolders() {
        ReflectionHelpers.getStaticField<HashMap<String, Any>>(androidx.core.content.FileProvider::class.java, "sCache").clear()
    }

    @Suppress("DEPRECATION")
    private fun sent(chooser: Intent): Intent = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!

    private fun exports() = File(context.cacheDir, "exports")

    @Test
    fun `the mission is written to the exports folder and offered to the share sheet`() {
        val chooser = ShareExport.prepare(context, ExportFile("ROUTE 1.msnx", bytes))
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = sent(chooser)
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/octet-stream", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertArrayEquals(bytes, File(exports(), "ROUTE 1.msnx").readBytes())
    }

    @Test
    fun `what the other app is given is one URI of this app's provider, which reads back the file`() {
        val send = sent(ShareExport.prepare(context, ExportFile("ROUTE 1.msnx", bytes)))
        @Suppress("DEPRECATION") val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.exports", uri.authority)
        assertEquals("ROUTE 1.msnx", uri.lastPathSegment)
        assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        val clip: ClipData = send.clipData!!
        assertEquals(uri, clip.getItemAt(0).uri)                                               // the grant is carried by the clip, which is how a share sheet is allowed to read it
    }

    @Test
    fun `a file outside the exports folder cannot be reached through the provider`() {
        val secret = File(context.cacheDir, "secret.txt").apply { writeText("not for sharing") }
        val send = sent(ShareExport.prepare(context, ExportFile("ROUTE 1.msnx", bytes)))
        @Suppress("DEPRECATION") val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        val sibling = Uri.parse("content://${uri.authority}/exports/../secret.txt")
        val failed = runCatching { context.contentResolver.openInputStream(sibling)!!.use { it.readBytes() } }
        assertTrue("the provider served a file outside its folder", failed.isFailure)
        assertEquals("not for sharing", secret.readText())
    }

    @Test
    fun `a name that tries to leave the folder is cut down to a file name`() {
        assertEquals("x.msnx", ShareExport.plainName("../../x.msnx"))
        assertEquals("x.msnx", ShareExport.plainName("..\\..\\x.msnx"))
        assertEquals("x.msnx", ShareExport.plainName("/data/user/0/x.msnx"))
        assertEquals("ROUTES.msnx", ShareExport.plainName(""))
        assertEquals("ROUTES.msnx", ShareExport.plainName("..."))
        assertEquals("ROUTES.msnx", ShareExport.plainName("/"))
        assertEquals("A B.msnx", ShareExport.plainName("A B"))                                 // the extension is added when it is missing
        assertEquals("A.msnx", ShareExport.plainName("A.msnx"))                                // and not doubled
        assertEquals("MISSION 1.ths", ShareExport.plainName("../../MISSION 1.ths"))             // threat files keep their own type
        ShareExport.prepare(context, ExportFile("../../escape.msnx", bytes))
        assertTrue(File(exports(), "escape.msnx").exists())
        assertFalse(File(context.cacheDir.parentFile, "escape.msnx").exists())
    }

    @Test
    fun `files from earlier exports are cleared after a day, and a recent one stays`() {
        exports().mkdirs()
        val old = File(exports(), "OLD.msnx").apply { writeBytes(bytes); setLastModified(1_000_000L) }
        val recent = File(exports(), "RECENT.msnx").apply { writeBytes(bytes); setLastModified(System.currentTimeMillis() - 60 * 60 * 1000) }
        ShareExport.prepare(context, ExportFile("NEW.msnx", bytes))
        assertFalse(old.exists())
        assertTrue(recent.exists())
        assertTrue(File(exports(), "NEW.msnx").exists())
    }

    @Test
    fun `exporting the same mission again replaces the file`() {
        ShareExport.prepare(context, ExportFile("ROUTE 1.msnx", bytes))
        ShareExport.prepare(context, ExportFile("ROUTE 1.msnx", byteArrayOf(1, 2)))
        assertArrayEquals(byteArrayOf(1, 2), File(exports(), "ROUTE 1.msnx").readBytes())
        assertEquals(1, exports().listFiles()!!.count { it.name == "ROUTE 1.msnx" })
    }

    @Test
    fun `the bundled mission is the web's template`() {
        val template = AssetMissionTemplate(context).bytes()
        assertNotNull(template)
        assertArrayEquals(Fixtures.repoBytes("frontend/public/msnx_template.msnx"), template)
    }

    @Test
    fun `the bundled threat database is the backend's template`() {
        assertArrayEquals(Fixtures.repoBytes("backend/threat_template.ths"), AssetThsTemplate(context).bytes())
    }

    // -- A threat file beside the mission ------------------------------------------------------------------------------------

    @Test
    fun `a ths keeps its own extension, whatever the folder it came with`() {
        assertEquals("MISSION 1.ths", ShareExport.plainName("MISSION 1.ths"))
        assertEquals("MISSION 1.ths", ShareExport.plainName("MISSION 1.THS"))
        assertEquals("x.ths", ShareExport.plainName("/data/user/0/x.ths"))
        assertEquals("x.ths", ShareExport.plainName("..\\..\\x.ths"))
    }

    @Test
    fun `the mission and its threat file go out together, each readable through its own URI`() {
        val ths = byteArrayOf(83, 81, 76, 105, 116, 101)
        val chooser = ShareExport.prepare(context, listOf(ExportFile("MISSION 1.msnx", bytes), ExportFile("MISSION 1.ths", ths)))
        val send = sent(chooser)
        assertEquals(Intent.ACTION_SEND_MULTIPLE, send.action)
        assertEquals("application/octet-stream", send.type)
        @Suppress("DEPRECATION") val uris = send.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals(listOf("MISSION 1.msnx", "MISSION 1.ths"), uris.map { it.lastPathSegment })
        assertArrayEquals(bytes, context.contentResolver.openInputStream(uris[0])!!.use { it.readBytes() })
        assertArrayEquals(ths, context.contentResolver.openInputStream(uris[1])!!.use { it.readBytes() })
        val clip: ClipData = send.clipData!!
        assertEquals(uris, (0 until clip.itemCount).map { clip.getItemAt(it).uri })            // the grant covers every one
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun `one file is still a plain send`() {
        val send = sent(ShareExport.prepare(context, listOf(ExportFile("threats.ths", bytes))))
        assertEquals(Intent.ACTION_SEND, send.action)
    }

    @Test
    fun `nothing to share is not a share`() {
        val failure = runCatching { ShareExport.prepare(context, emptyList()) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `a threat file is cleared after an hour, where a mission is kept a day`() {
        exports().mkdirs()
        val now = System.currentTimeMillis()
        val oldThs = File(exports(), "OLD.ths").apply { writeBytes(bytes); setLastModified(now - 2 * 60 * 60 * 1000) }
        val freshThs = File(exports(), "FRESH.ths").apply { writeBytes(bytes); setLastModified(now - 30 * 60 * 1000) }
        val twoHourMission = File(exports(), "TWO.msnx").apply { writeBytes(bytes); setLastModified(now - 2 * 60 * 60 * 1000) }
        ShareExport.prepare(context, ExportFile("NEW.msnx", bytes), now)
        assertFalse("a two-hour-old .ths is still there", oldThs.exists())
        assertTrue(freshThs.exists())
        assertTrue("a mission is kept a day", twoHourMission.exists())
    }

    @Test
    fun `clearing removes everything exported, a mission and a threat file alike, and is fine with nothing there`() {
        ShareExport.clear(context)                                                             // no folder yet: no complaint
        ShareExport.prepare(context, listOf(ExportFile("A.msnx", bytes), ExportFile("A.ths", bytes)))
        assertEquals(2, exports().listFiles()!!.size)
        ShareExport.clear(context)
        assertEquals(0, exports().listFiles()!!.size)
    }
}
