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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.PillButton
import app.ezpztac.designsystem.Tokens

/** Names are kept to what the server stores, as the web's field is (`maxLength={100}`). */
private const val MAX_NAME = 100

/** The design's SaveLz, as a modal bottom sheet: naming the open LZ/PZ ([SaveLzContent]). Dismissing it names nothing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveLzSheet(current: ActiveDiagramUi, otherNames: List<String>, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        SaveLzContent(current, otherNames, onSave, onDismiss)
    }
}

/**
 * Naming the open LZ/PZ (the design's SaveLz): the name, chosen and ready to type over; what is kept with it and what is not; and Save. Everything is
 * already on the device and synced, so what this adds is the name the Library finds it by. A name another LZ/PZ has is said before it is used, with
 * the name this one will get (`NAME 2`, the web's *Keep both*).
 */
@Composable
fun SaveLzContent(current: ActiveDiagramUi, otherNames: List<String>, onSave: (String) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(current.name, TextRange(0, current.name.length))) }
    val name = field.text.trim()
    val saveAs = if (name.isEmpty()) null else LzNames.free(name, otherNames)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = Tokens.Spacing.xl.dp, end = Tokens.Spacing.xl.dp, bottom = Tokens.Spacing.xl.dp),
        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
            Text("Save LZ/PZ", style = MaterialTheme.typography.titleLarge)
            Text("Name this LZ/PZ so you can find it in your Library.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(
            value = field,
            onValueChange = { if (it.text.length <= MAX_NAME) field = it },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            label = { Text("Name") },
            singleLine = true,
            isError = name.isEmpty(),
            shape = RoundedCornerShape(Tokens.Radius.sm.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (name.isNotEmpty()) onSave(name) }),
            trailingIcon = if (field.text.isNotEmpty()) {
                { IconButton(onClick = { field = TextFieldValue("") }) { Icon(painterResource(EzpzIcons.cancelFill), contentDescription = "Clear name") } }
            } else {
                null
            },
            supportingText = {
                Text(
                    when {
                        name.isEmpty() -> "Give it a name."
                        saveAs != name -> "An LZ/PZ called $name is already in your Library: this one is saved as $saveAs."
                        else -> "You can rename it any time."
                    },
                )
            },
        )
        Text("What is saved", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(Tokens.Radius.lg.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Tokens.Spacing.lg.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
                current.saveContents.forEach { line -> Point(EzpzIcons.check, line, tint = EzpzTheme.status.success) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Point(EzpzIcons.info, "Threats and routes are not included. Routes are saved on their own.", tint = EzpzTheme.status.accent)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
                Icon(painterResource(EzpzIcons.collectionsBookmark), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Text("Saves to your Library", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onCancel) { Text("Cancel") }
            PillButton("Save", onClick = { onSave(name) }, icon = EzpzIcons.save, enabled = name.isNotEmpty())
        }
    }
}

@Composable
private fun Point(icon: Int, text: String, tint: androidx.compose.ui.graphics.Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
