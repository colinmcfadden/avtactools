package app.ezpztac.viewer3d

import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.withMatrix
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ezpztac.data.RoutesPlacing
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.PillButton
import app.ezpztac.designsystem.Tokens
import app.ezpztac.scene3d.CompassPose
import app.ezpztac.scene3d.SceneLook
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** At night the scene is dimmed with the rest of the screen, as the map is (MapHome's `NightDim`). */
internal const val SCENE_NIGHT_DIM = 0.45f

/** The controls' own ground over the scene: the scene's dark, nearly opaque (the web's `rgba(12, 18, 27, 0.86)`). */
private val CONTROL_GROUND = SCENE_BACKGROUND.copy(alpha = 0.86f)

/** The routes' labels' words: the web's 12 pixels, at the person's text size. */
private val LABEL_TEXT = 12.sp

/** The web's compass is 72 px across. */
private val DIAL_SIZE = 72.dp

/** How a session's scene is drawn and what it puts over itself, for the page ([Viewer3DPage]). */
@Composable
internal fun sceneSlots(
    session: SceneSession,
    name: String,
    night: Boolean,
    pointSize: Int,
    onPointSize: (Int) -> Unit,
    retry: () -> Unit,
    surface: @Composable (SceneSession) -> Unit,
    routes: RoutesPlacing = RoutesPlacing.NONE,
    onRoutesUnavailable: () -> Unit = {},
): SceneSlots {
    val endWidth = remember(session) { mutableIntStateOf(0) }
    return sceneSlotsOf(session, name, night, pointSize, onPointSize, retry, surface, routes, onRoutesUnavailable, endWidth)
}

private fun sceneSlotsOf(
    session: SceneSession,
    name: String,
    night: Boolean,
    pointSize: Int,
    onPointSize: (Int) -> Unit,
    retry: () -> Unit,
    surface: @Composable (SceneSession) -> Unit,
    routes: RoutesPlacing,
    onRoutesUnavailable: () -> Unit,
    endWidth: MutableIntState,
): SceneSlots = SceneSlots(
    scene = {
        // The routes' labels in the palette (red at night) and at the person's text size, as the 2D map's are.
        val labelPx = with(LocalDensity.current) { LABEL_TEXT.toPx() }
        LaunchedEffect(session, night, labelPx) { session.setRouteLook(RouteLook(night, labelPx)) }
        val status by session.status.collectAsStateWithLifecycle()
        val drawn = status == SceneStatus.Drawing
        // Where a step left the camera, said as it changes; nothing until the first.
        var facing by remember(session) { mutableStateOf<String?>(null) }
        val steps = remember(session) {
            SceneStep.entries.map { step ->
                CustomAccessibilityAction(Viewer3DWords.step(step)) {
                    session.step(step)
                    facing = session.facing()
                    true
                }
            }
        }
        // The scene as a screen reader has it: what it is, said again as it is drawn and after each step, and the camera's steps for whoever cannot
        // use the fingers on it; after the bar and the controls over it, which it would otherwise come before, being the whole page.
        Box(
            Modifier.fillMaxSize().semantics {
                contentDescription = Viewer3DWords.scene(name, drawn, facing)
                liveRegion = LiveRegionMode.Polite
                traversalIndex = 1f
                if (drawn) customActions = steps
            },
        ) {
            surface(session)
            if (night) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = SCENE_NIGHT_DIM)))
        }
    },
    over = { SceneOver(session, retry, routes, onRoutesUnavailable) { endWidth.intValue = it } },
    foot = { SceneFoot(session, pointSize, onPointSize) },
    endWidthPx = { endWidth.intValue },
)

/**
 * The scene drawn by OpenGL ([SceneSurface]), where the device can; where it cannot, the session is told, and the page says so in words. The view
 * itself says nothing to a screen reader: the scene's slot says what it is and carries its actions.
 */
@Composable
internal fun GlScene(session: SceneSession) {
    val context = LocalContext.current
    val drawable = remember(context) { canDraw3D(context) }
    LaunchedEffect(session, drawable) { if (!drawable) session.cannotDraw(SceneProblem.NO_GL) }
    if (!drawable) return
    key(session) {
        AndroidView(
            factory = { SceneSurface(it, session).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS } },
            modifier = Modifier.fillMaxSize(),
            onRelease = { it.release() },
        )
    }
}

