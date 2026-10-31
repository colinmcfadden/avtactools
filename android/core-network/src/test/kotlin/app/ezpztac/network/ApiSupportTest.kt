package app.ezpztac.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ApiSupportTest {
    private val one = ApiSupport(listOf(1))

    @Test
    fun `a server serving a major this build talks is supported`() {
        assertEquals(ApiStanding.SUPPORTED, one.standingWith(listOf(1)))
        assertEquals(ApiStanding.SUPPORTED, one.standingWith(listOf(1, 2)))
        assertEquals(ApiStanding.SUPPORTED, ApiSupport(listOf(1, 2)).standingWith(listOf(2)))
    }

    @Test
    fun `a server past every major this build talks needs the app updated`() {
        assertEquals(ApiStanding.APP_TOO_OLD, one.standingWith(listOf(2)))
        // A server that kept an older major than this build's, and moved past it too, still needs the app updated.
        assertEquals(ApiStanding.APP_TOO_OLD, ApiSupport(listOf(2)).standingWith(listOf(1, 3)))
    }

    @Test
    fun `a server behind every major this build talks needs the server updated`() {
        assertEquals(ApiStanding.SERVER_TOO_OLD, ApiSupport(listOf(2)).standingWith(listOf(1)))
    }

    @Test
    fun `nothing known holds nothing back`() {
        assertEquals(ApiStanding.SUPPORTED, one.standingWith(null))
        assertEquals(ApiStanding.SUPPORTED, one.standingWith(emptyList()))
    }

    @Test
    fun `the build's list is read as it is written`() {
        assertEquals(setOf(1, 2), ApiSupport.parse(" 2, 1 ").majors)
        assertThrows<IllegalArgumentException> { ApiSupport.parse("") }
        assertThrows<IllegalArgumentException> { ApiSupport(listOf(0)) }
    }

    @Test
    fun `the majors a config serves`() {
        val config = AppConfig(
            configVersion = 1, serverVersion = "2.0.0", minAppVersion = AppConfig.MinAppVersion(),
            maintenance = AppConfig.Maintenance(active = false), services = AppConfig.Services(lidarBuilds = false, packs = false),
            mapbox = AppConfig.MapboxConfig(),
        )
        assertEquals(listOf(1), config.servedApiMajors)                                          // a server from before the fields
        assertEquals(listOf(3), config.copy(apiVersion = "3.1.0").servedApiMajors)
        assertEquals(listOf(2, 3), config.copy(apiVersion = "3.1.0", apiMajors = listOf(2, 3)).servedApiMajors)
        assertNull(config.copy(apiVersion = "three").servedApiMajors)                            // unreadable: nothing known
    }
}
