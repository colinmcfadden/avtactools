package app.ezpztac.viewer3d

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ezpztac.data.LidarPhase
import app.ezpztac.data.Viewer3DTarget
import app.ezpztac.designsystem.Banner
import app.ezpztac.designsystem.BannerKind
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.designsystem.EzpzTheme
import app.ezpztac.designsystem.PillButton
import app.ezpztac.designsystem.ThemeMode
import app.ezpztac.designsystem.ToastHost
import app.ezpztac.designsystem.Tokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the page can ask for. */
class Viewer3DActions(val close: () -> Unit = {}, val retry: () -> Unit = {})

/**
 * What the scene puts on the page once its point cloud is in hand: [scene] under everything (the whole page, under the bar), [over] in the area below
 * the bar (how the scene stands, the camera's controls), and [foot] along its foot (the point size), under the words for no terrain.
 */
class SceneSlots(
    val scene: @Composable () -> Unit = {},
    val over: @Composable BoxScope.() -> Unit = {},
    val foot: @Composable () -> Unit = {},
    /** How wide, in pixels, the controls at the top end of [over] are (the camera's and the compass): the foot keeps clear of them sideways. */
    val endWidthPx: () -> Int = { 0 },
)

/** The scene's own background: the web's `#05080d`, whatever the app's palette. */
internal val SCENE_BACKGROUND = Color(0xFF05080D)

/**
 * The 3D view over the whole screen, phone and tablet alike (the owner, 2026-10-20), while [target] is set: shown on it, closed (by its bar's
 * close button or Back) with [onClose]. The map and the frame under it are left composed and untouched, so closing shows them exactly as they
 * were; the frame's toasts ([snackbars]) are drawn over it, since the frame's own are under it. Drawn in the dark palette (the night one at
 * night), since the scene is always dark. [scene] draws the point cloud once it is there. The keyboard's focus is held in the page ([focus],
 * where the frame under it sends a focus search), starting on its first control.
 */
@Composable
fun Viewer3DHost(
    target: Viewer3DTarget?,
    night: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    snackbars: SnackbarHostState? = null,
    focus: FocusRequester = remember { FocusRequester() },
    viewModel: Viewer3DViewModel = hiltViewModel(),
    /** Draws the scene: OpenGL on a device; a stand-in under test, where there is none. */
    surface: @Composable (SceneSession) -> Unit = { GlScene(it) },
) {
    LaunchedEffect(target) { if (target == null) viewModel.close() else viewModel.show(target) }
    // The host can leave with a target still set (a sign-out, an emailed link, *Update required* take the map away): its lookup goes with it, or it
    // would go on asking after the build, and let go of it, under whoever signs in next. An activity made again for a new configuration (a font
    // size, a keyboard attached) is not the host leaving: the view model outlives it, so the view goes on as it was, its build not let go of and
    // its camera where it was.
    val activity = LocalActivity.current
    DisposableEffect(viewModel) { onDispose { if (activity?.isChangingConfigurations != true) viewModel.close() } }
    if (target == null) return
    val state by viewModel.state.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val pointSize by viewModel.pointSize.collectAsStateWithLifecycle()
    val routes by viewModel.routes.collectAsStateWithLifecycle()
    // The frame's toasts, or the page's own where there are none (a test), which *Routes* says why it is unavailable in.
    val toasts = snackbars ?: remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val routesUnavailable = {
        val words = Viewer3DWords.noRoutesTapped(viewModel.routes.value)
        viewModel.retryRoutes()
        if (toasts.currentSnackbarData?.visuals?.message != words) scope.launch { toasts.showSnackbar(words, withDismissAction = true) }
    }
    BackHandler(onBack = onClose)
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) {
        focusManager.clearFocus()                                                            // a field left focused under it keeps no keyboard up
        focus.requestFocus()                                                                 // a keyboard starts on the page's first control
    }
    EzpzTheme(pageTheme(night)) {
        val slots = session?.let {
            sceneSlots(it, target.name, night, pointSize, viewModel::setPointSize, viewModel::retryScene, surface, routes, routesUnavailable)
        } ?: SceneSlots()
        Viewer3DPage(
            state ?: Viewer3DUi(target.name, LidarPhase.RESOLVING, saved = target.saved), Viewer3DActions(onClose, viewModel::retry), modifier, toasts, slots,
            focus = focus,
        )
    }
}

/** The palette the page is drawn in: the night one at night, else the dark one, whatever the app's, since the scene is always dark. */
internal fun pageTheme(night: Boolean): ThemeMode = if (night) ThemeMode.Night else ThemeMode.Dark

/**
 * The page, stateless: the scene (or how it stands) under a bar with its title and close button, below the status bar. It takes every touch, so
 * none reaches the map under it, names itself to a screen reader as a pane of its own, and holds the keyboard's focus: a focus search does not
 * leave it, and [focus] takes it to its first control.
 */
