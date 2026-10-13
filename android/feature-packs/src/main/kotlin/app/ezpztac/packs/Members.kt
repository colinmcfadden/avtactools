package app.ezpztac.packs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ezpztac.designsystem.Banner
import app.ezpztac.designsystem.BannerKind
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.PillButton
import app.ezpztac.designsystem.PillStyle
import app.ezpztac.designsystem.ToastHost
import app.ezpztac.designsystem.Tokens
import app.ezpztac.designsystem.initialsOf
import app.ezpztac.network.Invite
import app.ezpztac.network.PackMember
import app.ezpztac.network.UserBrief
import java.time.Instant
import java.time.ZoneId

/** What Members does, over [MembersViewModel]. */
class MembersActions(
    val close: () -> Unit = {},
    val retry: () -> Unit = {},
    val focused: () -> Unit = {},
    val query: (String) -> Unit = {},
    val pick: (UserBrief) -> Unit = {},
    val inviteRole: (String) -> Unit = {},
    val invite: () -> Unit = {},
    val role: (PackMember, String) -> Unit = { _, _ -> },
    val remove: (PackMember) -> Unit = {},
    val resend: (Invite) -> Unit = {},
    val revoke: (Invite) -> Unit = {},
    val shareTo: (Int) -> Unit = {},
    val shareRole: (String) -> Unit = {},
    val share: () -> Unit = {},
    val sharedRole: (String) -> Unit = {},
    val stopSharing: () -> Unit = {},
    val confirm: () -> Unit = {},
    val dismissConfirm: () -> Unit = {},
)

fun membersActionsOf(viewModel: MembersViewModel) = MembersActions(
    close = viewModel::close, retry = viewModel::load, focused = viewModel::focused, query = viewModel::query, pick = viewModel::pick, inviteRole = viewModel::inviteRole,
    invite = viewModel::invite, role = viewModel::role, remove = viewModel::askRemove, resend = viewModel::resend, revoke = viewModel::askRevoke,
    shareTo = viewModel::shareTo, shareRole = viewModel::shareRole, share = viewModel::share, sharedRole = viewModel::sharedRole, stopSharing = viewModel::askStopSharing,
    confirm = viewModel::confirm, dismissConfirm = viewModel::dismissConfirm,
)

/**
 * Members of the open pack while it is open: a page of its own on a phone (the design's AP Members), a dialog 840 dp wide on a tablet (AT Members). A window of its own,
 * so the keyboard's focus and a screen reader stay in it; the frame's toasts ([snackbars]) are drawn over it too, since the frame is under it.
 */
@Composable
fun MembersHost(viewModel: MembersViewModel, tablet: Boolean, snackbars: SnackbarHostState) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.open) return
    val actions = remember(viewModel) { membersActionsOf(viewModel) }
    Dialog(onDismissRequest = actions.close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = tablet)) {
        Box(if (tablet) Modifier else Modifier.fillMaxSize()) {
            if (tablet) {
                MembersContent(state, actions, tablet = true, modifier = Modifier.membersDialog())
            } else {
                MembersContent(state, actions, tablet = false, modifier = Modifier.fillMaxSize())
            }
            // The frame's toasts, drawn again over the page: the frame's host times them whether or not the page is up, so none is left showing when it goes.
            ToastHost(snackbars, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(horizontal = Tokens.Spacing.lg.dp))
        }
    }
    MembersQuestion(state, actions)
}

/** The tablet's dialog: the design's 840 by 696 at most, 24 dp from the window's edges outside that. */
internal fun Modifier.membersDialog() = padding(Tokens.Spacing.xl.dp).widthIn(max = MEMBERS_WIDTH).heightIn(max = MEMBERS_HEIGHT)

val MEMBERS_WIDTH = 840.dp
val MEMBERS_HEIGHT = 696.dp

/**
 * What Members holds (tested without a window): who is in the pack, here now or when they last looked; for its owner, inviting by name or email, the team it is
 * shared with, each member's role and taking them off, and the invitations waiting. A phone has the design's bar with *Done*; a tablet its title, two columns and
 * a foot with the note and *Done*.
 */
