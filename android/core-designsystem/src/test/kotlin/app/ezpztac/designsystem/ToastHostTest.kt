package app.ezpztac.designsystem

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Toasts: with their action, and without one while a brief is presented on the live sandtable (a toast's action opens a planning flow). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ToastHostTest {
    @get:Rule
    val compose = createComposeRule()

    private val state = SnackbarHostState()
    private var result: SnackbarResult? = null
    private lateinit var scope: CoroutineScope

    private fun host(actions: Boolean) {
        compose.setContent {
            scope = rememberCoroutineScope()
            EzpzTheme(ThemeMode.Dark) { ToastHost(state, actions = actions) }
        }
        compose.runOnIdle { scope.launch { result = state.showSnackbar("Saved \"LZ HAWK\"", actionLabel = "Open Library", withDismissAction = true) } }
        compose.waitForIdle()
    }

    @Test
    fun `a toast offers its action`() {
        host(actions = true)
        compose.onNodeWithText("Open Library").performClick()
        compose.waitForIdle()
        assertEquals(SnackbarResult.ActionPerformed, result)
    }

    @Test
    fun `without actions a toast says its words and can be closed, and offers nothing else`() {
        host(actions = false)
        compose.onNodeWithText("Saved \"LZ HAWK\"").assertIsDisplayed()
        compose.onNodeWithText("Open Library").assertDoesNotExist()
        compose.onNodeWithContentDescription("Dismiss", substring = true, ignoreCase = true).performClick()
        compose.waitForIdle()
        assertEquals(SnackbarResult.Dismissed, result)
    }
}
