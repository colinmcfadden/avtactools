package app.ezpztac.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/*
 * A mission pack's live stream (docs/MISSION_PACKS.md §6; the service is backend/realtime/service.py): everyone else's changes to
 * the pack as they are made, and who else has it open. It is only ever a reason to fetch sooner. Every event on it is also in
 * `GET …/events`, which a client falls back on whenever the stream is down, so this has to be prompt and never wrong, not reliable.
 *
 * Over OkHttp's own WebSocket, on the connections every call uses, with a ping every 25 s ([ApiClient.liveHttp]).
 */

/** What the live service says, as a client acts on it. */
public sealed interface LiveMessage {
    /**
     * Access granted. [headSeq] is the pack's newest event: fetch from your own `seq` if it is ahead. [role] and [status] are what
     * the API said as it let the socket in, and [you] is this socket as everyone's [Presence] lists it.
     */
    public data class Welcome(val headSeq: Long, val role: String?, val status: String?, val you: JsonObject?) : LiveMessage

    /** One event, exactly as `GET …/events` gives it. */
    public data class Event(val seq: Long, val event: JsonObject) : LiveMessage

    /** Event [seq] exists but was too large to carry: fetch it. */
    public data class Head(val seq: Long) : LiveMessage

    /** Announcements may have been lost: fetch from your `seq`. */
    public data object Resync : LiveMessage

    /** Who has the pack open, this socket included: `{session, user_id, name, focus}` each. */
    public data class Presence(val people: List<JsonObject>) : LiveMessage

    /**
     * The stream is over, whoever ended it: always the last message, and only one. [code] is the service's close code (§6), or one
     * of these from this side: 1006 the connection dropped, never opened, or stopped answering pings; 1013 the collector fell 1,000
     * messages behind (the service closes a socket that far behind with the same); 4401 with [SIGNED_OUT] the account it was opened
     * for is no longer the one signed in here; or whatever [PackLiveConnection.close] was given (1000 when the scope it was opened in ended).
     */
    public data class Closed(val code: Int, val reason: String?) : LiveMessage {
        /** 4404 or 4410: the pack is not this person's to see any more, or it was deleted. Stop following it, as after a 404 from the API. */
        public val packGone: Boolean get() = code == 4404 || code == 4410

        /**
         * Whether to open the stream again later, with growing pauses, polling meanwhile. After every close but these, as the web does:
         * - the pack gone;
         * - 4400, a bad hello, which trying again would repeat;
         * - 4403, which the service sends when the API's access check answers 403. That route gives 403 only about the account
         *   (`feature_disabled`, `affiliation_required`), never about the pack, so ask the API instead: its own code says to pause.
         *   (The web takes 4403 as the pack gone, which would drop a tester's queued edits when an admin unticks the feature.)
         * - 4401 [SIGNED_OUT]: the account is no longer the one signed in here, and only its own next sign-in brings the stream back.
         *
         * A token the service refused (its 4401) is worth another try: the next poll refreshes it if it really lapsed, and the next
         * socket takes whatever is stored then. So are a hello that took over 10 s on a stalled link (4408), a busy service or a slow
         * collector (1013), a frame too large (1009) and a dropped connection.
         */
        public val reconnect: Boolean get() = !packGone && code != 4400 && code != 4403 && !(code == 4401 && reason == SIGNED_OUT)

        public companion object {
            /** The reason this side gives when the stream's account is no longer the one signed in; the service never sends it. */
            public const val SIGNED_OUT: String = "signed_out"
        }
    }
}

/** One pack's live stream, open. */
public interface PackLiveConnection {
    /**
     * What the service says, in order, ending with one [LiveMessage.Closed]. Meant for one collector. Frames for another pack, the
     * `closed` notice (the close frame after it says the same) and types this version does not know are passed over. At most 1,000
     * wait here to be collected: one more ends the stream (1013), and the collector catches up from the API as after any close.
     */
    public val messages: Flow<LiveMessage>

