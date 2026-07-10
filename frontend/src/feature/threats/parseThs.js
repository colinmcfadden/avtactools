import { SqliteReader } from "../localPoints/sqliteReader";
import { makeThreat, defaultRadar, RADAR_TYPES } from "./threatModel";

/**
 * Parses an AMPS .ths file (a SQLite DB) into threat objects to pre-fill the
 * add-threat dialog. Reads the THREATS table for identity/position and groups
 * THREATRADAR rows (detection/engagement) under each threat by ID.
 */
export async function parseThsFile(file) {
  const buffer = await file.arrayBuffer();
  let reader;
  try {
    reader = new SqliteReader(buffer);
  } catch {
    throw new Error("This doesn't look like an AMPS .ths threat file.");
  }

  const threats = reader.readTable("THREATS");
  if (!threats) {
    throw new Error("No THREATS table found — is this an AMPS .ths file?");
  }
  const radars = reader.readTable("THREATRADAR") || [];

  const radarsByThreat = new Map();
  for (const row of radars) {
    const list = radarsByThreat.get(row.ID) || [];
    list.push(row);
    radarsByThreat.set(row.ID, list);
  }

  const out = [];
  for (const t of threats) {
    const lat = Number(t.LATITUDE_DEG);
    const lon = Number(t.LONGITUDE_DEG);
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) continue;

    const radarRows = radarsByThreat.get(t.ID) || [];
    const parsedRadars = radarRows.map((r) => {
      const template = defaultRadar(Number(r.RADAR_TYPE) || 0);
      const bands = template.bands.map((b, i) => ({
        ...b,
        altFt: Number(r[`ELEVATION${i + 1}`] ?? b.altFt),
        colorIndex: Number(r[`COLOR${i + 1}`] ?? b.colorIndex),
        viewable: Number(r[`MASK${i + 1}_VIEWABLE`] ?? 1) !== 0,
      }));
      return {
        ...template,
        rangeNmi: Number(r.RANGE_NMI ?? template.rangeNmi),
        antennaHeightFt: Number(r.ANTENNAE_HEIGHT_FT ?? template.antennaHeightFt),
        aglNotMsl: Number(r.AGL_NOT_MSL ?? 0) !== 0,
        showMask: Number(r.SHOW_MASK ?? 1) !== 0,
        showRangeRings: Number(r.SHOW_RANGE_RINGS ?? 1) !== 0,
        bands,
      };
    });

    out.push(
      makeThreat(lat, lon, {
        name: (t.OFFICIAL_NAME || "Threat").trim(),
        milstdId: (t.MILSTD_ID || "SHGPEWMAI------").trim(),
        information: (t.INFORMATION || "").trim(),
        source: (t.SOURCE || "SOF").trim(),
        radars: parsedRadars.length
          ? parsedRadars
          : [defaultRadar(RADAR_TYPES.detection), defaultRadar(RADAR_TYPES.engagement)],
      }),
    );
  }

  if (out.length === 0) {
    throw new Error("This .ths file contains no readable threats.");
  }
  return out;
}
