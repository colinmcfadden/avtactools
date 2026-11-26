package app.ezpztac.network

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Documents in the Library (`/api/documents`, docs/DOCUMENTS.md §6). The server makes a document from the file it is sent and keeps it as a PDF with its page
 * text: a client never sends a document's content, so nothing about a document is queued in the sync engine's outbox. An upload, a rename, a new version and a
 * deletion are made online, and the change then comes back through the feed like any other (`document`). Every route needs the `documents` entitlement (403
 * `feature_disabled` without it), and a write `cloud_save` too. A pack's documents are its own routes (PackEndpoints.kt).
 */

/** The most a file sent as a document may be (the server's `MAX_UPLOAD_BYTES`): a larger one is refused before it is sent. */
public const val DOCUMENT_UPLOAD_MAX_BYTES: Long = 25L * 1024 * 1024

/** The most a document's PDF may hold (the server's own limit); a longer answer is refused as it arrives, never read whole. */
public const val DOCUMENT_PDF_MAX_BYTES: Long = 50L * 1024 * 1024

/** The most a document's page text may hold: 2,000,000 characters, at most four bytes each, with the JSON round them. */
public const val DOCUMENT_TEXT_MAX_BYTES: Long = 9L * 1024 * 1024

/** A file to upload as a document: its [name] (the server takes the title from it) and its [bytes]. */
public class DocumentFile(public val name: String, public val bytes: ByteArray)

internal fun documentCall(
    path: String,
    form: Map<String, String>,
    file: DocumentFile,
    asUser: Int?,
    headers: Map<String, String> = emptyMap(),
) = ApiClient.Call(
    "POST", path, form = form, file = ApiClient.FormFile("file", file.name, file.bytes), headers = headers,
    // An Office file is converted on the server, which can take a minute or two.
    readTimeoutSeconds = ApiClient.HEAVY_READ_TIMEOUT_SECONDS, asUser = asUser,
)

/** A document's page text file as the server keeps it, bounded (a 500-page PDF's can be megabytes): its bytes are what the text's SHA-256 names. */
internal suspend fun ApiClient.documentTextFileAt(path: String, asUser: Int?): ByteArray =
    execute(ApiClient.Call("GET", path, binary = true, maxBytes = DOCUMENT_TEXT_MAX_BYTES, asUser = asUser)).bytes

/** A document's page text file read as the JSON it is. */
internal suspend fun ApiClient.documentTextAt(path: String, asUser: Int?): DocumentText = readDocumentText(documentTextFileAt(path, asUser))

/** The page text of a file fetched as bytes ([documentTextFile], [packDocumentTextFile]); one that is not page text is [ApiException] `unreadable_response`. */
public fun readDocumentText(bytes: ByteArray): DocumentText = try {
    ApiClient.JSON.decodeFromString(DocumentText.serializer(), bytes.toString(Charsets.UTF_8))
} catch (e: kotlinx.serialization.SerializationException) {
    throw ApiException(200, "unreadable_response", "The document's text could not be read.", e)
} catch (e: IllegalArgumentException) {
    throw ApiException(200, "unreadable_response", "The document's text could not be read.", e)
}

/** The caller's documents, newest change first. */
public suspend fun ApiClient.listDocuments(asUser: Int? = null): List<DocumentSummary> =
    decode(execute(ApiClient.Call("GET", "/api/documents", asUser = asUser)))

/** One document, with the JSON that names its files. */
public suspend fun ApiClient.getDocument(id: Int, asUser: Int? = null): DocumentFull =
    decode(execute(ApiClient.Call("GET", "/api/documents/$id", asUser = asUser)))

/** One document's record as the JSON the server sent, whole, for a device that holds it as the pull would (`data` names its files). */
public suspend fun ApiClient.getDocumentRecord(id: Int, asUser: Int? = null): kotlinx.serialization.json.JsonObject =
    decode(execute(ApiClient.Call("GET", "/api/documents/$id", asUser = asUser)))

/**
 * Uploads [file] to the Library, where the server reads it and turns a Word, Excel or PowerPoint file into a PDF. [clientUuid] is this device's identity for
 * the new record, so an upload sent again after a lost answer is the first record ([Saved.created] false), answered before the file is read, and nothing is
 * converted twice; one made and deleted since is refused 410 `document_deleted`. [name] defaults to the file's name less its extension.
 */
public suspend fun ApiClient.uploadDocument(
    file: DocumentFile,
    clientUuid: String,
    name: String? = null,
    idempotencyKey: String = newKey(),
    asUser: Int? = null,
): Saved<DocumentSummary> {
    val form = buildMap {
        put("client_uuid", clientUuid)
        if (name != null) put("name", name)
    }
    val response = execute(documentCall("/api/documents", form, file, asUser, mapOf("Idempotency-Key" to idempotencyKey)))
    return Saved(decode(response), created = response.status == 201)
}

/**
 * Renames a document on top of [baseRevision], the revision the person saw. A server that has moved on renames nothing, and [RevisionConflictException] carries
 * its copy (a `DocumentFull`).
 */
public suspend fun ApiClient.renameDocument(
    id: Int,
    baseRevision: Int?,
    name: String,
    idempotencyKey: String = newKey(),
    asUser: Int? = null,
): DocumentSummary = decode(
    execute(
        ApiClient.Call(
            "PUT", "/api/documents/$id", body = buildJsonObject { put("name", name) },
            headers = revisionHeader(baseRevision) + ("Idempotency-Key" to idempotencyKey), asUser = asUser,
        ),
    ),
)

/**
 * A new version of a document from [file], under the same name and identity. Decided again on [baseRevision] once the file is converted: renamed meanwhile is a
 * [RevisionConflictException], deleted meanwhile a 404. A pack's copy keeps the version it was copied as.
 */
public suspend fun ApiClient.newDocumentVersion(
    id: Int,
    baseRevision: Int?,
    file: DocumentFile,
    idempotencyKey: String = newKey(),
    asUser: Int? = null,
): DocumentSummary = decode(
    execute(documentCall("/api/documents/$id/version", emptyMap(), file, asUser, revisionHeader(baseRevision) + ("Idempotency-Key" to idempotencyKey))),
)

/** Deletes a document. The server keeps a tombstone so other devices learn of it, and lets its files go once nothing names them. Deleting twice is not an error. */
public suspend fun ApiClient.deleteDocument(id: Int, baseRevision: Int? = null, idempotencyKey: String = newKey(), asUser: Int? = null) {
    execute(
        ApiClient.Call(
            "DELETE", "/api/documents/$id", headers = revisionHeader(baseRevision) + ("Idempotency-Key" to idempotencyKey), asUser = asUser,
        ),
    )
}

/** A document's PDF, bounded by the server's own limit. 404 `file_missing` when the server has lost the file. */
public suspend fun ApiClient.documentPdf(id: Int, asUser: Int? = null): ByteArray = execute(
    ApiClient.Call("GET", "/api/documents/$id/pdf", binary = true, maxBytes = DOCUMENT_PDF_MAX_BYTES, asUser = asUser),
).bytes

/** A document's page text, for search. A scan has none: [ApiException] with `no_text` (404). */
public suspend fun ApiClient.documentText(id: Int, asUser: Int? = null): DocumentText = documentTextAt("/api/documents/$id/text", asUser)

/** The page text file of a document as the server keeps it, so a device can check it against the hash its record names before keeping it. */
public suspend fun ApiClient.documentTextFile(id: Int, asUser: Int? = null): ByteArray = documentTextFileAt("/api/documents/$id/text", asUser)