    /**
     * Tells everyone else what this person has open ([focus]; null for nothing). Nothing is sent, and the answer is false, before
     * the welcome, after the end, and for a focus over 1 KB, which the service would drop without a word. Sending no more than every
     * 100 ms is the caller's part: the service passes presence on no faster.
     */
    public fun presence(focus: JsonObject?): Boolean

    /** Closes the stream; [messages] then ends with [LiveMessage.Closed] of [code]. */
    public fun close(code: Int = 1000)
}

/**
 * Opens [packUuid]'s live stream at [liveUrl] (the pack's `live_url`) for [asUser], who must be the person signed in now. Null, with
 * nothing opened, when nobody is signed in or someone else is, when the address is not `ws://` or `wss://`, or when the id is not a
 * pack's.
 *
 * The hello, with the access token stored now, is the first frame. Every token the session gets after it is passed on, because
 * the service checks access again with the last one it was given (every 5 minutes, and whenever the pack's members change). The
 * stream belongs to the account it was opened for: when that account is no longer the one signed in here, the stream ends (4401,
 * [LiveMessage.Closed.SIGNED_OUT]), and nobody else's token ever goes on it. It closes when [scope] ends.
 */
public suspend fun ApiClient.openPackLive(
    liveUrl: String,
    packUuid: String,
    scope: CoroutineScope,
    asUser: Int,
): PackLiveConnection? {
    try {
        PackPaths.pack(packUuid)
    } catch (_: IllegalArgumentException) {
        return null
    }
    val url = liveHttpUrl(liveUrl) ?: return null
    val access = storedAccessToken() ?: return null
    if (access.userId != asUser) return null

    val live = PackLiveSocket(packUuid, access.userId)
    live.socket = newWebSocket(Request.Builder().url(url).build(), live)
    // OkHttp keeps what is sent before the socket opens and writes it first, so the hello is the first frame whatever comes next.
    live.socket.send(PackLiveSocket.frame("hello") { put("pack", packUuid); put("token", access.token) })
    live.follow(this, scope, access.token)
    return live
}

/** OkHttp takes `ws://` and `wss://` as `http://` and `https://`; nothing else is a live stream's address. */
private fun liveHttpUrl(liveUrl: String): HttpUrl? {
    val http = when {
        liveUrl.startsWith("ws://", ignoreCase = true) -> "http://" + liveUrl.substring(5)
        liveUrl.startsWith("wss://", ignoreCase = true) -> "https://" + liveUrl.substring(6)
        else -> return null
    }
    return http.toHttpUrlOrNull()
}

/** One open stream: OkHttp's listener on one side, the collector on the other. OkHttp calls it from its own threads, one call at a time. */
internal class PackLiveSocket(private val pack: String, private val userId: Int) : WebSocketListener(), PackLiveConnection {
    lateinit var socket: WebSocket

    private val lock = Any()

    // Guarded by lock. [waiting] counts what was handed over and not yet collected; the end keeps a place of its own in the channel,
    // so it is never refused for want of room and nothing can follow it.
    private var ended = false
    private var waiting = 0
    private var tokens: Job? = null

    @Volatile private var welcomed = false

    private val channel = Channel<LiveMessage>(MAX_BUFFERED + 1)

    override val messages: Flow<LiveMessage> =
        channel.receiveAsFlow().onEach { if (it !is LiveMessage.Closed) synchronized(lock) { waiting-- } }

    override fun presence(focus: JsonObject?): Boolean {
        if (!welcomed || synchronized(lock) { ended }) return false
        if (focus != null && serviceLength(focus) > MAX_FOCUS) return false
        return socket.send(frame("presence") { put("focus", focus ?: JsonNull) })
    }

    override fun close(code: Int) {
        socket.close(code, null)
        end(LiveMessage.Closed(code, null))
    }

