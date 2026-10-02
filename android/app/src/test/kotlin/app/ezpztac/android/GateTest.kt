package app.ezpztac.android

import app.ezpztac.data.Ownership
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
