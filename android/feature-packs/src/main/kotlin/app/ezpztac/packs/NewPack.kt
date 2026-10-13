package app.ezpztac.packs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
import app.ezpztac.designsystem.Tokens
import app.ezpztac.designsystem.initialsOf
import app.ezpztac.network.UserBrief

/** What New pack does, over [NewPackViewModel]. */
class NewPackActions(
    val name: (String) -> Unit = {},
    val description: (String) -> Unit = {},
    val audience: (Audience) -> Unit = {},
    val team: (Int) -> Unit = {},
    val teamRole: (String) -> Unit = {},
    val query: (String) -> Unit = {},
    val pick: (UserBrief) -> Unit = {},
    val pickEmail: () -> Unit = {},
    val role: (key: String, role: String) -> Unit = { _, _ -> },
    val remove: (String) -> Unit = {},
    val create: () -> Unit = {},
    val close: () -> Unit = {},
)

fun newPackActionsOf(viewModel: NewPackViewModel) = NewPackActions(
    name = viewModel::name, description = viewModel::description, audience = viewModel::audience, team = viewModel::team, teamRole = viewModel::teamRole,
    query = viewModel::query, pick = viewModel::pick, pickEmail = viewModel::pickEmail, role = viewModel::role, remove = viewModel::remove,
    create = viewModel::create, close = viewModel::close,
)

/**
 * New Mission Pack while it is open: a full-screen dialog on a phone (the design's AP NewPack), a dialog 560 dp wide on a tablet (AT NewPack). A window of its
 * own, so the keyboard's focus and a screen reader stay in it; it cannot be put away while the pack is being made.
 */
@Composable
fun NewPackHost(viewModel: NewPackViewModel, tablet: Boolean) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.open) return
    val actions = remember(viewModel) { newPackActionsOf(viewModel) }
    val properties = DialogProperties(
        dismissOnBackPress = !state.busy, dismissOnClickOutside = !state.busy, usePlatformDefaultWidth = false, decorFitsSystemWindows = tablet,
    )
    Dialog(onDismissRequest = actions.close, properties = properties) {
        if (tablet) {
            NewPackContent(state, actions, tablet = true, modifier = Modifier.tabletDialog())
        } else {
            NewPackContent(state, actions, tablet = false, modifier = Modifier.fillMaxSize())
        }
    }
}

/** How wide the tablet's dialog is (the design's). */
val NEW_PACK_WIDTH = 560.dp

/** The tablet's dialog: 560 dp at most, kept 24 dp from the window's edges outside that (the margin is not taken from the 560). */
internal fun Modifier.tabletDialog() = padding(Tokens.Spacing.xl.dp).widthIn(max = NEW_PACK_WIDTH)

/**
 * What New pack holds (tested without a window): the phone's bar with *Create*, or the tablet's title with *Cancel* and *Create pack* at its foot; the name and
 * description; who can open it; the team and what it can do; the people to invite; and what a pack is for.
 */
@Composable
fun NewPackContent(state: NewPackUi, actions: NewPackActions, tablet: Boolean, modifier: Modifier = Modifier) {
    if (tablet) {
        Surface(modifier.semantics { paneTitle = NEW_PACK_TITLE }, shape = RoundedCornerShape(Tokens.Radius.sheet.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(Tokens.Spacing.xl.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg.dp)) {
                    PackGroupsTile()
                    Text(NEW_PACK_TITLE, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).semantics { heading() })
                    IconButton(onClick = actions.close, enabled = !state.busy, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(EzpzIcons.close), contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(INTRO, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Tokens.Spacing.sm.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = Tokens.Spacing.sm.dp)) { Fields(state, actions, tablet = true) }
                state.error?.let { Banner(it, BannerKind.Error, Modifier.padding(top = Tokens.Spacing.lg.dp)) }
                Row(
                    Modifier.fillMaxWidth().padding(top = Tokens.Spacing.xl.dp), horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = actions.close, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                    PillButton(if (state.busy) "Creating…" else "Create pack", onClick = actions.create, icon = EzpzIcons.add, enabled = !state.busy)
                }
            }
        }
    } else {
        Surface(modifier.semantics { paneTitle = NEW_PACK_TITLE }, color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = Tokens.Spacing.xs.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = actions.close, enabled = !state.busy, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(EzpzIcons.close), contentDescription = "Cancel, close without creating a pack")
                    }
                    Text(NEW_PACK_TITLE, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = Tokens.Spacing.xs.dp).semantics { heading() })
                    TextButton(onClick = actions.create, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (state.busy) "Creating…" else "Create", fontWeight = FontWeight.SemiBold)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                // Why the pack was not made stays in sight however far the form is scrolled.
                state.error?.let { Banner(it, BannerKind.Error, Modifier.padding(horizontal = Tokens.Spacing.lg.dp, vertical = Tokens.Spacing.sm.dp)) }
                Column(
                    Modifier.weight(1f).fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
                        .padding(start = Tokens.Spacing.lg.dp, top = Tokens.Spacing.lg.dp, end = Tokens.Spacing.lg.dp, bottom = Tokens.Spacing.xxl.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg.dp), verticalAlignment = Alignment.CenterVertically) {
                        PackGroupsTile()
                        Text(INTRO, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    }
                    Fields(state, actions, tablet = false)
                }
            }
        }
    }
}

