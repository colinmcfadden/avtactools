package app.ezpztac.data

import app.ezpztac.network.ApiClient
import app.ezpztac.network.DocumentText
import app.ezpztac.network.documentPdf
import app.ezpztac.network.documentTextFile
import app.ezpztac.network.packDocumentPdf
import app.ezpztac.network.packDocumentTextFile
import app.ezpztac.network.readDocumentText
import app.ezpztac.sync.FileHash
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Where a document's files are asked for: a Library record's routes, or a pack item's (any member may read a pack's). */
public sealed interface DocumentAddress {
    public data class Library(val serverId: Int) : DocumentAddress

    public data class Pack(val pack: String, val item: String) : DocumentAddress
}

/** The server's side of [DocumentFiles]: a document's PDF and its page text file, each as the bytes its hash names. */
public interface DocumentFileSource {
    public suspend fun pdf(at: DocumentAddress): ByteArray

    public suspend fun text(at: DocumentAddress): ByteArray
}

/** [DocumentFileSource] over the API client: each read bounded by the server's own limits (`DOCUMENT_PDF_MAX_BYTES`, `DOCUMENT_TEXT_MAX_BYTES`). */
public class ApiDocumentFileSource(private val client: ApiClient) : DocumentFileSource {
    override suspend fun pdf(at: DocumentAddress): ByteArray = when (at) {
        is DocumentAddress.Library -> client.documentPdf(at.serverId)
        is DocumentAddress.Pack -> client.packDocumentPdf(at.pack, at.item)
    }

    override suspend fun text(at: DocumentAddress): ByteArray = when (at) {
        is DocumentAddress.Library -> client.documentTextFile(at.serverId)
        is DocumentAddress.Pack -> client.packDocumentTextFile(at.pack, at.item)
    }
}

/** Why a document's file is not to hand, in words for the person. */
public class DocumentFileException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Lets go of every document kept on the device: at sign-out, since a document is the account's. */
public fun interface DocumentFilesCleaner {
    public fun clear()
}

/**
 * The documents' files kept on this device (docs/DOCUMENTS.md §9.2): each PDF and page text in [dir] under the SHA-256 its record names (`<hash>.pdf`,
 * `<hash>.json`), so a document opens with no signal once it has been opened, and a Library document and its copy in a pack, which name the same files, are
 * one file. Each is downloaded whole, **its hash checked against its name before it is kept** (a file that is not the one named is refused, never shown), and
 * written to a file of its own and renamed into place, so a reader never meets half of one. At most [maxBytes], the longest unopened let go of first; one
 * [pin]ned (*Keep on this device*) is kept past that. [clear] empties it at sign-out, and a file fetched across a clear is not kept: it was the last account's.
 *
 * [dir] is under `noBackupFilesDir`, so no document reaches a consumer cloud, and nothing but a hash names a file, so a path the server sends cannot reach
 * outside it.
 */
