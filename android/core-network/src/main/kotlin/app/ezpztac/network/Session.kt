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
)

/** Where the session is kept. Calls are made one at a time by the client, never concurrently. */
public interface SessionStore {
    public suspend fun read(): StoredSession?
    public suspend fun write(session: StoredSession)
    public suspend fun clear()
}

/** A store that lives only as long as the process: for tests and previews. */
public class InMemorySessionStore(initial: StoredSession? = null) : SessionStore {
    private var session: StoredSession? = initial
    public var writes: Int = 0
        private set

    override suspend fun read(): StoredSession? = session
    override suspend fun write(session: StoredSession) { this.session = session; writes++ }
    override suspend fun clear() { session = null }
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
}