/** The form's fields, in the design's order: the tablet puts the "Just me" line under the choice, the phone after the people. */
@Composable
private fun ColumnScope.Fields(state: NewPackUi, actions: NewPackActions, tablet: Boolean) {
    val gap = if (tablet) Tokens.Spacing.lg.dp else Tokens.Spacing.xl.dp
    // A name refused is brought into sight with the keyboard on it, however far the form was scrolled (the web focuses it).
    val nameFocus = remember { FocusRequester() }
    val nameInView = remember { BringIntoViewRequester() }
    LaunchedEffect(state.nameError) {
        if (state.nameError != null) {
            nameInView.bringIntoView()
            runCatching { nameFocus.requestFocus() }
        }
    }
    OutlinedTextField(
        value = state.name, onValueChange = actions.name, label = { Text("Name") }, placeholder = { Text("OP DK") }, singleLine = true,
        isError = state.nameError != null, readOnly = state.busy,
        supportingText = state.nameError?.let { { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) } },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next),
        shape = RoundedCornerShape(Tokens.Radius.sm.dp),
        modifier = Modifier.fillMaxWidth().padding(top = if (tablet) Tokens.Spacing.lg.dp else 28.dp).bringIntoViewRequester(nameInView).focusRequester(nameFocus),
    )
    OutlinedTextField(
        value = state.description, onValueChange = actions.description, label = { Text("Description (optional)") },
        placeholder = { Text("What it is for, so people know what they are opening.") }, minLines = if (tablet) 1 else 3, maxLines = 6, readOnly = state.busy,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        shape = RoundedCornerShape(Tokens.Radius.sm.dp), modifier = Modifier.fillMaxWidth().padding(top = gap),
    )
    Text(
        "Who can open it", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = if (tablet) Tokens.Spacing.lg.dp else Tokens.Spacing.xl.dp, bottom = Tokens.Spacing.sm.dp),
    )
    AudienceChoice(state, actions.audience, tablet)
    state.teamUnavailable?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Tokens.Spacing.xs.dp))
    }
    if (tablet && state.audience == Audience.JUST_ME) JustMe()
    if (state.audience == Audience.TEAM) state.team?.let { team -> TeamRow(team, state, actions) }
    if (state.audience != Audience.JUST_ME) People(state, actions, tablet)
    if (!tablet && state.audience == Audience.JUST_ME) JustMe()
    Row(
        Modifier.fillMaxWidth().padding(top = Tokens.Spacing.xl.dp)
            .then(if (tablet) Modifier else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Tokens.Radius.lg.dp)).padding(Tokens.Spacing.lg.dp)),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp),
    ) {
        Icon(painterResource(EzpzIcons.info), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(INFO, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
    }
}

/** The design's three-part choice: a tick on the one chosen; on a tablet the others show what they are. *A team* is off for someone in no team. */
@Composable
private fun AudienceChoice(state: NewPackUi, onChoose: (Audience) -> Unit, tablet: Boolean) {
    val outline = MaterialTheme.colorScheme.outline
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 48.dp).border(1.dp, outline, RoundedCornerShape(24.dp)).padding(1.dp)
            .semantics { contentDescription = "Who can open it" }.selectableGroup(),
    ) {
        Audience.entries.forEachIndexed { i, audience ->
            if (i > 0) VerticalDivider(Modifier.fillMaxHeight(), color = outline)
            val chosen = audience == state.audience
            val enabled = !state.busy && (audience != Audience.TEAM || state.teams.isNotEmpty())
            val shape = when (i) {
                0 -> RoundedCornerShape(topStart = 23.dp, bottomStart = 23.dp)
                Audience.entries.lastIndex -> RoundedCornerShape(topEnd = 23.dp, bottomEnd = 23.dp)
                else -> RoundedCornerShape(0)
            }
            val content = if (enabled || chosen) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            Row(
                Modifier.weight(if (audience == Audience.PEOPLE) 1.4f else 1f).fillMaxHeight().heightIn(min = 46.dp)
                    .background(if (chosen) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, shape)
                    .selectable(selected = chosen, role = Role.RadioButton, enabled = enabled) { if (!chosen) onChoose(audience) }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
            ) {
                val icon = if (chosen) EzpzIcons.check else if (tablet) audience.icon else null
                if (icon != null) {
                    Icon(
                        painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp),
                        tint = if (chosen) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    audience.label, style = MaterialTheme.typography.labelLarge, maxLines = 2, textAlign = TextAlign.Center,
                    color = if (chosen) MaterialTheme.colorScheme.onSecondaryContainer else content,
                    modifier = Modifier.padding(start = if (icon != null) 4.dp else 0.dp, top = 2.dp, bottom = 2.dp),
                )
            }
        }
    }
}

