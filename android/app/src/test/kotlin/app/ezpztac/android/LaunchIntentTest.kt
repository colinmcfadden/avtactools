package app.ezpztac.android

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the activity reads from the intent it is started with: what arrives, never what a task reopened from the recent apps brings back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LaunchIntentTest {
    private val link = Intent(Intent.ACTION_VIEW, Uri.parse("https://ezpztac.app/?invite=Xq3v_8yQm2LZk9-WbT4sPa7Rr1Nd5Cf6Hg0Jj2Kk3Ll"))

    @Test
    fun `a link or a file that arrives is read`() {
        assertTrue(isNews(link))
        assertTrue(isNews(Intent(link).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
        assertTrue(isNews(Intent(Intent.ACTION_MAIN)))
        assertTrue(isNews(null))
    }

    @Test
    fun `the intent a task is started again with from the recent apps is not read again`() {
        assertFalse(isNews(Intent(link).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)))
        assertFalse(isNews(Intent(link).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY or Intent.FLAG_ACTIVITY_NEW_TASK)))
    }
}
