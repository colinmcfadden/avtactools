package app.ezpztac.packs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.PillButton
import app.ezpztac.designsystem.PillStyle
import app.ezpztac.designsystem.Tokens

/** What the switcher does: open a workspace (null for the Library), answer an invitation, close, and make a new pack (offered when given). */
class SwitcherActions(
    val pick: (String?) -> Unit = {},
    val answer: (id: Int, accept: Boolean) -> Unit = { _, _ -> },
    val close: () -> Unit = {},
    val newPack: (() -> Unit)? = null,
)

/**
 * The workspace switcher (the design's Switcher, the web's WorkspaceSwitcher): invitations waiting, the Library, the packs (those still worked on, then
 * the finished ones) and the teams line. Asks for its lists again each time it opens: someone may have added this person meanwhile. A modal bottom sheet on a
 * phone; a tablet shows [SwitcherContent] in a popup at the rail's tile.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwitcherSheet(viewModel: WorkspaceViewModel, onDismiss: () -> Unit, onPick: (String?) -> Unit = {}, onNewPack: (() -> Unit)? = null) {
    val state by viewModel.switcher.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.switcherOpened() }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        SwitcherContent(state, actionsOf(viewModel, onDismiss, onPick, onNewPack), Modifier.verticalScroll(rememberScrollState()))
    }
}

/**
 * The switcher's actions over the view model: choosing a workspace closes it first (and tells [onPick], so the frame can show the pack's panel once
 * it opens), and so do an invitation that let the person in to a pack and *New pack* ([onNewPack]).
 */
fun actionsOf(viewModel: WorkspaceViewModel, close: () -> Unit, onPick: (String?) -> Unit = {}, onNewPack: (() -> Unit)? = null) = SwitcherActions(
    pick = { uuid -> close(); onPick(uuid); viewModel.switchTo(uuid) },
    answer = { id, accept -> viewModel.answer(id, accept, onJoined = close) },
    close = close,
    newPack = onNewPack?.let { open -> { close(); open() } },
)

/**
 * What the switcher shows, in a column that does not scroll by itself: its host scrolls it (the phone's sheet does; a tablet's menu already scrolls what it
 * holds, and a scroll inside that one would be measured without a height and fail).
 */
