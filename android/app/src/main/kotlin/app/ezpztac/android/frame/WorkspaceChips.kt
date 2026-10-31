package app.ezpztac.android.frame

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.map.ChipAction
import app.ezpztac.map.ChipsThatFit
import app.ezpztac.map.MapChip
import app.ezpztac.packs.OpenPackUi
import app.ezpztac.packs.PackAttentionLine
import app.ezpztac.packs.PackChip
import app.ezpztac.packs.PackStatusLine
import app.ezpztac.packs.PackWords
import app.ezpztac.packs.rememberPackAttention

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

/**
 * How the open [pack] stands, in words under the top of the map. A tablet's line says it always, under its search bar, as it did. A phone's chip shows it, so
 * its line says it only while it wants the person's attention, once that has lasted two seconds (the owner's decision of 2026-10-13: [rememberPackAttention]),
 * and never under a sheet at full height ([underFullSheet]), which would leave a sliver of it showing above its edge.
 */
@Composable
fun WorkspaceLine(pack: OpenPackUi?, tablet: Boolean, underFullSheet: Boolean) {
    if (tablet) {
        pack?.let { PackStatusLine(it) }
    } else {
        // Timed whatever the sheet does, so a line said stays said when the sheet comes down again.
        val attention = rememberPackAttention(pack)
        PackAttentionLine(pack?.takeIf { attention && !underFullSheet })
    }
}
