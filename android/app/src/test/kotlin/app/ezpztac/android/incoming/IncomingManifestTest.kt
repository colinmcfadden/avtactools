package app.ezpztac.android.incoming

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The manifest's filters decide which files the phone offers this app for. They are held to the rule in the manifest's own comment: the three types these formats
 * arrive as, from a content address or the share sheet, and nothing broader, so the app is not offered for every file on the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class IncomingManifestTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun offered(intent: Intent) =
        context.packageManager.queryIntentActivities(intent, 0).any { it.activityInfo.name == "app.ezpztac.android.MainActivity" }

    private fun view(address: String, type: String?) = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(address), type)

    private fun send(action: String, type: String) = Intent(action).setType(type)

    @Test
    fun `a file from Files or a mail is offered the app when it arrives as octet-stream or SQLite`() {
        for (type in listOf("application/octet-stream", "application/x-sqlite3", "application/vnd.sqlite3")) {
            assertTrue(type, offered(view("content://com.android.providers.downloads.documents/document/42", type)))
        }
    }

    @Test
    fun `other kinds of file are not`() {
        for (type in listOf("text/plain", "image/png", "application/pdf", "application/zip", "application/json")) {
            assertFalse(type, offered(view("content://p/doc/1", type)))
        }
    }

    @Test
    fun `a file path is not offered the app, whatever its type`() {
        assertFalse(offered(view("file:///sdcard/Download/SA-6.ths", "application/octet-stream")))
    }

    @Test
    fun `the share sheet offers the app for one file or several of those types, and for nothing else`() {
        for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            for (type in listOf("application/octet-stream", "application/x-sqlite3", "application/vnd.sqlite3")) assertTrue("$action $type", offered(send(action, type)))
            for (type in listOf("text/plain", "image/jpeg", "application/pdf")) assertFalse("$action $type", offered(send(action, type)))
        }
    }

    @Test
    fun `only the main activity takes files, and it is the one that is exported`() {
        val resolved = context.packageManager.queryIntentActivities(view("content://p/doc/1", "application/octet-stream"), 0)
        assertEquals(listOf("app.ezpztac.android.MainActivity"), resolved.map { it.activityInfo.name })
    }
}
