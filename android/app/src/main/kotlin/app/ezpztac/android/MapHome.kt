package app.ezpztac.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ezpztac.designsystem.Banner
import app.ezpztac.designsystem.BannerKind
import app.ezpztac.designsystem.TextAction
import app.ezpztac.designsystem.Tokens
import app.ezpztac.map.EzpzMap
import app.ezpztac.map.GpsLayer
import app.ezpztac.map.MapCommand
import app.ezpztac.map.MapScreen
import app.ezpztac.map.MapViewModel
import app.ezpztac.map.rememberMapHost
import kotlinx.coroutines.flow.filterNotNull

private val PEEK = 112.dp

/**
 * The signed-in app: the map is the root, with a bottom sheet over it (docs/NATIVE_APPS_PLAN.md, "Mobile UX"). The sheet holds the version
 * and a sign-out for now; the diagrams and the planning tools go into it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapHome(
    version: String,
    build: Int,
    maintenance: String?,
    onSignOut: () -> Unit,
    viewModel: MapViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val host = rememberMapHost()
    val scaffold = rememberBottomSheetScaffoldState()

    LaunchedEffect(host) {
        viewModel.commands.collect { command ->
            when (command) {
                is MapCommand.FlyTo -> host.flyTo(command.at, command.zoom)
                MapCommand.FaceNorth -> host.faceNorth()
            }
        }
    }
    LaunchedEffect(host) { snapshotFlow { host.camera }.filterNotNull().collect(viewModel::onCamera) }

    BottomSheetScaffold(
        scaffoldState = scaffold,
        sheetPeekHeight = PEEK,
        sheetContainerColor = MaterialTheme.colorScheme.surface,
        sheetContent = {
            Column(Modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.xl.dp, vertical = Tokens.Spacing.md.dp), verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.md.dp)) {
                if (maintenance != null) Banner(maintenance, BannerKind.Warning)
                Text(stringResource(R.string.home_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.home_version, version, build), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.classification_notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextAction("Sign out", onClick = onSignOut)
            }
        },
    ) { _ ->
        MapScreen(
            state = state, camera = host.camera, gps = state.gps,
            onSearch = viewModel::search, onClearSearchError = viewModel::clearSearchError, onSelectStyle = viewModel::selectStyle,
            onToggleGps = viewModel::toggleGps, onGpsPermissionResult = viewModel::permissionResult, onLocateMe = viewModel::locateMe,
            onFaceNorth = viewModel::faceNorth,
            bottomInset = PEEK,
            modifier = Modifier.fillMaxSize(),
        ) {
            EzpzMap(host = host, style = state.style, initial = viewModel.initialCamera, modifier = Modifier.fillMaxSize())
            GpsLayer(host, state.gps)
        }
    }
}
