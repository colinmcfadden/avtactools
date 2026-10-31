package app.ezpztac.packs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
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
import app.ezpztac.designsystem.PillButton
import app.ezpztac.designsystem.Tokens
import app.ezpztac.designsystem.ToastHost
import app.ezpztac.designsystem.initialsOf
import app.ezpztac.network.Invite
import app.ezpztac.network.TeamMember
import app.ezpztac.network.TeamSummary
import java.time.Instant
import java.time.ZoneId

/** What Teams does, over [TeamsViewModel]. */
class TeamsActions(
    val close: () -> Unit = {},
    val retryList: () -> Unit = {},
    val retryTeam: () -> Unit = {},
    val choose: (Int) -> Unit = {},
    val back: () -> Unit = {},
    val newTeam: () -> Unit = {},
    val rename: () -> Unit = {},
    val name: (String) -> Unit = {},
    val submitName: () -> Unit = {},
    val dismissName: () -> Unit = {},
    val email: (String) -> Unit = {},
    val inviteRole: (String) -> Unit = {},
    val invite: () -> Unit = {},
    val revoke: (Invite) -> Unit = {},
    val role: (TeamMember, String) -> Unit = { _, _ -> },
    val remove: (TeamMember) -> Unit = {},
    val leave: () -> Unit = {},
    val delete: () -> Unit = {},
    val confirm: () -> Unit = {},
    val dismissConfirm: () -> Unit = {},
)

fun teamsActionsOf(viewModel: TeamsViewModel) = TeamsActions(
    close = viewModel::close, retryList = viewModel::loadList, retryTeam = viewModel::loadTeam, choose = viewModel::choose, back = viewModel::back,
    newTeam = viewModel::newTeam, rename = viewModel::rename, name = viewModel::name, submitName = viewModel::submitName, dismissName = viewModel::dismissName,
    email = viewModel::email, inviteRole = viewModel::inviteRole, invite = viewModel::invite, revoke = viewModel::askRevoke, role = viewModel::role,
    remove = viewModel::askRemove, leave = viewModel::askLeave, delete = viewModel::askDelete, confirm = viewModel::confirm, dismissConfirm = viewModel::dismissConfirm,
)

/**
 * Teams while it is open: a page of its own on a phone (the list, then a team's page over it), a dialog 840 dp wide on a tablet (the list beside the team). A
 * window of its own, drawing the frame's toasts ([snackbars]) over itself.
 */
@Composable
fun TeamsHost(viewModel: TeamsViewModel, tablet: Boolean, snackbars: SnackbarHostState) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.open) return
    val actions = remember(viewModel) { teamsActionsOf(viewModel) }
    Dialog(onDismissRequest = teamsDismiss(tablet, state.detail, actions),properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = tablet)) {
        Box(if (tablet) Modifier else Modifier.fillMaxSize()) {
            TeamsContent(state, actions, tablet, if (tablet) Modifier.teamsDialog() else Modifier.fillMaxSize())
            ToastHost(snackbars, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(horizontal = Tokens.Spacing.lg.dp))
        }
    }
    state.naming?.let { naming ->
        Dialog(onDismissRequest = actions.dismissName, properties = DialogProperties(dismissOnBackPress = !naming.sending, dismissOnClickOutside = !naming.sending)) {
            TeamNameContent(naming, state.team?.name.orEmpty(), actions)
        }
    }
    TeamsQuestion(state, actions)
}

/** What Back does: on a phone a team's page goes back to the list before the page closes; a tablet's list and team are one page. */
internal fun teamsDismiss(tablet: Boolean, detail: Boolean, actions: TeamsActions): () -> Unit = if (!tablet && detail) actions.back else actions.close

/** The tablet's dialog: 840 by 696 at most (as Members'), 24 dp from the window's edges outside that. */
internal fun Modifier.teamsDialog() = padding(Tokens.Spacing.xl.dp).widthIn(max = MEMBERS_WIDTH).heightIn(max = MEMBERS_HEIGHT)