@Composable
fun SwitcherContent(state: SwitcherUi, actions: SwitcherActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(bottom = Tokens.Spacing.lg.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = Tokens.Spacing.lg.dp, end = Tokens.Spacing.sm.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Switch workspace", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f).semantics { heading() })
            IconButton(onClick = actions.close, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(EzpzIcons.close), contentDescription = "Close workspace switcher", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        state.invites.forEach { invite -> InviteCard(invite, enabled = !state.busy, actions) }
        state.answerError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = Tokens.Spacing.lg.dp, top = Tokens.Spacing.sm.dp, end = Tokens.Spacing.lg.dp))
        }
        state.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = Tokens.Spacing.lg.dp, top = Tokens.Spacing.sm.dp, end = Tokens.Spacing.lg.dp))
        }
        Label("Workspace")
        val inLibrary = state.current == null
        Box(Modifier.padding(horizontal = Tokens.Spacing.sm.dp)) {
            Row(
                Modifier.fillMaxWidth().background(if (inLibrary) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.large)
                    .clickable(enabled = !state.busy, role = Role.Button) { actions.pick(null) }.semantics { selected = inLibrary }
                    .padding(start = Tokens.Spacing.sm.dp, top = Tokens.Spacing.md.dp, end = Tokens.Spacing.md.dp, bottom = Tokens.Spacing.md.dp),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg.dp),
            ) {
                Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainerLow, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(EzpzIcons.collectionsBookmark), contentDescription = null, tint = EzpzTheme.status.accent, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("Library", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = if (inLibrary) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface)
                    Text(state.library, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (inLibrary) Icon(painterResource(EzpzIcons.check), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = Tokens.Spacing.sm.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(end = Tokens.Spacing.sm.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Label("Mission Packs") }
            actions.newPack?.let { newPack ->
                TextButton(onClick = newPack, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp).padding(top = Tokens.Spacing.sm.dp)) {
                    Icon(painterResource(EzpzIcons.add), contentDescription = null, modifier = Modifier.size(20.dp))
                    Text("New pack", modifier = Modifier.padding(start = Tokens.Spacing.sm.dp))
                }
            }
        }
        if (state.loaded && state.packs.isEmpty()) {
            Text(
                "No packs yet. A Mission Pack is a shared space for one operation: everyone in it edits the same LZ/PZs, routes and point sets.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Tokens.Spacing.lg.dp, vertical = Tokens.Spacing.sm.dp),
            )
        }
        state.packs.forEach { pack -> PackRow(pack, enabled = !state.busy, actions) }
        HorizontalDivider(Modifier.padding(vertical = Tokens.Spacing.sm.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.padding(horizontal = Tokens.Spacing.lg.dp, vertical = Tokens.Spacing.sm.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
            Icon(painterResource(EzpzIcons.diversity3), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Text(state.teams, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Tokens.Spacing.lg.dp, top = Tokens.Spacing.lg.dp, end = Tokens.Spacing.lg.dp, bottom = Tokens.Spacing.sm.dp).semantics { heading() },
    )
}

/** An invitation waiting: who asked and to what, and Decline or Accept (named for the pack, as the design's buttons are). */
@Composable
private fun InviteCard(invite: InviteUi, enabled: Boolean, actions: SwitcherActions) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large,
        modifier = Modifier.padding(start = Tokens.Spacing.lg.dp, top = Tokens.Spacing.sm.dp, end = Tokens.Spacing.lg.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(Tokens.Spacing.lg.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg.dp)) {
                PackTile(EzpzIcons.groupAddFill)
                Text(
                    buildAnnotatedString {
                        append("${invite.who} invited you to ")
                        if (invite.team) append("the team ")
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onTertiaryContainer)) { append(invite.what) }
                        invite.role?.let { append(" as $it") }
                    },
                    style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                )
            }
            FlowRow(
                Modifier.fillMaxWidth().padding(top = Tokens.Spacing.md.dp),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp),
            ) {
                PillButton(
                    "Decline", onClick = { actions.answer(invite.id, false) }, style = PillStyle.Outlined, enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = "Decline the invitation to ${invite.what}" },
                )
                PillButton(
                    "Accept", onClick = { actions.answer(invite.id, true) }, icon = EzpzIcons.check, enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = "Accept the invitation to ${invite.what}" },
                )
            }
        }
    }
}

/** A pack in the list: its tile, name, members and this person's role (or when it was finished), who is here and what it holds. */
@Composable
private fun PackRow(pack: PackRowUi, enabled: Boolean, actions: SwitcherActions) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button) { actions.pick(pack.uuid) }.semantics { selected = pack.current }
            .background(if (pack.current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = Tokens.Spacing.lg.dp, vertical = Tokens.Spacing.md.dp),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg.dp),
    ) {
        PackTile(if (pack.finished) EzpzIcons.groups else EzpzIcons.groupsFill, finished = pack.finished)
        Column(Modifier.weight(1f)) {
            Text(pack.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = if (pack.finished) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
                Text(pack.meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (pack.finished) Icon(painterResource(EzpzIcons.lock), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
            if (pack.chips.isNotEmpty()) {
                FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    pack.chips.forEachIndexed { i, chip ->
                        val here = chip.endsWith("here now")
                        if (here) Box(Modifier.align(Alignment.CenterVertically).size(8.dp).background(EzpzTheme.status.success, CircleShape))
                        Text(
                            if (i > 0) "· $chip" else chip, style = MaterialTheme.typography.bodyMedium,
                            color = if (here) EzpzTheme.status.success else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (pack.current) Icon(painterResource(EzpzIcons.check), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = Tokens.Spacing.sm.dp))
    }
}

/** A pack's square tile, in the pack's colour (violet: a pack's identity, never a state); a finished pack's is quieter. */
@Composable
private fun PackTile(icon: Int, finished: Boolean = false) {
    Box(
        Modifier.size(40.dp).background(if (finished) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = if (finished) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(22.dp))
    }
}
