package app.ezpztac.workspace

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.EzpzText
import app.ezpztac.designsystem.EzpzTextField
import app.ezpztac.designsystem.PrimaryButton
import app.ezpztac.designsystem.SecondaryButton
import app.ezpztac.designsystem.Tokens
import app.ezpztac.model.SymbolPresets
import app.ezpztac.model.UnitDraft
import app.ezpztac.symbols.SymbolOutcome
import app.ezpztac.symbols.SymbolSpec
import app.ezpztac.symbols.rememberSymbol

/**
 * The unit builder (the web's `UnitBuilder.jsx`): the affiliation, the function and the echelon, the unit's designation and the formation above
 * it, with a live preview of the symbol and the symbol code it makes. It changes nothing until [onConfirm]: the caller adds a new unit with it, or
 * applies it to the one that is held. [onCancel] is null where there is nothing to cancel.
 */
@Composable
internal fun UnitBuilder(
    draft: UnitDraft,
    onChange: (UnitDraft) -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    confirmEnabled: Boolean = true,
    onCancel: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
        SymbolPreview(draft)

        Text("Affiliation", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            SymbolPresets.affiliations.forEach { option ->
                val chosen = draft.affiliation == option.id
                val choose = { onChange(draft.copy(affiliation = option.id)) }
                if (chosen) PrimaryButton(option.label, onClick = choose, modifier = Modifier.weight(1f).semantics { contentDescription = "${option.label}, chosen" })
                else SecondaryButton(option.label, onClick = choose, modifier = Modifier.weight(1f))
            }
        }

        Choice(
            label = "Function", chosen = draft.functionLabel ?: "Other (${draft.functionId})",
            options = SymbolPresets.unitFunctions.map { it.label to it.functionId }, onChoose = { onChange(draft.copy(functionId = it)) },
        )
        Choice(
            label = "Echelon", chosen = SymbolPresets.echelons.firstOrNull { it.code == draft.echelon }?.label ?: draft.echelon,
            options = SymbolPresets.echelons.map { it.label to it.code }, onChoose = { onChange(draft.copy(echelon = it)) },
        )

        EzpzTextField(draft.uniqueDesignation, { onChange(draft.copy(uniqueDesignation = it)) }, "Designation", hint = "Such as A/1-171")
        EzpzTextField(draft.higherFormation, { onChange(draft.copy(higherFormation = it)) }, "Higher formation", hint = "Such as 2-101")

        Text("SIDC ${draft.sidc}", style = EzpzText.grid.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize), color = MaterialTheme.colorScheme.onSurfaceVariant)

        PrimaryButton(confirmLabel, onClick = onConfirm, enabled = confirmEnabled)
        if (onCancel != null) SecondaryButton("Cancel", onClick = onCancel)
    }
}

/** The symbol the draft makes, as milsymbol draws it, and what to say where it cannot be drawn. */
@Composable
private fun SymbolPreview(draft: UnitDraft) {
    val outcome by rememberSymbol(SymbolSpec(draft.sidc, draft.uniqueDesignation, draft.higherFormation, size = 64))
    Surface(shape = RoundedCornerShape(Tokens.Radius.md.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.heightIn(min = 120.dp).padding(Tokens.Spacing.md.dp), contentAlignment = Alignment.Center) {
            when (val result = outcome) {
                null -> Unit
                is SymbolOutcome.Drawn -> Image(result.symbol.bitmap.asImageBitmap(), contentDescription = "Symbol preview")
                SymbolOutcome.Invalid -> Text("Not a symbol", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SymbolOutcome.Unavailable -> Text(
                    "The preview needs this device's JavaScript engine, which is not available. The unit is added just the same, and drawn plainly until it is.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
