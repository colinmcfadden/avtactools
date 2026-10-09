package app.ezpztac.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 * What is kept between launches so the user stays signed in: the access token, the refresh token
 * (native apps only) and the user it belongs to. The app stores this encrypted (Android Keystore
 * with DataStore); nothing here assumes how.
 */
@Serializable
public data class StoredSession(
    val accessToken: String,
    /** Spent on use: every refresh replaces it, and the replacement must be stored before it is relied on. */
    val refreshToken: String?,
    /** When the refresh token stops working (epoch seconds), from the server's `refresh_expires_in`. */
    val refreshExpiresAtEpochSeconds: Long?,
    val user: ApiUser,
    /**
     * When the server last vouched for this account (epoch seconds): a sign-in, a refresh, or an answer to "who is this". A device that
     * has not heard from the server for [OfflineGrace.DAYS] has to sign in again, so an approval an admin withdrew cannot be used for ever.
     */
    val verifiedAtEpochSeconds: Long? = null,
)

/** An access token and the account it is for, so it is never passed on for anyone else ([ApiClient.accessTokens]). */
internal data class AccessToken(val userId: Int, val token: String) {
    override fun toString(): String = "AccessToken(user $userId)"                      // never the token itself, in a log or a failed test
}

/**
 * How long a device may go without the server confirming the account before it must sign in again: 14 days, as the owner decided
 * (docs/NATIVE_APPS_PLAN.md, "Risks and decisions"). It is the one place access that was withdrawn is noticed by a device that never
 * goes online; everything it saved stays on the device, only the session ends.
 */
public object OfflineGrace {
    public const val DAYS: Long = 14

    /**
     * Whether [session] is past the grace period at [nowEpochSeconds]. A session with no stamp is not: it is stamped at the next answer,
     * and locking someone out over a missing note would be worse than waiting for it.
     */
    public fun expired(session: StoredSession, nowEpochSeconds: Long): Boolean {
        val verified = session.verifiedAtEpochSeconds ?: return false
        return nowEpochSeconds - verified > DAYS * 24 * 60 * 60
    }
}

/**
 * Kept in place of a session the server ended (or [OfflineGrace] did), with the [code] it gave, so that a launch in a later process can
 * still say why the person has to sign in again: the session may be ended by a sync WorkManager ran with the app closed.
 */
@Serializable
public data class EndedSession(val code: String? = null)

/** Where the session is kept. Calls are made one at a time by the client, never concurrently. */
public interface SessionStore {
    public suspend fun read(): StoredSession?

    /** Keeps [session] in place of whatever was kept, a note of an ended session included. */
    public suspend fun write(session: StoredSession)

    /** Forgets the session and any note: the person signed out, and there is nothing to tell them at the next launch. */
    public suspend fun clear()

    /** Forgets the session and keeps [ended] in its place. */
    public suspend fun end(ended: EndedSession)

    /** The note [end] kept, until a session is written or the store is cleared. */
    public suspend fun ended(): EndedSession?
}

/** A store that lives only as long as the process: for tests and previews. */
public class InMemorySessionStore(initial: StoredSession? = null) : SessionStore {
    private var session: StoredSession? = initial
    private var note: EndedSession? = null
    public var writes: Int = 0
        private set

    override suspend fun read(): StoredSession? = session
    override suspend fun write(session: StoredSession) { this.session = session; note = null; writes++ }
    override suspend fun clear() { session = null; note = null }
    override suspend fun end(ended: EndedSession) { session = null; note = ended }
    override suspend fun ended(): EndedSession? = note
}

/** Why there is no session. */
public enum class SignedOutReason {
    /** Nobody has signed in on this device yet, or they signed out. */
    NOT_SIGNED_IN,

    /** The server ended the session (a password reset, a sign-out from another device, a suspension, 180 days). */
    SESSION_ENDED,
}

public sealed interface AuthState {
    /** The store has not been read yet. */
    public data object Unknown : AuthState

    public data class SignedOut(val reason: SignedOutReason, val code: String? = null) : AuthState

    public data class SignedIn(val user: ApiUser) : AuthState
}

/** Where the session state is announced. */
internal class AuthStateHolder {
    private val flow = MutableStateFlow<AuthState>(AuthState.Unknown)
    val state: StateFlow<AuthState> = flow.asStateFlow()
    fun set(state: AuthState) { flow.value = state }

    /** Sets [state] only if nothing has been set yet, and returns what is set now. */
    fun setIfUnknown(state: AuthState): AuthState = if (flow.compareAndSet(AuthState.Unknown, state)) state else flow.value
}
