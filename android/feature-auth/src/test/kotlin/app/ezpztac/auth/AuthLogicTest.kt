package app.ezpztac.auth

import app.ezpztac.network.ApiException
import app.ezpztac.network.NetworkException
import app.ezpztac.network.RateLimitedException
import app.ezpztac.network.SessionEndedException
import app.ezpztac.network.SignedOutReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AuthLogicTest {
    // -- Links -----------------------------------------------------------------------------------

    @Test
    fun `the links in the server's emails open the right screen with their token`() {
        assertEquals(AuthRoute.Verify("abc123"), AuthLinks.parse("https://ezpztac.app/?auth=verify&token=abc123"))
        assertEquals(AuthRoute.Reset("xyz"), AuthLinks.parse("https://ezpztac.app/?token=xyz&auth=reset"))
        assertEquals(AuthRoute.Verify("a b+c"), AuthLinks.parse("https://ezpztac.app/?auth=verify&token=a%20b%2Bc"))   // percent-encoded
        assertEquals(AuthRoute.Verify("first"), AuthLinks.parse("https://ezpztac.app/?auth=verify&token=first&token=second&auth=reset"))   // the first of each, as the web's get
    }

    @Test
    fun `a link with no token opens the screen that says it is incomplete`() {
        assertEquals(AuthRoute.Verify(""), AuthLinks.parse("https://ezpztac.app/?auth=verify"))
        assertEquals(AuthRoute.Reset(""), AuthLinks.parse("https://ezpztac.app/?auth=reset&token="))
    }

    @Test
    fun `anything else is not for the sign-in screens`() {
        assertNull(AuthLinks.parse(null))
        assertNull(AuthLinks.parse(""))
        assertNull(AuthLinks.parse("https://ezpztac.app/"))
        assertNull(AuthLinks.parse("https://ezpztac.app/?auth=login&token=x"))              // only the two links the server sends
        assertNull(AuthLinks.parse("https://ezpztac.app/?token=x"))
        assertNull(AuthLinks.parse("not a url at all %%%"))
        assertNull(AuthLinks.parse("https://ezpztac.app/r/abc"))                            // a route share link is another feature's
    }

    @Test
    fun `only https links to the site itself count, whatever app sent them`() {
        assertNull(AuthLinks.parse("https://evil.example/?auth=reset&token=attackers"))
        assertNull(AuthLinks.parse("http://ezpztac.app/?auth=reset&token=x"))                       // not encrypted
        assertNull(AuthLinks.parse("https://ezpztac.app.evil.example/?auth=reset&token=x"))         // a host that only starts like ours
        assertNull(AuthLinks.parse("https://user@evil.example/?auth=reset&token=x"))
        assertNull(AuthLinks.parse("ezpz://reset?auth=reset&token=x"))
        assertEquals(AuthRoute.Reset("x"), AuthLinks.parse("HTTPS://EZPZTAC.APP/?auth=reset&token=x"))
        assertEquals(AuthRoute.Reset("x"), AuthLinks.parse("https://www.ezpztac.app/?auth=reset&token=x"))
        assertEquals(AuthRoute.Reset("x"), AuthLinks.parse("https://staging.example/?auth=reset&token=x", hosts = setOf("staging.example")))   // a deployment that links elsewhere
    }

    @Test
    fun `a link with a broken escape is not for us, and does not crash`() {
        assertNull(AuthLinks.parse("https://ezpztac.app/?auth=verify&token=%E0%A4%A"))
    }

    // -- Validation ---------------------------------------------------------------------------------

    @Test
    fun `an email address is checked for its shape, not for being real`() {
        assertNull(AuthValidation.emailError("pilot@example.com"))
        assertNull(AuthValidation.emailError("  pilot@example.com  "))
        assertNotNull(AuthValidation.emailError(""))
        assertNotNull(AuthValidation.emailError("pilot"))
        assertNotNull(AuthValidation.emailError("pilot@example"))
        assertNotNull(AuthValidation.emailError("pi lot@example.com"))
        assertNotNull(AuthValidation.emailError("a@" + "b".repeat(120) + ".com"))
    }

    @Test
    fun `a mil address must end in mil, in any case, and any mil address will do`() {
        assertNull(AuthValidation.milEmailError("name@army.mil"))
        assertNull(AuthValidation.milEmailError("name@us.army.mil"))
        assertNull(AuthValidation.milEmailError("name@MAIL.MIL"))
        assertNotNull(AuthValidation.milEmailError("name@army.com"))
        assertNotNull(AuthValidation.milEmailError("name@milk.com"))
        assertNotNull(AuthValidation.milEmailError(""))
    }

    @Test
    fun `a new password is a passphrase of fifteen to a hundred and twenty-eight characters`() {
        assertNotNull(AuthValidation.newPasswordError("a".repeat(14)))
        assertNull(AuthValidation.newPasswordError("a".repeat(15)))
        assertNull(AuthValidation.newPasswordError("a".repeat(128)))
        assertNotNull(AuthValidation.newPasswordError("a".repeat(129)))
        assertNull(AuthValidation.newPasswordError("correct horse battery staple"))
    }

    @Test
    fun `the confirmation must match exactly`() {
        assertNull(AuthValidation.confirmationError("same passphrase here", "same passphrase here"))
        assertNotNull(AuthValidation.confirmationError("same passphrase here", "Same passphrase here"))
        assertNotNull(AuthValidation.confirmationError("same passphrase here", ""))
    }

    @Test
    fun `a name is required and not too long`() {
        assertNull(AuthValidation.nameError("Test Pilot"))
        assertNotNull(AuthValidation.nameError("   "))
        assertNotNull(AuthValidation.nameError("x".repeat(121)))
    }

    @Test
    fun `an address is masked enough to recognise but not to read over a shoulder`() {
        assertEquals("pi•••@example.com", AuthValidation.maskEmail("pilot@example.com"))
        assertEquals("ab•••@x.mil", AuthValidation.maskEmail("ab@x.mil"))
        assertEquals("pi" + "•".repeat(13) + "@example.com", AuthValidation.maskEmail("pilot.long.name@example.com"))
        assertEquals("your email address", AuthValidation.maskEmail(""))
        assertEquals("your email address", AuthValidation.maskEmail("nonsense"))
    }

    // -- What the person is told -----------------------------------------------------------------------

    @Test
    fun `the server's own words are used, and a missing connection is explained`() {
        assertEquals("Invalid email or password.", describe(ApiException(401, "invalid_credentials", "Invalid email or password."), "fallback"))
        assertEquals(
            "There is no connection to the server. Check your signal and try again.",
            describe(NetworkException("timeout", null, requestMayHaveBeenSent = true), "fallback"),
        )
        assertEquals("fallback", describe(ApiException(500, null, ""), "fallback"))
        assertEquals("fallback", describe(IllegalStateException("boom"), "fallback"))
    }

    @Test
    fun `a rate limit says how long to wait, in a person's units`() {
        fun wait(seconds: Long?) = describe(RateLimitedException("x", seconds), "f")
        assertEquals("Too many attempts. Try again later.", wait(null))
        assertEquals("Too many attempts. Try again in a minute.", wait(30))
        assertEquals("Too many attempts. Try again in about 10 minutes.", wait(600))
        assertEquals("Too many attempts. Try again in about 2 minutes.", wait(91))
        assertEquals("Too many attempts. Try again in about 1 hours.", wait(3600))
        assertEquals("Too many attempts. Try again in about 2 hours.", wait(7200))
    }

    @Test
    fun `an ended session is not shown as a server error`() {
        assertEquals("Your session has ended. Sign in again.", describe(SessionEndedException(SignedOutReason.SESSION_ENDED, "x", "raw words"), "f"))
    }
}