/** What Teams holds (tested without a window): on a phone the list, or a team's page over it; on a tablet both, with a foot to close it. */
@Composable
fun TeamsContent(
    state: TeamsUi,
    actions: TeamsActions,
    tablet: Boolean,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    now: Instant = Instant.now(),
) {
    if (tablet) {
        Surface(modifier.semantics { paneTitle = "Teams" }, shape = RoundedCornerShape(Tokens.Radius.sheet.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column {
                Row(Modifier.padding(start = Tokens.Spacing.xl.dp, top = Tokens.Spacing.xl.dp, end = Tokens.Spacing.md.dp, bottom = Tokens.Spacing.lg.dp)) {
                    Icon(painterResource(EzpzIcons.diversity3), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp).size(24.dp))
                    Column(Modifier.weight(1f).padding(start = Tokens.Spacing.lg.dp)) {
                        Text("Teams", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                        Text(TeamsWords.INTRO, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = actions.close, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(EzpzIcons.close), contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.weight(1f, fill = false).height(IntrinsicSize.Min).padding(horizontal = Tokens.Spacing.xl.dp)) {
                    Column(Modifier.width(280.dp).verticalScroll(rememberScrollState()).padding(end = Tokens.Spacing.lg.dp, bottom = Tokens.Spacing.lg.dp)) {
                        TeamList(state, actions, tablet = true)
                    }
                    VerticalDivider(Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = Tokens.Spacing.xl.dp, bottom = Tokens.Spacing.lg.dp)) {
                        TeamDetail(state, actions, zone, now, tablet = true)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.lg.dp, vertical = Tokens.Spacing.sm.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = actions.close, modifier = Modifier.heightIn(min = 48.dp)) { Text("Done") }
                }
            }
        }
    } else {
        val team = state.team?.takeIf { state.detail }
        // The team's name from the list while its page is on its way.
        val title = (if (state.detail) state.chosenName else null) ?: "Teams"
        // The list and each team's page keep a scroll of their own: going back finds the list where it was, and another team starts at its top.
        val listScroll = rememberScrollState()
        val pageScroll = key(state.chosen) { rememberScrollState() }
        Surface(modifier.semantics { paneTitle = title }, color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = Tokens.Spacing.xs.dp, end = Tokens.Spacing.sm.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (state.detail) {
                        IconButton(onClick = actions.back, modifier = Modifier.size(48.dp)) {
                            Icon(painterResource(EzpzIcons.arrowBack), contentDescription = "Back to your teams")
                        }
                    } else {
                        IconButton(onClick = actions.close, modifier = Modifier.size(48.dp)) {
                            Icon(painterResource(EzpzIcons.close), contentDescription = "Close teams")
                        }
                    }
                    Text(
                        title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = Tokens.Spacing.xs.dp).semantics { heading() },
                    )
                    // A team's page renames the team from its bar, beside its name.
                    if (team != null && state.manager) {
                        IconButton(onClick = actions.rename, enabled = !state.busy, modifier = Modifier.size(48.dp)) {
                            Icon(painterResource(EzpzIcons.edit), contentDescription = "Rename ${team.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    TextButton(onClick = actions.close, modifier = Modifier.heightIn(min = 48.dp)) { Text("Done", fontWeight = FontWeight.Medium) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    Modifier.weight(1f).fillMaxWidth().imePadding().verticalScroll(if (state.detail) pageScroll else listScroll)
                        .padding(start = Tokens.Spacing.lg.dp, top = Tokens.Spacing.lg.dp, end = Tokens.Spacing.lg.dp, bottom = Tokens.Spacing.xl.dp),
                ) {
                    if (state.detail) {
                        TeamDetail(state, actions, zone, now, tablet = false)
                    } else {
                        Text(TeamsWords.INTRO, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TeamList(state, actions, tablet = false)
                    }
                }
            }
        }
    }
}

/** The person's teams (the one shown marked on a tablet), and New team; with none, what a team is for. */
@Composable
private fun TeamList(state: TeamsUi, actions: TeamsActions, tablet: Boolean) {
    state.listError?.let { Banner(it, BannerKind.Error, Modifier.padding(top = Tokens.Spacing.md.dp), actionLabel = "Try again", onAction = actions.retryList) }
    if (!state.loaded) {
        if (state.listError == null) {
            Text("Loading your teams…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Tokens.Spacing.md.dp))
        }
        return
    }
    if (state.teams.isEmpty()) {
        Text(TeamsWords.EMPTY, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Tokens.Spacing.lg.dp))
        PillButton("New team", onClick = actions.newTeam, icon = EzpzIcons.add, modifier = Modifier.padding(top = Tokens.Spacing.lg.dp))
        return
    }
    Text(
        "Your teams", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Tokens.Spacing.lg.dp, bottom = Tokens.Spacing.sm.dp).semantics { heading() },
    )
    state.teams.forEach { team -> TeamRow(team, tablet, shown = tablet && team.id == state.chosen) { actions.choose(team.id) } }
    TextButton(onClick = actions.newTeam, modifier = Modifier.heightIn(min = 48.dp).padding(top = Tokens.Spacing.xs.dp)) {
        Icon(painterResource(EzpzIcons.add), contentDescription = null, modifier = Modifier.size(20.dp))
        Text("New team", modifier = Modifier.padding(start = Tokens.Spacing.sm.dp))
    }
}

@Composable
private fun TeamRow(team: TeamSummary, tablet: Boolean, shown: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .background(if (shown) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0f), RoundedCornerShape(Tokens.Radius.md.dp))
            // Only a tablet shows one team beside the list; a phone's rows open a page and are never "not selected".
            .clickable(role = Role.Button, onClick = onClick).then(if (tablet) Modifier.semantics { selected = shown } else Modifier).padding(horizontal = Tokens.Spacing.sm.dp, vertical = Tokens.Spacing.xs.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp),
    ) {
        Face(initialsOf(team.name))
        Column(Modifier.weight(1f)) {
            Text(team.name, style = MaterialTheme.typography.bodyLarge)
            Text(TeamsWords.listMeta(team), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(painterResource(EzpzIcons.chevronRight), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The team shown: its name (renamed by its owner or an admin), who is in it and as what, inviting, the invitations waiting, and leaving or deleting it. */
@Composable
private fun TeamDetail(state: TeamsUi, actions: TeamsActions, zone: ZoneId, now: Instant, tablet: Boolean) {
    state.teamError?.let { Banner(it, BannerKind.Error, Modifier.padding(top = Tokens.Spacing.md.dp), actionLabel = "Try again", onAction = actions.retryTeam) }
    val team = state.team
    if (team == null) {
        if (state.teamError == null && state.chosen != null) {
            Text("Loading the team…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Tokens.Spacing.md.dp))
        }
        return
    }
    // The phone's bar has the name and its rename; a tablet's pane has them here.
    if (tablet) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Tokens.Spacing.sm.dp)) {
            Text(team.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f, fill = false).semantics { heading() })
            if (state.manager) {
                IconButton(onClick = actions.rename, enabled = !state.busy, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(EzpzIcons.edit), contentDescription = "Rename ${team.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    Text(TeamsWords.meta(team, zone, now), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.manager) Invite(state, actions)
    Heading("Members · ${team.members.size}")
    team.members.forEach { member -> TeamMemberRow(member, state, actions, zone, now) }
    if (state.manager) {
        val invites = state.invites
        if (invites == null) {
            Heading("Pending")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("The invitations waiting could not be loaded.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = actions.retryTeam, modifier = Modifier.heightIn(min = 48.dp)) { Text("Try again") }
            }
        } else if (invites.isNotEmpty()) {
            Heading("Pending · ${invites.size}")
            invites.forEach { invite -> TeamInviteRow(invite, state.busy, actions, zone, now) }
        }
    }
    HorizontalDivider(Modifier.padding(top = Tokens.Spacing.lg.dp), color = MaterialTheme.colorScheme.outlineVariant)
    val danger = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
    if (state.owner) {
        TextButton(onClick = actions.delete, enabled = !state.busy, colors = danger, modifier = Modifier.heightIn(min = 48.dp).padding(top = Tokens.Spacing.sm.dp)) { Text("Delete team") }
    } else {
        TextButton(onClick = actions.leave, enabled = !state.busy, colors = danger, modifier = Modifier.heightIn(min = 48.dp).padding(top = Tokens.Spacing.sm.dp)) { Text("Leave team") }
    }
}

private val TeamsUi.invites: List<Invite>? get() = page?.invites

@Composable
private fun Heading(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Tokens.Spacing.xl.dp, bottom = Tokens.Spacing.sm.dp).semantics { heading() },
    )
}

/** Inviting by email (the team's owner or an admin), as a member (or an admin: the owner only). What was typed stays when it is refused. */
@Composable
private fun Invite(state: TeamsUi, actions: TeamsActions) {
    OutlinedTextField(
        value = state.email, onValueChange = actions.email, label = { Text("Invite by email") }, placeholder = { Text("name@army.mil") }, singleLine = true,
        readOnly = state.busy, isError = state.emailError != null,
        supportingText = {
            Text(state.emailError ?: TeamsWords.INVITE_HINT, if (state.emailError != null) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier)
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { actions.invite() }),
        shape = RoundedCornerShape(Tokens.Radius.sm.dp), modifier = Modifier.fillMaxWidth().padding(top = Tokens.Spacing.lg.dp),
    )
    FlowRow(
        Modifier.fillMaxWidth().padding(top = Tokens.Spacing.sm.dp), horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp),
        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp), itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text("As", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics { })
        val word = TeamsWords.roleWord(state.inviteRole)
        if (state.owner) {
            MenuChip(word, "Invite as $word. Change role", INVITE_CHOICES, enabled = !state.busy, onChoose = actions.inviteRole)
        } else {
            // "As" is not read: the role says it whole.
            Text(
                "Member", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics { contentDescription = "Invites as Member" },
            )
        }
        Spacer(Modifier.weight(1f))
        PillButton("Invite", onClick = actions.invite, icon = EzpzIcons.send, enabled = !state.busy)
    }
}

private val INVITE_CHOICES = listOf("Member" to "member", "Admin" to "admin")
private val MEMBER_CHOICES = listOf("Member" to "member", "Admin" to "admin", "Owner" to "owner")

/** A member of the team: who, since when, and for the owner their role to change; taking them off as the server allows. */
@Composable
private fun TeamMemberRow(member: TeamMember, state: TeamsUi, actions: TeamsActions, zone: ZoneId, now: Instant) {
    val name = member.name.ifBlank { member.email }
    val me = member.userId == state.me
    val changeable = state.owner && !me && member.role != "owner"
    val removable = state.removable(member)
    BesideOrUnder(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = Tokens.Spacing.xs.dp),
        words = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg.dp)) {
                Face(initialsOf(member.name, member.email))
                Column {
                    Text(if (me) "$name (you)" else name, style = MaterialTheme.typography.bodyLarge)
                    Text(TeamsWords.joined(member, zone, now), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        buttons = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val word = TeamsWords.roleWord(member.role)
                if (changeable) {
                    MenuChip(word, "$word, role for $name. Change role", MEMBER_CHOICES, enabled = !state.busy) { actions.role(member, it) }
                } else {
                    Text(word, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (removable) {
                    IconButton(onClick = { actions.remove(member) }, enabled = !state.busy, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(EzpzIcons.personRemove), contentDescription = "Remove $name from ${state.team?.name.orEmpty()}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        wordsMin = 168.dp, space = Tokens.Spacing.sm.dp,
    )
}

@Composable
private fun TeamInviteRow(invite: Invite, busy: Boolean, actions: TeamsActions, zone: ZoneId, now: Instant) {
    val email = invite.email.orEmpty()
    BesideOrUnder(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = Tokens.Spacing.xs.dp),
        words = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg.dp)) {
                Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(EzpzIcons.mail), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
                Column {
                    Text(email, style = MaterialTheme.typography.bodyLarge)
                    Text(TeamsWords.pending(invite, zone, now), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        buttons = {
            TextButton(
                onClick = { actions.revoke(invite) }, enabled = !busy, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Revoke the invitation to $email" },
            ) { Text("Revoke") }
        },
        wordsMin = 168.dp, space = Tokens.Spacing.sm.dp,
    )
}

/** A team's name asked for (tested without its window): a new team, or the one shown renamed, in the web's words. */
@Composable
fun TeamNameContent(naming: TeamNaming, current: String, actions: TeamsActions) {
    val title = if (naming.rename) "Rename $current" else "New team"
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.semantics { paneTitle = title }) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            if (!naming.rename) {
                Text("You will be its owner. Invite people once it is made.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
                value = naming.name, onValueChange = actions.name, label = { Text("Team name") }, placeholder = { Text("B Co 2-10 AVN") }, singleLine = true,
                readOnly = naming.sending, isError = naming.error != null,
                supportingText = {
                    Text(
                        naming.error ?: if (naming.rename) "Everyone in the team sees the new name." else "A unit or section people will recognise, such as B Co 2-10 AVN.",
                        if (naming.error != null) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { actions.submitName() }),
                shape = RoundedCornerShape(Tokens.Radius.sm.dp), modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp, Alignment.End)) {
                TextButton(onClick = actions.dismissName, enabled = !naming.sending) { Text("Cancel") }
                val word = if (naming.rename) {
                    if (naming.sending) "Renaming…" else "Rename"
                } else {
                    if (naming.sending) "Creating…" else "Create team"
                }
                TextButton(onClick = actions.submitName, enabled = !naming.sending) { Text(word) }
            }
        }
    }
}

/** What is asked before it is done, in the web's words (and Android's for withdrawing an invitation, which the web does without asking). */
@Composable
fun TeamsQuestion(state: TeamsUi, actions: TeamsActions) {
    val asked = state.confirm ?: return
    val team = state.team?.name.orEmpty()
    val (title, text, button) = when (asked) {
        is TeamsConfirm.HandOver -> Triple("Make ${asked.member.name.ifBlank { asked.member.email }} the owner of $team?", TEAM_HAND_OVER_TEXT, "Make owner")
        is TeamsConfirm.Remove -> Triple(
            "Remove ${asked.member.name.ifBlank { asked.member.email }} from $team?",
            "They stay in any pack they were added to by name; packs shared with the team stop opening for them.", "Remove",
        )
        is TeamsConfirm.Revoke -> Triple(
            "Withdraw the invitation to ${asked.invite.email.orEmpty()}?", "The link in their email stops working. You can invite them again.", "Withdraw",
        )
        is TeamsConfirm.Leave -> Triple(
            "Leave $team?", "Packs shared with the team stop opening for you, unless you were added to them by name. Someone in the team can invite you back.", "Leave",
        )
        is TeamsConfirm.Delete -> Triple(
            "Delete $team?",
            "It goes for all ${state.team?.members?.size ?: 0} members. Packs shared with it are not deleted, but stop opening for anyone who was only in them through the team.",
            "Delete team",
        )
    }
    val danger = asked !is TeamsConfirm.HandOver
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

internal const val TEAM_HAND_OVER_TEXT =
    "A team has one owner. You become an admin: you can still invite and remove members, but not delete the team or change roles."