@Composable
fun Viewer3DPage(
    ui: Viewer3DUi,
    actions: Viewer3DActions,
    modifier: Modifier = Modifier,
    snackbars: SnackbarHostState? = null,
    slots: SceneSlots = SceneSlots(),
    focus: FocusRequester = remember { FocusRequester() },
) {
    // A Surface takes the touches nothing on it takes (Material's own), where a plain background would let them fall through to the map.
    // The palette's own text colours (red at night, never bright), on the scene's dark whatever the palette.
    Surface(
        color = SCENE_BACKGROUND, contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxSize().semantics { paneTitle = Viewer3DWords.pane(ui.name) }
            .focusRequester(focus).focusProperties {
                // The keyboard's focus never leaves the page: past its last control it comes round to the first, and back past the first to the
                // lowest (entered moving up, as from below the page: a focus request takes no direction along the order).
                onExit = {
                    cancelFocusChange()
                    when (requestedFocusDirection) {
                        FocusDirection.Next -> focus.requestFocus()
                        FocusDirection.Previous -> focus.requestFocus(FocusDirection.Up)
                        else -> Unit
                    }
                }
            }.focusGroup(),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (ui.phase == LidarPhase.AVAILABLE) slots.scene()
            Column(Modifier.fillMaxSize()) {
                Bar(ui.title, actions.close)
                BoxWithConstraints(
                    Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
                    contentAlignment = Alignment.Center,
                ) {
                    // Wider than tall (a phone on its side), the foot rises into the height of the controls at the top end: it stops short of them.
                    val clear = if (maxWidth > maxHeight) with(LocalDensity.current) { slots.endWidthPx().toDp() } else 0.dp
                    // What stands along the foot (the point size, the notice of no terrain), which the toasts are drawn above rather than over.
                    var footHeight by remember { mutableIntStateOf(0) }
                    when (ui.phase) {
                        LidarPhase.AVAILABLE -> {
                            slots.over(this)
                            Column(Modifier.align(Alignment.BottomStart).padding(end = clear).onSizeChanged { footHeight = it.height }.padding(Tokens.Spacing.md.dp)) {
                                // A polite live region that stays whether or not there is a notice in it, so the notice is heard as it comes.
                                Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                                    if (ui.noGround) {
                                        Banner(Viewer3DWords.NO_GROUND, BannerKind.Warning, modifier = Modifier.widthIn(max = 560.dp).padding(bottom = Tokens.Spacing.sm.dp))
                                    }
                                }
                                slots.foot()
                            }
                        }
                        LidarPhase.IDLE -> Unit
                        else -> Status(ui, actions)
                    }
                    val above = if (ui.phase == LidarPhase.AVAILABLE) with(LocalDensity.current) { footHeight.toDp() } else 0.dp
                    snackbars?.let { ToastHost(it, Modifier.align(Alignment.BottomCenter).padding(bottom = above).padding(Tokens.Spacing.md.dp)) }
                }
            }
        }
    }
}

/** The bar: the close button and `3D · NAME`, on one line, cut with an ellipsis, below the status bar and any cut-out. */
@Composable
private fun Bar(title: String, close: () -> Unit) {
    Surface(color = SCENE_BACKGROUND.copy(alpha = 0.85f), contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = close) {
                Icon(painterResource(EzpzIcons.close), contentDescription = Viewer3DWords.CLOSE)
            }
            Text(
                title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = Tokens.Spacing.md.dp).semantics { heading() },
            )
        }
    }
}

/** How the point cloud stands while it is not drawn: looking up, building, not buildable here, or failed. */
@Composable
private fun Status(ui: Viewer3DUi, actions: Viewer3DActions) {
    Surface(
        shape = RoundedCornerShape(Tokens.Radius.lg.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.padding(Tokens.Spacing.lg.dp).widthIn(max = 480.dp),
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp),
        ) {
            val (title, body) = when (ui.phase) {
                LidarPhase.RESOLVING -> Viewer3DWords.LOOKING_UP to Viewer3DWords.LOOKING_UP_BODY
                LidarPhase.BUILDING -> Viewer3DWords.BUILDING to null
                LidarPhase.MISSING -> Viewer3DWords.MISSING to Viewer3DWords.MISSING_BODY
                LidarPhase.ERROR -> Viewer3DWords.failedTitle(ui.buildFailed) to Viewer3DWords.problem(ui.problem)
                else -> null to null
            }
            // One node for every phase, its words changed, so a screen reader hears each as it comes: one made anew with its words is not
            // announced. A failure is said at once, as the web's role="alert" says a build that failed after minutes of waiting.
            if (title != null) {
                Said(urgent = ui.phase == LidarPhase.ERROR) {
                    Heading(title)
                    body?.let { Body(it) }
                }
            }
            when (ui.phase) {
                LidarPhase.BUILDING -> {
                    Text(
                        Viewer3DWords.progress(ui.stage, ui.ahead), style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = Tokens.Spacing.xs.dp))
                    Text(
                        Viewer3DWords.elapsed(buildClock(ui.elapsedS)), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Body(Viewer3DWords.BUILDING_HINT + " " + Viewer3DWords.closing(ui.saved, ui.inPack))
                }
                LidarPhase.MISSING -> PillButton(Viewer3DWords.CHECK_AGAIN, onClick = actions.retry, icon = EzpzIcons.refresh)
                LidarPhase.ERROR -> PillButton(Viewer3DWords.RETRY, onClick = actions.retry, icon = EzpzIcons.refresh)
                else -> Unit
            }
        }
    }
}

/** Words a screen reader says as they come, without the person going to them: politely, or at once when [urgent]. */
@Composable
internal fun Said(urgent: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.semantics(mergeDescendants = true) { liveRegion = if (urgent) LiveRegionMode.Assertive else LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm.dp),
        content = content,
    )
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Seconds since the build was first seen here, ticking each second so the clock moves between the service's reports, or the service's own count
 * once it is more (the web's `useBuildClock`): reopening mid-build shows the real time rather than starting at 0:00.
 */
@Composable
private fun buildClock(reported: Int?): Int {
    val started = remember { System.currentTimeMillis() }
    val ticking by produceState(0, started) {
        while (true) {
            value = ((System.currentTimeMillis() - started) / 1000).toInt()
            delay(1_000)
        }
    }
    return maxOf(ticking, reported ?: 0)
}
