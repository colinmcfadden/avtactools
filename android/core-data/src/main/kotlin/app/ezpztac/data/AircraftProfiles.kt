package app.ezpztac.data

import app.ezpztac.model.AircraftDraft
import app.ezpztac.model.AircraftProfile
import app.ezpztac.network.aircraftProfiles
import app.ezpztac.sync.RecordFeed
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.SyncRepository
import app.ezpztac.sync.SyncScheduler
import app.ezpztac.sync.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** Where the admin's master list of airframes comes from (`GET /api/aircraft-profiles`). */
fun interface MasterProfileSource {
    suspend fun fetch(): List<AircraftProfile>
}

/** Where the master list is kept between launches, so the airframes are there with no signal. */
interface MasterProfileStore {
    /** What was last kept, or nothing. */
    fun read(): List<AircraftProfile>

    fun write(profiles: List<AircraftProfile>)
}

/** Which airframe this device plans with (the web keeps it in `localStorage` under `avtac.aircraftProfile`): by slug, so it survives a database that renumbers ids. */
interface ActiveAircraftChoice {
    fun slug(): String?

    fun choose(slug: String)
}

/**
 * One airframe in the list. [own] profiles are the user's own and sync; the master list is the admin's and does not. A profile made here that the
 * server has not yet seen has no [AircraftProfile.slug] (the server makes it from the designation), and so cannot be chosen or put on a diagram
 * until it has: a diagram that named a slug nobody knew yet would name nothing once the real one came.
 *
 * A [conflictOf] entry is the user's version of a profile another device changed, kept beside the other so nothing is lost. It is listed so it
 * can be settled, but is never chosen: it carries the same slug as the profile it is a copy of.
 */
data class AircraftEntry(
    val profile: AircraftProfile,
    val own: Boolean,
    /** The record's identity, for an own profile. */
    val uuid: String?,
    /** Whether the server has an own profile, is yet to be told, or it is the copy kept beside a conflict. */
    val sync: SyncStatus?,
    /** The uuid of the profile this is a conflict copy of, if it is one. */
    val conflictOf: String? = null,
) {
    val usable: Boolean get() = profile.slug.isNotEmpty() && conflictOf == null

    /** An own profile the server has not yet named: it will be usable once it has synced. */
    val waiting: Boolean get() = own && conflictOf == null && profile.slug.isEmpty()
}

/**
 * The aircraft the app knows: the master list (the admin's, kept on the device and refreshed when there is signal) and the user's own profiles
 * (from the sync store, so they are made and changed offline), and the one this device plans with. Until anything has loaded, and whenever the chosen
 * one has gone, the built-in UH-60L stands in, so the map and the planner always have real geometry (the web's rule).
 *
 * The chosen airframe is the mission default: it is what a new aircraft on the map is placed as, what LZ capacity is measured for, and what a
 * route starts from. Aircraft already on a diagram keep the profile they were placed with.
 */
