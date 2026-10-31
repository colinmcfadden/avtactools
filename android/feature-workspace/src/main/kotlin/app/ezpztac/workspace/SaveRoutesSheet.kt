package app.ezpztac.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.PillButton
import app.ezpztac.designsystem.Tokens

/** Names are kept to what the server stores, as the web's field is (`maxLength={100}`). */
private const val MAX_NAME = 100

/** The design's SaveRoutes, as a modal bottom sheet: naming the open set of sketched routes ([SaveRoutesContent]). Dismissing it names nothing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveRoutesSheet(open: OpenSetUi, others: List<Pair<String, String>>, onSave: (name: String, replacing: String?) -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        SaveRoutesContent(open, others, onSave, onDismiss)
    }
}

/**
 * The name a set is offered under: a set of one route takes that route's name, as the design shows it; otherwise the set's own (its made-up name, chosen and
 * ready to type over).
 */
internal fun offeredName(open: OpenSetUi): String = open.routes.singleOrNull()?.name?.takeIf { it.isNotBlank() } ?: open.name

/**
 * Naming the open set (the design's SaveRoutes): the name, chosen and ready to type over; what is kept with it and what is not; and Save. Everything is already on
 * the device and synced, so what this adds is the name the Library finds it by. A name another set has is said before it is used, with the name this one will
 * get (`NAME 2`, the web's *Keep both*). Names are in capitals, as the web writes them.
 */
@Composable
fun SaveRoutesContent(
    open: OpenSetUi,
    others: List<Pair<String, String>>,
    onSave: (name: String, replacing: String?) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val offered = offeredName(open)
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(offered, TextRange(0, offered.length))) }
    var replace by rememberSaveable { mutableStateOf(false) }
    val name = RouteSetNames.clean(field.text)
    val clash = others.firstOrNull { it.second.trim().equals(name, ignoreCase = true) }
    val saveAs = if (name.isEmpty()) null else if (clash != null && replace) name else RouteSetNames.free(name, others.map { it.second })
    // A made-up name is not a name (`RouteSetNames.MADE_UP_REFUSED`): said once Save is pressed, as the sheet may offer the made-up one to type over, not as an error.
    var tried by rememberSaveable { mutableStateOf(false) }
    val refused = saveAs != null && RouteSetNames.isMadeUp(saveAs)
    val save: () -> Unit = {
        if (refused) tried = true else if (name.isNotEmpty()) onSave(name, clash?.first?.takeIf { replace })
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = Tokens.Spacing.xl.dp, end = Tokens.Spacing.xl.dp, bottom = Tokens.Spacing.xl.dp),
        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
            Text("Save routes", style = MaterialTheme.typography.titleLarge)
            Text("Saves the sketched routes with their plans.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(
            value = field,
            onValueChange = { if (it.text.length <= MAX_NAME || it.text.length < field.text.length) { field = it; tried = false } },       // shorter, always
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            label = { Text("Name") },
            singleLine = true,
            isError = name.isEmpty() || (tried && refused),
            shape = RoundedCornerShape(Tokens.Radius.sm.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            trailingIcon = if (field.text.isNotEmpty()) {
                { IconButton(onClick = { field = TextFieldValue("") }) { Icon(painterResource(EzpzIcons.cancelFill), contentDescription = "Clear the name") } }
            } else {
                null
            },
            supportingText = {
                Text(
                    when {
                        name.isEmpty() -> "Give it a name."
                        tried && refused -> RouteSetNames.MADE_UP_REFUSED
                        clash != null -> "Name is already used: you already have a set of routes with this name."
                        else -> "You can rename it any time."
                    },
                )
            },
        )
        if (clash != null && saveAs != null) NameTakenChoice(RouteSetNames.free(name, others.map { it.second }), replace, onReplace = { replace = it })
        Text("What is saved", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(Tokens.Radius.lg.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Tokens.Spacing.lg.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
                open.saveContents.forEach { line -> Point(EzpzIcons.checkCircleFill, line, tint = EzpzTheme.status.success) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Point(EzpzIcons.info, "Threats are never saved. Export them as a .ths if you need them next to the mission.", tint = EzpzTheme.status.accent)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
                Icon(painterResource(EzpzIcons.folder), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Text("Saves to your Library", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onCancel) { Text("Cancel") }
            PillButton(if (clash == null) "Save" else if (replace) "Replace" else "Save as new", onClick = save, icon = EzpzIcons.save, enabled = name.isNotEmpty())
        }
    }
}

@Composable
private fun Point(icon: Int, text: String, tint: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
