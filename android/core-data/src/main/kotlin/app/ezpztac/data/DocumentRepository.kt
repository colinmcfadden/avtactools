package app.ezpztac.data

import app.ezpztac.network.ApiClient
import app.ezpztac.network.ApiException
import app.ezpztac.network.DOCUMENT_UPLOAD_MAX_BYTES
import app.ezpztac.network.DocumentFile
import app.ezpztac.network.DocumentSummary
import app.ezpztac.network.DocumentText
import app.ezpztac.network.NetworkException
import app.ezpztac.network.RevisionConflictException
import app.ezpztac.network.deleteDocument
import app.ezpztac.network.getDocumentRecord
import app.ezpztac.network.newDocumentVersion
import app.ezpztac.network.renameDocument
import app.ezpztac.network.uploadDocument
import app.ezpztac.sync.LocalRecord
import app.ezpztac.sync.RecordFeed
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.SyncRepository
import app.ezpztac.sync.SyncScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.util.UUID

/** What a change to a document came to: done, with the record as it is now, or refused, with why in words for the person. */
public sealed interface DocumentOutcome {
    public data class Done(val record: LocalRecord?) : DocumentOutcome

    public data class Refused(val message: String) : DocumentOutcome
}

/** The server's side of [DocumentRepository]: `/api/documents`' calls (docs/DOCUMENTS.md §6). */
public interface DocumentApi {
    public suspend fun upload(file: DocumentFile, clientUuid: String, name: String?): DocumentSummary

    /** One record as the JSON the server sent, whole. */
    public suspend fun record(id: Int): JsonObject

    public suspend fun rename(id: Int, baseRevision: Int?, name: String): DocumentSummary

    public suspend fun newVersion(id: Int, baseRevision: Int?, file: DocumentFile): DocumentSummary

    public suspend fun delete(id: Int, baseRevision: Int?)
}

/** [DocumentApi] over the API client. */
public class ApiDocumentApi(private val client: ApiClient) : DocumentApi {
    override suspend fun upload(file: DocumentFile, clientUuid: String, name: String?): DocumentSummary = client.uploadDocument(file, clientUuid, name = name).value

    override suspend fun record(id: Int): JsonObject = client.getDocumentRecord(id)

    override suspend fun rename(id: Int, baseRevision: Int?, name: String): DocumentSummary = client.renameDocument(id, baseRevision, name)

    override suspend fun newVersion(id: Int, baseRevision: Int?, file: DocumentFile): DocumentSummary = client.newDocumentVersion(id, baseRevision, file)

    override suspend fun delete(id: Int, baseRevision: Int?) = client.deleteDocument(id, baseRevision)
}

/** Uploading a document to the Library ([DocumentRepository.upload]): what the import review asks of it. */
public interface DocumentUploads {
    public suspend fun upload(fileName: String, bytes: ByteArray, name: String?, uuid: String): DocumentOutcome

    public companion object {
        /** Where documents are not uploaded: refused. */
        public val None: DocumentUploads = object : DocumentUploads {
            override suspend fun upload(fileName: String, bytes: ByteArray, name: String?, uuid: String) =
                DocumentOutcome.Refused("Documents are not turned on for your account.")
        }
    }
}

/** Lets go of the files of Library documents deleted since ([DocumentRepository.prune]): after each sync. */
public fun interface DocumentPruner {
    public suspend fun prune()
}

/**
 * The documents in the Library (docs/DOCUMENTS.md §6, §9.2): each a [RecordKind.DOCUMENT] record the sync engine pulls, its files kept by [files]. **A change
 * is made online**: the server makes a document from the file it is sent, so an upload, a rename, a new version and a deletion are calls of their own, and what
 * the server answered is held at once ([SyncRepository.take]) so the Library shows it before the next pull; a sync is asked for to bring the rest. With no
 * signal they are refused in words, never queued: a file waiting in an outbox would be a file the person thinks is uploaded.
 */
