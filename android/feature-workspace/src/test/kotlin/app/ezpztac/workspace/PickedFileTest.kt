package app.ezpztac.workspace

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException

/** What the system's picker hands over is an address, not a file: what is read from one, bounded, and what is said when it cannot be. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PickedFileTest {
    /** A provider like a picker's: files by name, a display name that can be missing, blank or refused, and opens that can fail. */
    class Provider : ContentProvider() {
        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            if (uri.lastPathSegment in refuseQuery) throw SecurityException("no")
            val shown = displayNames[uri.lastPathSegment] ?: return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME))       // no row at all
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).also { it.addRow(arrayOf<Any?>(shown)) }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val name = uri.lastPathSegment.orEmpty()
            if (name in withdrawn) throw SecurityException("the grant was withdrawn")
            val bytes = files[name] ?: throw FileNotFoundException(name)
            val temp = File.createTempFile("picked", ".bin").also { it.deleteOnExit(); it.writeBytes(bytes) }
            return ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

        companion object {
            val files = HashMap<String, ByteArray>()
            val displayNames = HashMap<String, String>()
            val withdrawn = HashSet<String>()
            val refuseQuery = HashSet<String>()
        }
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resolver get() = context.contentResolver

    @Before
    fun setUp() {
        Provider.files.clear(); Provider.displayNames.clear(); Provider.withdrawn.clear(); Provider.refuseQuery.clear()
        Robolectric.setupContentProvider(Provider::class.java, AUTHORITY)
    }

    private fun uri(name: String) = Uri.parse("content://$AUTHORITY/$name")

    private fun read(name: String, max: Long = PickedFile.MAX_BYTES) = PickedFile.read(resolver, uri(name), max)

    @Test
    fun `a file is read whole, under the name the picker shows for it`() {
        val bytes = ByteArray(40_000) { (it % 251).toByte() }                                   // more than one buffer, and not a round size
        Provider.files["a"] = bytes
        Provider.displayNames["a"] = "NORTH GEORGIA.LPS"
        val result = read("a") as PickedFile.Result.Read
        assertEquals("NORTH GEORGIA.LPS", result.name)
        assertArrayEquals(bytes, result.bytes)
    }

    @Test
    fun `an empty file is read as empty, and left to the reader to refuse`() {
        Provider.files["e"] = ByteArray(0)
        Provider.displayNames["e"] = "EMPTY.LPS"
        assertEquals(0, (read("e") as PickedFile.Result.Read).bytes.size)
    }

    @Test
    fun `with no name from the picker the last part of the address names it, and with none at all a plain name`() {
        Provider.files["no-row"] = byteArrayOf(1)
        assertEquals("no-row", (read("no-row") as PickedFile.Result.Read).name)

        Provider.files["blank"] = byteArrayOf(1)
        Provider.displayNames["blank"] = "   "
        assertEquals("blank", (read("blank") as PickedFile.Result.Read).name)

        Provider.files[""] = byteArrayOf(1)                                                     // an address with no path at all: nothing to name it by
        val bare = PickedFile.read(resolver, Uri.parse("content://$AUTHORITY"), 100) as PickedFile.Result.Read
        assertEquals("points.lps", bare.name)
    }

    @Test
    fun `a provider that will not say the name is not a reason to fail the import`() {
        Provider.files["q"] = byteArrayOf(1, 2, 3)
        Provider.refuseQuery += "q"
        val result = read("q") as PickedFile.Result.Read
        assertEquals("q", result.name)
        assertArrayEquals(byteArrayOf(1, 2, 3), result.bytes)
    }

    @Test
    fun `a file past the limit is not read into memory, and one exactly at it is`() {
        Provider.files["limit"] = ByteArray(1000)
        assertEquals(1000, (read("limit", max = 1000) as PickedFile.Result.Read).bytes.size)
        assertEquals(PickedFile.Result.Failed(PickedFile.TOO_BIG), read("limit", max = 999))
    }

    @Test
    fun `a file that is gone, or whose grant was withdrawn, says it could not be read`() {
        assertEquals(PickedFile.Result.Failed(PickedFile.UNREADABLE), read("missing"))
        Provider.files["w"] = byteArrayOf(1)
        Provider.withdrawn += "w"
        assertEquals(PickedFile.Result.Failed(PickedFile.UNREADABLE), read("w"))
    }

    @Test
    fun `an address that is not one this device knows says it could not be read`() {
        assertEquals(PickedFile.Result.Failed(PickedFile.UNREADABLE), PickedFile.read(resolver, Uri.parse("content://nobody.at.all/x"), 100))
        assertEquals(PickedFile.Result.Failed(PickedFile.UNREADABLE), PickedFile.read(resolver, Uri.parse("file:///no/such/file.lps"), 100))
    }

    @Test
    fun `the words are for a person and name nothing inside`() {
        assertTrue(PickedFile.UNREADABLE.endsWith("."))
        assertTrue(PickedFile.TOO_BIG.contains("local points"))
        assertEquals(32L * 1024 * 1024, PickedFile.MAX_BYTES)
    }

    private companion object {
        const val AUTHORITY = "app.ezpztac.test.picker"
    }
}
