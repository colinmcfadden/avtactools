package app.ezpztac.missionpacks

import app.ezpztac.network.ApiClient
import app.ezpztac.network.PackLiveConnection
import app.ezpztac.network.openPackLive
import app.ezpztac.network.packDocument
import app.ezpztac.network.packEventsDocument
import app.ezpztac.network.sendPackOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject

/**
 * What the pack client asks the server, and nothing else: the pack whole, a page of its log, a batch of edits, and the live
 * stream. The answers are the server's JSON as it wrote it, never typed and written out again: a session keeps nulls and fields a
 * newer server adds. A refusal is the exception core-network raises, which [PackFailure.of] reads.
 *
 * Every call names the account it is made for ([asUser]): under anyone else's session it is refused before anything is sent, so
 * edits queued on this device never go up as someone else.
 */
public interface PackApi {
    /** `GET /api/packs/<uuid>`: the pack's items as of its `head_seq`, its members and its `live_url`. */
    public suspend fun getPack(uuid: String, asUser: Int): JsonObject

    /**
     * `GET /api/packs/<uuid>/events?since=&limit=`: `{events, cursor, has_more, head_seq}`. A fetch nobody is waiting on (a poll, a
     * drain) passes [background], so it waits behind an analysis on the server's one interpreter.
     */
    public suspend fun events(uuid: String, since: Long, limit: Int, background: Boolean, asUser: Int): JsonObject

    /** `POST /api/packs/<uuid>/ops` with `{ops, base_seq}`: `{head_seq, results, events, has_more}`. */
    public suspend fun sendOps(uuid: String, batch: JsonObject, asUser: Int): JsonObject

    /** The pack's live stream at [liveUrl], open until [scope] ends or it is closed; null when it cannot be opened for [asUser]. */
    public suspend fun openLive(liveUrl: String, uuid: String, scope: CoroutineScope, asUser: Int): PackLiveConnection?
}

/** [PackApi] over the app's [ApiClient]: the engine's three untyped calls and the live stream (core-network's PackEndpoints, PackLive). */
public class ApiPackApi(private val client: ApiClient) : PackApi {
    override suspend fun getPack(uuid: String, asUser: Int): JsonObject = client.packDocument(uuid, asUser)

    override suspend fun events(uuid: String, since: Long, limit: Int, background: Boolean, asUser: Int): JsonObject =
        client.packEventsDocument(uuid, since, limit, background, asUser)

    override suspend fun sendOps(uuid: String, batch: JsonObject, asUser: Int): JsonObject = client.sendPackOps(uuid, batch, asUser)

    override suspend fun openLive(liveUrl: String, uuid: String, scope: CoroutineScope, asUser: Int): PackLiveConnection? =
        client.openPackLive(liveUrl, uuid, scope, asUser)
}
