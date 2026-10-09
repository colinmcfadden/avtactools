package app.ezpztac.missionpacks

import app.ezpztac.network.NetworkException
import app.ezpztac.network.PackLiveConnection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.ContinuationInterceptor

/**
 * The pack scenarios' world on [FakePackServer], with each device's copy in a store [stores] makes (in memory here; Room in core-data).
 * Everything, the devices' engines included, runs on the thread of [scope] (a `runBlocking`'s), one thing at a time, as the fake
 * server is not made for more.
 */
public class FakePackEnv(
    scope: CoroutineScope,
    private val stores: () -> PackStore = { InMemoryPackStore() },
    private val timing: PackTiming = PackScenarioBook.TIMING,
) : PackEnv {
    public val server: FakePackServer = FakePackServer()

    private val dispatcher = scope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
    private val devices = CoroutineScope(SupervisorJob() + dispatcher)
    private var people = 0
    private var packs = 0

    override suspend fun person(name: String): PackPerson {
        val id = ++people
        server.person(id, name)
        return PackPerson(PackUser(id, name), "person$id@example.com")
    }

    override suspend fun device(label: String, person: PackPerson): PackDevice {
        val network = Network()
        return PackDevice(label, person, DeviceApi(server, network), network, stores(), devices, dispatcher, timing)
    }

    override suspend fun createPack(owner: PackPerson, name: String): String = "p-${++packs}".also { server.createPack(it, name, owner.user.id) }

    override suspend fun addMember(pack: String, owner: PackPerson, member: PackPerson, role: String) {
        server.addMember(pack, member.user.id, role, owner.user.id)
    }

    override suspend fun setRole(pack: String, owner: PackPerson, member: PackPerson, role: String) {
        server.setRole(pack, member.user.id, role, owner.user.id)
    }

    override suspend fun removeMember(pack: String, owner: PackPerson, member: PackPerson) {
        server.remove(pack, member.user.id, owner.user.id)
    }

    override suspend fun finish(pack: String, owner: PackPerson) {
        server.finish(pack, owner.user.id)
    }

    override suspend fun send(pack: String, by: PackPerson, vararg ops: JsonObject) {
        server.elsewhere(pack, by.user.id, *ops)
    }

    override suspend fun log(pack: String, reader: PackPerson): List<JsonObject> = server.log(pack)

    override suspend fun data(pack: String, reader: PackPerson, item: String): JsonElement? = server.data(pack, item)

    override fun pageSize(size: Int) {
        server.pageSize = size
    }

    override fun limitItemSize(bytes: Int) {
        server.maxItemBytes = bytes
    }

    override fun close() {
        devices.cancel()
    }

    // The fake server as one device reaches it, through its own connection.
    private class DeviceApi(private val server: FakePackServer, private val network: Network) : PackApi {
        override suspend fun getPack(uuid: String, asUser: Int): JsonObject {
            reach()
            return server.getPack(uuid, asUser)
        }

        override suspend fun events(uuid: String, since: Long, limit: Int, background: Boolean, asUser: Int): JsonObject {
            reach()
            return server.events(uuid, since, limit, background, asUser)
        }

        override suspend fun sendOps(uuid: String, batch: JsonObject, asUser: Int): JsonObject {
            reach()
            val answer = server.sendOps(uuid, batch, asUser)
            network.takeHold()?.let { hold ->
                hold.await()
                throw NetworkException("The answer was held, then lost.", requestMayHaveBeenSent = true)
            }
            if (network.losesAnswer()) throw NetworkException("The answer was lost.", requestMayHaveBeenSent = true)
            return answer
        }

        override suspend fun openLive(liveUrl: String, uuid: String, scope: CoroutineScope, asUser: Int): PackLiveConnection? = null

        private fun reach() {
            if (network.offline) throw NetworkException("No connection.", requestMayHaveBeenSent = false)
        }
    }
}