public class DocumentRepository(
    private val api: DocumentApi,
    private val sync: SyncRepository,
    private val feed: RecordFeed,
    private val scheduler: SyncScheduler,
    private val files: DocumentFiles,
    private val newUuid: () -> String = { UUID.randomUUID().toString() },
) : DocumentPruner, DocumentUploads {
    /** Every document in the Library, as the Library lists them. */
    public fun observe(): Flow<List<LocalRecord>> = feed.observe(KIND)

    public suspend fun records(): List<LocalRecord> = sync.records(KIND)

    public suspend fun record(uuid: String): LocalRecord? = sync.record(KIND, uuid)

    /**
     * Uploads [bytes] as a document called [name] (null: the file's name less its extension), under [uuid]: the identity a try again after a lost answer is sent
     * under, so it is the same record and nothing is converted twice. Kept by the caller for as long as the person may try again.
     */
    override suspend fun upload(fileName: String, bytes: ByteArray, name: String?, uuid: String): DocumentOutcome = online(UPLOAD_FAILED) {
        if (bytes.size > DOCUMENT_UPLOAD_MAX_BYTES) return@online DocumentOutcome.Refused(TOO_LARGE)
        val saved = api.upload(DocumentFile(fileName, bytes), uuid, name?.trim()?.takeIf { it.isNotEmpty() })
        DocumentOutcome.Done(holdWhole(uuid, saved))
    }

    /** [upload] under a new identity. */
    public suspend fun upload(fileName: String, bytes: ByteArray, name: String?): DocumentOutcome = upload(fileName, bytes, name, newUuid())

    /** Renames [uuid] on the revision this device holds; one another device changed first is refused, and the pull brings its version. */
    public suspend fun rename(uuid: String, name: String): DocumentOutcome = online(RENAME_FAILED) {
        val record = sync.record(KIND, uuid) ?: return@online DocumentOutcome.Refused(GONE)
        val id = record.serverId ?: return@online DocumentOutcome.Refused(GONE)
        val renamed = api.rename(id, record.baseRevision, name.trim())
        sync.take(KIND, uuid, renamed.id, renamed.revision, renamed.name, data = null, updatedAt = renamed.updatedAt)
        DocumentOutcome.Done(sync.record(KIND, uuid))
    }

    /** A new version of [uuid] from [bytes], under its name and identity. A pack's copy keeps the version it was copied as. */
    public suspend fun newVersion(uuid: String, fileName: String, bytes: ByteArray): DocumentOutcome = online(VERSION_FAILED) {
        if (bytes.size > DOCUMENT_UPLOAD_MAX_BYTES) return@online DocumentOutcome.Refused(TOO_LARGE)
        val record = sync.record(KIND, uuid) ?: return@online DocumentOutcome.Refused(GONE)
        val id = record.serverId ?: return@online DocumentOutcome.Refused(GONE)
        val next = api.newVersion(id, record.baseRevision, DocumentFile(fileName, bytes))
        DocumentOutcome.Done(holdWhole(uuid, next))
    }

    /** Deletes [uuid] from the Library on every device; its files go from this one at once (another device's when its tombstone comes). */
    public suspend fun delete(uuid: String): DocumentOutcome = online(DELETE_FAILED) {
        val record = sync.record(KIND, uuid) ?: return@online DocumentOutcome.Done(null)
        val id = record.serverId ?: return@online DocumentOutcome.Done(null)
        try {
            api.delete(id, record.baseRevision)
        } catch (e: ApiException) {
            if (e.status != 404 || e is NetworkException) throw e                               // already gone there: gone here too
        }
        sync.forget(KIND, uuid)
        prune()
        DocumentOutcome.Done(null)
    }

    /** [record]'s PDF on the device, fetched first when it is not kept. */
    public suspend fun pdf(record: LocalRecord): File {
        val (info, at) = addressOf(record)
        return files.pdf(info, at)
    }

    /** [record]'s page text for find in the document, or null for a scan. */
    public suspend fun text(record: LocalRecord): DocumentText? {
        val (info, at) = addressOf(record)
        return files.text(info, at)
    }

    /** Lets go of the files of Library documents that are gone: after a sync, so another device's deletion reaches this one's files too. */
    override suspend fun prune() {
        val live = sync.records(KIND).flatMap { r -> DocumentInfo.of(r.data)?.let { listOfNotNull(it.pdf, it.text) }.orEmpty() }.toSet()
        files.prune(live)
    }

    /** The name a document would be given, with no name typed: the file's less its extension, as the server and the web name it. */
    public fun nameFrom(fileName: String): String = FileInspector.documentName(fileName)

    private fun addressOf(record: LocalRecord): Pair<DocumentInfo, DocumentAddress> {
        val info = DocumentInfo.of(record.data) ?: throw DocumentFileException(UNREADABLE)
        val id = record.serverId ?: throw DocumentFileException(UNREADABLE)
        return info to DocumentAddress.Library(id)
    }

    // An answer with no files named (an upload's, a new version's) is held with the whole record, asked for at once; one that cannot be asked for now comes with the pull.
    private suspend fun holdWhole(uuid: String, summary: DocumentSummary): LocalRecord? {
        try {
            // The record's JSON as the server sent it, so a field a newer server adds rides along as the pull's would.
            val full = api.record(summary.id)
            val data = full["data"] as? JsonObject
            val id = (full["id"] as? JsonPrimitive)?.intOrNull ?: summary.id
            val revision = (full["revision"] as? JsonPrimitive)?.intOrNull ?: summary.revision
            sync.take(KIND, uuid, id, revision, (full["name"] as? JsonPrimitive)?.contentOrNull ?: summary.name, data, updatedAt = summary.updatedAt)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            sync.take(KIND, uuid, summary.id, summary.revision, summary.name, data = null, updatedAt = summary.updatedAt)
        }
        return sync.record(KIND, uuid)
    }

    private suspend fun online(failed: String, block: suspend () -> DocumentOutcome): DocumentOutcome = try {
        block().also { if (it is DocumentOutcome.Done) scheduler.requestSync() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DocumentOutcome.Refused(refusal(e, failed))
    }

    public companion object {
        private val KIND = RecordKind.DOCUMENT
        private const val MAX_NAME = 100

        public const val TOO_LARGE: String = "This file is larger than 25 MB, the most a document can be."
        public const val GONE: String = "That document is no longer in your Library."
        public const val OFFLINE: String = "The document did not reach the server. Check the connection and try again."
        public const val UNREADABLE: String = "This document cannot be opened on this device."
        internal const val UPLOAD_FAILED = "The document could not be uploaded. Try again."
        internal const val RENAME_FAILED = "The document could not be renamed. Try again."
        internal const val VERSION_FAILED = "The new version could not be uploaded. Try again."
        internal const val DELETE_FAILED = "The document could not be deleted. Try again."
        internal const val CHANGED = "That document was changed on another device. Look at it again and try once more."

        /**
         * What a refusal says to the person. The server's own sentence where it is one of the refusals written for people (each with its `code`, docs/DOCUMENTS.md
         * §6: a file too large, a password, a type it does not take); the app's words for anything else, so nothing from inside a failure is shown.
         */
        public fun refusal(e: Exception, failed: String): String = when (e) {
            is NetworkException -> OFFLINE
            is RevisionConflictException -> CHANGED
            is ApiException -> when {
                e.code == "feature_disabled" -> "Documents are not turned on for your account."
                spoken(e) != null -> spoken(e)!!
                e.status == 404 -> GONE
                else -> failed
            }
            else -> failed
        }

        /** The server's sentence for [e], where it is one of those written for the person; null otherwise. */
        public fun spoken(e: ApiException): String? = e.message?.takeIf { e.code in SPOKEN && it.isNotBlank() }

        /** The refusals whose sentence the server writes for the person (`routes/document_routes.py`). */
        private val SPOKEN = setOf(
            "missing_file", "empty_file", "invalid_name", "document_deleted", "file_too_large", "pdf_too_large", "too_many_pages", "library_full",
            "unsupported_type", "unreadable", "password_protected", "no_pages", "conversion_refused", "conversion_failed", "conversion_unavailable",
            "converter_busy",
        )
    }
}
