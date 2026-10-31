package app.ezpztac.sync

import app.ezpztac.network.SyncChange
import kotlinx.coroutines.runBlocking

/** The shared scenarios against the in-memory server. */
internal class FakeEnv : Env {
    val server = FakeServer()
    override fun device(label: String): Device = Device(label, server)
    override suspend fun serverView(): List<SyncChange> = server.changes(0).changes
    override fun close() {}
}

internal class FakeSyncScenarios : ScenarioSuite() {
    override fun env(): Env = FakeEnv()
}

@Suppress("unused")
private fun keep() = runBlocking<Unit> { }
