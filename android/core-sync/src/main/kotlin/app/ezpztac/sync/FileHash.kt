package app.ezpztac.sync

import java.security.MessageDigest

/** What names a file in the store: the SHA-256 of its bytes, in lower-case hex. */
public object FileHash {
    public fun of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
