package app.ezpztac.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.ezpztac.missionpacks.FakePackEnv
import app.ezpztac.missionpacks.PackScenario
import app.ezpztac.missionpacks.PackScenarioBook
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every mission-pack scenario, run with each device's copy of its packs in a Room database of its own. core-missionpacks runs the same
 * ones with each copy in memory; that both pass is what says the two stores are interchangeable, restarts and offline spells included.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [36])
class RoomPackScenarioTest(private val name: String, private val scenario: PackScenario) {
    @Test
    fun scenario() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databases = mutableListOf<EzpzDatabase>()
        try {
            FakePackEnv(this, stores = { RoomPackStore(inMemoryDatabase(context).also { databases += it }) }).use { scenario.run(it) }
        } finally {
            databases.forEach { it.close() }
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun scenarios(): List<Array<Any>> = PackScenarioBook.all.map { arrayOf<Any>(it.name, it) }
    }
}
