package app.ezpztac.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.Tokens
import app.ezpztac.geo.PlaceChoice
import app.ezpztac.geo.PlaceReading
import app.ezpztac.geo.PlaceSearch
import app.ezpztac.model.LatLon

/** One row of the list: what it says large, what it says under that, and the place it goes to. */
internal data class PlaceRow(val main: String, val note: String, val choice: PlaceChoice)

/**
 * The rows for a reading, as the web's target field lays them out (`TargetField.jsx`): one place as two rows, its grid and its lat/long; an
 * unsigned pair's readings one row each, nearest [near] first, each its lat/long over its grid.
 */
internal fun placeRows(reading: PlaceReading, near: LatLon?): List<PlaceRow> {
    val choices = PlaceSearch.nearestFirst(reading.choices, near)
    val only = choices.singleOrNull()
    if (only != null) {
        // A grid says its precision on its own row; a lat/long says how it was read on its.
        return listOf(
            PlaceRow(only.mgrs, if (only.isGrid) only.how else "MGRS grid", only),
            PlaceRow(only.latLong, if (only.isGrid) "Lat/long of the grid square's centre" else "Lat/long, ${only.how.lowercase()}", only),
        )
    }
    return choices.map { PlaceRow(it.latLong, it.mgrs, it) }
}

/** What a screen reader is told the list holds, as the web's status says it. Empty when there is nothing to say. */
internal fun placeSummary(reading: PlaceReading, rows: List<PlaceRow>): String = buildList {
    reading.message?.let(::add)
    when {
        rows.size == 2 && reading.choices.size == 1 -> add("${rows[0].main}, ${rows[1].main}.")
        rows.isNotEmpty() -> add("${rows.size} places.")
    }
}.joinToString(" ")

/**
 * The places what is typed in the map's search could be, under the field as it is typed (the web's target field): a sentence when it is
 * not one place, and a row for each way to go, which [onChoose] takes the map to. Each row is a 48 dp button named for where it goes; the
 * sentence and the count are said politely by [PlaceAnnouncer], which stays composed while the list comes and goes.
 */
@Composable
internal fun PlaceList(reading: PlaceReading, rows: List<PlaceRow>, onChoose: (PlaceChoice) -> Unit, modifier: Modifier = Modifier) {
    if (rows.isEmpty() && reading.message == null) return
    Surface(
        modifier = modifier.widthIn(max = 520.dp).fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 3.dp,
    ) {
        Column {
            reading.message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Tokens.Spacing.lg.dp, vertical = Tokens.Spacing.sm.dp),
                )
            }
            rows.forEachIndexed { index, row ->
                if (index > 0 || reading.message != null) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable(role = Role.Button, onClickLabel = "Go there") { onChoose(row.choice) }
                        .padding(horizontal = Tokens.Spacing.lg.dp, vertical = Tokens.Spacing.sm.dp)
                        // Read as one: where it goes, then what it is.
                        .semantics(mergeDescendants = true) {},
                ) {
                    Text(row.main, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold))
                    Text(row.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * A polite live region that stays composed with the search, its words changed as the list changes: one made with its words is never heard
 * (AGENTS.md §14), so it is not the list's own.
 */
@Composable
internal fun PlaceAnnouncer(summary: String) {
    Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = summary })
}

