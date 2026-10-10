package app.ezpztac.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AppVersionTest {
    private fun v(text: String) = AppVersion.parse(text)!!

    @Test
    fun `versions compare by number, not as text`() {
        assertTrue(v("1.10.0") > v("1.9.9"))
        assertTrue(v("2.0.0") > v("1.99.99"))
        assertTrue(v("1.4.10") > v("1.4.9"))
        assertEquals(v("1.4.0"), v("1.4.0"))
    }

    @Test
    fun `a pre-release is below its own release and above the one before`() {
        assertTrue(v("1.4.0-beta.2") < v("1.4.0"))
        assertTrue(v("1.4.0-beta.2") > v("1.3.9"))
        assertTrue(v("1.4.0-beta.2") > v("1.4.0-beta.1"))
    }

    @Test
    fun `what is not a version is not one`() {
        for (bad in listOf("", "1", "1.2", "1.2.3.4", "v1.2.3", "1.2.x", "1.2.3-", "99999.0.0", null)) assertNull(AppVersion.parse(bad), bad)
        assertEquals("1.4.0-beta.2", v(" 1.4.0-beta.2 ").toString())
    }

    private fun config(android: String?, ios: String?) = AppConfig(
        1, "1.0.0", AppConfig.MinAppVersion(android, ios), AppConfig.Maintenance(false), AppConfig.Services(false, false), AppConfig.MapboxConfig(null),
    )

    @Test
    fun `an app below the minimum for its platform needs an update`() {
        val c = config(android = "1.4.0", ios = "2.0.0")
        assertTrue(c.updateRequired("android", "1.3.9"))
        assertFalse(c.updateRequired("android", "1.4.0"))
        assertFalse(c.updateRequired("android", "1.4.1"))
        assertTrue(c.updateRequired("android", "1.4.0-beta.1"))      // a pre-release of the minimum is still below it
        assertTrue(c.updateRequired("ios", "1.9.9"))
        assertFalse(c.updateRequired("ios", "2.0.0"))
    }

    @Test
    fun `no minimum, or one that cannot be read, never locks anyone out`() {
        assertFalse(config(null, null).updateRequired("android", "0.0.1"))
        assertFalse(config("garbage", "garbage").updateRequired("android", "0.0.1"))
        assertFalse(config("1.4.0", null).updateRequired("android", "not a version"))
        assertFalse(config("1.4.0", "1.4.0").updateRequired("web", "0.0.1"))
    }

    @Test
    fun `a minimum as the device remembers it is read the same way`() {
        assertTrue(isBelowMinimum("1.3.9", "1.4.0"))
        assertTrue(isBelowMinimum("1.4.0-beta.1", "1.4.0"))
        assertFalse(isBelowMinimum("1.4.0", "1.4.0"))
        assertFalse(isBelowMinimum("1.3.9", null))
        assertFalse(isBelowMinimum("1.3.9", "garbage"))
        assertFalse(isBelowMinimum("not a version", "1.4.0"))
    }
}

class ClientInfoTest {
    @Test
    fun `the header is what the server's pattern expects`() {
        assertEquals("android/1.4.0 (212)", ClientInfo.android("1.4.0", 212).header)
        assertEquals("android/1.4.0", ClientInfo.android("1.4.0").header)
        assertEquals("ios/2.0.0-beta.3 (9)", ClientInfo("ios", "2.0.0-beta.3", 9).header)
        assertEquals("X-EZPZ-Client", ClientInfo.HEADER)
    }

    @Test
    fun `a value the server would ignore is refused here`() {
        for (bad in listOf(
            Triple("windows", "1.0.0", 1), Triple("android", "1.0", 1), Triple("android", "1.0.0.0", 1),
            Triple("android", "1.0.0", -1), Triple("android", "1.0.0-" + "x".repeat(21), 1), Triple("Android", "1.0.0", 1),
            Triple("android", "1.0.0\n", 1),
        )) {
            assertThrows<IllegalArgumentException>(bad.toString()) { ClientInfo(bad.first, bad.second, bad.third) }
        }
    }
}
