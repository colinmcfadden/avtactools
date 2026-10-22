package app.ezpztac.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Boundary drawing's sheet section and toolbar drawn, for someone to look at: `-Pezpz.screenshots` writes them to build/screenshots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h640dp-xxhdpi")
class BoundaryScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String, mode: ThemeMode = ThemeMode.Dark, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            EzpzTheme(mode) { Surface(color = MaterialTheme.colorScheme.background) { Box(Modifier.padding(vertical = 12.dp)) { Column(Modifier.padding(horizontal = 12.dp)) { content() } } } }
        }
        compose.onRoot().captureRoboImage("build/screenshots/boundary-$name.png")
    }

    @Test fun sheetReady() = shot("sheet-ready") { BoundarySection(BoundaryUiState(canStart = true), BoundaryActions()) }

    @Test fun sheetClears() = shot("sheet-clears", ThemeMode.Light) { BoundarySection(BoundaryUiState(canStart = true, clears = true, drawnPoints = 4), BoundaryActions()) }

    @Test fun toolbarTwo() = shot("toolbar-two") { BoundaryToolbar(BoundaryUiState(drawing = true, points = 2), BoundaryActions()) }

    @Test fun toolbarCanFinish() = shot("toolbar-finish", ThemeMode.Night) { BoundaryToolbar(BoundaryUiState(drawing = true, points = 4, canFinish = true), BoundaryActions()) }

    @Test fun toolbarError() = shot("toolbar-error", ThemeMode.Light) {
        BoundaryToolbar(BoundaryUiState(drawing = true, points = 1, error = "Move the map to where the corner should go first."), BoundaryActions())
    }

    @Test fun offerReady() = shot("offer-ready") { AnalyzeOfferBar(AnalyzeOfferUi(points = 5), null, BoundaryActions()) }

    @Test fun offerRunning() = shot("offer-running", ThemeMode.Light) { AnalyzeOfferBar(AnalyzeOfferUi(points = 5, stage = "Finding the landing area…"), null, BoundaryActions()) }

    @Test fun offerFailed() = shot("offer-failed", ThemeMode.Night) {
        AnalyzeOfferBar(AnalyzeOfferUi(points = 5, failed = "The analysis service could not be reached. Try again."), null, BoundaryActions())
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-xxhdpi", fontScale = 1.3f)
    fun offerNarrowLargeText() = shot("offer-narrow-large") { AnalyzeOfferBar(AnalyzeOfferUi(points = 12), null, BoundaryActions()) }

    // The doghouse's bar, where its fields now are.
    private val doghouseHeld = HeldObjectUi(
        "Doghouse · [RP1]", "16S GD 66993 52949", heading = 105.0,
        doghouse = DoghouseUi(label = "[RP1]", time = "02+10", distanceKm = "5.2", airspeedKts = "40", feeds = "Sets the landing heading on the LZ card"),
        graphic = GraphicOf("lz-1", app.ezpztac.model.GraphicRef("doghouses", "dh2")),
    )

    @Test fun doghouseBar() = shot("held-doghouse") { HeldObjectBar(doghouseHeld, HeldObjectActions()) }

    // A point of a route held (the owner's request of 2026-10-21): its type in the bar, and the dialog that changes it.

    private fun pointOf(name: String, ptType: String) = PlanPointUi(
        id = "p2", name = name, ptType = ptType, first = false, values = app.ezpztac.planning.PointDraft.of(app.ezpztac.model.RoutePlan(), "p2"), clock = "--:--:--",
        hasClock = false, facts = "", elapsed = "", held = true, grid = "16S GD 66900 52900",
    )

    private fun routeWith(point: PlanPointUi, mission: Boolean = false) = RouteDetailUi(
        routeId = "r1", name = "ROUTE 1", aircraft = "UH-60L", plan = app.ezpztac.planning.PlanDraft.of(app.ezpztac.model.RoutePlan()), points = listOf(pointOf(".TGT", "target"), point, pointOf(".RP", "ip"), pointOf(".TGT", "target")).mapIndexed { i, p -> p.copy(id = "p$i", held = i == 1) },
        shapingPoints = 0, heldShaping = null, totals = null, warnings = emptyList(), hasElevations = false, isMission = mission, color = "#0A84FF",
    )

    private val spHeld = heldObjectOf(GraphicsUiState(), ThreatsUiState(), RoutesUiState(detail = routeWith(pointOf(".SP", "ip"))))!!

    @Test fun pointTypeBar() = shot("held-point-type") { HeldObjectBar(spHeld, HeldObjectActions()) }

    @Test
    @Config(qualifiers = "w360dp-h640dp-xxhdpi", fontScale = 1.3f)
    fun pointTypeBarNarrowLargeText() = shot("held-point-type-narrow-large", ThemeMode.Light) { HeldObjectBar(spHeld, HeldObjectActions()) }

    @Test fun pointTypeBarMission() = shot("held-point-type-mission") {
        HeldObjectBar(heldObjectOf(GraphicsUiState(), ThreatsUiState(), RoutesUiState(detail = routeWith(pointOf("HAWK", "turn"), mission = true)))!!, HeldObjectActions())
    }

    /** The dialog's content as the dialog draws it: its title over the choices, on the dialog's colour. */
    @androidx.compose.runtime.Composable
    private fun TypeDialog(type: PointTypeUi, refusal: String? = null) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(24.dp)) {
                androidx.compose.material3.Text("Point type", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 16.dp))
                PointTypeContent(type, refusal) {}
            }
        }
    }

    @Test fun pointTypeDialog() = shot("point-type-dialog") { TypeDialog(spHeld.pointType!!) }

    @Test fun pointTypeDialogNight() = shot("point-type-dialog-night", ThemeMode.Night) { TypeDialog(spHeld.pointType!!) }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi", fontScale = 1.3f)
    fun pointTypeDialogNarrowLargeText() = shot("point-type-dialog-narrow-large", ThemeMode.Light) {
        // A route of two named points: the shaping choice is off, with the reason; the point named for a place keeps its name as a checkpoint or the target.
        val two = heldObjectOf(GraphicsUiState(), ThreatsUiState(), RoutesUiState(detail = route2()))!!.pointType!!
        TypeDialog(two, refusal = "OP DK is finished: nothing in it can be changed.")
    }

    private fun route2() = routeWith(pointOf("HAWK", "turn")).let { it.copy(points = it.points.take(2)) }

    private val alsoOpenHeld = heldObjectOf(GraphicsUiState(), ThreatsUiState(), RoutesUiState(), alsoOpen = AlsoOpenUi("lz-2", "LZ HAWK (ROCK)", "16S GC 31204 58811", "Also open"))!!

    @Test fun alsoOpenBar() = shot("held-also-open") { HeldObjectBar(alsoOpenHeld, HeldObjectActions()) }

    @Test
    @Config(qualifiers = "w360dp-h640dp-xxhdpi", fontScale = 1.3f)
    fun alsoOpenBarNarrowLargeText() = shot("held-also-open-narrow-large", ThemeMode.Light) { HeldObjectBar(alsoOpenHeld, HeldObjectActions()) }

    @Test
    @Config(qualifiers = "w360dp-h640dp-xxhdpi", fontScale = 1.3f)
    fun doghouseBarNarrowLargeText() = shot("held-doghouse-narrow-large", ThemeMode.Light) { HeldObjectBar(doghouseHeld, HeldObjectActions()) }

    @Test fun offerFresh() = shot("offer-fresh") { AnalyzeOfferBar(AnalyzeOfferUi(points = 0, fresh = true, name = "LZ/PZ 3"), null, BoundaryActions()) }

    @Test
    @Config(qualifiers = "w360dp-h640dp-xxhdpi", fontScale = 1.3f)
    fun offerFreshFailedNarrow() = shot("offer-fresh-failed-narrow", ThemeMode.Light) {
        AnalyzeOfferBar(AnalyzeOfferUi(points = 0, fresh = true, name = "LZ/PZ 3", failed = "No distinct landing area was found here."), null, BoundaryActions())
    }
}

