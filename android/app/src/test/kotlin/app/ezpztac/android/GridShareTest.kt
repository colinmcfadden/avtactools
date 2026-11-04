package app.ezpztac.android

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Sending a grid (docs/GRID_SHARING.md): the link the app is opened with, the dialog, and what the share sheet is given. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GridShareTest {
    @get:Rule
    val compose = createComposeRule()

    private val place = GridSharePlace("16S GC 28864 55349", 33.913724026697224, -84.52450547388797, "LZ HAWK")
    private val text = "LZ HAWK\n16S GC 28864 55349\n33.91372° N, 84.52451° W\nhttps://ezpztac.app/#grid=16SGC2886455349&name=LZ%20HAWK"

    @Test
    fun `a grid link to the site is read, and nothing else is`() {
        val link = GridLinks.parse("https://ezpztac.app/#grid=16SGC2886455349&name=LZ%20HAWK")
        assertEquals("16S GC 28864 55349", link!!.choice.mgrs)
        assertEquals("LZ HAWK", link.name)
        assertNotNull(GridLinks.parse("https://www.ezpztac.app/?x=1#grid=16SGC2886455349"))
        assertNull(GridLinks.parse("http://ezpztac.app/#grid=16SGC2886455349"))                  // not https
        assertNull(GridLinks.parse("https://evil.example/#grid=16SGC2886455349"))                // not the site
        assertNull(GridLinks.parse("https://ezpztac.app/#grid=34%2084"))                         // not a grid
        assertNull(GridLinks.parse("https://ezpztac.app/?auth=verify&token=abc"))                // no fragment
        assertNull(GridLinks.parse(null))
    }

    @Test
    fun `every link the web reads is read, as the browser hands it over, raw characters and lone percent signs too`() {
        assertEquals("50%off", GridLinks.parse("https://ezpztac.app/#grid=16SGC2886455349&name=50%off")!!.name)
        assertEquals("LZ|HAWK", GridLinks.parse("https://ezpztac.app/#grid=16SGC2886455349&name=LZ|HAWK")!!.name)
        assertEquals("16S GC 28864 55349", GridLinks.parse("https://ezpztac.app/#grid=16S GC 28864 55349")!!.choice.mgrs)
        assertNull(GridLinks.fragmentOf("https://ezpztac.app/#section"))
    }

    @Test
    fun `the share sheet is given the lines as plain text, the label as its subject`() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        shareGrid(context, place)
        val chooser = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION") val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals(text, send.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("LZ HAWK", send.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    @Test
    fun `the dialog shows the grid and its lat and long, and copies the lines or the link`() {
        val copied = mutableListOf<String>()
        var shared = 0
        compose.setContent {
            EzpzTheme(ThemeMode.Dark) { GridShareDialog(place, onShare = { shared++ }, onCopy = { _, t -> copied += t }, onDismiss = {}) }
        }
        compose.onNodeWithText("16S GC 28864 55349").assertIsDisplayed()
        compose.onNodeWithText("33.91372° N, 84.52451° W").assertIsDisplayed()
        compose.onNodeWithText("Copy").performClick()
        compose.onNodeWithText("Copy link").performClick()
        compose.onNodeWithText("Share…").performClick()
        assertEquals(listOf(text, "https://ezpztac.app/#grid=16SGC2886455349&name=LZ%20HAWK"), copied)
        assertEquals(1, shared)
    }
}
