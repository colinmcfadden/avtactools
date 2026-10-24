package app.ezpztac.android

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What changes of the device the activity takes itself rather than being made again: a turn, a window resized, night mode, and a keyboard or a presenter's clicker
 * coming, going or sleeping (each a change of `keyboard` or `navigation`), so a live sandtable is not made again under the presenter (its map's camera and the step
 * it flew to are the activity's).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ManifestConfigTest {
    @Test
    fun `the activity is not made again for a turn, a resize, night mode, or a keyboard or clicker coming and going`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val changes = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0).configChanges
        mapOf(
            "orientation" to ActivityInfo.CONFIG_ORIENTATION, "screenSize" to ActivityInfo.CONFIG_SCREEN_SIZE, "uiMode" to ActivityInfo.CONFIG_UI_MODE,
            "keyboard" to ActivityInfo.CONFIG_KEYBOARD, "keyboardHidden" to ActivityInfo.CONFIG_KEYBOARD_HIDDEN, "navigation" to ActivityInfo.CONFIG_NAVIGATION,
        ).forEach { (name, flag) -> assertTrue("$name makes the activity again", changes and flag != 0) }
    }
}
