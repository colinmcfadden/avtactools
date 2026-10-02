package app.ezpztac.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.Scenario
import app.ezpztac.sync.ScenarioBook
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every sync scenario, run with each device's records in Room. core-sync runs the same ones against the in-memory store; that both
 * pass is what says the two stores are interchangeable.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [36])
class RoomScenarioTest(private val name: String, private val scenario: Scenario, private val kind: RecordKind) {
    @Test
    fun scenario() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        RoomEnv(context).use { scenario.run(it, kind) }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun scenarios(): List<Array<Any>> = ScenarioBook.all.flatMap { scenario ->
            scenario.kinds.map { kind -> arrayOf<Any>(if (scenario.perKind) "${scenario.name} [$kind]" else scenario.name, scenario, kind) }
        }
    }
}