@Composable
private fun BoxScope.SceneOver(session: SceneSession, retry: () -> Unit, routes: RoutesPlacing, onRoutesUnavailable: () -> Unit, onEndWidth: (Int) -> Unit) {
    val status by session.status.collectAsStateWithLifecycle()
    val pose by session.compass.collectAsStateWithLifecycle()
    val hasRoutes by session.hasRoutes.collectAsStateWithLifecycle()
    SceneOverContent(status, pose, session::frameLz, session::faceNorth, retry, hasRoutes, session::frameRoutes, routes, onRoutesUnavailable, onEndWidth)
}

/**
 * Over the scene: how it stands while it is not drawn (at the start), or the camera's *LZ* and *Routes* and the compass (at the end), as the
 * web's; *Routes* is there with no routes to frame, unavailable, as the web's is, and says why ([routes]).
 */
@Composable
internal fun BoxScope.SceneOverContent(
    status: SceneStatus,
    pose: CompassPose?,
    onLz: () -> Unit,
    onFaceNorth: () -> Unit,
    retry: () -> Unit,
    hasRoutes: Boolean = false,
    onRoutes: () -> Unit = {},
    routes: RoutesPlacing = RoutesPlacing.NONE,
    onRoutesUnavailable: () -> Unit = {},
    /** How wide the camera's controls and the compass are, with their margin, as they are laid out. */
    onEndWidth: (Int) -> Unit = {},
) {
    if (status == SceneStatus.Drawing) {
        Column(
            Modifier.align(Alignment.TopEnd).onSizeChanged { onEndWidth(it.width) }.padding(Tokens.Spacing.md.dp),
            horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp),
        ) {
            CameraButtons(onLz, hasRoutes, onRoutes, routes, onRoutesUnavailable)
            pose?.let { Compass(it, onFaceNorth) }
        }
    } else {
        LaunchedEffect(Unit) { onEndWidth(0) }
        SceneState(status, retry, Modifier.align(Alignment.TopStart).padding(Tokens.Spacing.md.dp))
    }
}

@Composable
private fun SceneFoot(session: SceneSession, pointSize: Int, onPointSize: (Int) -> Unit) {
    val status by session.status.collectAsStateWithLifecycle()
    SceneFootContent(status, pointSize, onPointSize, credit = session.hasImagery, colourCredit = session.streamed)
}

/**
 * Along the scene's foot while it is drawn: the point size, and under it the imagery's [credit] when there is imagery, and NAIP's for a
 * streamed survey's points ([colourCredit]), whose colour the server lays on from it.
 */
