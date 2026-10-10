package app.ezpztac.android.packs

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells mission packs the moment a connection is back, so edits made with no signal go then rather than at the next timed retry (up to
 * 30 s), and a pack that could not be loaded is tried again. Started once, with the process.
 *
 * The packs are reached lazily: the first callback (made on the platform's own thread, at once if a network is up) builds the engine and
 * its database, so starting the app does not.
 */
@Singleton
class NetworkWatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val packs: Lazy<PackRuntime>,
) {
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
        try {
            connectivity.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = packs.get().wake()
                },
            )
        } catch (_: RuntimeException) {
            // Refused (the platform limits callbacks per app): packs still try again on their own timers, only later.
        }
    }
}
