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
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.PanelSheetState
import app.ezpztac.designsystem.SheetDetent
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.designsystem.Tokens
import app.ezpztac.map.CameraState
import app.ezpztac.map.GpsState
import app.ezpztac.map.MapChip
import app.ezpztac.map.MapScreen
import app.ezpztac.map.MapStyles
import app.ezpztac.map.MapUiState
import app.ezpztac.map.Readout
import app.ezpztac.model.LatLon
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The phone's frame drawn for someone to look at beside the design's own pictures (docs/native-design/screens, AP-Routes and AP-Imports):
 * the real map screen over a green stand-in for the imagery, the sheet at each height, and the bar. `-Pezpz.screenshots`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h915dp-xxhdpi")
class FrameScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String, detent: SheetDetent, mode: ThemeMode = ThemeMode.Dark, panel: Panel = Panel.Routes) {
        val styles = MapStyles.available("pk.t")
        val sheet = PanelSheetState(detent)
        compose.setContent {
            EzpzTheme(mode) {
                PhoneFrame(
                    panels = Panel.entries - Panel.Pack, current = panel, counts = PanelCounts(threats = 1, imports = 3), onPick = {},
                    sheet = sheet, snackbars = remember { SnackbarHostState() },
                    head = PanelHead(panel.label, subtitle = if (panel == Panel.Threats) "1 · this device only" else null),
                    panel = { StandInPanel(it) },
                ) { frame ->
                    MapScreen(
                        state = MapUiState(styles, styles.first(), Readout.of(LatLon(34.783817, -84.08219))),
                        camera = CameraState(LatLon(34.78, -84.08), 16.0), gps = GpsState.Off,
                        onSearch = {}, onClearSearchError = {}, onSelectStyle = {}, onToggleGps = {}, onGpsPermissionResult = {}, onLocateMe = {}, onFaceNorth = {},
                        padding = frame.padding, controlsBottom = frame.controlsBottom,
                        account = {
                            AccountButton("Colin McFadden", "pilot@example.com", "Version 1.9.0 (1)", night = mode == ThemeMode.Night, onNight = {}, onSignOut = {}, notice = "")
                        },
                        chips = {
                            MapChip(EzpzIcons.person, "Personal", description = "Workspace: Personal")
                            MapChip(EzpzIcons.fileOpen, "Import", onClick = {})
                        },
                        map = { Box(Modifier.fillMaxSize().background(Color(0xFF55703F))) },
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/frame-$name.png")
    }

    @Test fun half() = shot("half", SheetDetent.Half)
    @Test fun lowered() = shot("lowered", SheetDetent.Lowered)
    @Test fun full() = shot("full", SheetDetent.Full, panel = Panel.Imports)
    @Test fun night() = shot("night", SheetDetent.Half, ThemeMode.Night, Panel.Threats)
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
