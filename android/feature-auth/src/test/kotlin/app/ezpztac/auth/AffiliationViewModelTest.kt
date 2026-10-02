package app.ezpztac.auth

import app.ezpztac.network.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AffiliationViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun before() = Dispatchers.setMain(dispatcher)

    @After
    fun after() = Dispatchers.resetMain()

    private fun model() = FakeAuthApi().let { it to AffiliationViewModel(it) }

    @Test
    fun `it starts by asking for a mil address`() {
        val (_, vm) = model()
        assertEquals(AffiliationStep.Email, vm.state.value.step)
    }

    @Test
    fun `an address already pending goes straight to the code, as the web does`() {
        val (_, vm) = model()
        vm.startWith("name@army.mil")
        assertEquals(AffiliationStep.Code, vm.state.value.step)
        assertEquals("name@army.mil", vm.state.value.email)
        vm.startWith("other@army.mil")                                                       // starting again changes nothing
        assertEquals("name@army.mil", vm.state.value.email)
    }

    @Test
    fun `nothing pending leaves it at the address`() {
        val (_, vm) = model()
        vm.startWith(null)
        vm.startWith("  ")
        assertEquals(AffiliationStep.Email, vm.state.value.step)
    }

    @Test
    fun `an address that is not military is refused on the device`() = runTest(dispatcher) {
        val (api, vm) = model()
        vm.sendCode("pilot@example.com")
        advanceUntilIdle()
        assertEquals("Enter a valid .mil email address.", vm.state.value.error)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `a code is sent to the trimmed address and the code step says where it went`() = runTest(dispatcher) {
        val (api, vm) = model()
        vm.sendCode("  name@army.mil ")
        advanceUntilIdle()
        assertEquals(listOf("milRequest name@army.mil"), api.calls)
        assertEquals(AffiliationStep.Code, vm.state.value.step)
        assertEquals("name@army.mil", vm.state.value.email)
        assertEquals("A code was sent. Check your .mil inbox.", vm.state.value.notice)
    }

    @Test
    fun `a refusal from the server keeps the person where they were`() = runTest(dispatcher) {
        val (api, vm) = model()
        api.failure = ApiException(409, null, "That .mil address is already verified on another account.")
        vm.sendCode("name@army.mil")
        advanceUntilIdle()
        assertEquals(AffiliationStep.Email, vm.state.value.step)
        assertEquals("That .mil address is already verified on another account.", vm.state.value.error)
    }

    @Test
    fun `a code is sent in capitals, as the server's alphabet is`() = runTest(dispatcher) {
        val (api, vm) = model()
        vm.verify("  abcd2345 ")
        advanceUntilIdle()
        assertEquals(listOf("milVerify ABCD2345"), api.calls)
    }

    @Test
    fun `no code is no request`() = runTest(dispatcher) {
        val (api, vm) = model()
        vm.verify("   ")
        advanceUntilIdle()
        assertEquals("Enter the code from the email.", vm.state.value.error)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `a wrong code says so and can be tried again`() = runTest(dispatcher) {
        val (api, vm) = model()
        api.failure = ApiException(400, "invalid_code", "That code is invalid or has expired.")
        vm.verify("WRONG123")
        advanceUntilIdle()
        assertEquals("That code is invalid or has expired.", vm.state.value.error)
        assertFalse(vm.state.value.busy)
        api.failure = null
        vm.verify("RIGHT234")
        advanceUntilIdle()
        assertNull(vm.state.value.error)
    }

    @Test
    fun `resending goes to the same address, and a different address starts over`() = runTest(dispatcher) {
        val (api, vm) = model()
        vm.sendCode("name@army.mil")
        advanceUntilIdle()
        vm.resend()
        advanceUntilIdle()
        assertEquals(listOf("milRequest name@army.mil", "milRequest name@army.mil"), api.calls)
        vm.useDifferentAddress()
        assertEquals(AffiliationStep.Email, vm.state.value.step)
        assertNull(vm.state.value.notice)
    }

    @Test
    fun `resending with no address does nothing`() = runTest(dispatcher) {
        val (api, vm) = model()
        vm.resend()
        advanceUntilIdle()
        assertTrue(api.calls.isEmpty())
    }
}