@Composable
internal fun SceneFootContent(
    status: SceneStatus,
    pointSize: Int,
    onPointSize: (Int) -> Unit,
    credit: Boolean = false,
    colourCredit: Boolean = false,
) {
    if (status != SceneStatus.Drawing) return
    Column {
        PointSizeControl(pointSize, onPointSize)
        if (credit) MapboxCredit()
        if (colourCredit) {
            Text(
                Viewer3DWords.NAIP_CREDIT, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Tokens.Spacing.sm.dp)
                    .background(CONTROL_GROUND, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * The imagery's credit, as the web's Cesium shows it (`MAPBOX_IMAGERY.attribution`) and as the 2D map keeps its own on (Mapbox's terms require
 * it): "© Mapbox", its link to Mapbox's page about its maps.
 */
@Composable
private fun MapboxCredit() {
    val uri = LocalUriHandler.current
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .clickable(onClickLabel = Viewer3DWords.MAPBOX_CREDIT_OPEN, role = Role.Button) { runCatching { uri.openUri(Viewer3DWords.MAPBOX_ABOUT) } }
            .heightIn(min = 48.dp).padding(horizontal = Tokens.Spacing.sm.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            Viewer3DWords.MAPBOX_CREDIT, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.background(CONTROL_GROUND, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** The web's status box: *Loading* while the tileset comes, or *3D view failed* and why, with *Retry* where trying again may help. */
@Composable
internal fun SceneState(status: SceneStatus, retry: () -> Unit, modifier: Modifier = Modifier) {
    val failed = status as? SceneStatus.Failed
    Surface(
        shape = RoundedCornerShape(Tokens.Radius.md.dp),
        color = if (failed != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (failed != null) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
        modifier = modifier.widthIn(max = 360.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp)) {
            Said(urgent = failed != null) {
                Text(if (failed != null) Viewer3DWords.SCENE_FAILED else Viewer3DWords.SCENE_LOADING, style = MaterialTheme.typography.labelLarge)
                Text(failed?.let { Viewer3DWords.sceneProblem(it.problem) } ?: Viewer3DWords.SCENE_LOADING_BODY, style = MaterialTheme.typography.bodyMedium)
            }
            if (failed != null && failed.problem.retryable) PillButton(Viewer3DWords.RETRY, onClick = retry, icon = EzpzIcons.refresh)
        }
    }
}

/**
 * The camera's buttons, side by side in one pill as the web's are: *LZ*, back to the view the scene opened on, and *Routes*, the routes drawn
 * framed; unavailable with none to frame, saying why ([routes]: none to draw, or ones that could not be placed): a screen reader hears it at once,
 * and a tap says it in a toast ([onRoutesUnavailable], where the web shows its title on hover), trying again where that may help.
 */
@Composable
internal fun CameraButtons(
    onLz: () -> Unit,
    hasRoutes: Boolean,
    onRoutes: () -> Unit,
    routes: RoutesPlacing = RoutesPlacing.NONE,
    onRoutesUnavailable: () -> Unit = {},
) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(CONTROL_GROUND)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        CameraButton(Viewer3DWords.LZ, Viewer3DWords.FRAME_LZ, onClick = onLz)
        CameraButton(
            Viewer3DWords.ROUTES, Viewer3DWords.FRAME_ROUTES, onClick = onRoutes,
            unavailable = if (hasRoutes) null else Unavailable(Viewer3DWords.noRoutes(routes), routes == RoutesPlacing.FAILED, onRoutesUnavailable),
        )
    }
}

/** Why a camera button cannot be used now, whether trying again may help, and what a tap on it does instead. */
private class Unavailable(val why: String, val tryAgain: Boolean, val tapped: () -> Unit)

// One of the camera's buttons: drawn in capitals, as the web's are (`text-transform: uppercase`), and heard as its word (a description is said in
// place of a text), with what it does; or,
// [unavailable], greyed, heard with why, its taps saying why, and *Try again* among a screen reader's actions where that may help.
@Composable
private fun CameraButton(word: String, doing: String, onClick: () -> Unit, unavailable: Unavailable? = null) {
    val ink = MaterialTheme.colorScheme.onSurface
    val current by rememberUpdatedState(unavailable)
    val acts = if (unavailable == null) {
        Modifier.clickable(onClickLabel = doing, role = Role.Button, onClick = onClick)
    } else {
        Modifier.pointerInput(Unit) { detectTapGestures { current?.tapped?.invoke() } }.semantics {
            role = Role.Button
            disabled()
            stateDescription = unavailable.why
            if (unavailable.tryAgain) customActions = listOf(CustomAccessibilityAction(Viewer3DWords.TRY_AGAIN) { unavailable.tapped(); true })
        }
    }
    Box(
        Modifier.clip(RoundedCornerShape(50)).then(acts).semantics(mergeDescendants = true) { contentDescription = word }
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            word.uppercase(), color = if (unavailable == null) ink else ink.copy(alpha = DISABLED_ALPHA),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
        )
    }
}

/** Material's for what cannot be used now. */
private const val DISABLED_ALPHA = 0.38f

/**
 * The compass: the web's dial (`Compass3D.jsx`) lying on the ground, turned so its N points north and leaned back as far as the view is
 * ([CompassPose.dialMatrix]), the heading in degrees true under it. A tap faces north about the middle of the view; a screen reader hears the
 * heading and what a tap does.
 */
@Composable
internal fun Compass(pose: CompassPose, onFaceNorth: () -> Unit, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    val north = MaterialTheme.colorScheme.error
    Column(
        modifier.clip(RoundedCornerShape(Tokens.Radius.md.dp))
            .semantics(mergeDescendants = true) { contentDescription = pose.spoken }
            .clickable(onClickLabel = Viewer3DWords.FACE_NORTH, role = Role.Button, onClick = onFaceNorth)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Canvas(Modifier.size(DIAL_SIZE)) { dial(pose, ink.toArgb(), north.toArgb()) }
        Text(
            pose.headingText,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, fontFeatureSettings = "tnum"),
            modifier = Modifier.clearAndSetSemantics { }.clip(RoundedCornerShape(50)).background(CONTROL_GROUND)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 1.dp),
        )
    }
}

// The web's SVG, in its own units (a ring of 46 in a viewBox 100 across), drawn through the dial's perspective.
private fun DrawScope.dial(pose: CompassPose, ink: Int, north: Int) {
    val values = pose.dialMatrix(size.width.toDouble(), center.x.toDouble(), center.y.toDouble())
    val matrix = android.graphics.Matrix().apply { setValues(FloatArray(9) { values[it].toFloat() }) }
    drawIntoCanvas { c ->
        c.nativeCanvas.withMatrix(matrix) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.style = Paint.Style.FILL
            paint.color = SCENE_BACKGROUND.copy(alpha = 0.78f).toArgb()
            drawCircle(0f, 0f, 46f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = withAlpha(ink, 0.75f)
            drawCircle(0f, 0f, 46f, paint)
            // Every 10°: long at the cardinal points, medium every 30°.
            paint.strokeWidth = 1.5f
            paint.color = withAlpha(ink, 0.8f)
            for (i in 0 until 36) {
                val inner = when {
                    i % 9 == 0 -> 38f
                    i % 3 == 0 -> 41f
                    else -> 43f
                }
                val a = Math.toRadians(i * 10.0)
                val s = sin(a).toFloat()
                val co = cos(a).toFloat()
                drawLine(46f * s, -46f * co, inner * s, -inner * co, paint)
            }
            // The needle: its north half red, its south half pale.
            paint.style = Paint.Style.FILL
            paint.color = north
            drawPath(triangle(-19f), paint)
            paint.color = withAlpha(ink, 0.85f)
            drawPath(triangle(19f), paint)
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 13f
            paint.color = north
            drawText("N", 0f, -24f, paint)
            paint.textSize = 10f
            paint.color = withAlpha(ink, 0.85f)
            drawText("E", 29f, 4f, paint)
            drawText("S", 0f, 33f, paint)
            drawText("W", -29f, 4f, paint)
        }
    }
}

private fun triangle(tip: Float): Path = Path().apply {
    moveTo(0f, tip)
    lineTo(6f, 0f)
    lineTo(-6f, 0f)
    close()
}

private fun withAlpha(argb: Int, alpha: Float): Int = (((argb ushr 24) * alpha).roundToInt() shl 24) or (argb and 0xFFFFFF)

/**
 * The point size, 2 to 16, the largest a point is drawn in the screen's points (the web's control, bottom left: Cesium's `maximumAttenuation`,
 * which the screen's density then multiplies). The words give way before the slider does on a narrow phone with large text.
 */
@Composable
internal fun PointSizeControl(size: Int, onSize: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.widthIn(max = 420.dp).clip(RoundedCornerShape(50)).background(CONTROL_GROUND)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50)).padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp),
    ) {
        Text(
            Viewer3DWords.POINT_SIZE, style = MaterialTheme.typography.labelMedium, maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics { },
        )
        Slider(
            value = size.toFloat(), onValueChange = { onSize(it.roundToInt()) },
            valueRange = SceneLook.MIN_POINT_SIZE.toFloat()..SceneLook.MAX_POINT_SIZE.toFloat(),
            steps = SceneLook.MAX_POINT_SIZE - SceneLook.MIN_POINT_SIZE - 1,
            // As tall as a gloved finger needs, the app's least target.
            modifier = Modifier.weight(1f, fill = false).widthIn(min = 96.dp, max = 160.dp).heightIn(min = 48.dp).semantics {
                contentDescription = Viewer3DWords.POINT_SIZE
                // The size as it is shown, not the slider's per cent of its range.
                stateDescription = size.toString()
            },
        )
        Text(
            size.toString(), style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            modifier = Modifier.widthIn(min = 20.dp).clearAndSetSemantics { },
        )
    }
}
