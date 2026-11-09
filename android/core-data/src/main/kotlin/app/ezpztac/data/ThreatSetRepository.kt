package app.ezpztac.data

import app.ezpztac.sync.LocalRecord
import app.ezpztac.sync.MAX_NAME
import app.ezpztac.sync.RecordFeed
import app.ezpztac.sync.RecordKind
import app.ezpztac.sync.SyncRepository
import app.ezpztac.sync.SyncScheduler
import app.ezpztac.sync.cutToCharacters
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The named sets of threats saved on this device and synced with the account, the web's `/api/threatsets`: each a record whose document is `{version: 1, threats: [...]}`
 * ([app.ezpztac.model.ThreatSets]). What is open and how it is edited is [ThreatStore]'s; this is the Library's side of them.
 */
@Singleton
class ThreatSetRepository @Inject constructor(
    private val sync: SyncRepository,
    private val feed: RecordFeed,
    private val scheduler: SyncScheduler,
) {
    /** Every saved set, as the Library lists them. Each is read whole, so this is for the Library's page, not for following a few ([observe] with ids). */
    fun observe(): Flow<List<LocalRecord>> = feed.observe(KIND)

    /** The saved sets named in [uuids], the only rows read. */
    fun observe(uuids: Set<String>): Flow<List<LocalRecord>> = feed.observe(KIND, uuids)

    suspend fun records(): List<LocalRecord> = sync.records(KIND)

    suspend fun record(uuid: String): LocalRecord? = sync.record(KIND, uuid)

    suspend fun create(uuid: String, name: String, document: JsonObject): LocalRecord = sync.create(KIND, name, document, uuid)

    /** Writes a changed name or document ([name] or [document] null for one not changed). */
    suspend fun edit(uuid: String, name: String?, document: JsonObject?): LocalRecord = sync.edit(KIND, uuid, name, document)

    suspend fun rename(uuid: String, name: String) {
        sync.edit(KIND, uuid, name = name)
        scheduler.requestSync()
    }

    suspend fun delete(uuid: String) {
        sync.delete(KIND, uuid)
        scheduler.requestSync()
    }

    /** A copy of a saved set under an identity of its own, called [name]. Null when the set is not there. */
    suspend fun duplicate(uuid: String, name: String): LocalRecord? {
        val record = sync.record(KIND, uuid) ?: return null
        return sync.create(KIND, name, record.data, UUID.randomUUID().toString()).also { scheduler.requestSync() }
    }

    fun requestSync() = scheduler.requestSync()

    /** [name], or the next `NAME 2` no saved set has, cut to what the server stores and never cutting the number. */
    suspend fun freeName(name: String): String = free(name, records().map { it.name })

    companion object {
        private val KIND = RecordKind.THREAT_SET

        fun free(name: String, taken: Collection<String>): String {
            val used = taken.mapTo(HashSet()) { it.trim().lowercase() }
            val whole = cutToCharacters(name, MAX_NAME).trimEnd()
            if (whole.lowercase() !in used) return whole
            var n = 2
            while (true) {
                val suffix = " $n"
                val candidate = cutToCharacters(name, MAX_NAME - suffix.length).trimEnd() + suffix
                if (candidate.lowercase() !in used) return candidate
                n++
            }
        }
    }
}
