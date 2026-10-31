package app.ezpztac.auth

import app.ezpztac.network.ApiException
import app.ezpztac.network.NetworkException
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun before() = Dispatchers.setMain(dispatcher)

    @After
    fun after() = Dispatchers.resetMain()

    private val pass = "a secure flight password"

    private fun TestScope.model(api: FakeAuthApi = FakeAuthApi()) = AuthViewModel(api).also { advanceUntilIdle() } to api

    // -- Signing in ----------------------------------------------------------------------------------

    @Test
    fun `signing in sends the trimmed address and the password as typed`() = runTest(dispatcher) {
        val (vm, api) = model()
        vm.signIn("  pilot@example.com ", " $pass ")
        advanceUntilIdle()
        assertEquals(listOf("login pilot@example.com  $pass "), api.calls)                  // a password is never trimmed
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `a mistake on the device is caught before the server is asked`() = runTest(dispatcher) {
        val (vm, api) = model()
        vm.signIn("", pass)
        assertEquals("Enter your email address.", vm.state.value.error)
        vm.signIn("pilot@example.com", "")
        assertEquals("Enter your password.", vm.state.value.error)
        vm.signIn("nope", pass)
        assertEquals("Enter a valid email address.", vm.state.value.error)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), api.calls)
    }

    @Test
    fun `an old short password still goes to the server, which decides`() = runTest(dispatcher) {
        val (vm, api) = model()
        vm.signIn("pilot@example.com", "short")
        advanceUntilIdle()
        assertEquals(1, api.calls.size)
    }

    @Test
    fun `a refusal is shown in the server's words and the button comes back`() = runTest(dispatcher) {
        val (vm, api) = model()
        api.failure = ApiException(401, "invalid_credentials", "Invalid email or password.")
        vm.signIn("pilot@example.com", pass)
        advanceUntilIdle()
        assertEquals("Invalid email or password.", vm.state.value.error)
        assertFalse(vm.state.value.busy)
        vm.dismissError()
        assertNull(vm.state.value.error)
    }

    @Test
    fun `a refusal also carries what the server said, for a developer, the status and its code`() = runTest(dispatcher) {
        val (vm, api) = model()
        api.failure = ApiException(401, "invalid_credentials", "Invalid email or password.")
        vm.signIn("pilot@example.com", pass)
        advanceUntilIdle()
        assertEquals("HTTP 401 · invalid_credentials · \"Invalid email or password.\"", vm.state.value.detail)
    }

    @Test
    fun `no answer says what the network said, and an unexpected failure its own type`() = runTest(dispatcher) {
        val (vm, api) = model()
        api.failure = NetworkException("Unable to connect", java.net.ConnectException("Failed to connect to /127.0.0.1:5000"), requestMayHaveBeenSent = false)
        vm.signIn("pilot@example.com", pass)
        advanceUntilIdle()
        assertEquals("no answer: ConnectException: Failed to connect to /127.0.0.1:5000", vm.state.value.detail)
        api.failure = IllegalStateException("decoded nothing")
        vm.signIn("pilot@example.com", pass)
        advanceUntilIdle()
        assertEquals("IllegalStateException: decoded nothing", vm.state.value.detail)
        assertEquals("Unable to sign in. Check your credentials.", vm.state.value.error)           // the person's words are the fallback, as before
    }

    @Test
    fun `the detail goes with the error, so dismissing, moving on, a new try and a local refusal all clear it`() = runTest(dispatcher) {
        val (vm, api) = model()
        api.failure = ApiException(401, "invalid_credentials", "Invalid email or password.")
        vm.signIn("pilot@example.com", pass); advanceUntilIdle()
        vm.dismissError()
        assertNull(vm.state.value.detail)
        vm.signIn("pilot@example.com", pass); advanceUntilIdle()
        vm.open(AuthRoute.Register)
        assertNull(vm.state.value.detail)
        vm.open(AuthRoute.SignIn)
        vm.signIn("pilot@example.com", pass); advanceUntilIdle()
        vm.signIn("not an address", pass)                                                          // refused before anything is sent
        assertNull(vm.state.value.detail)
        assertNotNull(vm.state.value.error)
        api.failure = null
        vm.signIn("pilot@example.com", pass)
        assertNull(vm.state.value.detail)                                                          // a new try starts clean
    }

    @Test
    fun `no signal is explained, not blamed on the password`() = runTest(dispatcher) {
        val (vm, api) = model()
        api.failure = NetworkException("timeout", null, requestMayHaveBeenSent = false)
        vm.signIn("pilot@example.com", pass)
        advanceUntilIdle()
        assertTrue(vm.state.value.error!!.contains("no connection", ignoreCase = true))
    }

    @Test
    fun `a second tap while a request is out does nothing`() = runTest(dispatcher) {
        val (vm, api) = model()
        api.gate = CompletableDeferred()
        vm.signIn("pilot@example.com", pass)
        advanceUntilIdle()
        assertTrue(vm.state.value.busy)
        vm.signIn("pilot@example.com", pass)
        vm.signIn("pilot@example.com", pass)
        advanceUntilIdle()
        assertEquals(1, api.calls.size)
        api.gate!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `a Google sign-in that was dismissed is not an error, one that failed is`() = runTest(dispatcher) {
        val (vm, _) = model()
        vm.googleFailed(null)
        assertNull(vm.state.value.error)
        vm.googleFailed("Google sign-in could not be completed.")
        assertEquals("Google sign-in could not be completed.", vm.state.value.error)
    }

    @Test
    fun `a Google token goes to the server as it is`() = runTest(dispatcher) {
        val (vm, api) = model()
        vm.signInWithGoogle("id-token")
        advanceUntilIdle()
        assertEquals(listOf("google id-token"), api.calls)
    }

    // -- Registering ------------------------------------------------------------------------------------

    @Test
    fun `registering goes to check your inbox, with the address and a wait before another link`() = runTest(dispatcher) {
        val (vm, api) = model()
        vm.open(AuthRoute.Register)
        vm.register(" New Pilot ", " new@example.com ")
        runCurrent()                                                                          // the request, not the minute that follows it
        assertEquals(listOf("register New Pilot new@example.com"), api.calls)
        assertEquals(AuthRoute.CheckEmail("new@example.com"), vm.state.value.route)
        assertEquals("new@example.com", vm.state.value.email)
        assertEquals(AuthViewModel.RESEND_SECONDS, vm.state.value.resendCooldownSeconds)
    }

    @Test
    fun `a name and an address are both needed`() = runTest(dispatcher) {
        val (vm, api) = model()
        vm.register("", "new@example.com")
        assertEquals("Enter your name.", vm.state.value.error)
        vm.register("New Pilot", "")
        assertEquals("Enter your email address.", vm.state.value.error)
        advanceUntilIdle()
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `a failed registration stays on the form`() = runTest(dispatcher) {
        val (vm, api) = model()
        vm.open(AuthRoute.Register)
        api.failure = ApiException(400, null, "Enter a valid email address.")
        vm.register("New Pilot", "new@example.com")
        advanceUntilIdle()
        assertEquals(AuthRoute.Register, vm.state.value.route)
        assertEquals("Enter a valid email address.", vm.state.value.error)
    }

    // -- Resending -----------------------------------------------------------------------------------------

    @Test
    fun `a link cannot be asked for again until the wait is over, then it can`() = runTest(dispatcher) {
        val (vm, api) = model()
        vm.open(AuthRoute.CheckEmail("new@example.com"))
        vm.resend("new@example.com")
        runCurrent()
        assertEquals(1, api.calls.size)
        assertTrue(vm.state.value.notice!!.contains("on its way"))
        assertEquals(60, vm.state.value.resendCooldownSeconds)

        vm.resend("new@example.com")                                                         // too soon: nothing is sent
        runCurrent()
        assertEquals(1, api.calls.size)

        advanceTimeBy(30_000)                                                                // 29 ticks: the 30th is at exactly 30 s
        assertEquals(31, vm.state.value.resendCooldownSeconds)
        advanceTimeBy(30_001)
        assertEquals(0, vm.state.value.resendCooldownSeconds)
        vm.resend("new@example.com")
        advanceUntilIdle()
        assertEquals(2, api.calls.size)
    }

    // -- Verifying ----------------------------------------------------------------------------------------------

    @Test
    fun `following the verification link and choosing a password completes it`() = runTest(dispatcher) {
        val (vm, api) = model()
        vm.open(AuthRoute.Verify("tok"))
        vm.verify(pass, pass)
        advanceUntilIdle()
        assertEquals(listOf("verify tok $pass"), api.calls)
        assertTrue(vm.state.value.completed)
    }

    @Test
    fun `a weak or mismatched password is caught on the device`() = runTest(dispatcher) {
        val (vm, api) = model()
        vm.open(AuthRoute.Verify("tok"))
        vm.verify("short", "short")
        assertTrue(vm.state.value.error!!.contains("at least 15"))
        vm.verify(pass, "$pass!")
        assertEquals("The passwords do not match.", vm.state.value.error)
        advanceUntilIdle()
        assertTrue(api.calls.isEmpty())
        assertFalse(vm.state.value.completed)
    }

    @Test
    fun `a link that is no longer good says so, and is not marked complete`() = runTest(dispatcher) {
        val (vm, api) = model()
        vm.open(AuthRoute.Verify("old"))
        api.failure = ApiException(400, "invalid_token", "This verification link is invalid or has expired.")
        vm.verify(pass, pass)
        advanceUntilIdle()
        assertEquals("This verification link is invalid or has expired.", vm.state.value.error)
        assertFalse(vm.state.value.completed)
    }

    @Test
    fun `verifying with no verification link open does nothing`() = runTest(dispatcher) {
        val (vm, api) = model()
        vm.verify(pass, pass)
        advanceUntilIdle()
        assertTrue(api.calls.isEmpty())
    }

    // -- Forgetting and resetting -----------------------------------------------------------------------------------

    @Test
    fun `asking for a reset link says the same thing for every address`() = runTest(dispatcher) {
        val (vm, api) = model()
        vm.open(AuthRoute.Forgot)
        vm.forgot("pilot@example.com")
        advanceUntilIdle()
        assertEquals(listOf("forgot pilot@example.com"), api.calls)
        assertTrue(vm.state.value.resetRequested)
        assertEquals("pilot@example.com", vm.state.value.email)
    }

    @Test
    fun `resetting uses the link's token and the new password`() = runTest(dispatcher) {
        val (vm, api) = model()
        vm.open(AuthRoute.Reset("reset-tok"))
        vm.reset(pass, pass)
        advanceUntilIdle()
        assertEquals(listOf("reset reset-tok $pass"), api.calls)
        assertTrue(vm.state.value.completed)
    }

    @Test
    fun `an expired reset link offers no completion`() = runTest(dispatcher) {
        val (vm, api) = model()
        vm.open(AuthRoute.Reset("old"))
        api.failure = ApiException(400, "invalid_token", "This password reset link is invalid or has expired.")
        vm.reset(pass, pass)
        advanceUntilIdle()
        assertFalse(vm.state.value.completed)
        assertEquals("This password reset link is invalid or has expired.", vm.state.value.error)
    }

    // -- Moving around -----------------------------------------------------------------------------------------------------

    @Test
    fun `back goes to the sign-in from anywhere, and says when there is nowhere to go`() = runTest(dispatcher) {
        val (vm, _) = model()
        assertFalse(vm.back())
        vm.open(AuthRoute.Forgot)
        assertTrue(vm.back())
        assertEquals(AuthRoute.SignIn, vm.state.value.route)
    }

    @Test
    fun `moving to another screen clears what the last one said, and keeps the address`() = runTest(dispatcher) {
        val (vm, api) = model()
        api.failure = ApiException(401, null, "Invalid email or password.")
        vm.signIn("pilot@example.com", pass)
        advanceUntilIdle()
        vm.open(AuthRoute.Forgot)
        assertNull(vm.state.value.error)
        assertEquals("pilot@example.com", vm.state.value.email)
    }

    @Test
    fun `a message from outside is shown on the sign-in`() = runTest(dispatcher) {
        val (vm, _) = model()
        vm.notify("Your session expired. Sign in again to continue.")
        assertNotNull(vm.state.value.notice)
        vm.signIn("", "")
        assertNull(vm.state.value.notice)                                                    // the next thing the person does replaces it
    }

    @Test
    fun `a finished step does not stay finished when the person comes back to the screen`() = runTest(dispatcher) {
        val (vm, _) = model()
        vm.open(AuthRoute.Verify("tok"))
        vm.verify(pass, pass)
        advanceUntilIdle()
        assertTrue(vm.state.value.completed)
        vm.open(AuthRoute.SignIn)
        vm.open(AuthRoute.Verify("another"))
        assertFalse(vm.state.value.completed)
    }

    @Test
    fun `a screen that goes away while a request is out leaves no error behind`() = runTest(dispatcher) {
        val (vm, api) = model()
        api.gate = CompletableDeferred()
        vm.signIn("pilot@example.com", pass)
        advanceUntilIdle()
        assertTrue(vm.state.value.busy)
        vm.viewModelScope.coroutineContext.cancel()                                         // the screen was closed: not a failure to report
        advanceUntilIdle()
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.busy)
    }
}
