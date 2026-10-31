package app.ezpztac.briefs

import androidx.compose.ui.graphics.ImageBitmap
import app.ezpztac.brief.BriefFormats
import app.ezpztac.data.BriefInputs
import app.ezpztac.data.BriefPackFacts
import app.ezpztac.data.BriefState
import app.ezpztac.data.BriefWeather
import app.ezpztac.data.BriefSave
import app.ezpztac.testing.Fixtures
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** OP DK's air movement brief, as compile.json's first case compiles it: the brief the screens' tests lay out. */
internal object BriefFixtures {
    val opdk: JsonObject = Fixtures.load("brief/compile.json")["cases"]!!.jsonArray[0].jsonObject
    val model: JsonObject = opdk["expected"]!!.jsonObject
    val document: JsonObject = opdk["document"]!!.jsonObject
    val plan: JsonObject = opdk["plan"]!!.jsonObject

    /** The brief in OP DK the pack ([pack]: its name), or this session's ([pack] null). */
    fun state(pack: String? = "OP DK", readOnly: Boolean = false, save: BriefSave? = null, model: JsonObject = this.model, doc: JsonObject = document) = BriefState(
        doc = doc,
        hasDoc = true,
        format = BriefFormats.format("amb")!!,
        model = model,
        inputs = BriefInputs(plan, threatCount = 1, pack = pack?.let { BriefPackFacts("p-1", it, readOnly) }),
        summary = "5 LZ/PZs, 1 route set, 1 threat",
        save = save,
        weather = BriefWeather(loading = false, fetchedAt = null),
    )
}

/** The brief being set up, as a test holds it: what it compiles to, what was asked of it. */
internal class FakeDoor(initial: BriefState? = null) : BriefDoor {
    val mutableState = MutableStateFlow(initial)
    override val state: StateFlow<BriefState?> = mutableState
    val mutableOpened = MutableStateFlow(false)
    override val opened: StateFlow<Boolean> = mutableOpened
    val mutableFailed = MutableStateFlow(false)
    override val failed: StateFlow<Boolean> = mutableFailed
    override val refusals = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val log = mutableListOf<String>()
    val changes = mutableListOf<(JsonObject) -> JsonObject>()
    val printed = mutableListOf<JsonObject>()
    var printFailure: Exception? = null

    /** While set, a print waits for it: what the setup shows meanwhile can be seen. */
    var printGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var pdfBytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2d)

    override fun open() {
        log += "open"
        mutableOpened.value = true
    }

    override suspend fun close() {
        log += "close"
        mutableOpened.value = false
    }

    override fun change(transform: (JsonObject) -> JsonObject) {
        changes += transform
    }

    override fun refreshWeather() {
        log += "weather"
    }

    override suspend fun print(model: JsonObject): ByteArray {
        printed += model
        printGate?.await()
        printFailure?.let { throw it }
        return pdfBytes
    }
}

/** A PDF of [pages] pages that draws nothing (a test has no renderer), and says when it is closed. */
internal class FakePdf(override val pages: Int) : BriefPdf {
    var closed = false

    override suspend fun page(index: Int, widthPx: Int): ImageBitmap? = null

    override fun close() {
        closed = true
    }
}

internal class FakePdfs(private val pages: Int = 3) : BriefPdfs {
    val opened = mutableListOf<FakePdf>()

    override suspend fun open(bytes: ByteArray): BriefPdf = FakePdf(pages).also { opened += it }
}
