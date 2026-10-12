package app.ezpztac.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.Tokens

/** The design's Import, as a modal bottom sheet ([ImportSheetContent]). Dismissing it opens nothing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSheet(threatsAllowed: Boolean, onChoose: () -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        ImportSheetContent(threatsAllowed, onChoose = { onDismiss(); onChoose() })
    }
}

/**
 * What can be imported (the design's Import menu): an AMPS mission, local points, threats (only with the account's threats on, and kept on this device). Each
 * opens the system's picker, and every file chosen goes to the review, which says what it turned out to be and where it goes: what a file is comes from what is
 * in it, not the row it was chosen from, so the rows only say what the app takes. Map overlays are not offered (the owner's decision 2 of the redesign).
 */
@Composable
fun ImportSheetContent(threatsAllowed: Boolean, onChoose: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        Column(Modifier.padding(start = Tokens.Spacing.xl.dp, end = Tokens.Spacing.xl.dp, bottom = Tokens.Spacing.md.dp)) {
            Text("Import", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(
                "You check each file, and where it goes, before anything is added.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Tokens.Spacing.xs.dp),
            )
        }
        Choice(EzpzIcons.route, "Mission file", ".msnx · AMPS routes and points", onChoose)
        Choice(EzpzIcons.scatterPlot, "Local points", ".LPS · AMPS point sets", onChoose)
        if (threatsAllowed) Choice(EzpzIcons.crisisAlert, "Threats", ".ths · stays on this device", onChoose, locked = true)
        HorizontalDivider(Modifier.padding(top = Tokens.Spacing.sm.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.padding(start = Tokens.Spacing.xl.dp, top = Tokens.Spacing.lg.dp, end = Tokens.Spacing.xl.dp),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg.dp),
        ) {
            Icon(painterResource(EzpzIcons.openInNew), contentDescription = null, tint = EzpzTheme.status.accent, modifier = Modifier.size(24.dp))
            Text(
                "Or open a file in Files, Gmail or Drive and choose EZ/PZ. We work out the type.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
        }
    }
}

/** One kind the app takes: a 72 dp row, its badge, its name and what it is. [locked] marks one that stays on this device. */
@Composable
private fun Choice(icon: Int, title: String, supporting: String, onClick: () -> Unit, locked: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(role = Role.Button, onClickLabel = "Choose a file", onClick = onClick)
            .padding(start = Tokens.Spacing.lg.dp, end = Tokens.Spacing.xl.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg.dp),
    ) {
        Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (locked) Icon(painterResource(EzpzIcons.lock), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}
