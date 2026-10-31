package app.ezpztac.android.notifications

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** A notification shown on this device: its id, and what a tap on it opens, as it was shown (never as a tap's intent says, which any app can make up). */
data class Shown(val id: Long, val type: String, val pack: String?)

/**
 * What this device keeps about notifications, for each account: those it has shown (so none is shown twice here, and a tap opens what was shown), the last
 * feed's tag (so a poll that finds nothing new is a 304), and whether it saw the account held at the gate (so it can say when access is approved).
 * Forgotten at sign-out.
 */
interface NotificationBook {
    fun shown(user: Int): Set<Long>

    /** The one shown with [id], or null: never shown here. */
    fun entry(user: Int, id: Long): Shown?

    fun remember(user: Int, shown: Collection<Shown>)

    fun etag(user: Int): String?

    fun keepEtag(user: Int, etag: String?)

    fun sawGated(user: Int): Boolean

    fun markGated(user: Int, gated: Boolean)

    fun forget()

    /** How often the system's permission dialog has been asked for on this device: after two it shows no more. */
    fun asks(): Int

    fun noteAsked()

    /** The person said *Not now* to the switcher's offer, for this account. */
    fun saidNotNow(user: Int): Boolean

    fun notNow(user: Int)
}

/** [NotificationBook] in plain preferences: ids, a tag and flags, nothing about a pack or a place. Excluded from backup with the rest. */
@Singleton
class NotificationPreferences @Inject constructor(@ApplicationContext context: Context) : NotificationBook {
    private val prefs = context.getSharedPreferences("notifications", Context.MODE_PRIVATE)

    // Kept apart, and through a sign-out: the system's own count of refusals is the device's, and a Not now is the person's choice.
    private val asks = context.getSharedPreferences("notification_asks", Context.MODE_PRIVATE)

    @Synchronized
    override fun shown(user: Int): Set<Long> = entries(user).map { it.id }.toSet()

    @Synchronized
    override fun entry(user: Int, id: Long): Shown? = entries(user).lastOrNull { it.id == id }

    @Synchronized
    override fun remember(user: Int, shown: Collection<Shown>) {
        if (shown.isEmpty()) return
        val ids = shown.map { it.id }.toSet()
        val kept = (entries(user).filter { it.id !in ids } + shown).takeLast(MAX_SHOWN)
        prefs.edit { putString("u$user.shown", kept.joinToString(",") { "${it.id}/${it.type}/${it.pack.orEmpty()}" }) }
    }

    override fun etag(user: Int): String? = prefs.getString("u$user.etag", null)

    override fun keepEtag(user: Int, etag: String?) = prefs.edit { if (etag == null) remove("u$user.etag") else putString("u$user.etag", etag) }

    override fun sawGated(user: Int): Boolean = prefs.getBoolean("u$user.gated", false)

    override fun markGated(user: Int, gated: Boolean) = prefs.edit { if (gated) putBoolean("u$user.gated", true) else remove("u$user.gated") }

    @Synchronized
    override fun forget() = prefs.edit { clear() }

    override fun asks(): Int = asks.getInt("asks", 0)

    override fun noteAsked() = asks.edit { putInt("asks", asks() + 1) }

    override fun saidNotNow(user: Int): Boolean = asks.getBoolean("u$user.notnow", false)

    override fun notNow(user: Int) = asks.edit { putBoolean("u$user.notnow", true) }

    // Each "id/type/pack": a type is a word, a pack a uuid, so neither holds a slash or a comma; anything else is passed over.
    private fun entries(user: Int): List<Shown> = prefs.getString("u$user.shown", null)?.split(',')?.mapNotNull { entry ->
        val parts = entry.split('/')
        val id = parts.getOrNull(0)?.toLongOrNull()
        if (parts.size != 3 || id == null || parts[1].isEmpty()) null else Shown(id, parts[1], parts[2].ifEmpty { null })
    }.orEmpty()

    private companion object {
        /** A few a week, so this is months of them; the server keeps 30 days. */
        const val MAX_SHOWN = 200
    }
}
