package app.ezpztac.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.model.GraphicRef
import app.ezpztac.model.LatLon
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The doghouse box drawn over a stand-in for imagery, upright, turned and held, for someone to look at (`-Pezpz.screenshots`). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h400dp-xxhdpi")
class DoghouseBoxScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun box(label: String, rotation: Double, selected: Boolean = false, minutes: String = "01", seconds: String = "57", distance: String = "3.13", speed: String = "60") =
        SceneDoghouse(GraphicRef("doghouses", label), LatLon(0.0, 0.0), rotation, label, "%03d".format(rotation.toInt()), minutes, seconds, distance, speed, selected)

    private fun shot(name: String, mode: ThemeMode = ThemeMode.Dark, vararg boxes: SceneDoghouse) {
        compose.setContent {
            EzpzTheme(mode) {
                Box(Modifier.size(411.dp, 400.dp).background(Color(0xFF55703F)).padding(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(36.dp), modifier = Modifier.padding(top = 60.dp, start = 24.dp)) {
                        boxes.forEach { b -> Box(Modifier.graphicsLayer { rotationZ = b.rotationDeg.toFloat() }) { DoghouseBox(b) } }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/doghouse-$name.png")
    }

    @Test fun upright() = shot("upright", boxes = arrayOf(box("[SP1]", 0.0), box("[RP1]", 0.0, minutes = "02", seconds = "10", distance = "5.2", speed = "40")))
    @Test fun turned() = shot("turned", boxes = arrayOf(box("[SP1]", 90.0), box("[RP1]", 270.0), box("[SP2]", 35.0)))
    @Test fun held() = shot("held", ThemeMode.Night, box("[SP1]", 0.0, selected = true), box("[RP1]", 0.0))
    @Test fun longLabelAndBigNumbers() = shot("long", boxes = arrayOf(box("[SP12]", 0.0, minutes = "123", seconds = "45", distance = "125.75", speed = "135")))
}
