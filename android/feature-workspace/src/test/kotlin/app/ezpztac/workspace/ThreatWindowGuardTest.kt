package app.ezpztac.workspace

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import app.ezpztac.data.ThreatMaskApi
import app.ezpztac.data.ThreatMasks
import app.ezpztac.data.ThreatPicture
import app.ezpztac.data.ThreatSelection
import app.ezpztac.data.ThreatStore
import app.ezpztac.data.ThreatTransfer
import app.ezpztac.data.ThreatVault
import app.ezpztac.data.ThsWriter
import app.ezpztac.model.LatLon
import app.ezpztac.model.Radar
import app.ezpztac.model.Radars
import app.ezpztac.model.Threat
import app.ezpztac.network.ThreatMaskDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The window kept out of screenshots, screen recording and the app switcher while a threat picture is on the device to be drawn. The guard is the map
 * frame's, not the Threats panel's: the panel is composed only while it is offered (the review of S1 to S3 found threats drawn with no guard).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ThreatWindowGuardTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private class Memory : ThreatVault {
        var picture: ThreatPicture? = null
        override fun load(): ThreatPicture? = picture
        override fun save(picture: ThreatPicture) { this.picture = picture }
        override fun wipe() { picture = null }
    }

    private object NoMasks : ThreatMaskApi {
        override suspend fun mask(at: LatLon, radars: List<Radar>): ThreatMaskDto = error("not asked here")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val store = ThreatStore(Memory(), scope, Dispatchers.Unconfined) { 1_000L }
    private val model = ThreatsViewModel(store, ThreatSelection(), ThreatTransfer(ThsWriter { ByteArray(0) }), ThreatMasks(NoMasks, store, scope))

    private val secure get() = compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    private fun threat() = Threat("SA-8", "SHGPEWRR------", 34.75, -84.05, "", "SOF", radars = Radars.defaultPair())

    @Test
    fun `the window is kept out of screenshots while there are threats, and let go once there are none`() {
        compose.setContent { ThreatWindowGuard(model) }
        compose.waitForIdle()
        assertFalse(secure)
        val id = store.add(threat())
        compose.waitForIdle()
        assertTrue(secure)
        store.remove(id)
        compose.waitForIdle()
        assertFalse(secure)
    }

    @Test
    fun `the guard leaving the screen lets the window go`() {
        var shown by mutableStateOf(true)
        store.add(threat())
        compose.setContent { if (shown) ThreatWindowGuard(model) }
        compose.waitForIdle()
        assertTrue(secure)
        shown = false
        compose.waitForIdle()
        assertFalse(secure)
    }
}