@Composable
fun MembersContent(
    state: MembersUi,
    actions: MembersActions,
    tablet: Boolean,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    now: Instant = Instant.now(),
) {
    val title = "Members of ${state.pack}"
    if (tablet) {
        Surface(modifier.semantics { paneTitle = title }, shape = RoundedCornerShape(Tokens.Radius.sheet.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column {
                Row(Modifier.padding(start = Tokens.Spacing.xl.dp, top = Tokens.Spacing.xl.dp, end = Tokens.Spacing.md.dp, bottom = Tokens.Spacing.lg.dp)) {
                    Icon(painterResource(EzpzIcons.groupsFill), contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 4.dp).size(24.dp))
                    Column(Modifier.weight(1f).padding(start = Tokens.Spacing.lg.dp)) {
                        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                        Text(MembersWords.INTRO, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = actions.close, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(EzpzIcons.close), contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Problems(state, actions, Modifier.padding(horizontal = Tokens.Spacing.xl.dp))
                Row(Modifier.weight(1f, fill = false).height(IntrinsicSize.Min).padding(horizontal = Tokens.Spacing.xl.dp)) {
                    if (state.owner) {
                        Column(Modifier.width(344.dp).verticalScroll(rememberScrollState()).padding(end = Tokens.Spacing.xl.dp, bottom = Tokens.Spacing.lg.dp)) {
                            Invite(state, actions, tablet = true)
                            TeamShare(state, actions, tablet = true)
                        }
                        VerticalDivider(Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState())
                            .padding(start = if (state.owner) Tokens.Spacing.xl.dp else 0.dp, bottom = Tokens.Spacing.lg.dp),
                    ) {
                        People(state, actions, zone, now, tablet = true)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().padding(start = Tokens.Spacing.xl.dp, top = Tokens.Spacing.md.dp, end = Tokens.Spacing.lg.dp, bottom = Tokens.Spacing.lg.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp),
                ) {
                    Icon(painterResource(EzpzIcons.visibility), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Text(MembersWords.NOTE, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = actions.close, modifier = Modifier.heightIn(min = 48.dp)) { Text("Done") }
                }
            }
        }
    } else {
        Surface(modifier.semantics { paneTitle = title }, color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = Tokens.Spacing.xs.dp, end = Tokens.Spacing.sm.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = actions.close, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(EzpzIcons.arrowBack), contentDescription = "Back to ${state.pack}")
                    }
                    Column(Modifier.weight(1f).padding(start = Tokens.Spacing.xs.dp)) {
                        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                        state.people?.let { people ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
                                Icon(painterResource(EzpzIcons.groupsFill), contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(14.dp))
                                Text(PackPanelWords.subtitle(people.role), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                            }
                        }
                    }
                    TextButton(onClick = actions.close, modifier = Modifier.heightIn(min = 48.dp)) { Text("Done", fontWeight = FontWeight.Medium) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.weight(1f).fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(bottom = Tokens.Spacing.xl.dp)) {
                    Text(
                        MembersWords.INTRO, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = Tokens.Spacing.lg.dp, top = Tokens.Spacing.lg.dp, end = Tokens.Spacing.lg.dp),
                    )
                    Problems(state, actions, Modifier.padding(start = Tokens.Spacing.lg.dp, top = Tokens.Spacing.md.dp, end = Tokens.Spacing.lg.dp))
                    if (state.owner) {
                        Column(Modifier.padding(horizontal = Tokens.Spacing.lg.dp)) {
                            Invite(state, actions, tablet = false)
                            TeamShare(state, actions, tablet = false)
                        }
                    }
                    Column(Modifier.padding(horizontal = Tokens.Spacing.lg.dp)) { People(state, actions, zone, now, tablet = false) }
                    Row(
                        Modifier.padding(start = Tokens.Spacing.lg.dp, top = Tokens.Spacing.lg.dp, end = Tokens.Spacing.lg.dp).fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(Tokens.Radius.lg.dp))
                            .padding(horizontal = Tokens.Spacing.lg.dp, vertical = Tokens.Spacing.md.dp),
                        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp),
                    ) {
                        Icon(painterResource(EzpzIcons.visibility), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Text(MembersWords.NOTE, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** The people could not be loaded (*Try again*), or are on their way. */
@Composable
private fun Problems(state: MembersUi, actions: MembersActions, modifier: Modifier) {
    state.loadError?.let { Banner(it, BannerKind.Error, modifier, actionLabel = "Try again", onAction = actions.retry) }
    if (state.people == null && state.loadError == null) {
        Text("Loading the members…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
    }
}

/** A section's heading: the phone's in the accent colour, the tablet's quieter (the design's). */
@Composable
private fun Heading(text: String, tablet: Boolean, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = if (tablet) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = Tokens.Spacing.xl.dp, bottom = Tokens.Spacing.md.dp).semantics { heading() },
    )
}

/**
 * Inviting (the owner's): the field (a teammate by name, or anyone by whole email address), what it found, as what they come in, and *Invite*. Opened from Invite,
 * the field takes the keyboard.
 */
@Composable
private fun ColumnScope.Invite(state: MembersUi, actions: MembersActions, tablet: Boolean) {
    Heading("Invite", tablet)
    val focus = remember { FocusRequester() }
    LaunchedEffect(state.focusInvite) {
        if (state.focusInvite) {
            runCatching { focus.requestFocus() }
            actions.focused()
        }
    }
    OutlinedTextField(
        value = state.query, onValueChange = actions.query, label = { Text("Name or email") }, singleLine = true, readOnly = state.busy,
        leadingIcon = if (tablet) null else { { Icon(painterResource(EzpzIcons.personSearch), contentDescription = null) } },
        trailingIcon = if (state.query.isEmpty()) {
            null
        } else {
            {
                IconButton(onClick = { actions.query("") }, enabled = !state.busy, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(EzpzIcons.cancel), contentDescription = if (tablet) "Clear" else "Clear the name")
                }
            }
        },
        isError = state.inviteError != null,
        supportingText = {
            Text(state.inviteError ?: state.inviteHint, if (state.inviteError != null) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier)
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { if (state.found.size == 1 && state.chosen == null) actions.pick(state.found.single()) else actions.invite() }),
        shape = RoundedCornerShape(Tokens.Radius.sm.dp), modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    if (state.found.isNotEmpty()) {
        Column(
            Modifier.fillMaxWidth().padding(top = Tokens.Spacing.xs.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(Tokens.Radius.lg.dp)).padding(vertical = Tokens.Spacing.xs.dp),
        ) {
            Text(
                foundWords(state.found.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Tokens.Spacing.lg.dp, top = Tokens.Spacing.sm.dp, end = Tokens.Spacing.lg.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            state.found.forEach { person ->
                PersonRow(initialsOf(person.name, person.email), person.name.ifBlank { person.email }, person.email, enabled = !state.busy) { actions.pick(person) }
            }
        }
    }
    FlowRow(
        Modifier.fillMaxWidth().padding(top = Tokens.Spacing.md.dp), horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp),
        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp), itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text("As", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics { })
        val word = MembersWords.roleWord(state.role)
        if (tablet) {
            RoleSegments(state.role, enabled = !state.busy, onChoose = actions.inviteRole)
        } else {
            MenuChip(word, "Invite as $word. Change role", ROLE_CHOICES, enabled = !state.busy, onChoose = actions.inviteRole)
        }
        Spacer(Modifier.weight(1f))
        PillButton("Invite", onClick = actions.invite, icon = EzpzIcons.personAdd, enabled = state.chosen != null && !state.busy)
    }
}

private val ROLE_CHOICES = listOf("Editor" to "editor", "Viewer" to "viewer")

/** The tablet's "As": Editor or Viewer, side by side. */
@Composable
private fun RoleSegments(role: String, enabled: Boolean, onChoose: (String) -> Unit) {
    val outline = MaterialTheme.colorScheme.outline
    Row(Modifier.height(IntrinsicSize.Min).heightIn(min = 48.dp).border(1.dp, outline, RoundedCornerShape(24.dp)).padding(1.dp).selectableGroup()) {
        ROLE_CHOICES.forEachIndexed { i, (label, value) ->
            if (i > 0) VerticalDivider(Modifier.fillMaxHeight(), color = outline)
            val chosen = value == role
            val shape = if (i == 0) RoundedCornerShape(topStart = 23.dp, bottomStart = 23.dp) else RoundedCornerShape(topEnd = 23.dp, bottomEnd = 23.dp)
            Row(
                Modifier.fillMaxHeight().heightIn(min = 46.dp).background(if (chosen) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, shape)
                    .selectable(selected = chosen, role = Role.RadioButton, enabled = enabled) { if (!chosen) onChoose(value) }
                    .semantics { contentDescription = "Invite as $label" }.padding(horizontal = Tokens.Spacing.lg.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (chosen) Icon(painterResource(EzpzIcons.check), contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (chosen) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** A choice shown on a pill with the menu's arrow (the design's role chips): [description] says what it is and that it changes. */
@Composable
private fun MenuChip(text: String, description: String, options: List<Pair<String, String>>, enabled: Boolean, onChoose: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            // Read once, by what it is and holds: its words are in the description.
            Modifier.heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button) { open = true }
                .clearAndSetSemantics { contentDescription = description; role = Role.Button },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.heightIn(min = 36.dp).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp)).padding(start = Tokens.Spacing.md.dp, end = Tokens.Spacing.xs.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Icon(painterResource(EzpzIcons.arrowDropDown), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            options.forEach { (label, value) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onChoose(value) }) }
        }
    }
}

/** The team the pack is shared with (what it can do, and no longer sharing), or one of the owner's teams to share it with. Nothing when there is neither. */
@Composable
private fun TeamShare(state: MembersUi, actions: MembersActions, tablet: Boolean) {
    val shared = state.people?.shared
    val team = state.shareTeam
    if (shared == null && team == null) return
    Column(
        Modifier.fillMaxWidth().padding(top = Tokens.Spacing.lg.dp)
            .then(if (tablet) Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Tokens.Radius.lg.dp)).padding(Tokens.Spacing.md.dp) else Modifier),
    ) {
        if (!tablet) HorizontalDivider(Modifier.padding(bottom = Tokens.Spacing.md.dp), color = MaterialTheme.colorScheme.outlineVariant)
        BesideOrUnder(
            Modifier.fillMaxWidth(),
            words = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
                    Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(painterResource(if (tablet) EzpzIcons.group else EzpzIcons.groups), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
                    }
                    Column {
                        if (shared != null) {
                            Text(shared.name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (tablet) FontWeight.Medium else null)
                            Text("Shared with this team", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else if (team != null) {
                            if (state.shareable.size > 1) {
                                MenuChip(team.name, "Team to share with, ${team.name}. Change team", state.shareable.map { it.name to it.id.toString() }, enabled = !state.busy) {
                                    actions.shareTo(it.toInt())
                                }
                            } else {
                                Text(team.name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (tablet) FontWeight.Medium else null)
                            }
                            Text("${team.count} · not shared with this pack", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            buttons = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp, Alignment.End), itemVerticalAlignment = Alignment.CenterVertically) {
                    if (shared != null) {
                        val word = if (shared.role == "viewer") "Can view" else "Can edit"
                        MenuChip(word, "What ${shared.name} can do, $word. Change", SHARE_CHOICES, enabled = !state.busy, onChoose = actions.sharedRole)
                        TextButton(onClick = actions.stopSharing, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Stop sharing") }
                    } else if (team != null) {
                        val word = if (state.shareRole == "viewer") "Can view" else "Can edit"
                        MenuChip(word, "What the team can do, $word. Change", SHARE_CHOICES, enabled = !state.busy, onChoose = actions.shareRole)
                        PillButton(
                            "Share with team", onClick = actions.share, icon = if (tablet) EzpzIcons.groupAdd else null, style = PillStyle.Tonal, enabled = !state.busy,
                            modifier = Modifier.semantics { contentDescription = "Share with team ${team.name}, ${team.count}" },
                        )
                    }
                }
            },
            wordsMin = 168.dp, space = Tokens.Spacing.sm.dp,
        )
    }
}

private val SHARE_CHOICES = listOf("Can edit" to "editor", "Can view" to "viewer")

/** Who is in the pack, and for its owner the invitations waiting. */
@Composable
private fun People(state: MembersUi, actions: MembersActions, zone: ZoneId, now: Instant, tablet: Boolean) {
    val people = state.people ?: return
    Heading("Members · ${people.members.size}", tablet)
    people.members.forEach { member -> MemberRow(member, state, actions, zone, now) }
    val pending = people.pending
    if (state.owner && pending == null) {
        Heading("Pending", tablet)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "The invitations waiting could not be loaded.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = actions.retry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Try again") }
        }
    }
    if (state.owner && !pending.isNullOrEmpty()) {
        Heading("Pending · ${pending.size}", tablet)
        pending.forEach { invite -> PendingRow(invite, state.busy, actions, zone, now) }
    }
}

/** A member: their face (a dot when here), who, where they are, and for the owner their role to change and a way to take them off. */
@Composable
private fun MemberRow(member: PackMember, state: MembersUi, actions: MembersActions, zone: ZoneId, now: Instant) {
    val name = MembersWords.label(member)
    val seen = MembersWords.seen(member, state.here, state.me, zone, now)
    val here = member.userId in state.here
    val manage = state.owner && member.role != "owner"
    BesideOrUnder(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(vertical = Tokens.Spacing.sm.dp),
        words = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg.dp)) {
                Face(initialsOf(member.name, member.email), here = here)
                Column {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        Text(if (member.userId == state.me) "$name (you)" else name, style = MaterialTheme.typography.bodyLarge)
                        seen?.let {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
                                // The design's dot, drawn: a character for it would be read aloud.
                                if (here) Box(Modifier.size(6.dp).background(EzpzTheme.status.success, CircleShape))
                                Text(it, style = MaterialTheme.typography.bodySmall, color = if (here) EzpzTheme.status.success else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (member.email.isNotBlank() && member.email != name) {
                        Text(member.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        buttons = {
            if (manage) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val word = MembersWords.roleWord(member.role)
                    MenuChip(word, "$word, role for $name. Change role", MEMBER_CHOICES, enabled = !state.busy) { actions.role(member, it) }
                    IconButton(onClick = { actions.remove(member) }, enabled = !state.busy, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(EzpzIcons.personRemove), contentDescription = "Remove $name from ${state.pack}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                Text(MembersWords.roleWord(member.role), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        wordsMin = 168.dp, space = Tokens.Spacing.sm.dp,
    )
}

private val MEMBER_CHOICES = listOf("Editor" to "editor", "Viewer" to "viewer", "Owner" to "owner")

/** An invitation waiting: to whom, when and as what, when it stops working, and sending it again or withdrawing it. */
@Composable
private fun PendingRow(invite: Invite, busy: Boolean, actions: MembersActions, zone: ZoneId, now: Instant) {
    val email = invite.email.orEmpty()
    BesideOrUnder(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(vertical = Tokens.Spacing.sm.dp),
        words = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg.dp)) {
                Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(EzpzIcons.scheduleSend), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
                Column {
                    Text(email, style = MaterialTheme.typography.bodyLarge)
                    Text(MembersWords.pending(invite, zone, now), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        buttons = {
            Row {
                TextButton(
                    onClick = { actions.resend(invite) }, enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Resend the invitation to $email" },
                ) { Text("Resend") }
                TextButton(
                    onClick = { actions.revoke(invite) }, enabled = !busy, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Revoke the invitation to $email" },
                ) { Text("Revoke") }
            }
        },
        wordsMin = 168.dp, space = Tokens.Spacing.sm.dp,
    )
}

/** What is asked before it is done, in the web's words (and Android's, for withdrawing and no longer sharing, which the web does without asking). */
@Composable
fun MembersQuestion(state: MembersUi, actions: MembersActions, zone: ZoneId = ZoneId.systemDefault(), now: Instant = Instant.now()) {
    val asked = state.confirm ?: return
    val (title, text, button) = when (asked) {
        is MembersConfirm.HandOver -> Triple("Make ${MembersWords.label(asked.member)} the owner of ${state.pack}?", HAND_OVER_TEXT, "Make owner")
        is MembersConfirm.Remove -> Triple(
            "Take ${MembersWords.label(asked.member)} off ${state.pack}?",
            "They can no longer open it. What they added stays in the pack. Last seen ${MembersWords.lastSeen(asked.member, zone, now)}.", "Take off",
        )
        is MembersConfirm.Revoke -> Triple(
            "Withdraw the invitation to ${asked.invite.email.orEmpty()}?", "The link in their email stops working. You can invite them again.", "Withdraw",
        )
        is MembersConfirm.StopSharing -> Triple(
            "Stop sharing ${state.pack} with ${asked.team.name}?", "Its members can no longer open it, unless they are in it by name. You can share it again.", "Stop sharing",
        )
    }
    val danger = asked !is MembersConfirm.HandOver
    AlertDialog(
        onDismissRequest = actions.dismissConfirm,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = actions.confirm, colors = if (danger) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors()) {
                Text(button)
            }
        },
        dismissButton = { TextButton(onClick = actions.dismissConfirm) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

internal const val HAND_OVER_TEXT =
    "A pack has one owner. You become an editor: you can still change what is in it, but only they can manage members, finish, reopen or delete it, or hand it back to you."
