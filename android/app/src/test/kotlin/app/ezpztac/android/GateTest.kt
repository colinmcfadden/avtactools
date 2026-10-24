package app.ezpztac.android

import app.ezpztac.data.Ownership
import app.ezpztac.network.ApiSupport
import app.ezpztac.network.ApiUser
import app.ezpztac.network.AppConfig
import app.ezpztac.network.AuthState
import app.ezpztac.network.SignedOutReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GateTest {
    private fun user(accessOk: Boolean = true) = ApiUser(
        id = 1, email = "pilot@example.com", name = "Pilot", role = "user", isAdmin = false, isActive = true, features = emptyMap(), accessOk = accessOk,
    )

    private fun config(minimum: String? = null, maintenance: Boolean = false, message: String? = null) = AppConfig(
        configVersion = 1, serverVersion = "1.7.6",
        minAppVersion = AppConfig.MinAppVersion(android = minimum),
        maintenance = AppConfig.Maintenance(maintenance, message),
        services = AppConfig.Services(lidarBuilds = false, packs = false),
        mapbox = AppConfig.MapboxConfig("pk.x"),
    )

    private val signedIn = AuthState.SignedIn(user())

    // -- An update comes before everything --------------------------------------------------------

    @Test
    fun `a server past every API major this build talks needs an update, even before anyone signs in`() {
        val moved = config().copy(apiVersion = "2.0.0", apiMajors = listOf(2))
        assertEquals(Gate.UpdateRequired(null), gateFor(moved, AuthState.SignedOut(SignedOutReason.NOT_SIGNED_IN), null, "0.1.0", api = ApiSupport(listOf(1))))
        assertEquals(Gate.UpdateRequired(null), gateFor(moved, signedIn, Ownership.Yours, "0.1.0", api = ApiSupport(listOf(1))))
    }

    @Test
    fun `a server that still serves this build's API major is fine, as is one from before the field`() {
        val kept = config().copy(apiVersion = "2.0.0", apiMajors = listOf(1, 2))
        assertEquals(Gate.Ready(user(), null), gateFor(kept, signedIn, Ownership.Yours, "0.1.0", api = ApiSupport(listOf(1))))
        assertEquals(Gate.Ready(user(), null), gateFor(config(), signedIn, Ownership.Yours, "0.1.0", api = ApiSupport(listOf(1))))
    }

    @Test
    fun `a server behind this build's API opens planning, marked for its own banner`() {
        val behind = config().copy(apiVersion = "1.4.0", apiMajors = listOf(1))
        assertEquals(Gate.Ready(user(), null, serverBehind = true), gateFor(behind, signedIn, Ownership.Yours, "0.1.0", api = ApiSupport(listOf(2))))
        // A server from before the field serves API 1.
        assertEquals(Gate.Ready(user(), null, serverBehind = true), gateFor(config(), signedIn, Ownership.Yours, "0.1.0", api = ApiSupport(listOf(2))))
    }

    @Test
    fun `API majors the device last heard mark the banner with no config, and unreadable ones mark nothing`() {
        assertEquals(Gate.Ready(user(), null, tooOld = true), gateFor(null, signedIn, Ownership.Yours, "0.1.0", rememberedApi = listOf(2), api = ApiSupport(listOf(1))))
        assertEquals(Gate.Ready(user(), null), gateFor(config().copy(apiVersion = "two"), signedIn, Ownership.Yours, "0.1.0", api = ApiSupport(listOf(1))))
    }

    @Test
    fun `this build's own list is the one the build was made with`() {
        assertTrue(BUILT_API.majors.isNotEmpty())
        assertEquals(BUILT_API.majors, ApiSupport.parse(BuildConfig.SUPPORTED_API_MAJORS).majors)
    }

    @Test
    fun `nothing is sent while the API has nothing in common, either way`() {
        assertTrue(holdsBack("0.1.0", null, listOf(2), ApiSupport(listOf(1))))
        assertTrue(holdsBack("0.1.0", null, listOf(1), ApiSupport(listOf(2))))
        assertTrue(!holdsBack("0.1.0", null, listOf(1, 2), ApiSupport(listOf(1))))
        assertTrue(!holdsBack("0.1.0", null, null, ApiSupport(listOf(1))))
        assertTrue(holdsBack("0.1.0", "0.2.0", listOf(1), ApiSupport(listOf(1))))
    }

    @Test
    fun `an app the server no longer supports is stopped, even before anyone signs in`() {
        assertEquals(Gate.UpdateRequired("1.8.0"), gateFor(config(minimum = "1.8.0"), AuthState.SignedOut(SignedOutReason.NOT_SIGNED_IN), null, "1.7.6"))
        assertEquals(Gate.UpdateRequired("1.8.0"), gateFor(config(minimum = "1.8.0"), signedIn, Ownership.Yours, "1.7.6"))
        assertEquals(Gate.UpdateRequired("1.8.0"), gateFor(config(minimum = "1.8.0"), AuthState.Unknown, null, "1.7.6"))
    }

    @Test
    fun `a version at or above the minimum is supported`() {
        assertTrue(gateFor(config(minimum = "1.7.6"), signedIn, Ownership.Yours, "1.7.6") is Gate.Ready)
        assertTrue(gateFor(config(minimum = "1.7.6"), signedIn, Ownership.Yours, "2.0.0") is Gate.Ready)
        assertTrue(gateFor(config(minimum = "1.7.6"), signedIn, Ownership.Yours, "1.7.6-beta.1") !is Gate.Ready)   // a pre-release is below its release
    }

    @Test
    fun `no signal at launch blocks nothing, and neither does a minimum nobody can read`() {
        assertTrue(gateFor(null, signedIn, Ownership.Yours, "1.7.6") is Gate.Ready)
        assertTrue(gateFor(config(minimum = "soon"), signedIn, Ownership.Yours, "1.7.6") is Gate.Ready)
        assertTrue(gateFor(config(minimum = null), signedIn, Ownership.Yours, "1.7.6") is Gate.Ready)
    }

    // The owner's decision: below the server's minimum nothing is sent. With no config here to show the update screen, the minimum the device last
    // heard blocks nothing on the screen (planning is local), and only marks the gate for the banner that says why nothing goes.
    @Test
    fun `a minimum the device last heard opens planning with no config, marked for the banner`() {
        assertEquals(Gate.Ready(user(), null, tooOld = true), gateFor(null, signedIn, Ownership.Yours, "1.7.6", remembered = "1.8.0"))
        assertEquals(Gate.Ready(user(), null, tooOld = false), gateFor(null, signedIn, Ownership.Yours, "1.7.6", remembered = "1.7.6"))
        assertEquals(Gate.Ready(user(), null, tooOld = false), gateFor(null, signedIn, Ownership.Yours, "1.7.6", remembered = null))
        assertEquals(Gate.UpdateRequired("1.8.0"), gateFor(config(minimum = "1.8.0"), signedIn, Ownership.Yours, "1.7.6", remembered = "1.8.0"))
    }

    // -- Who is signed in ---------------------------------------------------------------------------

    @Test
    fun `the session is not known until it has been read`() {
        assertEquals(Gate.Starting, gateFor(null, AuthState.Unknown, null, "1.7.6"))
    }

    @Test
    fun `signed out says why`() {
        assertEquals(
            Gate.SignedOut(SignedOutReason.SESSION_ENDED, "offline_too_long"),
            gateFor(null, AuthState.SignedOut(SignedOutReason.SESSION_ENDED, "offline_too_long"), null, "1.7.6"),
        )
        assertEquals(
            Gate.SignedOut(SignedOutReason.NOT_SIGNED_IN, null),
            gateFor(null, AuthState.SignedOut(SignedOutReason.NOT_SIGNED_IN), null, "1.7.6"),
        )
    }

    @Test
    fun `signed in but outside the gate is held at the gate, whoever's plans are here`() {
        val outside = AuthState.SignedIn(user(accessOk = false))
        assertEquals(Gate.NeedsAffiliation(user(accessOk = false)), gateFor(null, outside, null, "1.7.6"))
        assertEquals(Gate.NeedsAffiliation(user(accessOk = false)), gateFor(null, outside, Ownership.SomeoneElses(3), "1.7.6"))
    }

    // -- Whose plans are on the device ------------------------------------------------------------------

    @Test
    fun `nothing is shown until it is known whose plans are here`() {
        assertEquals(Gate.Starting, gateFor(null, signedIn, null, "1.7.6"))
    }

    @Test
    fun `someone else's plans are not shown, and the cost of clearing them is`() {
        assertEquals(Gate.DataBelongsToSomeoneElse(user(), 4), gateFor(null, signedIn, Ownership.SomeoneElses(4), "1.7.6"))
    }

    @Test
    fun `an unclaimed device, or one that is theirs, is ready`() {
        assertEquals(Gate.Ready(user(), null), gateFor(config(), signedIn, Ownership.Unclaimed, "1.7.6"))
        assertEquals(Gate.Ready(user(), null), gateFor(config(), signedIn, Ownership.Yours, "1.7.6"))
    }

    // -- Maintenance is a notice, never a block ----------------------------------------------------------------

    @Test
    fun `a server in maintenance is a banner, because planning is local`() {
        assertEquals(Gate.Ready(user(), "Back at 1500Z."), gateFor(config(maintenance = true, message = "Back at 1500Z."), signedIn, Ownership.Yours, "1.7.6"))
    }

    @Test
    fun `a maintenance notice with no words still says something`() {
        val ready = gateFor(config(maintenance = true, message = "  "), signedIn, Ownership.Yours, "1.7.6") as Gate.Ready
        assertTrue(ready.maintenance!!.contains("maintenance"))
        assertTrue((gateFor(config(maintenance = true, message = null), signedIn, Ownership.Yours, "1.7.6") as Gate.Ready).maintenance != null)
    }

    @Test
    fun `a message while maintenance is off is not shown`() {
        assertEquals(Gate.Ready(user(), null), gateFor(config(maintenance = false, message = "stale words"), signedIn, Ownership.Yours, "1.7.6"))
    }
}
