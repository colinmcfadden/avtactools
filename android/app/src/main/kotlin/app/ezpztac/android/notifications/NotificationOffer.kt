package app.ezpztac.android.notifications

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect

/** The offer to turn notifications on, where one is made: whether it is [offered] now, and what its buttons do. */
class NotificationOffer(val offered: Boolean, val turnOn: () -> Unit, val notNow: () -> Unit)

/**
 * Asks for the system's notification permission (Android 13 and later; nothing is asked before) where the person sees what they get for it: the gate's
 * waiting screen, the switcher (docs/NOTIFICATIONS.md §4.5), never at launch. [offers] says whether to offer it (not given, not asked twice, not put away),
 * and is asked again as the app comes back (the person may have turned it on in the system's settings) and after the dialog answers. [onAllowed] is told
 * when the person allows it, so what waits is looked at at once.
 */
@Composable
fun rememberNotificationOffer(offers: () -> Boolean, onAsked: () -> Unit, onAllowed: () -> Unit, onNotNow: () -> Unit = {}): NotificationOffer {
    val asks by rememberUpdatedState(offers)
    var offered by remember { mutableStateOf(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && offers()) }
    val recheck = { offered = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && asks() }
    LifecycleResumeEffect(Unit) {
        recheck()
        onPauseOrDispose {}
    }
    val dialog = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        recheck()
        if (allowed) onAllowed()
    }
    return NotificationOffer(
        offered,
        turnOn = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                onAsked()
                dialog.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        notNow = {
            onNotNow()
            recheck()
        },
    )
}
