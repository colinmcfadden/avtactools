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
 *
 * The API majors the server serves ([rememberedApi], `/api/config`'s `apiMajors`) are kept beside it for the same reason: a server that
 * serves none of this build's ([holdsBack]) is sent nothing either, whichever of the two is behind.
 */
interface MinimumVersion {
    /** What the server last said, or null: none set, or never heard. */
    val remembered: StateFlow<String?>

    /** The API majors the server last said it serves, or null: never heard. */
    val rememberedApi: StateFlow<List<Int>?>

    fun remember(minimum: String?, apiMajors: List<Int>?)
}

/** The minimum in a plain preference: it is the server's public config, nothing about the person. Excluded from backup with the rest. */
@Singleton
class MinimumVersionPreferences @Inject constructor(@ApplicationContext context: Context) : MinimumVersion {
    private val prefs = context.getSharedPreferences("server", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(prefs.getString(MINIMUM, null))
    private val api = MutableStateFlow(majorsOf(prefs.getString(API_MAJORS, null)))

    override val remembered: StateFlow<String?> = state.asStateFlow()
    override val rememberedApi: StateFlow<List<Int>?> = api.asStateFlow()

    @Synchronized
    override fun remember(minimum: String?, apiMajors: List<Int>?) {
        if (minimum == state.value && apiMajors == api.value) return
        prefs.edit {
            if (minimum == null) remove(MINIMUM) else putString(MINIMUM, minimum)
            if (apiMajors == null) remove(API_MAJORS) else putString(API_MAJORS, apiMajors.joinToString(","))
        }
        state.value = minimum
        api.value = apiMajors
    }

    private companion object {
        const val MINIMUM = "minAppVersionAndroid"
        const val API_MAJORS = "apiMajorsServed"

        /** What was kept, or null for nothing or anything this cannot read (which must hold nothing back). */
        fun majorsOf(text: String?): List<Int>? = text?.split(',')?.map { it.trim().toIntOrNull() ?: return null }?.takeIf { it.isNotEmpty() }
    }
}
