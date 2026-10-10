package app.ezpztac.android

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The oldest version of the app the server last said it supports (`/api/config`'s `minAppVersion.android`), kept on the device. While this
 * version is below it nothing is sent, mission packs or the library's sync (the owner's decision, 2026-10-10): an app the server no longer
 * supports must not go on writing to shared packs behind the "Update required" screen, where nobody can see what it does. What waits stays on
 * the device and goes once the app is updated. Kept so that a sync WorkManager runs in a process the app did not start, which never asks for
 * the config, obeys it too, and so does a launch before the config has come. A seam, so the shell and the sync are tried without Android.
 */
interface MinimumVersion {
    /** What the server last said, or null: none set, or never heard. */
    fun remembered(): String?

    fun remember(minimum: String?)
}

/** The minimum in a plain preference: it is the server's public config, nothing about the person. Excluded from backup with the rest. */
@Singleton
class MinimumVersionPreferences @Inject constructor(@ApplicationContext context: Context) : MinimumVersion {
    private val prefs = context.getSharedPreferences("server", Context.MODE_PRIVATE)

    override fun remembered(): String? = prefs.getString(MINIMUM, null)

    override fun remember(minimum: String?) {
        if (minimum == remembered()) return
        prefs.edit { if (minimum == null) remove(MINIMUM) else putString(MINIMUM, minimum) }
    }

    private companion object {
        const val MINIMUM = "minAppVersionAndroid"
    }
}