private val Audience.icon: Int
    get() = when (this) {
        Audience.JUST_ME -> EzpzIcons.person
        Audience.TEAM -> EzpzIcons.groups
        Audience.PEOPLE -> EzpzIcons.personAdd
    }

/** The team and what it can do, side by side, or one under the other where they do not both fit. */
@Composable
private fun TeamRow(team: TeamOption, state: NewPackUi, actions: NewPackActions) {
    val teams = state.teams.map { "${it.name} · ${it.count}" to it.id.toString() }
    val roles = listOf("Edit" to "editor", "View" to "viewer")
    val roleWord = if (state.teamRole == "viewer") "View" else "Edit"
    val teamField: @Composable (Modifier) -> Unit = { m ->
        FieldMenu(
            "Team", team.name, team.count, "Team, ${team.name}, ${team.count}. Change team", teams, enabled = !state.busy, modifier = m,
        ) { actions.team(it.toInt()) }
    }
    val roleField: @Composable (Modifier) -> Unit = { m ->
        FieldMenu("They can", roleWord, null, "They can, $roleWord. Change what the team can do", roles, enabled = !state.busy, modifier = m, onChoose = actions.teamRole)
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = Tokens.Spacing.lg.dp)) {
        if (maxWidth >= SIDE_BY_SIDE) {
            // As tall as each other: the team's count under its name makes it the taller.
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
                teamField(Modifier.weight(1f).fillMaxHeight())
                roleField(Modifier.width(140.dp).fillMaxHeight())
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) { teamField(Modifier.fillMaxWidth()); roleField(Modifier.fillMaxWidth()) }
        }
    }
}

/** Below this width the team and what it can do go one under the other. */
private val SIDE_BY_SIDE = 340.dp

/** A field whose value is chosen from a menu (the design's exposed dropdown): its label, its value and a word under it, and the menu's arrow. */
@Composable
internal fun FieldMenu(
    label: String,
    value: String,
    supporting: String?,
    description: String,
    options: List<Pair<String, String>>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onChoose: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().fillMaxHeight().heightIn(min = 56.dp).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(Tokens.Radius.sm.dp))
                .clickable(enabled = enabled, role = Role.Button) { open = true }.semantics { contentDescription = description }
                .padding(start = Tokens.Spacing.lg.dp, top = 6.dp, end = Tokens.Spacing.xs.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Icon(painterResource(EzpzIcons.arrowDropDown), contentDescription = null, modifier = Modifier.padding(horizontal = Tokens.Spacing.sm.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            options.forEach { (text, option) -> DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onChoose(option) }) }
        }
    }
}

/**
 * Who else is brought in: the field (a refusal said under it), what it found (or the address typed, to invite) in a card brought into sight as it comes and
 * said to a screen reader, and those chosen with their role. Done takes the address typed, or the one teammate found; else it puts the keyboard away.
 */
