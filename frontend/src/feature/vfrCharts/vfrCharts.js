/*
 * The FAA's VFR charts as this server builds them (docs/VFR_CHARTS.md §4.5, §4.6, §8): what `/api/config` says of the live
 * build, where its layers go, and what the map says of the charts under it. Plain code, so the words can be held to
 * contracts/fixtures/charts (words.json, over the recorded coverage.json), which Android's are held to as well.
 */

export const VFR_STYLE_ID = "vfr-sectional";

/** What the layers from ArcGIS's service are called: it publishes no edition. */
export const ARCGIS_WORDS = "FAA, via ArcGIS";

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const TIER_WORDS = { sectional: "Sectional", tac: "TAC", helicopter: "Helicopter chart", inset: "Inset" };
const NAMED = 2;              // charts of one edition named before "and n more"
const SAMPLES = 5;            // points a side of the view tried against a chart's outline
const DAY_MS = 24 * 60 * 60 * 1000;
const DENVER = { lat: 39.74, lon: -104.99 }; // the other base maps' thumbnails are the Rockies too
const PREVIEW_LEVEL = 8;

const numbers = (value, count) => Array.isArray(value) && value.length === count && value.every(Number.isFinite);

/** The API's root, which `/api/config`'s chart URLs are relative to: VITE_API_URL ends in /api. */
export const apiRootOf = (apiBase) => (apiBase || "").replace(/\/api\/?$/, "");

/** The live build as `/api/config` names it, its URLs made whole; null where there is none (ArcGIS draws the VFR map, §4.6). */
export const liveBuildOf = (config, apiRoot = "") => {
  const vfr = config?.charts?.vfr;
  if (
    !vfr ||
    typeof vfr.build !== "string" ||
    typeof vfr.tiles !== "string" ||
    typeof vfr.coverage !== "string" ||
    !Number.isInteger(vfr.minZoom) ||
    !Number.isInteger(vfr.maxZoom) ||
    !Number.isInteger(vfr.tileSize) ||
    !numbers(vfr.bounds, 4)
  ) {
    return null;
  }
  return {
    build: vfr.build,
    tiles: `${apiRoot}${vfr.tiles}`,
    coverage: `${apiRoot}${vfr.coverage}`,
    tileSize: vfr.tileSize,
    minZoom: vfr.minZoom,
    maxZoom: vfr.maxZoom,
    effective: vfr.effective,
    expires: vfr.expires,
    bounds: vfr.bounds,
    fallback: Array.isArray(vfr.fallback) ? vfr.fallback.filter((box) => numbers(box, 4)) : [],
  };
};

/** Whether a fetched coverage.json can be read. */
export const isCoverage = (data) => Array.isArray(data?.charts) && Number.isInteger(data?.minLevel);

/** `[west, south, east, north]` as Leaflet's bounds. */
export const leafletBounds = ([west, south, east, north]) => [[south, west], [north, east]];

/**
 * How many levels finer than its zoom a screen draws the charts from (§8, *A dense screen*): 1 where a CSS pixel is more than about one
 * physical pixel across, else 0. A tile holds its size in pixels, no more (Mapbox's `@2x` hold twice that), so laid over 512 CSS pixels it
 * is spread over 1,024 physical ones on a 2× screen; the next level, laid over 256, is one for one. From √2 that level is the nearer of the two.
 */
export const levelsFiner = (pixelRatio) => (pixelRatio >= Math.SQRT2 ? 1 : 0);

/**
 * The chart layer's options. Leaflet counts in map zooms, which at 512 px are the build's levels plus one (§8); `finer` lays each tile over
 * half the width, where they are the build's levels.
 */
export const chartLayerOptions = (live, finer = 0) => {
  const zoomOffset = finer - 1;
  return {
    tileSize: Math.floor(live.tileSize / 2 ** finer),
    zoomOffset,
    minNativeZoom: live.minZoom - zoomOffset,
    maxNativeZoom: live.maxZoom - zoomOffset,
    maxZoom: 20,
    bounds: leafletBounds(live.bounds),
  };
};

/** The switcher's thumbnail: a tile the build has, over Denver where the build reaches it. */
export const chartPreviewUrl = (live) => {
  const [west, south, east, north] = live.bounds;
  const within = DENVER.lon > west && DENVER.lon < east && DENVER.lat > south && DENVER.lat < north;
  const { lat, lon } = within ? DENVER : { lat: (south + north) / 2, lon: (west + east) / 2 };
  const z = Math.min(Math.max(PREVIEW_LEVEL, live.minZoom), live.maxZoom);
  const n = 2 ** z;
  const x = Math.floor(((lon + 180) / 360) * n);
  const y = Math.floor(((1 - Math.asinh(Math.tan((lat * Math.PI) / 180)) / Math.PI) / 2) * n);
  return live.tiles.replace("{z}", z).replace("{x}", x).replace("{y}", y);
};

/** The next instant a chart's edition runs out, after `now`; null when none will. */
export const nextExpiry = (live, coverage, now) => {
  const instants = [live?.expires, ...(coverage?.charts || []).map((chart) => chart.edition?.expires)]
    .map((text) => Date.parse(text))
    .filter((at) => at > now);
  return instants.length ? Math.min(...instants) : null;
};

const day = (text, withYear = true) => {
  const [year, month, date] = text.split("-");
  return `${Number(date)} ${MONTHS[Number(month) - 1]}${withYear ? ` ${year}` : ""}`;
};

const span = ({ start, end }) => `${day(start, start.slice(0, 4) !== end.slice(0, 4))} – ${day(end)}`;

