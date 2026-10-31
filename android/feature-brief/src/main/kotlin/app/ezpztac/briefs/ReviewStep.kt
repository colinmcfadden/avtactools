package app.ezpztac.briefs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.ezpztac.brief.BriefEdits
import app.ezpztac.data.BriefState
import app.ezpztac.designsystem.Banner
import app.ezpztac.designsystem.BannerKind
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.PillButton
import app.ezpztac.designsystem.Tokens
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Setup, step 3 (the design's *review and share*): the PDF as the server prints it, its pages down the side and the one chosen beside them, drawn by the platform's
 * renderer; the PDF's options; and what could not be briefed. A brief never says a page number of its own (Chromium decides where a long step breaks): the pages
 * numbered here are the PDF's, once it is made. Before then the side lists the packet by section. A page is drawn as it scrolls into sight, one at a time. Narrower
 * than [WIDE] the side columns narrow, so the page keeps the room.
 */
@Composable
internal fun ReviewStepContent(state: BriefUi, brief: BriefState, actions: BriefActions, modifier: Modifier = Modifier) {
    val made = state.pdf.made
    var page by rememberSaveable(made) { mutableIntStateOf(0) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val wide = maxWidth >= WIDE
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg.dp)) {
            val side = Modifier.width(if (wide) 200.dp else 132.dp).fillMaxHeight()
            if (made != null) {
                LazyColumn(side, contentPadding = PaddingValues(vertical = Tokens.Spacing.lg.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                    items(made.pdf.pages, key = { it }) { i -> Thumbnail(made, i, i == page) { page = i } }
                }
            } else {
                Column(side.verticalScroll(rememberScrollState()).padding(vertical = Tokens.Spacing.lg.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                    Text("In the packet", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                    sections(brief).forEach { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(vertical = Tokens.Spacing.lg.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
                state.pdf.failure?.let { Banner(it, BannerKind.Error, actionLabel = "Try again", onAction = actions.makePdf) }
                if (state.stale && !state.pdf.making) Banner(SetupWords.STALE, BannerKind.Warning, actionLabel = "Make it again", onAction = actions.makePdf)
                Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(Tokens.Radius.md.dp)), contentAlignment = Alignment.Center) {
                    when {
                        state.pdf.making -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
                            CircularProgressIndicator()
                            Text(SetupWords.MAKING, style = MaterialTheme.typography.bodyMedium)
                        }
                        made != null -> PageView(made, page.coerceIn(0, (made.pdf.pages - 1).coerceAtLeast(0)))
                        else -> PillButton("Make the PDF", onClick = actions.makePdf, icon = EzpzIcons.description)
                    }
                }
            }
            Column(Modifier.width(if (wide) 320.dp else 280.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = Tokens.Spacing.lg.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.lg.dp)) {
                val problems = BriefReading.problems(brief.model)
                if (problems.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(Tokens.Radius.lg.dp),
                        color = app.ezpztac.designsystem.EzpzTheme.status.warning.copy(alpha = 0.10f),
                        border = BorderStroke(1.dp, app.ezpztac.designsystem.EzpzTheme.status.warning.copy(alpha = 0.30f)),
                    ) {
                        Column(Modifier.padding(Tokens.Spacing.lg.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
                            Text(SetupWords.CHECK, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                            problems.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
                Options(brief, actions)
                Hint(SetupWords.CLASSIFICATION)
            }
        }
    }
}

/** The packet's sections as compiled, before the PDF is made: by title, never by a page number of the brief's own. */
private fun sections(brief: BriefState): List<String> {
    val steps = BriefReading.steps(brief.model).associateBy { it.id }
    return BriefReading.pages(brief.model).map { p ->
        when (p.kind) {
            "cover" -> "Cover · ${steps[p.stepId]?.let { "${it.number} ${it.title}" }.orEmpty()}"
            "step" -> steps[p.stepId]?.let { "${it.number} ${it.title}" }.orEmpty()
            else -> p.title.orEmpty()
        }
    }
}

@Composable
private fun Thumbnail(made: MadePdf, index: Int, current: Boolean, onClick: () -> Unit) {
    val widthPx = with(LocalDensity.current) { 160.dp.roundToPx() }
    val image by produceState<ImageBitmap?>(null, made, index) { value = made.pdf.page(index, widthPx) }
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .border(if (current) 2.dp else 1.dp, if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Tokens.Radius.sm.dp))
            .padding(Tokens.Spacing.sm.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Page ${index + 1} of ${made.pdf.pages}"
                selected = current
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(PAGE_ASPECT).background(Color.White)) {
            image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        }
        Text("Page ${index + 1}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun PageView(made: MadePdf, index: Int) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(Tokens.Spacing.md.dp), contentAlignment = Alignment.TopCenter) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        val image by produceState<ImageBitmap?>(null, made, index, widthPx) { value = made.pdf.page(index, widthPx) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().aspectRatio(image?.let { it.width.toFloat() / it.height } ?: PAGE_ASPECT).background(Color.White)) {
                image?.let {
                    Image(it, contentDescription = "Page ${index + 1} of ${made.pdf.pages} of the PDF", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** A letter page, width over height, until a page is drawn. */
private const val PAGE_ASPECT = 8.5f / 11f

private val OPTIONS = listOf(
    Triple("script", "Script under each figure", "Who briefs it, and what to say"),
    Triple("constants", "Mission constants on every page", "The band across the top"),
    Triple("lzCards", "LZ/PZ cards", "Appendix, one for each LZ/PZ in the flow"),
    Triple("navLogs", "Nav logs", "Appendix, one for each route"),
    Triple("strips", "Strip charts", "Appendix, each route up its course, three strips to a page for a kneeboard"),
    Triple("iimc", "IIMC card", "Appendix, when the IIMC procedure is set"),
    Triple("writeIns", "Write-in lines for blanks", "Off leaves blank fields out"),
)
private val MAPS = listOf("satellite" to "Satellite", "topo" to "Topo", "sectional" to "Sectional")
private val STRIP_MAPS = listOf("topo" to "Topo", "sectional" to "Sectional", "satellite" to "Satellite")
private val STRIP_SCALES = listOf(100000 to "1:100,000", 250000 to "1:250,000")
private val PAPERS = listOf("letter" to "Letter", "a4" to "A4")

@Composable
private fun Options(brief: BriefState, actions: BriefActions) {
    val doc = brief.doc
    fun option(key: String): String? = (BriefEdits.option(doc, key) as? JsonPrimitive)?.contentOrNull
    SetupCard(SetupWords.IN_THE_PDF) {
        OPTIONS.forEach { (key, title, hint) ->
            SwitchRow(title, hint, BriefReading.option(brief.model, key), { on -> actions.edit(BriefEdits.setOption(key, JsonPrimitive(on))) })
        }
    }
    SetupCard("Map in the figures") {
        Choice("Map in the figures", MAPS, option("figureMap") ?: "satellite", { id -> actions.edit(BriefEdits.setOption("figureMap", JsonPrimitive(id))) })
    }
    if (BriefReading.option(brief.model, "strips")) {
        val strips = BriefReading.strips(brief.model)
        val scale = (strips.firstOrNull()?.get("scale") as? JsonPrimitive)?.intOrNull ?: (BriefEdits.option(doc, "stripScale") as? JsonPrimitive)?.intOrNull ?: 100000
        SetupCard("Strip charts") {
            Choice("Map on the strip charts", STRIP_MAPS, option("stripMap") ?: "topo", { id -> actions.edit(BriefEdits.setOption("stripMap", JsonPrimitive(id))) })
            Choice("Strip chart scale", STRIP_SCALES, scale, { value -> actions.edit(BriefEdits.setOption("stripScale", JsonPrimitive(value))) })
            Hint(stripLine(strips))
        }
    }
    SetupCard("Paper") {
        Choice("Paper", PAPERS, option("paper") ?: "letter", { id -> actions.edit(BriefEdits.setOption("paper", JsonPrimitive(id))) })
    }
}

/** "12 strips, each 21.5 by 5.5 km of ground, with the 1 km MGRS grid." (the web's), or why there are none. */
internal fun stripLine(strips: List<JsonObject>): String {
    val first = strips.firstOrNull() ?: return "No route in the flow has two named points to draw a strip along."
    fun km(key: String) = (first[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.let { app.ezpztac.model.JsNumber.toText(it / 1000) } ?: "?"
    return "${strips.size} ${if (strips.size == 1) "strip" else "strips"}, each ${km("length")} by ${km("width")} km of ground, with the 1 km MGRS grid."
}
