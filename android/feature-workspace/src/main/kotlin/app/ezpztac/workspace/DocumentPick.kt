package app.ezpztac.workspace

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.ezpztac.data.DocumentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The types a document may be chosen as (docs/DOCUMENTS.md §9.2): a PDF, and the Word, Excel and PowerPoint files where the server turns them into PDFs. */
fun documentTypes(conversion: Boolean): Array<String> = if (conversion) arrayOf(PDF_TYPE) + OFFICE_TYPES else arrayOf(PDF_TYPE)

/** The Office types the server converts, as the web's `OFFICE_ACCEPT` lists them. */
val OFFICE_TYPES = arrayOf(
    "application/msword", "application/vnd.ms-excel", "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
)

/**
 * A new version of a document, chosen in the system's picker (one file of [types]) for the document [key] it was asked for, and read bounded off the main thread:
 * [onRead] has its name and bytes, [onFailed] why it could not be read, in words. What it returns opens the picker for a key. The key outlives the activity being
 * made again while the picker is up.
 */
@Composable
fun rememberVersionPick(
    types: Array<String>,
    onRead: (key: String, name: String, bytes: ByteArray) -> Unit,
    onFailed: (String) -> Unit,
): (key: String) -> Unit {
    var asked by rememberSaveable { mutableStateOf<String?>(null) }
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val read by rememberUpdatedState(onRead)
    val failed by rememberUpdatedState(onFailed)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val key = asked
        asked = null
        if (uri == null || key == null) return@rememberLauncherForActivityResult
        scope.launch {
            when (val picked = withContext(Dispatchers.IO) { PickedFile.read(resolver, uri, tooBig = DocumentRepository.TOO_LARGE) }) {
                is PickedFile.Result.Read -> read(key, picked.name, picked.bytes)
                is PickedFile.Result.Failed -> failed(picked.message)
            }
        }
    }
    return { key -> asked = key; picker.launch(types) }
}
