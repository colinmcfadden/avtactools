package app.ezpztac.viewer3d

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
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

/** What the page can ask for. */
class Viewer3DActions(val close: () -> Unit = {}, val retry: () -> Unit = {})

/** The scene's own background: the web's `#05080d`, whatever the app's palette. */
internal val SCENE_BACKGROUND = Color(0xFF05080D)

/**
 * The 3D view over the whole screen, phone and tablet alike (the owner, 2026-10-20), while [target] is set: shown on it, closed (by its bar's
 * close button or Back) with [onClose]. The map and the frame under it are left composed and untouched, so closing shows them exactly as they
 * were; the frame's toasts ([snackbars]) are drawn over it, since the frame's own are under it. Drawn in the dark palette (the night one at
 * night), since the scene is always dark. [scene] draws the point cloud once it is there.
 */
@Composable
fun Viewer3DHost(
    target: Viewer3DTarget?,
    night: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    snackbars: SnackbarHostState? = null,
    viewModel: Viewer3DViewModel = hiltViewModel(),
    scene: @Composable (Viewer3DUi) -> Unit = { ReadyForNow() },
) {
    LaunchedEffect(target) { if (target == null) viewModel.close() else viewModel.show(target) }
    if (target == null) return
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onClose)
    val focus = LocalFocusManager.current
    LaunchedEffect(Unit) { focus.clearFocus() }                                             // a field left focused under it keeps no keyboard up
    EzpzTheme(if (night) ThemeMode.Night else ThemeMode.Dark) {
        Viewer3DPage(
            state ?: Viewer3DUi(target.name, LidarPhase.RESOLVING, saved = target.saved), Viewer3DActions(onClose, viewModel::retry), modifier, snackbars, scene,
        )
    }
}

/**
 * The page, stateless: the scene (or how it stands) under a bar with its title and close button, below the status bar. It takes every touch, so
 * none reaches the map under it, and names itself to a screen reader as a pane of its own.
 */
@Composable
fun Viewer3DPage(
    ui: Viewer3DUi,
    actions: Viewer3DActions,
    modifier: Modifier = Modifier,
    snackbars: SnackbarHostState? = null,
    scene: @Composable (Viewer3DUi) -> Unit = { ReadyForNow() },
) {
    // A Surface takes the touches nothing on it takes (Material's own), where a plain background would let them fall through to the map.
    // The palette's own text colours (red at night, never bright), on the scene's dark whatever the palette.
    Surface(
        color = SCENE_BACKGROUND, contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxSize().semantics { paneTitle = Viewer3DWords.pane(ui.name) },
    ) {
        Box(Modifier.fillMaxSize()) {
            if (ui.phase == LidarPhase.AVAILABLE) scene(ui)
            Column(Modifier.fillMaxSize()) {
                Bar(ui.title, actions.close)
                Box(
                    Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
                    contentAlignment = Alignment.Center,
                ) {
                    when (ui.phase) {
                        LidarPhase.AVAILABLE -> if (ui.noGround) {
                            Banner(Viewer3DWords.NO_GROUND, BannerKind.Warning, modifier = Modifier.align(Alignment.TopCenter).padding(Tokens.Spacing.md.dp).widthIn(max = 560.dp))
                        }
                        LidarPhase.IDLE -> Unit
                        else -> Status(ui, actions)
                    }
                    snackbars?.let { ToastHost(it, Modifier.align(Alignment.BottomCenter).padding(Tokens.Spacing.md.dp)) }
                }
            }
        }
    }
}

/** What the scene's area says while the point cloud is in hand and nothing draws it yet (V2 of docs/ANDROID_3D_PLAN.md; V4 draws it). */
@Composable
fun ReadyForNow() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            Viewer3DWords.READY, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
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
            when (ui.phase) {
                LidarPhase.RESOLVING -> {
                    Heading(Viewer3DWords.LOOKING_UP)
                    Body(Viewer3DWords.LOOKING_UP_BODY)
                }
                LidarPhase.BUILDING -> {
                    Heading(Viewer3DWords.BUILDING)
                    Text(
                        Viewer3DWords.progress(ui.stage, ui.ahead), style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = Tokens.Spacing.xs.dp))
                    Text(
                        Viewer3DWords.elapsed(buildClock(ui.elapsedS)), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Body(Viewer3DWords.BUILDING_HINT + " " + if (ui.saved) Viewer3DWords.BUILDING_SAVED else Viewer3DWords.BUILDING_UNSAVED)
                }
                LidarPhase.MISSING -> {
                    Heading(Viewer3DWords.MISSING)
                    Body(Viewer3DWords.MISSING_BODY)
                    PillButton(Viewer3DWords.CHECK_AGAIN, onClick = actions.retry, icon = EzpzIcons.refresh)
                }
                LidarPhase.ERROR -> {
                    Heading(Viewer3DWords.failedTitle(ui.buildFailed))
                    Body(Viewer3DWords.problem(ui.problem))
                    PillButton(Viewer3DWords.RETRY, onClick = actions.retry, icon = EzpzIcons.refresh)
                }
                else -> Unit
            }
        }
    }
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
