package app.ezpztac.missionpacks

import app.ezpztac.network.LiveMessage
import app.ezpztac.network.PackLiveConnection
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A pack's live stream whose service side the test plays: it says what the service would ([welcome], [event], [head], [resync],
 * [people]) and closes it with any code ([drop]). Like the real one, it ends with one [LiveMessage.Closed], whoever ended it, and
 * sends no presence before the welcome or over 1 KB. [FakePackServer] opens one per [PackApi.openLive] and passes its events to
 * those it has welcomed.
 */
public class FakeLive(public val pack: String, public val url: String, public val user: Int) : PackLiveConnection {
    private val channel = Channel<LiveMessage>(Channel.UNLIMITED)

    override val messages: Flow<LiveMessage> = channel.receiveAsFlow()

    /** Whether the service has said welcome. */
    public var welcomed: Boolean = false
        private set

    /** Whether it is still open. */
    public var open: Boolean = true
        private set

    /** The code the client closed it with, if it was the client. */
    public var closedWith: Int? = null
        private set

    /** Every focus the client sent, in order. */
    public val presenceSent: MutableList<JsonObject?> = mutableListOf()

    override fun presence(focus: JsonObject?): Boolean {
        if (!welcomed || !open) return false
        if (focus != null && focus.toString().length > MAX_FOCUS) return false
        presenceSent += focus
        return true
    }

    override fun close(code: Int) {
        if (!open) return
        closedWith = code
        end(LiveMessage.Closed(code, null))
    }

    public fun welcome(headSeq: Long, role: String? = "editor", status: String? = "active") {
        welcomed = true
        say(LiveMessage.Welcome(headSeq, role, status, JsonObject(mapOf("session" to JsonPrimitive("s-$user"), "user_id" to JsonPrimitive(user)))))
    }

    public fun event(seq: Long, event: JsonObject) {
        say(LiveMessage.Event(seq, event))
    }

    public fun head(seq: Long) {
        say(LiveMessage.Head(seq))
    }

    public fun resync() {
        say(LiveMessage.Resync)
    }

    public fun people(people: List<JsonObject>) {
        say(LiveMessage.Presence(people))
    }

    /** The service closed it, or the connection dropped (1006). */
    public fun drop(code: Int, reason: String? = null) {
        end(LiveMessage.Closed(code, reason))
    }

    private fun say(message: LiveMessage) {
        if (open) channel.trySend(message)
    }

    private fun end(closed: LiveMessage.Closed) {
        if (!open) return
        open = false
        channel.trySend(closed)
        channel.close()
    }

    private companion object {
        const val MAX_FOCUS = 1024
    }
}
