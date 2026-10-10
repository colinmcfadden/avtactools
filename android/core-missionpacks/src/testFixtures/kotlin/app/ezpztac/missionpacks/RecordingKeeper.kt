package app.ezpztac.missionpacks

import kotlinx.serialization.json.JsonArray
import java.io.IOException
import java.util.UUID

/**
 * A library that records what it is asked to keep, as the real keeper keeps it: once per key (asking again answers as the first
 * time), and a point set with no points kept as nothing, since the library cannot hold one. [failNext] makes the next calls fail
 * whole, as a library that cannot be written would.
 */
public class RecordingKeeper : PackKeeper {
    /** One version kept, under its library record's uuid. */
    public data class Record(val pack: String, val version: KeptVersion, val libraryUuid: String)

    private val byKey = LinkedHashMap<String, Record>()

    /** Every call, in order: the pack and the versions asked for. */
    public val calls: MutableList<Pair<String, List<KeptVersion>>> = mutableListOf()

    /** How many of the next calls fail. */
    public var failNext: Int = 0

    /** What is in the library, one record per key, in the order they were first kept. */
    public val records: List<Record> get() = byKey.values.toList()

    override suspend fun keep(pack: String, versions: List<KeptVersion>): List<KeptOutcome> {
        calls += pack to versions
        if (failNext > 0) {
            failNext--
            throw IOException("The library could not be written.")
        }
        return versions.map { version ->
            if (version.kind == "pointset" && (version.data as? JsonArray).isNullOrEmpty()) {
                KeptOutcome.NothingToKeep(version.key, "empty_point_set")
            } else {
                val record = byKey.getOrPut(version.key) {
                    Record(pack, version, UUID.nameUUIDFromBytes("ezpz-pack-kept:${version.key}".toByteArray()).toString())
                }
                KeptOutcome.Saved(version.key, version.kind, record.libraryUuid, record.version.name)
            }
        }
    }
}