    /** Passes on every token the session gets after [sent] while it is [userId]'s; once it is nobody's, or someone else's, the stream ends. */
    fun follow(client: ApiClient, scope: CoroutineScope, sent: String) {
        val job = scope.launch {
            var last = sent
            client.accessTokens.collect {
                // An announcement says the session changed; what it is now is read from the store, which a refresh writes before it
                // announces, so a token older than the one stored never goes out.
                val now = client.storedAccessToken()
                when {
                    now == null || now.userId != userId -> if (end(LiveMessage.Closed(4401, LiveMessage.Closed.SIGNED_OUT))) socket.close(1000, null)
                    now.token != last -> if (socket.send(frame("token") { put("token", now.token) })) last = now.token
                }
            }
        }
        // The stream lives no longer than the scope it was opened in.
        job.invokeOnCompletion { if (end(LiveMessage.Closed(1000, null))) socket.close(1000, null) }
        val endedAlready = synchronized(lock) {
            tokens = job
            ended
        }
        if (endedAlready) job.cancel()
    }

    // -- OkHttp's side ------------------------------------------------------------------

    override fun onMessage(webSocket: WebSocket, text: String) {
        val message = read(text, pack) ?: return
        // Before it is handed over: the collector answers a welcome with its presence at once.
        if (message is LiveMessage.Welcome) welcomed = true
        if (!offer(message) && end(LiveMessage.Closed(1013, "too_slow"))) webSocket.close(1000, null)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString): Unit = Unit                  // the service sends text only

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        end(LiveMessage.Closed(code, reason.ifEmpty { null }))
        webSocket.close(1000, null)                                                                 // the close handshake is ours to finish
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        end(LiveMessage.Closed(code, reason.ifEmpty { null }))
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        end(LiveMessage.Closed(1006, null))
    }

    /** Hands [message] to the collector: false when the stream has ended, or when 1,000 are already waiting. */
    private fun offer(message: LiveMessage): Boolean {
        synchronized(lock) {
            if (ended || waiting >= MAX_BUFFERED) return false
            waiting++
            return channel.trySend(message).isSuccess
        }
    }

    /** Ends the stream with [closed]: false, and nothing done, if it had already ended. */
    private fun end(closed: LiveMessage.Closed): Boolean {
        val job = synchronized(lock) {
            if (ended) return false
            ended = true
            channel.trySend(closed)
            channel.close()
            tokens
        }
        job?.cancel()
        return true
    }

    internal companion object {
        /** As many as the service keeps waiting for one socket before it closes it as too slow (MAX_QUEUE). */
        const val MAX_BUFFERED: Int = 1000

        /** The largest focus the service passes on (MAX_FOCUS), measured as it measures it ([serviceLength]). */
        const val MAX_FOCUS: Int = 1024

        fun frame(type: String, fields: JsonObjectBuilder.() -> Unit): String = buildJsonObject { put("type", type); fields() }.toString()

        /** One frame from the service, or null for one to pass over: unreadable, for another pack, the `closed` notice, or a type this version does not know. */
        fun read(text: String, pack: String): LiveMessage? {
            val frame = runCatching { ApiJson.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
            if (frame.text("pack") != pack) return null
            return when (frame.text("type")) {
                "welcome" -> frame.whole("head_seq")?.let { LiveMessage.Welcome(it, frame.text("role"), frame.text("status"), frame["you"] as? JsonObject) }
                "event" -> {
                    val seq = frame.whole("seq")
                    val event = frame["event"] as? JsonObject
                    if (seq != null && event != null) LiveMessage.Event(seq, event) else null
                }
                "head" -> frame.whole("seq")?.let { LiveMessage.Head(it) }
                "resync" -> LiveMessage.Resync
                "presence" -> (frame["people"] as? JsonArray)?.let { people -> LiveMessage.Presence(people.filterIsInstance<JsonObject>()) }
                else -> null
            }
        }

        /**
         * How long [focus] is as the service measures it: Python's `json.dumps` with no spaces, which writes every character outside
         * printable ASCII as a six-character `\uXXXX` (a character beyond the BMP as two). kotlinx writes the same text except for
         * those, and escapes control characters as Python does. A number is taken as written, where Python may write a float a
         * character or two differently; a focus is a few short fields, nowhere near the limit.
         */
        fun serviceLength(focus: JsonObject): Int = focus.toString().sumOf { c -> if (c.code < 0x7F) 1 else 6 }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.whole(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    }
}
