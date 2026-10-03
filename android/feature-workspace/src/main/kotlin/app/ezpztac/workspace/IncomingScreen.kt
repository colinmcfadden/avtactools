package app.ezpztac.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ezpztac.designsystem.Banner
import app.ezpztac.designsystem.BannerKind
import app.ezpztac.designsystem.PrimaryButton
import app.ezpztac.designsystem.SecondaryButton
import app.ezpztac.designsystem.TextAction
import app.ezpztac.designsystem.Tokens

/** What the offer's buttons do. */
data class IncomingActions(
    val accept: () -> Unit,
    val decline: () -> Unit,
    val closeResult: () -> Unit,
)

/**
 * The question asked when another app hands the app a file (Files, a mail, the share sheet): in a dialog, because nothing is imported until the person
 * says so and nothing else should be tapped meanwhile. Back means "not now". A tap outside does not, so a stray touch cannot lose the file.
 */
@Composable
fun IncomingHost(viewModel: IncomingViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.visible) return
    val actions = IncomingActions(viewModel::accept, viewModel::decline, viewModel::closeResult)
    Dialog(
        onDismissRequest = { if (state.offer != null) actions.decline() else actions.closeResult() },
        properties = DialogProperties(dismissOnClickOutside = false),
    ) {
        Surface(shape = RoundedCornerShape(Tokens.Radius.lg.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = Tokens.Spacing.sm.dp) {
            IncomingOfferContent(state, actions, Modifier.verticalScroll(rememberScrollState()).padding(Tokens.Spacing.xl.dp))
        }
    }
}

/** The offer itself, without the dialog around it. */
@Composable
fun IncomingOfferContent(state: IncomingUiState, actions: IncomingActions, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
        state.result?.let { Banner(it, BannerKind.Success) }
        state.error?.let { Banner(it, BannerKind.Error) }
        when (val offer = state.offer) {
            null -> TextAction("Close", onClick = actions.closeResult)
            is IncomingOfferUi.Reading -> {
                Heading("Opening ${offer.fileName}")
                Body("Looking at what is in the file.")
                SecondaryButton("Not now", onClick = actions.decline)
            }
            is IncomingOfferUi.Threats -> {
                Heading("Add threats?")
                Body("${offer.fileName} has ${plural(offer.count, "threat")}. Adding them puts them on the map and in the threat picture on this device. Threats stay on this device: they are not saved to your account.")
                PrimaryButton("Add ${plural(offer.count, "threat")}", onClick = actions.accept)
                SecondaryButton("Not now", onClick = actions.decline)
            }
            is IncomingOfferUi.Points -> {
                Heading("Save local points?")
                Body("${offer.fileName} has ${plural(offer.count, "point")}. They are saved as a set called ${offer.setName}, and sync with your account like your other saved sets.")
                PrimaryButton("Save ${plural(offer.count, "point")}", onClick = actions.accept, busy = state.busy, busyText = "Saving")
                SecondaryButton("Not now", onClick = actions.decline, enabled = !state.busy)
            }
            is IncomingOfferUi.Mission -> {
                Heading("Bring in the routes?")
                Body(
                    "${offer.fileName} has ${plural(offer.routes, "route")} with ${plural(offer.namedPoints, "named point")}" +
                        (offer.aircraft?.let { ", planned for the $it" } ?: "") + ". " +
                        "They come in as a new set of routes you can change and send on. The mission file itself is not changed or kept, and what only AMPS holds " +
                        "(its vehicle model, anything that is not a route) does not come with them: exporting makes a new mission from the routes.",
                )
                PrimaryButton("Bring in ${plural(offer.routes, "route")}", onClick = actions.accept, busy = state.busy, busyText = "Bringing in")
                SecondaryButton("Not now", onClick = actions.decline, enabled = !state.busy)
            }
            is IncomingOfferUi.Problem -> {
                Heading("Can't open ${offer.fileName}")
                Body(offer.message)
                PrimaryButton("Close", onClick = actions.decline)
            }
        }
        if (state.waiting > 0) {
            Text(
                "${plural(state.waiting, "more file")} waiting.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Heading(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

private fun plural(n: Int, noun: String) = if (n == 1) "1 $noun" else "${withCommas(n.toLong())} ${noun}s"
