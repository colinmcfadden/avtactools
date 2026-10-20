package app.ezpztac.data

import java.io.File
import java.io.IOException
import java.util.UUID

/** Lets go of what the 3D view kept on the device of the point clouds: at sign-out, so the next person finds nothing of this one's places. */
public fun interface TilesetCacheCleaner {
    public fun clear()
}

/**
 * The point cloud's files ([inner], the server's) kept in the app's private cache ([dir]) for as long as the server says they keep
 * (`Cache-Control: private, max-age` of a week, `TILE_CACHE_SECONDS` in `routes/lidar_routes.py`: [MAX_AGE_MS]), as the web's browser keeps
 * them, so an LZ/PZ looked at again opens from the device, with no signal too. At most [maxBytes], the longest unused let go of first; a file is
 * taken back as it came, at most [maxFileBytes]. Anything wrong with the cache (a file cut short or gone, no room, a path it will not hold) is a
 * fetch, never a failure. [clear] empties it at sign-out: a tileset is a place, and its key can be turned back into it (AGENTS.md §15); a file
 * fetched across a clear is not kept.
 *
 * A file is kept at `<dir>/<key>/<path>`, its key and every part of its path checked as the API client checks them (`LidarPaths`), so nothing
 * the server names can reach outside [dir]. Written whole to a file of its own and renamed into place, so a reader never meets half of one.
 */
public class CachedTilesetFiles(
    private val inner: TilesetFiles,
    private val dir: File,
    private val maxBytes: Long = MAX_BYTES,
    private val maxFileBytes: Long = MAX_FILE_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) : TilesetFiles, TilesetCacheCleaner {
    private class Entry(val bytes: Long, var lastUsed: Long)

    // What is on disk, by file, read from it the first time it is wanted; the bytes in all. Under [this].
    private var index: LinkedHashMap<File, Entry>? = null
    private var held = 0L
    private var generation = 0L

    override suspend fun file(key: String, path: String): ByteArray {
        val file = fileFor(key, path) ?: return inner.file(key, path)
        cached(file)?.let { return it }
        val started = synchronized(this) { generation }
        val bytes = inner.file(key, path)
        keep(file, bytes, started)
        return bytes
    }

    override fun clear() {
        synchronized(this) {
            generation++
            index = LinkedHashMap()
            held = 0
            dir.deleteRecursively()
        }
    }

    /** The bytes kept on disk, for a test. */
    internal val heldBytes: Long get() = synchronized(this) { indexed(); held }

    // [file] as kept, if it is there, young enough and whole; a file that is not is let go of.
    private fun cached(file: File): ByteArray? {
        val now = clock()
        synchronized(this) {
            val entry = indexed()[file] ?: return null
            val fresh = now - file.lastModified() in 0 until MAX_AGE_MS
            if (!fresh || entry.bytes > maxFileBytes) {
                forget(file)
                return null
            }
            entry.lastUsed = now
        }
        return try {
            file.readBytes().takeIf { it.size.toLong() <= maxFileBytes }
        } catch (_: IOException) {
            null
        } ?: run {
            synchronized(this) { forget(file) }
            null
        }
    }

    // Written to a file of its own, then renamed into place; not kept when a clear came since the fetch began, or it is larger than any is kept.
    private fun keep(file: File, bytes: ByteArray, started: Long) {
        if (bytes.size.toLong() > maxFileBytes || bytes.size.toLong() > maxBytes) return
        val part = File(file.parentFile, "${file.name}.part-${UUID.randomUUID()}")
        try {
            file.parentFile?.mkdirs()
            part.writeBytes(bytes)
        } catch (_: IOException) {
            part.delete()
            return
        }
        val now = clock()
        // Stamped with the cache's clock, which its age is read on.
        part.setLastModified(now)
        synchronized(this) {
            if (generation != started || !part.renameTo(file)) {
                part.delete()
                return
            }
            val index = indexed()
            index.remove(file)?.let { held -= it.bytes }
            index[file] = Entry(bytes.size.toLong(), now)
            held += bytes.size
            trim()
        }
    }

    // Under [this]: the longest unused go first until what is held fits.
    private fun trim() {
        val index = indexed()
        if (held <= maxBytes) return
        for ((file, _) in index.entries.sortedBy { it.value.lastUsed }.map { it.key to it.value }) {
            if (held <= maxBytes) break
            forget(file)
        }
    }

    // Under [this].
    private fun forget(file: File) {
        val gone = indexed().remove(file) ?: return
        held -= gone.bytes
        file.delete()
    }

    // Under [this]: the files on disk the first time they are wanted, the oldest first; a half-written one left by a process that ended is removed.
    private fun indexed(): LinkedHashMap<File, Entry> {
        index?.let { return it }
        val found = LinkedHashMap<File, Entry>()
        held = 0
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        for (file in files.sortedBy { it.lastModified() }) {
            if (".part-" in file.name) {
                file.delete()
                continue
            }
            found[file] = Entry(file.length(), file.lastModified())
            held += file.length()
        }
        index = found
        return found
    }

    // Where [key]'s [path] is kept, or null for one the cache will not hold (the API client refuses it too).
    private fun fileFor(key: String, path: String): File? {
        if (!KEY.matches(key)) return null
        val parts = path.split('/')
        if (parts.size !in 1..MAX_PARTS || !parts.all { PART.matches(it) && ".part-" !in it }) return null
        return File(File(dir, key), path)
    }

    public companion object {
        /** As long as the server says a tileset's files keep: a week. */
        public const val MAX_AGE_MS: Long = 7L * 24 * 3600 * 1000

        /** About five LZ/PZs' point clouds, as the web's browser would hold them. */
        public const val MAX_BYTES: Long = 256L * 1024 * 1024

        /** As much as the API client takes of one file (`TILESET_FILE_MAX_BYTES`). */
        public const val MAX_FILE_BYTES: Long = 32L * 1024 * 1024

        // As the API client checks them (core-network's LidarPaths): a key of the server's, and a path of plain names, none starting with a dot.
        private val KEY = Regex("[0-9a-f]{16}")
        private val PART = Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,127}")
        private const val MAX_PARTS = 16
    }
}
