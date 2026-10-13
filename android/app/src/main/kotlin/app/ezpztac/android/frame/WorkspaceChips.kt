package app.ezpztac.android.frame

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.map.ChipAction
import app.ezpztac.map.ChipsThatFit
import app.ezpztac.map.MapChip
import app.ezpztac.packs.OpenPackUi
import app.ezpztac.packs.PackChip
import app.ezpztac.packs.PackWords

/**
 * The chips on the phone's row, between the folded search and the account: the workspace (the person's own, or the open [pack]'s in its colour; it opens the
 * switcher while packs run here, [packsHere]), then Import and the Library. Import and the Library give up their words before the workspace's is cut (a narrow
 * phone, large text, a long name: [ChipsThatFit]).
 */
@Composable
fun WorkspaceChips(
    pack: OpenPackUi?,
    packsHere: Boolean,
    onSwitcher: () -> Unit,
    onImport: () -> Unit,
    onLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ChipsThatFit(
        lead = {
            if (pack != null) {
                PackChip(pack, onClick = onSwitcher)
            } else if (packsHere) {
                MapChip(EzpzIcons.person, "Personal", description = PackWords.chipDescription(null), trailingIcon = EzpzIcons.arrowDropDown, onClick = onSwitcher)
            } else {
                MapChip(EzpzIcons.person, "Personal", description = "Workspace: Personal")
            }
        },
        chips = listOf(
            ChipAction(EzpzIcons.fileOpen, "Import", "Import a mission, local points or threats", onImport),
            ChipAction(EzpzIcons.collectionsBookmark, "Library", "Library: everything you have saved", onLibrary),
        ),
        modifier = modifier,
    )
}
