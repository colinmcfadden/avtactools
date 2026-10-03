package app.ezpztac.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.Banner
import app.ezpztac.designsystem.BannerKind
import app.ezpztac.designsystem.EzpzTextField
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.TextAction
import app.ezpztac.designsystem.Tokens
import app.ezpztac.model.NotamGroup
import app.ezpztac.model.Notams
import app.ezpztac.model.WeatherAge

/**
 * The weather at an analysed landing zone: the nearest station's wind, temperature and altimeter, which station and how far, how old the report is, and the
 * NOTAMs around the target. [nowMillis] is the time the age is measured to, given by the host so that this does not tick by itself.
 *
 * A report is never shown without its age, and an old one says so in words (not only in colour): a crew must not take a morning's weather for now's. A
 * NOTAM search that could not be done says so too, because "none fetched" is not "none active".
 */
@Composable
internal fun WeatherSection(weather: WeatherUi, nowMillis: Long, onRefresh: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Weather", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (weather.fetching) Text("Updating…", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else TextAction("Refresh", onClick = onRefresh, modifier = Modifier.semantics { contentDescription = "Update the weather" })
        }
        Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {         // equal height: the wind's gust can make its tile taller
            WindTile(weather, Modifier.weight(1f).fillMaxHeight())
            WeatherTile("Temp", weather.temp, "°C", Modifier.weight(1f).fillMaxHeight())
            WeatherTile("Altimeter", weather.altimeter, "inHg", Modifier.weight(1f).fillMaxHeight())
        }
        weather.station?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
        weather.category?.let { Text("Flight category $it", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface) }
        AgeLine(weather, nowMillis)
        weather.failure?.let { Banner(it, BannerKind.Warning) }
        weather.notams?.let { NotamsPanel(it) }
    }
}

@Composable
private fun AgeLine(weather: WeatherUi, nowMillis: Long) {
    val fetched = weather.fetchedAtMillis
    when {
        fetched == null && weather.fetching -> Text("Fetching the weather…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        fetched == null && weather.failure == null -> Text("The weather has not been fetched yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        fetched == null -> Unit                                                                // the failure says why
        else -> {
            val age = WeatherAge.words(nowMillis - fetched)
            val stale = nowMillis - fetched > WeatherAge.STALE_AFTER_MS
            if (!weather.hasReport) {
                Text("No weather station reported near this position. Checked $age.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (stale) {
                // Words as well as colour: this report is old.
                Text("Fetched $age. This report is old.", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = EzpzTheme.status.warning)
            } else {
                Text("Fetched $age.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WindTile(weather: WeatherUi, modifier: Modifier) {
    val said = when {
        !weather.hasReport || weather.windSpeed == "--" -> "Wind not reported"
        weather.windVariable -> "Wind variable at ${weather.windSpeed} knots"
        weather.windFrom != null -> "Wind from ${weather.windFrom} degrees at ${weather.windSpeed} knots" + (weather.windGust?.let { ", gusting ${it.removePrefix("G")}" } ?: "")
        else -> "Wind ${weather.windSpeed} knots"
    }
    WeatherTile(
        label = "Wind", value = weather.windSpeed + (weather.windGust?.let { " $it" } ?: ""), unit = "kt", modifier = modifier, description = said,
        leading = {
            when {
                weather.windVariable -> Text("VRB", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // An arrow that points where the wind is going: a wind *from* 270 blows towards 090, and ↓ turned by the bearing it comes from points that way.
                weather.windFrom != null -> Text("↓", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.graphicsLayer { rotationZ = weather.windFrom.toFloat() })
                else -> Unit
            }
        },
    )
}

@Composable
private fun WeatherTile(label: String, value: String, unit: String, modifier: Modifier, description: String? = null, leading: @Composable () -> Unit = {}) {
    Surface(
        modifier = modifier.then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        shape = RoundedCornerShape(Tokens.Radius.sm.dp), color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(Tokens.Spacing.md.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
                leading()
                Text(value, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
            }
            Text(unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// -- NOTAMs ------------------------------------------------------------------------------------------------------------------

@Composable
private fun NotamsPanel(notams: Notams) {
    var open by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("") }
    when (notams) {
        Notams.Clear -> Text("NOTAMs · none active within 10 nm", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Notams.Unavailable -> Banner("NOTAMs could not be fetched. Do not take that to mean there are none: check them before flight.", BannerKind.Warning)
        is Notams.Listed -> {
            Row(
                Modifier.fillMaxWidth().heightIn(min = Tokens.Size.touchTarget.dp).clickable { open = !open }.semantics {
                    contentDescription = "NOTAMs, ${notams.count} active within 10 nautical miles. ${if (open) "Tap to hide them" else "Tap to read them"}"
                },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("NOTAMs · ${notams.count} active within 10 nm", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(if (open) "Hide" else "Read", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            if (open) {
                if (notams.count > 3) EzpzTextField(filter, { filter = it }, "Search the NOTAMs", hint = "A word from the text, or the kind (Obstruction, Airspace…).")
                val shown = NotamFilter.apply(notams.groups, filter)
                if (shown.isEmpty()) Text("No NOTAM matches that.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                shown.forEach { group ->
                    Text(group.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    group.texts.forEach { text -> Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface) }
                }
            }
        }
    }
}

/** Which NOTAMs a search matches: a word of the text or of the kind they are about, in any case. A kind that matches brings all of its NOTAMs. */
internal object NotamFilter {
    fun apply(groups: List<NotamGroup>, query: String): List<NotamGroup> {
        val q = query.trim()
        if (q.isEmpty()) return groups
        return groups.mapNotNull { g ->
            if (g.title.contains(q, ignoreCase = true)) g else g.texts.filter { it.contains(q, ignoreCase = true) }.takeIf { it.isNotEmpty() }?.let { NotamGroup(g.title, it) }
        }
    }
}