@Composable
private fun People(state: NewPackUi, actions: NewPackActions, tablet: Boolean) {
    val fieldFocus = remember { FocusRequester() }
    val fieldInView = remember { BringIntoViewRequester() }
    LaunchedEffect(state.peopleError) {
        if (state.peopleError != null) {
            fieldInView.bringIntoView()
            runCatching { fieldFocus.requestFocus() }
        }
    }
    OutlinedTextField(
        value = state.query, onValueChange = actions.query, label = { Text(state.inviteLabel) }, singleLine = true, readOnly = state.busy,
        leadingIcon = { Icon(painterResource(EzpzIcons.personAdd), contentDescription = null) },
        trailingIcon = if (tablet) {
            {
                IconButton(onClick = actions.pickEmail, enabled = state.typedEmail != null && !state.busy, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(EzpzIcons.add), contentDescription = "Add to the invite list")
                }
            }
        } else {
            null
        },
        isError = state.peopleError != null,
        supportingText = {
            Text(state.peopleError ?: PEOPLE_HINT, if (state.peopleError != null) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier)
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            when {
                state.typedEmail != null -> actions.pickEmail()
                state.found.size == 1 -> actions.pick(state.found.single())
                else -> defaultKeyboardAction(ImeAction.Done)
            }
        }),
        shape = RoundedCornerShape(Tokens.Radius.sm.dp),
        modifier = Modifier.fillMaxWidth().padding(top = Tokens.Spacing.lg.dp).bringIntoViewRequester(fieldInView).focusRequester(fieldFocus),
    )
    val offered = state.typedEmail != null || state.found.isNotEmpty()
    val suggestionsInView = remember { BringIntoViewRequester() }
    // What the field found comes a moment after typing stops, under the field and so under the keyboard: it is brought into sight.
    LaunchedEffect(state.found, state.typedEmail) { if (offered) suggestionsInView.bringIntoView() }
    if (offered) {
        Column(
            Modifier.fillMaxWidth().padding(top = Tokens.Spacing.xs.dp).bringIntoViewRequester(suggestionsInView)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Tokens.Radius.lg.dp)).padding(vertical = Tokens.Spacing.xs.dp),
        ) {
            if (state.found.isNotEmpty()) {
                Text(
                    foundWords(state.found.size), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Tokens.Spacing.lg.dp, top = Tokens.Spacing.sm.dp, end = Tokens.Spacing.lg.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            state.typedEmail?.let { email ->
                PersonRow(null, "Invite $email", "They get an email with a link to join", enabled = !state.busy) { actions.pickEmail() }
            }
            state.found.forEach { person ->
                PersonRow(initialsOf(person.name, person.email), person.name.ifBlank { person.email }, person.email, enabled = !state.busy) { actions.pick(person) }
            }
        }
    }
    state.invitees.forEach { invitee -> InviteeRow(invitee, state.busy, actions) }
}

/** "1 person on your teams", "3 people on your teams": what the field found, said as it comes. */
internal fun foundWords(count: Int) = if (count == 1) "1 person on your teams" else "$count people on your teams"

/** A suggestion: a teammate found (their initials), or the address typed (a letter). */
@Composable
internal fun PersonRow(initials: String?, title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Tokens.Spacing.lg.dp, vertical = Tokens.Spacing.xs.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp),
    ) {
        Face(initials)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Someone chosen: who, as what (a tap makes an editor a viewer and back), and taking them off the list. The name or address is never cut: where it does not fit
 * on one line beside the buttons, they go under it.
 */
@Composable
private fun InviteeRow(invitee: Invitee, busy: Boolean, actions: NewPackActions) {
    val word = if (invitee.role == "viewer") "Viewer" else "Editor"
    val other = if (invitee.role == "viewer") "an editor" else "a viewer"
    BesideOrUnder(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = Tokens.Spacing.xs.dp),
        words = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
                Face(invitee.initials)
                Text(invitee.label, style = MaterialTheme.typography.bodyLarge)
            }
        },
        buttons = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { actions.role(invitee.key, if (invitee.role == "viewer") "editor" else "viewer") }, enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "$word: make ${invitee.label} $other" },
                ) { Text(word) }
                IconButton(onClick = { actions.remove(invitee.key) }, enabled = !busy, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(EzpzIcons.close), contentDescription = "Remove ${invitee.label}", modifier = Modifier.size(18.dp))
                }
            }
        },
        space = Tokens.Spacing.sm.dp, whole = true,
    )
}

/**
 * A person's face: their initials, or a letter for someone invited by address, with a dot when they are [here] now. Not read aloud: the name or address beside it
 * says who it is.
 */
@Composable
internal fun Face(initials: String?, here: Boolean = false) {
    Box(Modifier.size(40.dp).clearAndSetSemantics {}) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            if (initials != null) {
                Text(initials, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            } else {
                Icon(painterResource(EzpzIcons.mail), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
            }
        }
        if (here) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(12.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape).padding(2.dp)
                    .background(EzpzTheme.status.success, CircleShape),
            )
        }
    }
}

/** "Just me": nobody else can open it until they are invited. */
@Composable
private fun JustMe() {
    Row(Modifier.padding(top = Tokens.Spacing.lg.dp), horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
        Icon(painterResource(EzpzIcons.lock), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text("Only you can open it. You can invite people later from Members.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** A pack's tile (violet: a pack's identity). */
@Composable
private fun PackGroupsTile() {
    Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        Icon(painterResource(EzpzIcons.groupsFill), contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(22.dp))
    }
}

internal const val NEW_PACK_TITLE = "New Mission Pack"
internal const val INTRO = "A shared space for one operation. Everyone in it edits the same LZ/PZs, routes and point sets live."
internal const val PEOPLE_HINT = "Type a name or an email address. Names are found among your teammates."
internal const val INFO = "While a pack is open, everything you create goes into it and its members can see it. Threats are never added: they stay on this device only."
