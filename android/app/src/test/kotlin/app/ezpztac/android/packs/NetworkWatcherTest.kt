package app.ezpztac.android.packs

import android.content.Context
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import app.ezpztac.missionpacks.DrainOutcome
import app.ezpztac.missionpacks.PackUser
import dagger.Lazy
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NetworkWatcherTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private class Packs : PackRuntime {
        var wakes = 0
        override fun wake() { wakes++ }
        override suspend fun enable(user: PackUser) = error("not the watcher's")
        override suspend fun disable() = error("not the watcher's")
        override fun start() = error("not the watcher's")
        override fun foreground(visible: Boolean) = error("not the watcher's")
        override suspend fun unsentCount(): Int = error("not the watcher's")
        override suspend fun drainAll(user: PackUser): DrainOutcome = error("not the watcher's")
    }

    @Test
    fun `a connection coming back wakes the packs, which are not built until then, and it listens once`() {
        val connectivity = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        val before = connectivity.networkCallbacks.toSet()                    // the app's own watcher, started with the process
        val packs = Packs()
        var built = 0
        val watcher = NetworkWatcher(context, Lazy { built++; packs })

        watcher.start()
        watcher.start()

        val added = connectivity.networkCallbacks - before
        assertEquals(1, added.size)
        assertEquals(0, built)
        added.single().onAvailable(ShadowNetwork.newInstance(7))
        added.single().onAvailable(ShadowNetwork.newInstance(8))
        assertEquals(2, packs.wakes)
    }
}