const insideRing = (ring, lon, lat) => {
  let inside = false;
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i, i += 1) {
    const [xi, yi] = ring[i];
    const [xj, yj] = ring[j];
    if (yi > lat !== yj > lat && lon < ((xj - xi) * (lat - yi)) / (yj - yi) + xi) inside = !inside;
  }
  return inside;
};

const wrapped = (lon) => ((((lon + 180) % 360) + 360) % 360) - 180;

// The view with its west within ±180°, and once more a whole turn west where it crosses the date line: a map panned round
// the world reports longitudes past 180°, and the charts' never are.
const viewsOf = ({ west, south, east, north }) => {
  const shift = wrapped(west) - west;
  const view = { west: west + shift, south, east: east + shift, north };
  return view.east > 180 ? [view, { ...view, west: view.west - 360, east: view.east - 360 }] : [view];
};

const samplesOf = ({ west, south, east, north }) => {
  const out = [];
  for (let i = 0; i < SAMPLES; i += 1) {
    for (let j = 0; j < SAMPLES; j += 1) {
      out.push([west + ((east - west) * i) / (SAMPLES - 1), south + ((north - south) * j) / (SAMPLES - 1)]);
    }
  }
  return out;
};

const ringOf = (chart) => chart.outline.coordinates[0];

const touches = (ring, view) =>
  ring.some(([lon, lat]) => lon >= view.west && lon <= view.east && lat >= view.south && lat <= view.north) ||
  samplesOf(view).some(([lon, lat]) => insideRing(ring, lon, lat));

const covers = (ring, view) => samplesOf(view).every(([lon, lat]) => insideRing(ring, lon, lat));

const finestFirst = (charts) => [...charts].sort((a, b) => a.scale - b.scale);

// The level whose tiles the map is drawn from: Leaflet rounds its zoom, and shrinks the first level's tiles below it.
const levelAt = (zoom, coverage, finer) => Math.max(Math.round(zoom) - 1 + finer, coverage.minLevel);

// The charts drawn in the view, finest first: those shown at this level whose outlines reach it, less any wholly under a finer one.
const chartsInView = (coverage, views, level) => {
  const reach = finestFirst(coverage.charts.filter((chart) => chart.shownFrom <= level)).filter((chart) =>
    views.some((view) => touches(ringOf(chart), view)),
  );
  return reach.filter(
    (chart) => !reach.some((finer) => finer.scale < chart.scale && views.some((view) => covers(ringOf(finer), view))),
  );
};

const lineOf = (charts, edition, now) => {
  const more = charts.length - NAMED;
  const names = charts.slice(0, NAMED).map((chart) => chart.title).join(", ") + (more > 0 ? ` and ${more} more` : "");
  const expired = now >= Date.parse(edition.expires);
  return {
    text: expired ? `FAA ${names} · Expired ${day(edition.end)} — not current` : `FAA ${names}, ${span(edition)}`,
    expired,
  };
};

// One line for each edition in view (a chart held back keeps its own, §4.2), so a set of charts of one cycle is one line.
const linesOf = (charts, now) => {
  const editions = new Map();
  charts.forEach((chart) => {
    const key = `${chart.edition.start}/${chart.edition.expires}`;
    if (!editions.has(key)) editions.set(key, []);
    editions.get(key).push(chart);
  });
  const lines = [...editions.values()].map((group) => lineOf(group, group[0].edition, now));
  return [...lines.filter((line) => line.expired), ...lines.filter((line) => !line.expired)];
};

// Before coverage.json has come, the build's own edition: its expiry instant is 0901Z the day after its printed end date (§4.5).
const buildLine = (live, now) => {
  const end = new Date(Date.parse(live.expires) - DAY_MS).toISOString().slice(0, 10);
  const edition = { start: live.effective.slice(0, 10), end, expires: live.expires };
  return lineOf([{ title: "VFR charts" }], edition, now);
};

// Past the chart's own detail, by the whole power of two nearest: a line that changed with every tenth of a zoom would not be quiet.
// Counted from the chart's native zoom, so the same on every screen; only which chart is drawn there follows the screen.
const enlargedWords = (coverage, center, zoom, finer) => {
  const level = levelAt(zoom, coverage, finer);
  const lon = wrapped(center.lon);
  const chart = finestFirst(coverage.charts).find(
    (candidate) => candidate.shownFrom <= level && insideRing(ringOf(candidate), lon, center.lat),
  );
  if (!chart) return null;
  const times = 2 ** Math.round(zoom - 1 - chart.nativeLevel);
  return times >= 2 ? `${TIER_WORDS[chart.tier] || "Chart"} enlarged ${times}× — no finer chart here` : null;
};

/**
 * What the map says of the VFR charts under it (§4.5): `lines`, each `{text, expired}`, naming what is in `view` with its edition,
 * and `enlarged`, the quiet line about the chart at `center`, or null. `finer` is this screen's `levelsFiner`: it decides which charts are drawn.
 */
export const chartWords = ({ live, coverage, view, center, zoom, now, finer = 0 }) => {
  if (!live) return { lines: [{ text: ARCGIS_WORDS, expired: false }], enlarged: null };
  const views = viewsOf(view);
  const lines = coverage ? linesOf(chartsInView(coverage, views, levelAt(zoom, coverage, finer)), now) : [buildLine(live, now)];
  const overArcgis = live.fallback.some(([west, south, east, north]) =>
    views.some((v) => v.west < east && west < v.east && v.south < north && south < v.north),
  );
  return {
    lines: overArcgis ? [...lines, { text: ARCGIS_WORDS, expired: false }] : lines,
    enlarged: coverage ? enlargedWords(coverage, center, zoom, finer) : null,
  };
};
