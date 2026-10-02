package app.ezpztac.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** What kind of notice a [Banner] is. Colour never carries the meaning alone: each has its own words and role. */
enum class BannerKind { Error, Warning, Info, Success }

/**
 * An inline notice with a retry or dismiss, in place of the web's blocking `alert()` (docs/NATIVE_APPS_PLAN.md, "App architecture").
 * A screen reader announces it when it appears.
 */
@Composable
fun Banner(
    text: String,
    kind: BannerKind,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = bannerColors(kind)
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        color = colors.container,
        contentColor = colors.content,
        shape = RoundedCornerShape(Tokens.Radius.md.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Tokens.Spacing.lg.dp, vertical = Tokens.Spacing.md.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            if (actionLabel != null && onAction != null) {
                TextButton(
                    onClick = onAction,
                    modifier = Modifier.heightIn(min = Tokens.Size.touchTarget.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.content),
                ) { Text(actionLabel, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

private class BannerColors(val container: androidx.compose.ui.graphics.Color, val content: androidx.compose.ui.graphics.Color)

@Composable
private fun bannerColors(kind: BannerKind): BannerColors {
    val scheme = MaterialTheme.colorScheme
    val status = EzpzTheme.status
    return when (kind) {
        BannerKind.Error -> BannerColors(scheme.error, scheme.onError)
        BannerKind.Warning -> BannerColors(status.warning, scheme.background)
        BannerKind.Info -> BannerColors(scheme.surfaceVariant, scheme.onSurface)
        BannerKind.Success -> BannerColors(status.success, scheme.background)
    }
}

/** A labelled text field, with a hint under it and an error that replaces the hint. Touch target at least 56 dp. */
@Composable
fun EzpzTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
    enabled: Boolean = true,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    contentType: ContentType? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.Size.touchTarget.dp)
            .semantics {
                if (contentType != null) this.contentType = contentType
                if (error != null) this.error(error)
            },
        enabled = enabled,
        singleLine = singleLine,
        isError = error != null,
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, capitalization = capitalization, imeAction = imeAction),
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
        supportingText = (error ?: hint)?.let { text -> { Text(text) } },
        trailingIcon = trailing,
        shape = RoundedCornerShape(Tokens.Radius.md.dp),
    )
}

/** A password field that can be revealed, because typing a 15-character passphrase on glass without seeing it is how mistakes happen. */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    error: String? = null,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    newPassword: Boolean = false,
    enabled: Boolean = true,
    showLabel: String = "Show",
    hideLabel: String = "Hide",
) {
    var visible by remember { mutableStateOf(false) }
    EzpzTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
        hint = hint,
        error = error,
        keyboardType = KeyboardType.Password,
        imeAction = imeAction,
        onImeAction = onImeAction,
        enabled = enabled,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        contentType = if (newPassword) ContentType.NewPassword else ContentType.Password,
        trailing = {
            TextButton(onClick = { visible = !visible }, modifier = Modifier.heightIn(min = Tokens.Size.touchTarget.dp)) {
                Text(if (visible) hideLabel else showLabel)
            }
        },
    )
}

/** The main action of a screen. While [busy] it shows progress and does not take another tap. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    busyText: String? = null,
    enabled: Boolean = true,
) {
    Button(
        // While busy it is still drawn as an enabled button (a grey one hides the spinner and looks broken); it just takes no taps.
        onClick = { if (!busy) onClick() },
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = Tokens.Size.touchTarget.dp).semantics { if (busy) stateDescription = "Working" },
        shape = RoundedCornerShape(Tokens.Radius.md.dp),
        colors = ButtonDefaults.buttonColors(),
    ) {
        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(Tokens.Size.iconSmall.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Text(busyText ?: text)
            }
        } else {
            Text(text)
        }
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = Tokens.Size.touchTarget.dp),
        shape = RoundedCornerShape(Tokens.Radius.md.dp),
        // A disabled one often carries words that have to be read (a countdown), so its text stays at the secondary-text contrast
        // the palettes are tested to; only the border and the missing tap say it is off.
        colors = ButtonDefaults.outlinedButtonColors(disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant),
    ) { Text(text) }
}

/** A quiet action in running text or beside a form: a link-like button with a full-size touch target. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = Tokens.Size.touchTarget.dp)) { Text(text) }
}

/** A screen's heading and what it is for. */
@Composable
fun ScreenHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null, eyebrow: String? = null) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs.dp)) {
        if (eyebrow != null) {
            Text(eyebrow, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A separator with a word in it, such as "or". */
@Composable
fun LabelledDivider(label: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outline)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outline)
    }
}
