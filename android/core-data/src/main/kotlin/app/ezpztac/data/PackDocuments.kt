package app.ezpztac.data

import app.ezpztac.missionpacks.PackEngine
import app.ezpztac.missionpacks.PackFailure
import app.ezpztac.missionpacks.PackMessages
import app.ezpztac.network.ApiClient
import app.ezpztac.network.ApiException
import app.ezpztac.network.DOCUMENT_UPLOAD_MAX_BYTES
import app.ezpztac.network.DocumentFile
import app.ezpztac.network.NetworkException
import app.ezpztac.network.newPackDocumentVersion
import app.ezpztac.network.savePackItemToLibrary
import app.ezpztac.network.uploadPackDocument
import app.ezpztac.sync.SyncScheduler
import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * A Mission Pack's documents (docs/DOCUMENTS.md §7), as the Library's are ([DocumentRepository]): the server makes one from the file it is sent, so an upload, a
 * new version and a copy to the Library are calls of their own, made online and never queued, and the pack's log brings the change to every member. Renaming and
 * removing one are the pack's ordinary operations. Each is made for the account packs run for ([PackEngine.me]), never under another account's session.
 */
interface PackDocuments {
    /** Uploads [bytes] into pack [pack] as item [item]: the id a try again after a lost answer is sent under, so it is the same item ([newItem]). */
    suspend fun upload(pack: String, item: String, fileName: String, bytes: ByteArray, name: String?): DocumentOutcome

    /** A new version of pack [pack]'s document [item] from [bytes], under its name. */
    suspend fun newVersion(pack: String, item: String, fileName: String, bytes: ByteArray): DocumentOutcome

    /** A copy of pack [pack]'s document [item] in this person's Library under [name], naming the same files; the next sync brings it. */
    suspend fun saveToLibrary(pack: String, item: String, name: String): DocumentOutcome

    /** An id for a new item, made once for each file and kept for every try of it. */
    fun newItem(): String = "doc-${UUID.randomUUID()}"

    companion object {
        /** Where packs do not run: everything refused. */
        val None: PackDocuments = object : PackDocuments {
            override suspend fun upload(pack: String, item: String, fileName: String, bytes: ByteArray, name: String?) = DocumentOutcome.Refused(NOT_OPEN)

            override suspend fun newVersion(pack: String, item: String, fileName: String, bytes: ByteArray) = DocumentOutcome.Refused(NOT_OPEN)

            override suspend fun saveToLibrary(pack: String, item: String, name: String) = DocumentOutcome.Refused(NOT_OPEN)
        }

        const val NOT_OPEN = "The pack is not open here."

        /** What a refusal says: the server's sentence where it wrote one for people, else the pack's words for its refusals, else [failed]. */
        fun refusal(e: Exception, failed: String): String = when {
            e is NetworkException -> DocumentRepository.OFFLINE
            e is ApiException && e.code == "feature_disabled" -> "Documents are not turned on for your account."
            e is ApiException && DocumentRepository.spoken(e) != null -> DocumentRepository.spoken(e)!!
            e is ApiException && e.status in 400..499 -> PackMessages.words(PackFailure.of(e))
            else -> failed
        }
    }
}

/** [PackDocuments] over the API client; once the server has made a change, the open pack is asked for what is new. */
class ApiPackDocuments(
    private val api: ApiClient,
    private val engine: PackEngine,
    private val scheduler: SyncScheduler,
) : PackDocuments {
    override suspend fun upload(pack: String, item: String, fileName: String, bytes: ByteArray, name: String?): DocumentOutcome = online(UPLOAD_FAILED, bytes) { user ->
        api.uploadPackDocument(pack, item, DocumentFile(fileName, bytes), name = name?.trim()?.takeIf { it.isNotEmpty() }, asUser = user)
        engine.refresh()
    }

    override suspend fun newVersion(pack: String, item: String, fileName: String, bytes: ByteArray): DocumentOutcome = online(VERSION_FAILED, bytes) { user ->
        api.newPackDocumentVersion(pack, item, DocumentFile(fileName, bytes), asUser = user)
        engine.refresh()
    }

    override suspend fun saveToLibrary(pack: String, item: String, name: String): DocumentOutcome = online(SAVE_FAILED) { user ->
        api.savePackItemToLibrary(pack, item, name, asUser = user)
        scheduler.requestSync()                                                                           // the Library's record comes with the pull
    }

    private suspend fun online(failed: String, bytes: ByteArray? = null, call: suspend (Int) -> Unit): DocumentOutcome {
        if (bytes != null && bytes.size > DOCUMENT_UPLOAD_MAX_BYTES) return DocumentOutcome.Refused(DocumentRepository.TOO_LARGE)
        val user = engine.me.value?.id ?: return DocumentOutcome.Refused(PackDocuments.NOT_OPEN)
        return try {
            call(user)
            DocumentOutcome.Done(null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DocumentOutcome.Refused(PackDocuments.refusal(e, failed))
        }
    }

    companion object {
        internal const val UPLOAD_FAILED = "The document could not be uploaded to the pack. Try again."
        internal const val VERSION_FAILED = "The new version could not be uploaded. Try again."
        internal const val SAVE_FAILED = "The document could not be saved to your Library. Try again."
    }
}
