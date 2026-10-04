package app.ezpztac.android.incoming

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/**
 * What another app asked this one to open, taken from the intent it started it with: a VIEW of one file ("Open with" in Files or a mail) or a SEND of
 * one or several (the share sheet).
 *
 * **Any app on the phone can start the activity with any address**, so what is taken is the narrowest thing that is safe to read:
 * - only a `content://` address, which the sender must grant permission for. A `file://` address could name one of this app's own private files, and
 *   that is the classic way to make an app read its own data for someone else;
 * - never an address of this app's own provider (`ownAuthority`), whose files are what it shares out;
 * - at most [MAX_FILES], because each one is read into memory.
 * Everything not taken is counted, so the person is told rather than left wondering where the file went.
 */
internal object IncomingIntents {
    const val MAX_FILES = 5

    class Found(
        /** Addresses to read, in the order the sender gave them. */
        val uris: List<Uri>,
        /** Addresses that were refused (a file path, this app's own provider), by what the last part of each is called. */
        val refused: List<String>,
        /** More than [MAX_FILES] were sent; this many were left out. */
        val leftOut: Int,
    ) {
        val isEmpty: Boolean get() = uris.isEmpty() && refused.isEmpty() && leftOut == 0
    }

    fun find(intent: Intent?, ownAuthority: String): Found {
        val offered = when (intent?.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }.distinct()
        val taken = ArrayList<Uri>()
        val refused = ArrayList<String>()
        var leftOut = 0
        for (uri in offered) {
            when {
                // An email's link is also a VIEW, with an https address: that is the auth flow's, not a file.
                uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true) -> {}
                !uri.scheme.equals("content", ignoreCase = true) -> refused += label(uri)
                uri.authority.isNullOrBlank() || uri.authority.equals(ownAuthority, ignoreCase = true) -> refused += label(uri)
                taken.size >= MAX_FILES -> leftOut++
                else -> taken += uri
            }
        }
        return Found(taken, refused, leftOut)
    }

    /** What to call an address in words: the last part of its path, cut short (it is the sender's text), or a plain name. */
    fun label(uri: Uri): String = uri.lastPathSegment?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_LABEL) ?: "a file"

    private const val MAX_LABEL = 80
}
