package app.ezpztac.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.Tokens

/** A choice from a list, shown as a button with the choice on it and a menu of the rest. */
@Composable
internal fun Choice(label: String, chosen: String, options: List<Pair<String, String>>, onChoose: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(
                onClick = { open = true }, shape = RoundedCornerShape(Tokens.Radius.md.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.Size.touchTarget.dp).semantics { contentDescription = "$label: $chosen" },
            ) { Text("$chosen  ▾") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (text, value) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onChoose(value) })
                }
            }
        }
    }
}
