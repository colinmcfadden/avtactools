package app.ezpztac.missionpacks

import app.ezpztac.network.InviteAccepted
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Where an invitation link the app was opened with stands ([InviteAcceptance]). */
public sealed interface InviteState {
    /** No link, or the person put the last answer away. */
    public data object None : InviteState

    /** A link, but Mission Packs are not on for this account: it is kept in case they are turned on. */
    public data class Waiting(val message: String) : InviteState

    /** Being accepted. */
    public data object Accepting : InviteState

    /** Accepted: [answer] is what the person joined, and [message] tells them. */
    public data class Joined(val answer: InviteAccepted, val message: String) : InviteState

    /** Not accepted: [message] says why, in the app's words; [retryable] when it may pass (no connection, the server busy or down). */
    public data class Failed(val message: String, val retryable: Boolean) : InviteState
}

/**
 * Accepts the invitation link the app was opened with, once the person can open packs: the web's `useInviteLink.js`, without React.
 * The caller keeps the link (the web keeps it for the tab; the app across sign-in) and runs [run] once the person is signed in and past
 * the `.mil` gate, and again whenever whether they have Mission Packs changes or they ask to try again.
 *
 * One request per link, however many times it is run meanwhile: a second accept of the same link would be refused as already used.
 * The request runs in [scope], so a caller that goes away does not end it, and a run after it is told what it came to.
 *
 * It is for one account at a time, as the web's hook is (it goes with the screen at sign-out): a run for another account, or [forget],
 * starts from nothing, and an answer that comes once its account has gone is never said. An answer names a pack and a role, which are
 * nobody else's to see, and a link one account used is asked about again for another, as that account, never answered from the first's.
 *
 * **Not like the web**, by the owner's decisions for edits (paused, never dropped, for what is about the account): a refusal about the
 * account rather than the link ([PackFailure.pauses]: signed out, someone else signed in now, the `.mil` gate, Mission Packs off) keeps
 * the link and says it waits, where the web drops it with words about the server.
 */
public class InviteAcceptance(
    private val accept: suspend (token: String, account: Int) -> InviteAccepted,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow<InviteState>(InviteState.None)
    private val mutableAttempts = MutableStateFlow(0)

    /** Where the link stands, for the screens. */
    public val state: StateFlow<InviteState> = mutableState.asStateFlow()

    /**
     * How many times a link has been tried: one more for every run that has one. A state flow says nothing when the same answer comes
     * twice in a row (a retry that failed as the last one did), so a screen that tells each answer keys on this too.
     */
    public val attempts: StateFlow<Int> = mutableAttempts.asStateFlow()

    // The account the requests and answers are for, and its requests, under one lock.
    private val lock = Any()
    private var account: Int? = null
    private val asked = HashMap<String, CompletableDeferred<InviteAccepted>>()

    /**
     * Accepts [token] for [account] when [enabled] (the account has Mission Packs), or says it waits. Returns true when the link is done
     * with and the caller forgets it: accepted, or refused for good. A link that could not be sent, or met a busy or broken server, is
     * kept for a retry, as it may still be good, and so is one refused for the account's sake, which waits for an account that can take
     * it. With no link it does nothing, as the web's does: the last answer stays until [dismiss], so a run after the caller forgot the
     * link (the feature turned on or off meanwhile) never takes it away before the person has seen it.
     */
    public suspend fun run(token: String?, enabled: Boolean, account: Int): Boolean {
        synchronized(lock) { if (this.account != account) startOver(account) }
        if (token == null) return false
        mutableAttempts.update { it + 1 }
        if (!enabled) {
            say(account, InviteState.Waiting(WAITING))
            return false
        }
        say(account, InviteState.Accepting)
        return try {
            val answer = acceptOnce(token, account).await()
            say(account, InviteState.Joined(answer, PackMessages.joinedMessage(answer)))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure = PackFailure.of(e)
            if (failure.pauses) {
                // Asked before retryable: a call never sent because someone else is signed in now is both, and about the account.
                say(account, InviteState.Waiting(if (failure.code == FEATURE_DISABLED) WAITING else WAITING_FOR_ACCOUNT))
                false
            } else {
                say(account, InviteState.Failed(PackMessages.words(failure), failure.retryable))
                !failure.retryable
            }
        }
    }

    /** Puts the answer away. Only this does, a run with a link, or another account: one without leaves it. */
    public fun dismiss() {
        mutableState.value = InviteState.None
    }

    /** Forgets the account and all of its (a sign-out): what was said is put away, and a request still out goes on, its answer unsaid. */
    public fun forget() {
        synchronized(lock) { startOver(null) }
    }

    // Under the lock.
    private fun startOver(account: Int?) {
        this.account = account
        asked.clear()
        mutableState.value = InviteState.None
    }

    // Only while [account] is still the one: an answer that comes after a sign-out, or after another account's run, is nobody's to see.
    private fun say(account: Int, state: InviteState) {
        synchronized(lock) { if (this.account == account) mutableState.value = state }
    }

    // The request for [token]: the one under way or answered, or a new one, listed before it starts so every run shares it. An answer
    // stays with its token (a second accept of the link would be refused as used, so the first answer is the one to give); a failure is
    // forgotten once it comes, so the next run asks again.
    private fun acceptOnce(token: String, account: Int): CompletableDeferred<InviteAccepted> {
        val (answer, first) = synchronized(lock) {
            val known = asked[token]
            if (known != null) known to false else CompletableDeferred<InviteAccepted>().also { asked[token] = it } to true
        }
        if (first) {
            scope.launch {
                try {
                    answer.complete(accept(token, account))
                } catch (e: Throwable) {
                    synchronized(lock) { asked.remove(token, answer) }
                    answer.completeExceptionally(e)
                }
            }
        }
        return answer
    }

    private companion object {
        const val FEATURE_DISABLED = "feature_disabled"
        const val WAITING = "Mission Packs are not turned on for your account yet, so the invitation is waiting."
        const val WAITING_FOR_ACCOUNT = "The invitation is waiting until your account can open Mission Packs."
    }
}
