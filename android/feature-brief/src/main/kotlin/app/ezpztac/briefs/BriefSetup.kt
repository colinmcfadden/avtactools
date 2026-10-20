package app.ezpztac.briefs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ezpztac.brief.BriefEdits
import app.ezpztac.brief.BriefFormats
import app.ezpztac.data.BriefState
import app.ezpztac.data.BriefWords
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.PillButton
import app.ezpztac.designsystem.PillStyle
import app.ezpztac.designsystem.ToastHost
import app.ezpztac.designsystem.Tokens
import kotlinx.serialization.json.JsonObject

/** What the setup does, over [BriefViewModel]. */
class BriefActions(
    val close: () -> Unit = {},
    val show: (SetupStep) -> Unit = {},
    val next: () -> Unit = {},
    val edit: ((JsonObject) -> JsonObject) -> Unit = {},
    val refreshWeather: () -> Unit = {},
    val makePdf: () -> Unit = {},
    val share: () -> Unit = {},
    val save: () -> Unit = {},
    /** Presents the brief on the live sandtable: the setup closes for it. */
    val present: () -> Unit = {},
)

/** The setup's actions over [viewModel]; [narrow] (a window narrower than the sandtable's [NARROW]) starts the sandtable with its steps and constants folded. */
fun briefActionsOf(viewModel: BriefViewModel, narrow: Boolean = false) = BriefActions(
    close = viewModel::close, show = viewModel::show, next = viewModel::next, edit = viewModel::edit, refreshWeather = viewModel::refreshWeather,
    makePdf = viewModel::makePdf, share = viewModel::share, save = viewModel::save, present = { viewModel.present(narrow) },
)

/**
 * The brief's setup while it is open: a dialog nearly as large as a tablet's window (the design's *brief setup*), a window of its own so the keyboard's focus and a
 * screen reader stay in it, with the frame's toasts ([snackbars]) drawn over it. *Save to device* opens the system's create-document picker here, where an activity
 * can answer it; *Share PDF* goes to the frame ([BriefViewModel.exports]).
 */
@Composable
fun BriefSetupHost(viewModel: BriefViewModel, snackbars: SnackbarHostState) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PDF)) { uri -> viewModel.saveTo(uri) }
    LaunchedEffect(viewModel) { viewModel.saves.collect { picker.launch(it) } }
    if (!state.open) return
    val narrow = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } < NARROW
    val actions = remember(viewModel, narrow) { briefActionsOf(viewModel, narrow) }
    Dialog(onDismissRequest = actions.close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)) {
        Box {
            BriefSetupContent(state, actions, Modifier.briefDialog())
            ToastHost(snackbars, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(horizontal = Tokens.Spacing.lg.dp))
        }
    }
}

private const val PDF = "application/pdf"

/** The dialog's size: nearly the window, 24 dp from its edges, 1,200 dp wide at most. */
internal fun Modifier.briefDialog() = padding(Tokens.Spacing.xl.dp).widthIn(max = BRIEF_WIDTH).fillMaxHeight()

val BRIEF_WIDTH = 1200.dp

/**
 * What the setup holds (tested without a window): its title (what the brief is of: the pack, or this session) and format, the three steps as tabs, the step showing,
 * and a foot that says how the brief is kept, how much of it is filled, and on the review the PDF's Save and Share.
 */
