/**
 * Threat data model + defaults, derived from the AMPS .ths schema
 * (THREATS + THREATRADAR tables). Threats are export-only — never persisted.
 *
 * A threat has a position, MIL-STD symbol id, and up to two "radars":
 *   type 0 = Detection, type 1 = Engagement.
 * Each radar has a max range, an antenna height (the "height above ground"),
 * an AGL/MSL flag, and up to three aircraft-altitude bands whose terrain mask
 * (line-of-sight exposure) is drawn as an overlay.
 */

export const RADAR_TYPES = { detection: 0, engagement: 1 };
export const NMI_TO_M = 1852;

let seq = 0;
const genId = () => `threat-${Date.now()}-${seq++}`;

const detectionBands = () => [
  { altFt: 50, color: "#fbbf24", alpha: 0.32, colorIndex: 1, viewable: true },
  { altFt: 250, color: "#f97316", alpha: 0.28, colorIndex: 3, viewable: true },
  { altFt: 500, color: "#facc15", alpha: 0.24, colorIndex: 5, viewable: true },
];

const engagementBands = () => [
  { altFt: 50, color: "#ef4444", alpha: 0.42, colorIndex: 2, viewable: true },
  { altFt: 250, color: "#dc2626", alpha: 0.34, colorIndex: 4, viewable: true },
  { altFt: 500, color: "#b91c1c", alpha: 0.26, colorIndex: 0, viewable: true },
];

export const defaultRadar = (type) => ({
  type,
  rangeNmi: type === RADAR_TYPES.detection ? 25 : 15,
  antennaHeightFt: 20,
  aglNotMsl: true,
  showMask: true,
  showRangeRings: true,
  bands: type === RADAR_TYPES.detection ? detectionBands() : engagementBands(),
});

/** Builds a new threat at a location with sensible defaults. */
export const makeThreat = (lat, lon, overrides = {}) => ({
  id: genId(),
  name: overrides.name || "Threat",
  milstdId: overrides.milstdId || "SHGPEWMAI------",
  lat,
  lon,
  information: overrides.information || "",
  source: overrides.source || "SOF",
  showThreat: true,
  color: "#ef4444",
  visible: true,
  radars: overrides.radars || [
    defaultRadar(RADAR_TYPES.detection),
    defaultRadar(RADAR_TYPES.engagement),
  ],
  // Populated by the backend mask endpoint.
  mask: null,
  maskLoading: false,
  maskError: null,
  ...overrides,
});

/** The payload the /api/threat-mask and /api/threats-ths endpoints expect. */
export const threatToPayload = (threat) => ({
  name: threat.name,
  milstdId: threat.milstdId,
  lat: threat.lat,
  lon: threat.lon,
  information: threat.information,
  source: threat.source,
  showThreat: threat.showThreat,
  radars: threat.radars.map((r) => ({
    type: r.type,
    rangeNmi: r.rangeNmi,
    antennaHeightFt: r.antennaHeightFt,
    aglNotMsl: r.aglNotMsl,
    showMask: r.showMask,
    showRangeRings: r.showRangeRings,
    bands: r.bands.map((b) => ({
      altFt: b.altFt,
      color: b.color,
      alpha: b.alpha,
      colorIndex: b.colorIndex,
      viewable: b.viewable,
    })),
  })),
});
