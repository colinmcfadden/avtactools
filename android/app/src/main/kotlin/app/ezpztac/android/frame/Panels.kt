package app.ezpztac.android.frame

import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.SheetDetent
import app.ezpztac.network.ApiUser

/**
 * The panels the bar along the bottom switches between (docs/native-design: LZ/PZ, Routes, Threats, Imports; Pack comes first once mission
 * packs have their screens). [entitlement] is the feature that must be on for the panel to be offered: the web hides the same ones.
 */
enum class Panel(val label: String, val icon: Int, val selectedIcon: Int, val entitlement: String?) {
    Lz("LZ/PZ", EzpzIcons.flightLand, EzpzIcons.flightLandFill, null),
    Routes("Routes", EzpzIcons.route, EzpzIcons.routeFill, "routes"),
    Threats("Threats", EzpzIcons.crisisAlert, EzpzIcons.crisisAlertFill, "threats"),
    Imports("Imports", EzpzIcons.moveToInbox, EzpzIcons.moveToInboxFill, null),
}

/** The panels [user] is offered: those whose feature is on (a missing key is on). */
fun panelsFor(user: ApiUser): List<Panel> = Panel.entries.filter { panel -> panel.entitlement?.let(user::hasFeature) ?: true }

/** What the bar's tabs carry: the threats in the picture on this device, and the sets of local points brought in. */
data class PanelCounts(val threats: Int = 0, val imports: Int = 0)

/**
 * What picking [picked] does, with [current] showing at [detent] (the web's sheet does the same): another panel opens, to half if the sheet
 * was lowered and at the height it was otherwise; the panel already showing goes down to its header, or up to half when it is down.
 */
fun pick(current: Panel, detent: SheetDetent, picked: Panel): Pair<Panel, SheetDetent> = when {
    picked != current -> picked to (if (detent == SheetDetent.Lowered) SheetDetent.Half else detent)
    detent == SheetDetent.Lowered -> picked to SheetDetent.Half
    else -> picked to SheetDetent.Lowered
}

/** The panel to show from [panels]: [chosen] if it is offered, the first otherwise (an admin turned its feature off). */
fun shownPanel(chosen: Panel, panels: List<Panel>): Panel = chosen.takeIf { it in panels } ?: panels.firstOrNull() ?: Panel.Lz

/**
 * The bar along the bottom (Material's navigation bar): one tab a panel, the one showing marked, threats counted in the alert colours (they
 * are what a crew least wants to miss) and imports in the plain ones.
 */
@Composable
fun PanelBar(panels: List<Panel>, current: Panel, counts: PanelCounts, onPick: (Panel) -> Unit, modifier: Modifier = Modifier) {
    NavigationBar(modifier = modifier, containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
        panels.forEach { panel ->
            val selected = panel == current
            // Material clears the icon's semantics when there is a label, the badge's with it, so the count is said by the tab itself.
            val spoken = spokenCount(panel, counts)
            NavigationBarItem(
                selected = selected,
                onClick = { onPick(panel) },
                modifier = if (spoken != null) Modifier.semantics { contentDescription = "${panel.label}, $spoken" } else Modifier,
                icon = {
                    BadgedBox(badge = { PanelBadge(panel, counts) }) {
                        Icon(painterResource(if (selected) panel.selectedIcon else panel.icon), contentDescription = null)
                    }
                },
                label = { Text(panel.label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium) },
            )
        }
    }
}

/** What a tab's count is, in words: "2 threats", "3 items"; null for a tab with nothing to count. */
internal fun spokenCount(panel: Panel, counts: PanelCounts): String? = when (panel) {
    Panel.Threats -> counts.threats.takeIf { it > 0 }?.let { if (it == 1) "1 threat" else "$it threats" }
    Panel.Imports -> counts.imports.takeIf { it > 0 }?.let { if (it == 1) "1 item" else "$it items" }
    Panel.Lz, Panel.Routes -> null
}

@Composable
internal fun PanelBadge(panel: Panel, counts: PanelCounts) {
    when (panel) {
        Panel.Threats -> if (counts.threats > 0) Badge { Text(shown(counts.threats)) }
        Panel.Imports -> if (counts.imports > 0) {
            Badge(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) { Text(shown(counts.imports)) }
        }
        Panel.Lz, Panel.Routes -> Unit
    }
}

/** A count in a badge: past 99 it says 99+, as the web's does. */
internal fun shown(count: Int): String = if (count > 99) "99+" else count.toString()
