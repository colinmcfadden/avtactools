package app.ezpztac.android

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The oldest version of the app the server last said it supports (`/api/config`'s `minAppVersion.android`), kept on the device. While this
 * version is below it nothing is sent (the owner's decision, 2026-10-10): no mission pack, no invitation accepted, no library sync. An app
 * the server no longer supports must not go on writing to shared packs behind the "Update required" screen, where nobody can see what it does.
 * What waits stays on the device and goes once the app is updated.
 *
 * Every look at the config is remembered here first (the shell's, at launch and each time the app comes to the front, and the background
 * sync's before it sends), and [remembered] is what they all go by: so a raised minimum one of them hears stops the others in the same
 * process at once, and a launch with no signal obeys the last one heard. A seam, so the shell and the sync are tried without Android.
 */
interface MinimumVersion {
    /** What the server last said, or null: none set, or never heard. */
    val remembered: StateFlow<String?>

    fun remember(minimum: String?)
}

/** The minimum in a plain preference: it is the server's public config, nothing about the person. Excluded from backup with the rest. */
@Singleton
class MinimumVersionPreferences @Inject constructor(@ApplicationContext context: Context) : MinimumVersion {
    private val prefs = context.getSharedPreferences("server", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(prefs.getString(MINIMUM, null))

    override val remembered: StateFlow<String?> = state.asStateFlow()

    @Synchronized
    override fun remember(minimum: String?) {
        if (minimum == state.value) return
        prefs.edit { if (minimum == null) remove(MINIMUM) else putString(MINIMUM, minimum) }
        state.value = minimum
    }

    private companion object {
        const val MINIMUM = "minAppVersionAndroid"
    }
}