public class DocumentFiles(
    private val dir: File,
    private val source: DocumentFileSource,
    private val maxBytes: Long = MAX_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : DocumentFilesCleaner {
    private class Entry(val bytes: Long, var lastUsed: Long)

    // What is on disk, by file name, read from it the first time it is wanted; the bytes in all; the clears so far. Under [this].
    private var index: LinkedHashMap<String, Entry>? = null
    private var held = 0L
    private var generation = 0L
    private val fetching = ConcurrentHashMap<String, Mutex>()
    private val _pinned = MutableStateFlow<Set<String>>(emptySet())
    private var pinsRead = false

    /** The files kept past the limit (*Keep on this device*), by hash: a pinned document's PDF and its text. */
    public val pinned: StateFlow<Set<String>>
        get() {
            readPins()
            return _pinned.asStateFlow()
        }

    /** Whether [info] is kept on this device past the limit. */
    public fun isPinned(info: DocumentInfo): Boolean = synchronized(this) { readPins(); info.pdf in _pinned.value }

    /** The PDF [info] names, from the device when it is kept there, else fetched from [at] and checked first. */
    public suspend fun pdf(info: DocumentInfo, at: DocumentAddress): File = kept("${info.pdf}.pdf", info.pdf, at) { source.pdf(at) }

    /**
     * The page text [info] names, for find in the document, or null for a scan (none to search). Fetched as the PDF is; a text that cannot be read is let go
     * of and refused.
     */
    public suspend fun text(info: DocumentInfo, at: DocumentAddress): DocumentText? {
        val hash = info.text ?: return null
        val file = kept("$hash.json", hash, at) { source.text(at) }
        return withContext(io) {
            try {
                readDocumentText(file.readBytes())
            } catch (e: Exception) {
                synchronized(this@DocumentFiles) { forgetFile("$hash.json") }
                throw DocumentFileException("The document's text could not be read.", e)
            }
        }
    }

    /** Whether the PDF [hash] names is on the device, so it opens with no signal. */
    public fun isKept(hash: String): Boolean = synchronized(this) { indexed().containsKey("$hash.pdf") }

    /** *Keep on this device*: [info]'s files are kept past the limit while [on] (and let go of by the limit again once not). */
    public fun pin(info: DocumentInfo, on: Boolean) {
        synchronized(this) {
            readPins()
            val hashes = listOfNotNull(info.pdf, info.text)
            _pinned.value = if (on) _pinned.value + hashes else _pinned.value - hashes.toSet()
            writePins()
            if (!on) trim(keep = null)
        }
    }

    /** Lets go of the files of documents that are gone (a Library document deleted, its tombstone come): [hashes] are their PDFs' and texts'. */
    public fun forget(hashes: Collection<String>) {
        synchronized(this) {
            readPins()
            for (hash in hashes) {
                if (!DocumentInfo.isHash(hash)) continue
                forgetFile("$hash.pdf")
                forgetFile("$hash.json")
            }
            if (hashes.any { it in _pinned.value }) {
                _pinned.value = _pinned.value - hashes.toSet()
                writePins()
            }
        }
    }

    /**
     * Lets go of what a Library document deleted since named ([library]: the hashes every live Library document names). A file a pack's document was opened
     * with is left to the limit: a closed pack is not read here to see whether it still names it, and its members may open it again.
     */
    public fun prune(library: Set<String>) {
        synchronized(this) {
            readPins()
            val packs = packHashes()
            val gone = indexed().keys.map { it.substringBefore('.') }.filter { it !in library && it !in packs }.toSet()
            if (gone.isEmpty()) return
            gone.forEach { forgetFile("$it.pdf"); forgetFile("$it.json") }
            if (_pinned.value.any { it in gone }) {
                _pinned.value = _pinned.value - gone
                writePins()
            }
        }
    }

    override fun clear() {
        synchronized(this) {
            generation++
            index = LinkedHashMap()
            held = 0
            _pinned.value = emptySet()
            pinsRead = true
            fromPacks = HashSet()
            dir.deleteRecursively()
        }
    }

    /** The bytes kept on disk, for a test. */
    internal val heldBytes: Long get() = synchronized(this) { indexed(); held }

    // The file called [name], kept under [hash]: from the disk when it is whole there, else fetched, checked against [hash] and kept. One fetch a file at a time.
    private suspend fun kept(name: String, hash: String, at: DocumentAddress, fetch: suspend () -> ByteArray): File {
        if (!DocumentInfo.isHash(hash)) throw DocumentFileException("This document names no file this device can keep.")
        if (at is DocumentAddress.Pack) notePack(hash)
        onDisk(name)?.let { return it }
        val lock = fetching.getOrPut(name) { Mutex() }
        return lock.withLock {
            onDisk(name) ?: run {
                val started = synchronized(this) { generation }
                val bytes = fetch()
                withContext(io) { keep(name, hash, bytes, started) }
            }
        }
    }

    // [name] as kept, if it is there; noted as used now.
    private fun onDisk(name: String): File? = synchronized(this) {
        val entry = indexed()[name] ?: return null
        val file = File(dir, name)
        if (!file.isFile || file.length() != entry.bytes) {
            forgetFile(name)
            return null
        }
        entry.lastUsed = clock()
        file.setLastModified(entry.lastUsed)
        file
    }

    private fun keep(name: String, hash: String, bytes: ByteArray, started: Long): File {
        if (FileHash.of(bytes) != hash) throw DocumentFileException("What came from the server was not this document. Try again.")
        synchronized(this) {
            if (generation != started) throw DocumentFileException("You signed out while the document was being fetched.")
            val target = File(dir, name)
            val part = File(dir, ".part-${UUID.randomUUID()}")
            try {
                dir.mkdirs()
                part.writeBytes(bytes)
                if (!part.renameTo(target)) {
                    target.delete()
                    if (!part.renameTo(target)) throw IOException("could not move the file into place")
                }
            } catch (e: IOException) {
                part.delete()
                throw DocumentFileException("There is no room on this device to keep the document.", e)
            }
            val now = clock()
            target.setLastModified(now)
            indexed().remove(name)?.let { held -= it.bytes }
            indexed()[name] = Entry(bytes.size.toLong(), now)
            held += bytes.size
            trim(keep = name)
            return target
        }
    }

    // The longest unopened let go of until what is held is within the limit, never [keep] (the file just asked for) nor a pinned PDF and its text.
    private fun trim(keep: String?) {
        val pins = _pinned.value
        val order = indexed().entries.sortedBy { it.value.lastUsed }.map { it.key }
        for (name in order) {
            if (held <= maxBytes) return
            if (name == keep || name.substringBefore('.') in pins) continue
            forgetFile(name)
        }
    }

    private fun forgetFile(name: String) {
        indexed().remove(name)?.let { held -= it.bytes }
        File(dir, name).delete()
    }

    private fun indexed(): LinkedHashMap<String, Entry> {
        index?.let { return it }
        val found = LinkedHashMap<String, Entry>()
        held = 0
        dir.listFiles()?.forEach { file ->
            when {
                file.name.startsWith(".part-") -> file.delete()                          // a write the process did not live to finish
                file.isFile && NAME.matches(file.name) -> {
                    found[file.name] = Entry(file.length(), file.lastModified())
                    held += file.length()
                }
            }
        }
        index = found
        return found
    }

    // The hashes a pack's document was opened with, which [prune] leaves to the limit. Kept beside the files, and gone with them at a clear.
    private var fromPacks: MutableSet<String>? = null

    private fun packHashes(): MutableSet<String> = synchronized(this) {
        fromPacks ?: (
            try {
                File(dir, PACKS).takeIf { it.isFile }?.readLines().orEmpty()
            } catch (_: IOException) {
                emptyList()
            }.map { it.trim() }.filter(DocumentInfo::isHash).toHashSet()
            ).also { fromPacks = it }
    }

    private fun notePack(hash: String) {
        synchronized(this) {
            val packs = packHashes()
            if (!packs.add(hash)) return
            writeLines(PACKS, packs)
        }
    }

    private fun writeLines(name: String, lines: Collection<String>) {
        try {
            dir.mkdirs()
            val part = File(dir, ".part-$name")
            part.writeText(lines.joinToString("\n"))
            if (!part.renameTo(File(dir, name))) part.delete()
        } catch (_: IOException) {
            // Kept in memory for this run; after a restart the worst is a file let go of early, which costs a fetch.
        }
    }

    private fun readPins() {
        synchronized(this) {
            if (pinsRead) return
            pinsRead = true
            val lines = try {
                File(dir, PINS).takeIf { it.isFile }?.readLines().orEmpty()
            } catch (_: IOException) {
                emptyList()
            }
            _pinned.value = lines.map { it.trim() }.filter(DocumentInfo::isHash).toSet()
        }
    }

    private fun writePins() = writeLines(PINS, _pinned.value)

    public companion object {
        /** The most the documents kept on the device may take (docs/DOCUMENTS.md §9.2), pinned ones apart. */
        public const val MAX_BYTES: Long = 500L * 1024 * 1024

        private const val PINS = "pins.txt"
        private const val PACKS = "packs.txt"
        private val NAME = Regex("^[0-9a-f]{64}\\.(pdf|json)$")
    }
}
