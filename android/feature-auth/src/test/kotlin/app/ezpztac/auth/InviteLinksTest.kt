package app.ezpztac.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Invitation links (the web's `inviteLink.test.js` cases, and the site checks [AuthLinks] makes). */
class InviteLinksTest {
    private val token = "Xq3v_8yQm2LZk9-WbT4sPa7Rr1Nd5Cf6Hg0Jj2Kk3Ll"

    @Test
    fun `an invitation link gives its token`() {
        assertEquals(token, InviteLinks.parse("https://ezpztac.app/?invite=$token"))
        assertEquals(token, InviteLinks.parse("https://www.ezpztac.app/?invite=$token&view=sat#map"))           // other parameters and a fragment
        assertEquals(token, InviteLinks.parse("https://ezpztac.app/?view=sat&invite=$token"))
        assertEquals(token, InviteLinks.parse("https://ezpztac.app/?invite=$token&invite=Zz9_another-token-0001"))   // the first, as on the web
        assertEquals(token, InviteLinks.parse("https://ezpztac.app/?&&invite=$token&"))                              // empty pieces are nothing
    }

    @Test
    fun `the first invite is the one read, even when it is empty or has no value, as the web's searchParams reads it`() {
        assertNull(InviteLinks.parse("https://ezpztac.app/?invite&invite=$token"))
        assertNull(InviteLinks.parse("https://ezpztac.app/?invite=&invite=$token"))
    }

    @Test
    fun `only a token of the server's shape is kept, and anything else is dropped unsent`() {
        assertNull(InviteLinks.parse("https://ezpztac.app/?invite=<script>"))
        assertNull(InviteLinks.parse("https://ezpztac.app/?invite=%3Cscript%3E"))
        assertNull(InviteLinks.parse("https://ezpztac.app/?invite=" + "a".repeat(15)))                           // shorter than any the server makes
        assertNull(InviteLinks.parse("https://ezpztac.app/?invite=" + "a".repeat(201)))                          // longer than it takes
        assertNull(InviteLinks.parse("https://ezpztac.app/?invite="))
        assertNull(InviteLinks.parse("https://ezpztac.app/?invite=$token%20"))                                    // a space is not in a token
        assertNull(InviteLinks.parse("https://ezpztac.app/?invite=$token.x"))
        assertEquals("a".repeat(16), InviteLinks.parse("https://ezpztac.app/?invite=" + "a".repeat(16)))
        assertEquals("a".repeat(200), InviteLinks.parse("https://ezpztac.app/?invite=" + "a".repeat(200)))
    }

    @Test
    fun `only https links to the site itself count, whatever app sent them`() {
        assertNull(InviteLinks.parse("http://ezpztac.app/?invite=$token"))                                       // not encrypted
        assertNull(InviteLinks.parse("https://evil.example/?invite=$token"))
        assertNull(InviteLinks.parse("https://ezpztac.app.evil.example/?invite=$token"))                         // a host that only starts like ours
        assertNull(InviteLinks.parse("https://ezpztac.app@evil.example/?invite=$token"))
        assertNull(InviteLinks.parse("ezpz://open?invite=$token"))
        assertEquals(token, InviteLinks.parse("HTTPS://EZPZTAC.APP/?invite=$token"))
        assertEquals(token, InviteLinks.parse("https://staging.example/?invite=$token", hosts = setOf("staging.example")))
    }

    @Test
    fun `a sign-in link is not an invitation, and an invitation is not for the sign-in screens`() {
        assertNull(InviteLinks.parse("https://ezpztac.app/?auth=verify&token=$token"))
        assertNull(AuthLinks.parse("https://ezpztac.app/?invite=$token"))
        assertNull(InviteLinks.parse(null))
        assertNull(InviteLinks.parse(""))
        assertNull(InviteLinks.parse("https://ezpztac.app/"))
        assertNull(InviteLinks.parse("not a url at all %%%"))
    }

    @Test
    fun `a link with a broken escape is not an invitation, and does not crash`() {
        assertNull(InviteLinks.parse("https://ezpztac.app/?invite=%E0%A4%A"))
    }
}
