package app.ezpztac.data

import app.ezpztac.brief.BriefFormat
import app.ezpztac.brief.BriefFormats
import app.ezpztac.brief.CompileBrief
import app.ezpztac.brief.StripGrid
import app.ezpztac.model.LatLon
import app.ezpztac.network.ApiClient
import app.ezpztac.network.printBrief
import app.ezpztac.network.weatherAt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** The server, as a brief needs it. [ApiClientBriefApi] is the real one; tests script their own. */
interface BriefApi {
    /** The report or forecast for a place at the instant [at] (ISO; null: now), the body whole; null when the server could not answer, as the web's `weatherAt`. */
    suspend fun weatherAt(lat: Double, lon: Double, at: String?): JsonObject?

    /** The brief printed from its compiled [model]: the PDF's bytes. Throws the client's failures, which [BriefWords.printFailure] says in the app's words. */
    suspend fun print(model: JsonObject): ByteArray
}

/** [BriefApi] over the real client. */
class ApiClientBriefApi(private val client: ApiClient) : BriefApi {
    override suspend fun weatherAt(lat: Double, lon: Double, at: String?): JsonObject? = try {
        client.weatherAt(LatLon(lat, lon), at)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    override suspend fun print(model: JsonObject): ByteArray = client.printBrief(model)
}

/** The weather a brief is compiled with: whether it is being fetched, and when what is there was ([fetchedAt]; null: none yet). */
data class BriefWeather(val loading: Boolean, val fetchedAt: Long?)

/**
 * The brief compiled now (docs/MISSION_BRIEF.md §2.3): the document the person is working on ([doc], a new one of the default format before anything is answered;
 * [hasDoc] whether anything is), its format, the model compiled from it, the plan and what it was built from, how saving stands and the weather.
 */
data class BriefState(
    val doc: JsonObject,
    val hasDoc: Boolean,
    val format: BriefFormat,
    val model: JsonObject,
    val inputs: BriefInputs,
    val summary: String,
    val save: BriefSave?,
    val weather: BriefWeather,
) {
    val formatId: String get() = format.template.id
}

/**
 * The brief being set up (docs/MISSION_BRIEF.md §3), the web's `useBrief`: the person's answers and where they are kept ([BriefKeeper]), the plan as it is now
 * ([BriefSources]), the weather at the planned times, and the brief compiled from all of them.
 *
 * It compiles only while it is open ([open], [close]), as the web's does while the setup is open (`active`), and closes itself when the account changes: another
 * person's brief, plan or weather is never shown. Compiled once without the weather to learn where and when to ask for it (the departure, the arrival and each
 * stop, at the times the routes give them), then with it; the weather is asked for again whenever those places or times change, and on [refreshWeather]. A plan's
 * clock times are wall-clock times read as local at the device's offset at the departure, the offset the light data is worked out for; the generation time is
 * the moment the brief was opened. A compile that fails (a bug, or a plan no client writes) is said ([failed]), never the end of the app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BriefSession(
    private val keeper: BriefKeeper,
    /** What the brief is compiled from, as it is now ([BriefSources.inputs]). */
    private val inputs: Flow<BriefInputs>,
    private val api: BriefApi,
    /** Where the account is followed and the weather fetched: never cancelled with a screen. */
    private val scope: CoroutineScope,
    /** Where the brief is compiled: the work of the whole plan, so not the main thread. */
    private val compileOn: CoroutineDispatcher,
    /** Whose brief it is (null: nobody may keep one now): followed, so another account's brief is never opened. */
    account: Flow<Int?>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    /** The compile: core-brief's, and a test's that fails. */
    private val compile: (BriefFormat, JsonObject, JsonObject, JsonObject) -> JsonObject = CompileBrief::compile,
) {
    private val active = MutableStateFlow(false)
    private val openedAt = MutableStateFlow(clock())
    private val broken = MutableStateFlow(false)

    // Closed for another account: a change the setup makes as it goes (a field written as it leaves) is not the next person's.
    @Volatile
    private var forgotten = false

    /** Whether the brief is open: from [open] to [close], or until the account changes. */
    val opened: StateFlow<Boolean> = active.asStateFlow()

    /** The brief could not be put together from the plan as it is: the setup says so, rather than wait for it. */
    val failed: StateFlow<Boolean> = broken.asStateFlow()

    /**
     * What the weather fetched for the places of [key] (the places' JSON, the ones asked about last) said: null for a place whose server did not answer.
     * [fetchedFor] is the places what is here was fetched for, kept while a fetch for others is out.
     */
    private data class Fetched(
        val key: String? = null,
        val loading: Boolean = false,
        val fetchedAt: Long? = null,
        val departure: JsonObject? = null,
        val arrival: JsonObject? = null,
        val stops: Map<String, JsonObject> = emptyMap(),
        val fetchedFor: String? = null,
    )

    private val fetched = MutableStateFlow(Fetched())
    private var weatherJob: Job? = null

    /** A compile without the weather: what is briefed, and where and when the weather is wanted. */
    private class Bare(
        val document: BriefDocument,
        val inputs: BriefInputs,
        val format: BriefFormat,
        val working: JsonObject,
        val openedAt: Long,
        val places: JsonObject,
        val placesKey: String,
        val offset: Int,
    )

    // Shared by the brief compiled and the weather's asking, so each change is compiled once.
    private val bare: StateFlow<Bare?> = active.flatMapLatest { on ->
        if (!on) {
            flowOf(null)
        } else {
            combine(keeper.document, inputs, openedAt) { document, plan, opened -> Triple(document, plan, opened) }
                // Nothing while the brief is still being read: the blank one is never shown.
                .mapLatest { (document, inputs, opened) -> if (document.loading) null else compiled { compileBare(document, inputs, opened) } }
                .flowOn(compileOn)
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** The brief compiled now, while it is open; null while it is not. */
    val state: StateFlow<BriefState?> = combine(bare, fetched) { b, w -> b to w }
        .mapLatest { (b, w) -> b?.let { compiled { compileFull(it, w) } } }
        .flowOn(compileOn)
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** What the person is told when the pack would not take a change to its brief. */
    val refusals: SharedFlow<String> get() = keeper.refusals

    init {
        scope.launch {
            var first = true
            account.collect { user ->
                // Another account, or nobody: what was open for the one before is closed, and its weather forgotten, before the next one's brief is read.
                if (!first) forget()
                first = false
                keeper.use(user)
            }
        }
        // The weather is asked for while the brief is open, whenever its places or their times change.
        scope.launch {
            bare.collect { b ->
                if (b != null && b.placesKey != fetched.value.key) fetchWeather(b.places, b.placesKey)
            }
        }
    }

    /** Opens the brief: it compiles from now, with this moment as its generation time. */
    fun open() {
        if (active.value) return
        forgotten = false
        openedAt.value = clock()
        keeper.open()
        active.value = true
    }

    /** Closes it, writing a change still waiting first. */
    suspend fun close() {
        active.value = false
        keeper.flush()
    }

    /** Writes a change still waiting now. */
    suspend fun flush() = keeper.flush()

    /**
     * Changes the brief: [transform] of the document as it is (see [BriefKeeper.change]). Called on the main thread, where the screens make their changes, also
     * as the setup closes (a field written as it leaves), but not once it was closed for another account.
     */
    fun change(transform: (JsonObject) -> JsonObject) {
        if (!forgotten) keeper.change(transform)
    }

    /** Asks for the weather again, for the places the brief asks about now (the ones the automatic fetch is keyed by, so the two never differ). */
    fun refreshWeather() {
        val b = bare.value ?: return
        fetchWeather(b.places, b.placesKey)
    }

    private fun forget() {
        forgotten = true
        active.value = false
        synchronized(this) {
            weatherJob?.cancel()
            weatherJob = null
            fetched.value = Fetched()
        }
    }

    private inline fun <T> compiled(block: () -> T): T? = try {
        block().also { broken.value = false }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        broken.value = true
        null
    }

    /**
     * The brief printed as it is compiled now ([BriefApi.print]), with its strip charts' MGRS grid worked out for the print only (core-brief's [StripGrid], as the
     * web's `printBrief` does); null when it is not open. Throws the client's failures ([BriefWords.printFailure] says them).
     */
    suspend fun print(): ByteArray? {
        val model = state.value?.model ?: return null
        return print(model)
    }

    /** [model] printed, a brief compiled here (the one shown when the PDF was asked for), with its strip charts' grid worked out for the print only. */
    suspend fun print(model: JsonObject): ByteArray = api.print(withContext(compileOn) { StripGrid.withStripGrids(model) })

    private fun formatOf(document: BriefDocument): BriefFormat {
        val id = ((document.doc?.get("template") as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content
        return id?.let(BriefFormats::format) ?: BriefFormats.format(BriefKeeper.DEFAULT_FORMAT)!!
    }

    private fun compileBare(document: BriefDocument, inputs: BriefInputs, opened: Long): Bare {
        val format = formatOf(document)
        val working = document.doc ?: BriefKeeper.newDocument(format.template.id)
        val context = buildJsonObject {
            put("generatedAt", iso(opened))
            put("utcOffsetMinutes", offsetAt(opened))
        }
        val model = compile(format, working, inputs.plan, context)
        val places = model["places"] as? JsonObject ?: JsonObject(emptyMap())
        val departureAt = ((places["departure"] as? JsonObject)?.get("at") as? JsonPrimitive)?.contentOrNull
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: opened
        return Bare(document, inputs, format, working, opened, places, places.toString(), offsetAt(departureAt))
    }

    private fun compileFull(b: Bare, w: Fetched): BriefState {
        val context = buildJsonObject {
            put("generatedAt", iso(b.openedAt))
            put("utcOffsetMinutes", b.offset)
            put(
                "weather",
                buildJsonObject {
                    put("departure", w.departure ?: JsonNull)
                    put("arrival", w.arrival ?: JsonNull)
                    put("stops", JsonObject(w.stops))
                },
            )
            w.departure?.get("notams")?.let { put("notams", it) }
        }
        val model = compile(b.format, b.working, b.inputs.plan, context)
        return BriefState(
            doc = b.working,
            hasDoc = b.document.doc != null,
            format = b.format,
            model = model,
            inputs = b.inputs,
            summary = BriefPlans.summary(b.inputs.plan, b.inputs.threatCount),
            save = b.document.save,
            weather = BriefWeather(w.loading, w.fetchedAt),
        )
    }

    private fun fetchWeather(places: JsonObject, key: String) = synchronized(this) {
        weatherJob?.cancel()
        fetched.update { it.copy(key = key, loading = true) }
        weatherJob = scope.launch {
            val answers = coroutineScope {
                fun ask(place: JsonElement?) = async {
                    val p = place as? JsonObject ?: return@async null
                    val lat = (p["lat"] as? JsonPrimitive)?.doubleOrNull ?: return@async null
                    val lon = (p["lon"] as? JsonPrimitive)?.doubleOrNull ?: return@async null
                    api.weatherAt(lat, lon, (p["at"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() })
                }
                val stops = (places["stops"] as? JsonArray).orEmpty()
                listOf(ask(places["departure"]), ask(places["arrival"])) + stops.map { ask(it) }
            }.awaitAll()
            val stops = (places["stops"] as? JsonArray).orEmpty().mapIndexedNotNull { i, stop ->
                val id = ((stop as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
                val report = answers[i + 2]
                if (id != null && report != null) id to report else null
            }.toMap()
            fetched.update { now ->
                when {
                    now.key != key -> now
                    // No answer at all (no connection): what was fetched for these places stays, with its age, as the weather tiles keep theirs.
                    answers.all { it == null } && now.fetchedFor == key && now.fetchedAt != null -> now.copy(loading = false)
                    else -> Fetched(key, false, clock(), answers[0], answers[1], stops, fetchedFor = key)
                }
            }
        }
    }

    private fun offsetAt(ms: Long): Int = zone().rules.getOffset(Instant.ofEpochMilli(ms)).totalSeconds / 60

    private companion object {
        // JavaScript's toISOString: always three digits of milliseconds, in UTC.
        val ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        fun iso(ms: Long): String = ISO.format(Instant.ofEpochMilli(ms))
    }
}