class AircraftProfiles(
    feed: RecordFeed,
    private val store: MasterProfileStore,
    private val source: MasterProfileSource,
    private val choice: ActiveAircraftChoice,
    scope: CoroutineScope,
    private val sync: SyncRepository,
    private val scheduler: SyncScheduler,
) {
    private val master = MutableStateFlow(store.read().filter { it.isSystem })

    /** Every airframe, the master list first (in the server's order) and then the user's own by name; the unusable ones are in it, marked. */
    val entries: StateFlow<List<AircraftEntry>> = combine(master, feed.observe(RecordKind.AIRCRAFT)) { masters, records ->
        masters.map { AircraftEntry(it, own = false, uuid = null, sync = null) } +
            records.map { r ->
                val raw = JsonObject(r.data + ("name" to JsonPrimitive(r.name)))
                val slug = (raw["slug"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                AircraftEntry(
                    AircraftProfile.normalize(raw).copy(slug = slug, isSystem = false, id = r.serverId?.toLong()),
                    own = true, uuid = r.uuid, sync = r.status, conflictOf = r.conflictOf,
                )
            }.sortedBy { it.profile.name.lowercase() }
    }.stateIn(scope, SharingStarted.Eagerly, master.value.map { AircraftEntry(it, own = false, uuid = null, sync = null) })

    /** The airframes that can be chosen and put on a diagram. */
    val profiles: StateFlow<List<AircraftProfile>> = entries.map { all -> all.filter { it.usable }.map { it.profile } }
        .stateIn(scope, SharingStarted.Eagerly, entries.value.filter { it.usable }.map { it.profile })

    private val chosen = MutableStateFlow(choice.slug())

    /** The mission aircraft. */
    val active: StateFlow<AircraftProfile> = combine(profiles, chosen) { list, slug -> resolve(list, slug) }
        .stateIn(scope, SharingStarted.Eagerly, resolve(profiles.value, chosen.value))

    private fun resolve(list: List<AircraftProfile>, slug: String?): AircraftProfile =
        list.firstOrNull { slug != null && it.slug == slug } ?: list.firstOrNull { it.slug == AircraftProfile.FALLBACK.slug } ?: AircraftProfile.FALLBACK

    /** Chooses [slug] as the mission aircraft and remembers it. False (and nothing changes) if no airframe that can be chosen has it. */
    fun select(slug: String): Boolean {
        if (profiles.value.none { it.slug == slug }) return false
        choice.choose(slug)
        chosen.value = slug
        return true
    }

    // -- The user's own profiles ------------------------------------------------------------------------------------------

    /**
     * Makes a profile of [draft], at once and with no signal: it is kept on the device and sent when there is one. Null when it is made, or what is wrong
     * with the draft in words (nothing is made then). It cannot be chosen until the server has named it.
     */
    suspend fun create(draft: AircraftDraft): String? = when (val checked = draft.check()) {
        is AircraftDraft.Checked.Refused -> checked.message
        is AircraftDraft.Checked.Valid -> {
            sync.create(RecordKind.AIRCRAFT, checked.name, checked.data)
            scheduler.requestSync()
            null
        }
    }

    /** Changes the own profile [uuid] to what [draft] says. Null when it is saved; else why not. What the form does not show is left as it was. */
    suspend fun update(uuid: String, draft: AircraftDraft): String? {
        val current = sync.record(RecordKind.AIRCRAFT, uuid) ?: return "That aircraft is no longer here."
        return when (val checked = draft.check(current.data)) {
            is AircraftDraft.Checked.Refused -> checked.message
            is AircraftDraft.Checked.Valid -> {
                sync.edit(RecordKind.AIRCRAFT, uuid, name = checked.name, data = checked.data)
                scheduler.requestSync()
                null
            }
        }
    }

    /**
     * Deletes the own profile [uuid]. If it is the mission aircraft the choice falls back to the UH-60L; a diagram that was placed with it keeps
     * the geometry it was placed with, because the geometry is saved in the diagram.
     */
    suspend fun delete(uuid: String) {
        sync.delete(RecordKind.AIRCRAFT, uuid)
        scheduler.requestSync()
    }

    /**
     * Asks the server for the master list and keeps it. Throws what the call throws (no signal, a signed-out session) with the kept list as it was;
     * false if the server sent no master airframes, which is taken as a fault and not as "there are none".
     */
    suspend fun refresh(): Boolean {
        val fetched = source.fetch().filter { it.isSystem }
        if (fetched.isEmpty()) return false
        if (fetched != master.value) {
            store.write(fetched)
            master.value = fetched
        }
        return true
    }

    /** Like [refresh], for a caller with nobody to tell: a failure leaves the list as it was. */
    suspend fun refreshQuietly() {
        try {
            refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}

/** A master list that is kept only while the process lives: for a screen tried without a device, and for tests. */
class InMemoryMasterProfileStore(var kept: List<AircraftProfile> = emptyList()) : MasterProfileStore {
    override fun read(): List<AircraftProfile> = kept

    override fun write(profiles: List<AircraftProfile>) {
        kept = profiles
    }
}

/** A choice that is kept only while the process lives. */
class InMemoryAircraftChoice(var chosen: String? = null) : ActiveAircraftChoice {
    override fun slug(): String? = chosen

    override fun choose(slug: String) {
        chosen = slug
    }
}

/** The master list as a file: written whole to a temporary file and moved into place, so a crash never leaves half a list. A file that cannot be read is no list. */
class FileMasterProfileStore(private val file: File) : MasterProfileStore {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun read(): List<AircraftProfile> = try {
        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(AircraftProfile.serializer()), file.readText())
    } catch (_: Exception) {
        emptyList()
    }

    override fun write(profiles: List<AircraftProfile>) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(AircraftProfile.serializer()), profiles))
        if (!temporary.renameTo(file)) {
            file.delete()
            check(temporary.renameTo(file)) { "could not keep the aircraft list" }
        }
    }
}

/** [MasterProfileSource] over the real client. */
class ApiClientMasterProfileSource(private val client: app.ezpztac.network.ApiClient) : MasterProfileSource {
    override suspend fun fetch(): List<AircraftProfile> = client.aircraftProfiles().map {
        AircraftProfile.normalize(app.ezpztac.network.ApiJson.encodeToJsonElement(app.ezpztac.network.AircraftProfileDto.serializer(), it) as JsonObject)
    }
}
