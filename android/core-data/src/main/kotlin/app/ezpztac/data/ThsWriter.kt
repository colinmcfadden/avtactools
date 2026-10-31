package app.ezpztac.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.core.database.sqlite.transaction
import app.ezpztac.formats.ThsExport
import app.ezpztac.model.Threat
import java.io.File
import java.time.Instant
import javax.inject.Inject

/** The AMPS threat file an export is built on: `backend/threat_template.ths`, a real file with its data removed, so the schema AMPS expects survives exactly. */
public fun interface ThsTemplate {
    public fun bytes(): ByteArray
}

/**
 * Writes an AMPS `.ths` from threats: copies the bundled template, empties its `THREATS`, `THREATRADAR`, `SYSTEM` and `LINKS` tables, and inserts the rows
 * [ThsExport] says (what goes in each column is decided there, and held to what the backend writes: `contracts/fixtures/threats/export.json`). The file is the
 * platform's SQLite writing it, because that is a database and not a format to imitate.
 *
 * Things that are easy to get wrong:
 * - **The schema must come out as it went in.** Opening a database with Android's API adds an `android_metadata` table for the locale unless it is told not
 *   to ([SQLiteDatabase.NO_LOCALIZED_COLLATORS]); a table AMPS did not expect is the kind of thing that makes an import fail somewhere nobody can see.
 *   The template's one virtual table (`@INFO_SCHEMA_COLUMNS`, a module only AMPS has) is never touched, which SQLite allows.
 * - **The file holds threats, so it does not stay.** It is built in a temporary file that is deleted whatever happens, journal files included.
 */
public class ThsWriter @Inject constructor(private val template: ThsTemplate) {
    /** The `.ths` for [threats], dated [now] (UTC, as AMPS writes it). */
    public fun build(threats: List<Threat>, now: Instant = Instant.now()): ByteArray {
        val rows = ThsExport.rows(threats, ThsExport.dtg(now))
        val file = File.createTempFile("threats", ".ths")
        try {
            file.writeBytes(template.bytes())
            val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
            try {
                db.transaction {
                    for (table in EMPTIED) delete(table, null, null)
                    rows.threats.forEach { insertOrThrow("THREATS", null, values(it)) }
                    rows.radars.forEach { insertOrThrow("THREATRADAR", null, values(it)) }
                    rows.systems.forEach { insertOrThrow("SYSTEM", null, values(it)) }
                }
            } finally {
                db.close()
            }
            return file.readBytes()
        } finally {
            // The file, and any journal the platform left beside it.
            file.delete()
            File(file.path + "-journal").delete()
            File(file.path + "-wal").delete()
            File(file.path + "-shm").delete()
        }
    }

    private fun values(row: Map<String, Any?>): ContentValues {
        val values = ContentValues()
        for ((column, value) in row) {
            when (value) {
                null -> values.putNull(column)
                is Long -> values.put(column, value)
                is Double -> values.put(column, value)
                is String -> values.put(column, value)
                else -> error("a .ths row holds only numbers, text and nothing: $column is ${value::class.simpleName}")
            }
        }
        return values
    }

    private companion object {
        val EMPTIED = listOf("THREATS", "THREATRADAR", "SYSTEM", "LINKS")
    }
}
