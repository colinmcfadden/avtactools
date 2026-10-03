package app.ezpztac.data

import app.ezpztac.data.session.SecretBox
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException

/**
 * [ThreatVault] in one file sealed by [box] (AES-GCM, a key that never leaves the Keystore). Written whole to a temporary file and renamed over the old one, so a
 * process that ends part way through leaves the old picture or the new, never half; and anything that cannot be read (a key the system lost, a changed
 * byte, a file from another release) is removed and is no picture. Nothing in the file is readable without the key.
 *
 * Put the file in `Context.noBackupFilesDir`: threats are sensitive, and a backup would carry them to a cloud account.
 */
public class EncryptedThreatVault(
    private val file: File,
    private val box: SecretBox,
) : ThreatVault {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val temporary get() = File(file.parentFile, file.name + ".tmp")

    override fun load(): ThreatPicture? {
        if (!file.isFile) return null
        return try {
            json.decodeFromString(ThreatPicture.serializer(), box.open(file.readBytes()).decodeToString())
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

    override fun save(picture: ThreatPicture) {
        val sealed = box.seal(json.encodeToString(ThreatPicture.serializer(), picture).encodeToByteArray())
        file.parentFile?.mkdirs()
        FileOutputStream(temporary).use { out ->
            out.write(sealed)
            out.fd.sync()
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun wipe() {
        discard()
    }

    /** Removes the file and any half-written one; one that will not go is emptied, so no threat is left in it. */
    private fun discard(): ThreatPicture? {
        try {
            temporary.delete()
            if (file.exists() && !file.delete()) file.writeBytes(ByteArray(0))
        } catch (_: Exception) {
            // Nothing more can be done from here.
        }
        return null
    }
}
