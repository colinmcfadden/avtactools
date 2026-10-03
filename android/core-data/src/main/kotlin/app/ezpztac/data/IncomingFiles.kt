package app.ezpztac.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** A file another app handed to this one (Files, a mail, the share sheet), waiting for the person to say what to do with it. */
public sealed interface IncomingFile {
    public val id: Long
    public val name: String

    /** The file's bytes, read when it arrived: the permission to read it ends with the task that was given it, so it cannot be read later. */
    public class Received(override val id: Long, override val name: String, public val bytes: ByteArray) : IncomingFile

    /** A file that could not be read (gone, too large, a provider that would not answer); [reason] is in words for the person. */
    public class Unreadable(override val id: Long, override val name: String, public val reason: String) : IncomingFile
}

/**
 * What other apps have sent to this one and has not yet been dealt with. **Nothing here is imported**: a file from anywhere can be anything,
 * and a threat file added to a crew's picture, or a set of points saved and synced under their name, is something they chose. So a file waits
 * here, in memory only, until a screen offers it and the person accepts or declines.
 *
 * Bounded, because the bytes are held: at most [MAX_PENDING] entries and [MAX_TOTAL_BYTES] of content, so a sender that shares file after file
 * cannot grow the process without limit. [clear] runs at sign-out: a file handed to the app is not carried over to whoever signs in next, the same
 * rule as the threat picture and the shared exports.
 */
@Singleton
public class IncomingFiles @Inject constructor() {
    private val lock = Any()
    private var nextId = 1L
    private val _files = MutableStateFlow<List<IncomingFile>>(emptyList())

    /** In the order they arrived. The first is the one a screen offers. */
    public val files: StateFlow<List<IncomingFile>> = _files.asStateFlow()

    /** Holds [bytes]. False when the app is already holding as many as it will. */
    public fun offer(name: String, bytes: ByteArray): Boolean = synchronized(lock) {
        val held = _files.value
        if (held.size >= MAX_PENDING) return false
        val heldBytes = held.sumOf { (it as? IncomingFile.Received)?.bytes?.size?.toLong() ?: 0L }
        if (heldBytes + bytes.size > MAX_TOTAL_BYTES) return false
        _files.value = held + IncomingFile.Received(nextId++, name, bytes)
        true
    }

    /** Records that [name] could not be read, so the person is told rather than left wondering. False when the list is full. */
    public fun refuse(name: String, reason: String): Boolean = synchronized(lock) {
        val held = _files.value
        if (held.size >= MAX_PENDING) return false
        _files.value = held + IncomingFile.Unreadable(nextId++, name, reason)
        true
    }

    public fun dismiss(id: Long) {
        synchronized(lock) { _files.value = _files.value.filterNot { it.id == id } }
    }

    public fun clear() {
        synchronized(lock) { _files.value = emptyList() }
    }

    public companion object {
        public const val MAX_PENDING: Int = 8
        public const val MAX_TOTAL_BYTES: Long = 64L * 1024 * 1024
    }
}
