package app.ezpztac.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.Tokens

/**
 * The mission aircraft (the web's `AircraftPicker`): what a new aircraft on the map is placed as, how many fit in the landing zone, and the
 * separation alerts. Aircraft already on the diagram keep the airframe they were placed as. Numbers that are only a spec sheet's are said to be
 * (in words, not a tooltip: there is no hover on a phone).
 */
@Composable
internal fun AircraftPicker(aircraft: AircraftUi, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
        Choice("Mission aircraft", aircraft.activeLabel, aircraft.options.map { it.label to it.slug }, onSelect)
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg.dp)) {
            Text("${aircraft.spacingM} m spacing", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${aircraft.cruiseKts} kt", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (aircraft.unverified) {
            Text("Unverified performance", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = EzpzTheme.status.warning)
            Text(
                "These numbers are from published specifications, not from an AMPS vehicle model. Check them before planning fuel.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (aircraft.waiting.isNotEmpty()) {
            Text(
                "Waiting to sync before it can be chosen: ${aircraft.waiting.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