@Composable
fun BriefSetupContent(state: BriefUi, actions: BriefActions, modifier: Modifier = Modifier) {
    val brief = state.brief
    val of = brief?.inputs?.pack?.name?.takeIf { it.isNotEmpty() } ?: if (brief?.inputs?.pack != null) "the pack" else "this session"
    val title = "Brief setup · $of"
    Surface(modifier.semantics { paneTitle = title }, shape = RoundedCornerShape(Tokens.Radius.sheet.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Narrower than the steps' wide layout, the format goes under the title, which would otherwise be a few letters beside it.
            val wide = maxWidth >= WIDE
            val formatChoice: @Composable (Modifier) -> Unit = { m ->
                if (brief != null) Choice("Format", BriefFormats.CHOICES.map { it.id to it.title }, brief.formatId, { id -> actions.edit(BriefEdits.chooseFormat(id)) }, m)
            }
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = Tokens.Spacing.md.dp, top = Tokens.Spacing.md.dp, end = Tokens.Spacing.xl.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp),
                ) {
                    IconButton(onClick = actions.close, modifier = Modifier.size(48.dp)) {
                        Icon(painterResource(EzpzIcons.close), contentDescription = "Close")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                        if (brief != null) {
                            val template = brief.format.template
                            Text(
                                "${template.title} · ${template.source.replace(Regex(", Part \\d+$"), "")} · Built from ${brief.summary}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (brief != null) {
                        // Wide enough for both formats' names: the title gives way first.
                        if (wide) formatChoice(Modifier.widthIn(min = 420.dp))
                        if (state.step != SetupStep.REVIEW) {
                            val after = SetupStep.entries[SetupStep.entries.indexOf(state.step) + 1]
                            PillButton("Next: ${after.title.lowercase()}", onClick = actions.next)
                        }
                    }
                }
                if (!wide && brief != null) formatChoice(Modifier.padding(start = 64.dp, top = Tokens.Spacing.sm.dp, end = Tokens.Spacing.xl.dp).widthIn(min = 420.dp))
                PrimaryTabRow(selectedTabIndex = state.step.ordinal, modifier = Modifier.padding(top = Tokens.Spacing.sm.dp)) {
                    SetupStep.entries.forEach { step ->
                        Tab(selected = step == state.step, onClick = { actions.show(step) }, text = { Text(step.title) })
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = Tokens.Spacing.xl.dp)) {
                    if (brief == null && state.failed) {
                        Text(
                            SetupWords.FAILED,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.align(Alignment.Center).widthIn(max = 560.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    } else if (brief == null) {
                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text("Opening the brief…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Tokens.Spacing.md.dp))
                        }
                    } else {
                        when (state.step) {
                            SetupStep.FLOW -> FlowStepContent(brief, actions.edit, Modifier.fillMaxSize())
                            SetupStep.DETAILS -> DetailsStepContent(brief, actions.edit, actions.refreshWeather, Modifier.fillMaxSize())
                            SetupStep.REVIEW -> ReviewStepContent(state, brief, actions, Modifier.fillMaxSize())
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Foot(state, brief, actions)
            }
        }
    }
}

/**
 * The setup's foot: how the brief is kept and how much of it is filled, *Present sandtable* on every step, and on the review *Save to device* and *Share PDF*. The
 * buttons stand beside the note while it keeps [FOOT_NOTE_MIN], and go under it, at the end, where they do not (a narrow window, large text), each whole.
 *
 * **Not like the web**, whose setup offers *Present sandtable* only at the review: the web's Brief menu presents the brief without the setup, and on Android the
 * rail's *Brief* opens the setup itself (B8), so the setup offers it from its first step; the review asks the server for its PDF, which presenting does not need.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Foot(state: BriefUi, brief: BriefState?, actions: BriefActions) {
    FlowRow(
        Modifier.fillMaxWidth().padding(start = Tokens.Spacing.xl.dp, top = Tokens.Spacing.md.dp, end = Tokens.Spacing.xl.dp, bottom = Tokens.Spacing.md.dp),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).widthIn(min = FOOT_NOTE_MIN)) {
            if (brief != null) {
                Hint(footNote(state, brief))
                val (filled, blanks) = BriefEdits.counts(brief.model)
                Text("$filled filled · $blanks print as write-in lines", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.step == SetupStep.REVIEW && brief != null) {
            state.pdf.made?.takeIf { !state.stale }?.let { Hint("${it.pdf.pages} ${if (it.pdf.pages == 1) "page" else "pages"}") }
        }
        // The same steps walked on the map (the web's, beside its Download PDF): the setup closes for it.
        if (brief != null) PillButton(SandtableWords.PRESENT, onClick = actions.present, icon = EzpzIcons.playArrow, style = PillStyle.Outlined)
        if (state.step == SetupStep.REVIEW && brief != null) {
            PillButton("Save to device", onClick = actions.save, icon = EzpzIcons.download, style = PillStyle.Outlined, enabled = !state.pdf.making)
            PillButton("Share PDF", onClick = actions.share, icon = EzpzIcons.share, enabled = !state.pdf.making)
        }
    }
}

/** The least the foot's note keeps beside the buttons before they go under it. */
private val FOOT_NOTE_MIN = 240.dp

/** How the brief is kept, as the foot says it (the web's), or that the PDF is being made. */
internal fun footNote(state: BriefUi, brief: BriefState): String {
    if (state.pdf.making) return SetupWords.MAKING
    val pack = brief.inputs.pack
    if (pack != null) {
        val name = pack.name.ifEmpty { "the pack" }
        return "Kept with $name for everyone in it.${if (pack.readOnly) " The pack is read-only, but its brief stays open to change." else ""}"
    }
    return BriefWords.save(brief.save) ?: "Kept with this brief in your Library. Generating again starts from these answers."
}
