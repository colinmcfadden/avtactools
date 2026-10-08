package app.ezpztac.data.session

import app.ezpztac.network.EndedSession
import app.ezpztac.network.SessionStore
import app.ezpztac.network.StoredSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException

/**
 * Keeps the signed-in session between launches, sealed by [box], in one file.
 *
 * - **Written whole and atomically**: to a temporary file in the same folder, flushed to disk, then renamed over the old one. A crash
 *   leaves the old session or the new one, never half of either, which matters because the refresh token in it is spent on use and a
 *   lost one signs the user out.
 * - **The file holds the session or the note of its end, never both** ([end]): a session the server ended is replaced by the note in
 *   the same one rename, so no crash can leave a session that was ended, or the note beside a session that came after it.
 * - **Anything it cannot read is no session**: a key the OS lost, a file that changed, a file from a future version. The file is removed
 *   and the app starts signed out, rather than crashing on every launch.
 * - Nothing in the file is readable without the key; in particular the tokens are not in the clear.
 *
 * Put the file in a folder that is excluded from backup (`Context.noBackupFilesDir`).
 */
public class EncryptedSessionStore(
    private val file: File,
    private val box: SecretBox,
) : SessionStore {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = true }

    /** How the note is kept: an object with this one key, which a session never has, so a session is still kept exactly as it always was. */
    @Serializable
    private data class Note(val ended: EndedSession)

    override suspend fun read(): StoredSession? = load() as? StoredSession

    override suspend fun ended(): EndedSession? = (load() as? Note)?.ended

    override suspend fun write(session: StoredSession): Unit = put(json.encodeToString(StoredSession.serializer(), session))

    override suspend fun end(ended: EndedSession): Unit = put(json.encodeToString(Note.serializer(), Note(ended)))

    override suspend fun clear(): Unit = lock.withLock {
        withContext(Dispatchers.IO) {
            file.delete()
            File(file.parentFile, file.name + ".tmp").delete()
        }
    }

    /** What the file holds: a [StoredSession], a [Note], or null for nothing (or nothing that can be read, which is then removed). */
    private suspend fun load(): Any? = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!file.isFile) return@withContext null
            try {
                val kept = json.parseToJsonElement(box.open(file.readBytes()).decodeToString())
                if (kept is JsonObject && "ended" in kept) json.decodeFromJsonElement(Note.serializer(), kept)
                else json.decodeFromJsonElement(StoredSession.serializer(), kept)
            } catch (_: GeneralSecurityException) {
                discard()
            } catch (_: SerializationException) {
                discard()
            } catch (_: IllegalArgumentException) {
                discard()
            } catch (_: IOException) {
                discard()
            }
        }
    }

    private suspend fun put(text: String): Unit = lock.withLock {
        withContext(Dispatchers.IO) {
            val sealed = box.seal(text.encodeToByteArray())
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(temporary).use { out ->
                out.write(sealed)
                out.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun discard(): Any? {
        file.delete()
        return null
    }
}
