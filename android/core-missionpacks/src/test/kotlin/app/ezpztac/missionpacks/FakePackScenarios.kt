package app.ezpztac.missionpacks

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory

/** Every pack scenario ([PackScenarioBook]) against [FakePackServer], each device's copy in memory, one test each. */
class FakePackScenarios {
    @TestFactory
    fun scenarios(): List<DynamicTest> = PackScenarioBook.all.map { scenario ->
        dynamicTest(scenario.name) {
            runBlocking<Unit> { FakePackEnv(this).use { scenario.run(it) } }
        }
    }
}
