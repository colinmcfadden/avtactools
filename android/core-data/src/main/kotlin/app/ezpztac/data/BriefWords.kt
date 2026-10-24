package app.ezpztac.data

import app.ezpztac.network.ApiException
import app.ezpztac.network.NetworkException

/** What a brief says to the person in the app's own words: never the server's text, which can name a Python exception. */
object BriefWords {
    // The web's PDF_WORDS (feature/brief/briefApi.js), by the server's code.
    private val PDF_WORDS = mapOf(
        "pdf_unavailable" to "The PDF is not available on this server yet.",
        "pdf_failed" to "The PDF could not be made. Try again in a moment.",
        "invalid_brief" to "The brief could not be printed: open it again and retry.",
        "too_large" to "The brief is too large to print.",
        "feature_disabled" to "Mission briefs are not turned on for your account.",
    )

    /** Why the PDF was not made: the server's refusal by its code, else that no answer came (a PDF needs a connection), else that it could not be made. */
    fun printFailure(failure: Throwable): String = when (failure) {
        // Before ApiException, which it is a kind of: no answer came at all.
        is NetworkException -> "No connection to the server: the PDF needs one."
        is ApiException -> PDF_WORDS[failure.code] ?: "The PDF could not be made."
        else -> "The PDF could not be made."
    }

    /** How a Library brief's saving stands, as the setup's foot says it (the web's SAVE_WORDS); null for a brief not yet changed. */
    fun save(save: BriefSave?): String? = when (save) {
        BriefSave.SAVING -> "Saving…"
        BriefSave.SAVED -> "Saved to your Library"
        BriefSave.FAILED -> "Could not save: your answers are kept here and tried again with your next change"
        BriefSave.NOT_KEPT -> "Cloud save is off for your account: these answers are kept only while the app is open"
        null -> null
    }
}
