package app.ezpztac.android.frame

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.PanelSheetState
import app.ezpztac.designsystem.SheetDetent
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.designsystem.Tokens
import app.ezpztac.map.CameraState
import app.ezpztac.map.GpsState
import app.ezpztac.map.MapScreen
import app.ezpztac.map.MapSearchState
import app.ezpztac.map.MapStyles
import app.ezpztac.map.MapUiState
import app.ezpztac.map.Readout
import app.ezpztac.missionpacks.PackStatus
import app.ezpztac.model.LatLon
import app.ezpztac.packs.OpenPackUi
import app.ezpztac.packs.ATTENTION_AFTER_MS
import app.ezpztac.packs.PersonUi
import app.ezpztac.workspace.GraphicOf
import app.ezpztac.workspace.HeldObjectActions
import app.ezpztac.workspace.HeldObjectBar
import app.ezpztac.workspace.HeldObjectUi
import app.ezpztac.workspace.LzToolRail
import app.ezpztac.workspace.RailTool
import app.ezpztac.workspace.ToolSymbol
import app.ezpztac.model.GraphicRef
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The phone's frame drawn for someone to look at: the real map screen over a green stand-in for the imagery, the row along the top (the folded search, the
 * workspace, Import, the Library and the account, the owner's decision of 2026-10-13), the sheet at each height, and the bar. `-Pezpz.screenshots`. The width
 * and the text size are each method's own (a class's would win over a method's, AGENTS.md §14).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi")
class FrameScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val people = listOf(PersonUi(7, "Colin McFadden", "CM", me = true), PersonUi(9, "Sam B.", "SB", false), PersonUi(11, "Jess R.", "JR", false))
    private val longPack = OpenPackUi("p-ex", "B CO FIELD EXERCISE NORTH GEORGIA", people, PackStatus.LIVE, finished = false, readOnly = false, pending = 0)

    private fun shot(
        name: String,
        detent: SheetDetent,
        mode: ThemeMode = ThemeMode.Dark,
        panel: Panel = Panel.Routes,
        pack: OpenPackUi? = null,
        search: MapSearchState = MapSearchState(),
        hidden: Boolean = false,
        tools: (@androidx.compose.runtime.Composable () -> Unit)? = null,
        overlay: @androidx.compose.runtime.Composable () -> Unit = {},
    ) {
        val styles = MapStyles.available("pk.t")
        val sheet = PanelSheetState(detent)
        compose.setContent {
            EzpzTheme(mode) {
                PhoneFrame(
                    panels = if (pack != null) Panel.entries else Panel.entries - Panel.Pack, current = panel, counts = PanelCounts(threats = 1, imports = 3), onPick = {},
                    sheet = sheet, snackbars = remember { SnackbarHostState() },
                    head = PanelHead(panel.label, subtitle = if (panel == Panel.Threats) "1 · this device only" else null),
                    panel = { StandInPanel(it) },
                    hidden = hidden,
                ) { frame ->
                    MapScreen(
                        state = MapUiState(styles, styles.first(), Readout.of(LatLon(34.783817, -84.08219))),
                        camera = CameraState(LatLon(34.78, -84.08), 16.0), gps = GpsState.Off,
                        onSearch = { true }, onClearSearchError = {}, onSelectStyle = {}, onToggleGps = {}, onGpsPermissionResult = {}, onLocateMe = {}, onFaceNorth = {},
                        padding = frame.padding, controlsBottom = frame.controlsBottom, search = search, hiding = frame.hiding, tools = tools, overlay = overlay,
                        account = {
                            AccountButton("Colin McFadden", "pilot@example.com", "Version 1.9.0 (1)", night = mode == ThemeMode.Night, onNight = {}, onSignOut = {}, notice = "", floating = true)
                        },
                        chips = { WorkspaceChips(pack, packsHere = true, onSwitcher = {}, onImport = {}, onLibrary = {}) },
                        belowChips = { WorkspaceLine(pack, tablet = false, underFullSheet = detent == SheetDetent.Full) },
                        map = { Box(Modifier.fillMaxSize().background(Color(0xFF55703F))) },
                    )
                }
            }
        }
        compose.waitForIdle()
        // What the pack says under the row waits until its state has lasted (ATTENTION_AFTER_MS).
        compose.mainClock.advanceTimeBy(ATTENTION_AFTER_MS + 100)
        compose.onRoot().captureRoboImage("build/screenshots/frame-$name.png")
    }

    @Test fun half() = shot("half", SheetDetent.Half)
    @Test fun lowered() = shot("lowered", SheetDetent.Lowered)
    @Test fun full() = shot("full", SheetDetent.Full, panel = Panel.Imports)
    @Test fun night() = shot("night", SheetDetent.Half, ThemeMode.Night, Panel.Threats)

    @Test fun pack412() = shot("pack-412", SheetDetent.Lowered, pack = longPack, panel = Panel.Pack)

    // Offline long enough for the words under the row (the chip shows the shape from the first moment).
    @Test fun packOffline412() = shot("pack-offline-412", SheetDetent.Lowered, pack = longPack.copy(status = PackStatus.OFFLINE, pending = 2), panel = Panel.Pack)

    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun packOffline360Night() = shot("pack-offline-360-night", SheetDetent.Half, ThemeMode.Night, pack = longPack.copy(status = PackStatus.OFFLINE), panel = Panel.Pack)
    @Test fun searchOpen412() = shot("search-open-412", SheetDetent.Lowered, search = MapSearchState(expanded = true, text = "16S GD 66993 52949"))
    @Test fun packNight412() = shot("pack-night-412", SheetDetent.Half, ThemeMode.Night, pack = longPack, panel = Panel.Pack)

    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun personal360() = shot("personal-360", SheetDetent.Lowered)

    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun pack360() = shot("pack-360", SheetDetent.Lowered, pack = longPack, panel = Panel.Pack)

    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun searchOpen360() = shot("search-open-360", SheetDetent.Half, search = MapSearchState(expanded = true))

    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi", fontScale = 1.3f)
    fun pack360Large() = shot("pack-360-font-1.3", SheetDetent.Lowered, pack = longPack, panel = Panel.Pack)

    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi", fontScale = 2.0f)
    fun pack360Largest() = shot("pack-360-font-2.0", SheetDetent.Lowered, pack = longPack, panel = Panel.Pack)

    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi", fontScale = 2.0f)
    fun personal360LargestNight() = shot("personal-360-font-2.0-night", SheetDetent.Lowered, ThemeMode.Night)

    // The frame hidden by a tap on the map: the readout, the map's buttons and the crosshair stay, the readout at the foot.
    @Test fun hidden412() = shot("hidden-412", SheetDetent.Half, pack = longPack, panel = Panel.Pack, hidden = true)
    @Test fun hiddenNight412() = shot("hidden-night-412", SheetDetent.Full, ThemeMode.Night, hidden = true)

    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun hidden360() = shot("hidden-360", SheetDetent.Lowered, hidden = true)

    // The LZ/PZ tools on the map's edge (the owner's request of 2026-10-22): a column where they fit, one Add button where they do not, and above a held bar.
    private val railTools = listOf(
        RailTool(ToolSymbol.Boundary, "Draw LZ", "Draw the LZ/PZ boundary", enabled = true, on = false) {},
        RailTool(ToolSymbol.Helicopter, "Helicopter", "Place helicopters", enabled = true, on = true) {},
        RailTool(ToolSymbol.Pz, "PZ marker", "Place PZ markers", enabled = true, on = false) {},
        RailTool(ToolSymbol.Sector, "Sector of fire", "Place sectors of fire", enabled = true, on = false) {},
        RailTool(ToolSymbol.Unit, "Unit", "Add a unit at the crosshair", enabled = true, on = false) {},
        RailTool(ToolSymbol.GoAroundLeft, "Left go-around", "Place left go-arounds", enabled = true, on = false) {},
        RailTool(ToolSymbol.GoAroundRight, "Right go-around", "Place right go-arounds", enabled = true, on = false) {},
    )
    private val rail: @androidx.compose.runtime.Composable () -> Unit = { LzToolRail(railTools) }
    private val heldHelicopter: @androidx.compose.runtime.Composable () -> Unit = {
        HeldObjectBar(
            HeldObjectUi("CHALK 1 · UH-60L", "16S GD 66993 52949", heading = 90.0, aircraftName = "CHALK 1", graphic = GraphicOf("lz-1", GraphicRef("helicopters", "1"))),
            HeldObjectActions(),
        )
    }

    @Test fun lzToolsLowered() = shot("lz-tools-lowered", SheetDetent.Lowered, panel = Panel.Lz, tools = rail)
    @Test fun lzToolsHalf() = shot("lz-tools-half", SheetDetent.Half, panel = Panel.Lz, tools = rail)
    @Test fun lzToolsHeldNight() = shot("lz-tools-held-night", SheetDetent.Lowered, ThemeMode.Night, panel = Panel.Lz, tools = rail, overlay = heldHelicopter)

    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun lzTools360() = shot("lz-tools-360", SheetDetent.Lowered, ThemeMode.Light, panel = Panel.Lz, tools = rail)

    @Test
    @Config(fontScale = 1.3f)
    fun searchOpen412Large() = shot("search-open-412-font-1.3", SheetDetent.Lowered, search = MapSearchState(expanded = true, text = "16S GD 66993 52949"))
}

/** Something the shape of a panel's cards, so the sheet's spacing can be judged. */
@androidx.compose.runtime.Composable
private fun StandInPanel(panel: Panel) {
    Column(Modifier.fillMaxSize().padding(horizontal = Tokens.Spacing.lg.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
        repeat(3) { i ->
            Surface(shape = RoundedCornerShape(Tokens.Radius.lg.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().height(120.dp)) {
                Text("${panel.label} card ${i + 1}", Modifier.padding(Tokens.Spacing.lg.dp), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
