package app.ezpztac.android.frame

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.Tokens
import app.ezpztac.designsystem.initialsOf

/**
 * The account button at the end of the search bar (the person's initials) and its menu: who is signed in, night mode, the app's version and
 * what it is not cleared for, and Sign out. Night mode is the crew's choice, never the system's (docs/NATIVE_APPS_PLAN.md, "Night mode").
 */
@Composable
fun AccountButton(
    name: String,
    email: String,
    version: String,
    night: Boolean,
    onNight: (Boolean) -> Unit,
    onSignOut: () -> Unit,
    notice: String,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp).semantics { contentDescription = "Account, ${name.ifBlank { email }}" }) {
            Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Text(initialsOf(name, email), color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(Tokens.Radius.lg.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.widthIn(min = 240.dp, max = 320.dp).padding(horizontal = Tokens.Spacing.lg.dp, vertical = Tokens.Spacing.sm.dp)) {
                Text(name.ifBlank { email }, style = MaterialTheme.typography.titleMedium)
                if (name.isNotBlank()) Text(email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(vertical = Tokens.Spacing.xs.dp), color = MaterialTheme.colorScheme.outlineVariant)
            DropdownMenuItem(
                text = { Text("Night mode") },
                onClick = { onNight(!night) },
                modifier = Modifier.semantics {
                    role = Role.Switch
                    stateDescription = if (night) "On" else "Off"
                },
                leadingIcon = { Icon(painterResource(EzpzIcons.darkMode), contentDescription = null) },
                // The switch only shows the state: the whole row is what is tapped, so it is not a second target.
                trailingIcon = { Switch(checked = night, onCheckedChange = null) },
            )
            Column(Modifier.widthIn(max = 320.dp).padding(horizontal = Tokens.Spacing.lg.dp, vertical = Tokens.Spacing.sm.dp)) {
                Text(version, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Tokens.Spacing.xs.dp))
            }
            HorizontalDivider(Modifier.padding(vertical = Tokens.Spacing.xs.dp), color = MaterialTheme.colorScheme.outlineVariant)
            DropdownMenuItem(
                text = { Text("Sign out") },
                onClick = { open = false; onSignOut() },
                leadingIcon = { Icon(painterResource(EzpzIcons.logout), contentDescription = null) },
            )
        }
    }
}

