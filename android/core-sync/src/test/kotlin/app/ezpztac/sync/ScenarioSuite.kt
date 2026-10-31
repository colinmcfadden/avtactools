package app.ezpztac.sync

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory

/** Runs every scenario in [ScenarioBook] (for each kind of record it applies to) against [env], one test each. */
internal abstract class ScenarioSuite {
    abstract fun env(): Env

    @TestFactory
    fun scenarios(): List<DynamicTest> = ScenarioBook.all.flatMap { scenario ->
        scenario.kinds.map { kind ->
            dynamicTest(if (scenario.perKind) "${scenario.name} [$kind]" else scenario.name) {
                runBlocking<Unit> { env().use { scenario.run(it, kind) } }
            }
        }
    }
}
