package app.ezpztac.android

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the app draws with the night palette (red on near black, the map dimmed) rather than the dark one. The crew's choice, never the
 * system's: the right brightness depends on the aircraft (docs/NATIVE_APPS_PLAN.md, "Night mode"). A seam, so a screen is tried without Android.
 */
interface DisplaySettings {
    val night: StateFlow<Boolean>

    fun setNight(on: Boolean)
}

/** [DisplaySettings] in a plain preference: it is about this device's screen, not the person, so it stays when someone else signs in. */
@Singleton
class DisplayPreferences @Inject constructor(@ApplicationContext context: Context) : DisplaySettings {
    private val prefs = context.getSharedPreferences("display", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(prefs.getBoolean(NIGHT, false))

    override val night: StateFlow<Boolean> = state.asStateFlow()

    @Synchronized
    override fun setNight(on: Boolean) {
        if (on == state.value) return
        prefs.edit { putBoolean(NIGHT, on) }
        state.value = on
    }

    private companion object {
        const val NIGHT = "night"
    }
}

/** What the account menu reads and changes. */
@HiltViewModel
class DisplayViewModel @Inject constructor(private val settings: DisplaySettings) : ViewModel() {
    val night: StateFlow<Boolean> = settings.night

    fun setNight(on: Boolean) = settings.setNight(on)
}
