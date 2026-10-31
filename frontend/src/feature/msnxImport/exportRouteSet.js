/**
 * Export .msnx for one route set from the Routes panel, with the threats beside it as a .ths when
 * asked (AMPS reads the two as a mission and its threat overlay; threats are never saved). No .ths is
 * made when the .msnx was not: threats on their own are not the export that was asked for. Results
 * and errors go to `toast`, never a browser alert (docs/MENU_REDESIGN.md §2).
 *
 * `exportMission(fileId)` and `exportSketches(routes)` download the .msnx and throw when they cannot;
 * either may resolve to { warning } when AMPS will open the file as another airframe than the one
 * planned with. Resolves to whether the .msnx was made.
 */
export const exportRouteSetFiles = async (
  set,
  { withThreats = false, threatCount = 0, exportMission, exportSketches, exportThreats, toast },
) => {
  let result;
  try {
    result = set.kind === "mission" ? await exportMission(set.key) : await exportSketches(set.routes);
  } catch (err) {
    toast({ tone: "error", message: `The mission file could not be made${err?.message ? `: ${err.message}` : "."}` });
    return false;
  }
  // The file has downloaded; the warning is a paragraph, so it stays up long enough to read.
  if (result?.warning) toast({ tone: "warn", message: result.warning, duration: 12000 });
  if (withThreats && threatCount > 0) {
    const baseName = set.kind === "mission" ? set.fileName : set.routes.map((route) => route.name).join("_");
    try {
      // Companion file travels with the mission, e.g. "GOAT SUCKER_threats.ths".
      await exportThreats(`${(baseName || "mission").replace(/\.msnx$/i, "")}_threats`);
    } catch (err) {
      toast({ tone: "warn", message: `The mission exported, but the threats (.ths) could not be${err?.message ? `: ${err.message}` : "."}` });
    }
  }
  return true;
};
