package app.ezpztac.data

import app.ezpztac.network.DocumentText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File

/** Which document is read: one of the Library's, by its record's uuid, or one of the open pack's, by the pack and the item. */
sealed interface DocumentKey {
    data class Library(val uuid: String) : DocumentKey

    data class Pack(val pack: String, val item: String) : DocumentKey
}

/** Where the document a [DocumentKey] names stands: not known yet (a pack still opening), there to read, or gone, with why in words. */
sealed interface DocumentState {
    data object Waiting : DocumentState

    /** [name] and what it is ([info]), its files asked for at [at]. */
    data class Shown(val key: DocumentKey, val name: String, val info: DocumentInfo, val at: DocumentAddress) : DocumentState

    data class Gone(val why: String) : DocumentState
}

/**
 * The document being read (docs/DOCUMENTS.md §8.1): one at a time, over the frame. [key] is what is asked for; [follow] is that document as it is now, so a rename
 * is shown at once, a new version opens the new PDF, and one deleted, removed from its pack or whose pack is left is let go of, with why. Its files are the
 * device's ([DocumentFiles]): fetched once, read offline after.
 */
class DocumentViewing(
    private val documents: DocumentRepository,
    private val pack: OpenPack,
    private val files: DocumentFiles,
) {
    private val shown = MutableStateFlow<DocumentKey?>(null)

    /** The document asked for; null while none is read. */
    val key: StateFlow<DocumentKey?> = shown.asStateFlow()

    fun show(key: DocumentKey) {
        shown.value = key
    }

    fun close() {
        shown.value = null
    }

    /** The document [key] names as it is now: once for each change the viewer would show (its name, its files), never for a pointer moving in its pack. */
    fun follow(key: DocumentKey): Flow<DocumentState> = when (key) {
        is DocumentKey.Library -> documents.observe().map { records ->
            val record = records.firstOrNull { it.uuid == key.uuid && !it.deleted }
            val info = record?.let { DocumentInfo.of(it.data) }
            val id = record?.serverId
            if (record == null || info == null || id == null) DocumentState.Gone(NOT_IN_LIBRARY) else DocumentState.Shown(key, record.name, info, DocumentAddress.Library(id))
        }
        is DocumentKey.Pack -> pack.glance.map { glance ->
            when {
                glance == null || glance.uuid != key.pack -> DocumentState.Gone(PACK_CLOSED)
                !glance.loaded -> DocumentState.Waiting
                else -> {
                    val item = glance.items.firstOrNull { it.uuid == key.item && it.kind == KIND }
                    val info = item?.let { DocumentInfo.of(it.data) }
                    if (item == null || info == null) DocumentState.Gone(NOT_IN_PACK) else DocumentState.Shown(key, item.name, info, DocumentAddress.Pack(key.pack, key.item))
                }
            }
        }
    }.distinctUntilChanged()

    /** The PDF on this device, fetched first when it is not kept. */
    suspend fun pdf(document: DocumentState.Shown): File = files.pdf(document.info, document.at)

    /** The page text, for find in the document; null for a scan. */
    suspend fun text(document: DocumentState.Shown): DocumentText? = files.text(document.info, document.at)

    /** The PDF hashes kept on this device past the limit (*Keep on this device*). */
    val pinned: StateFlow<Set<String>> get() = files.pinned

    fun pin(document: DocumentState.Shown, on: Boolean) = files.pin(document.info, on)

    companion object {
        const val KIND = "document"
        const val NOT_IN_LIBRARY = "That document is no longer in your Library."
        const val NOT_IN_PACK = "That document is no longer in the pack."
        const val PACK_CLOSED = "The pack it is in is no longer open here."
    }
}
