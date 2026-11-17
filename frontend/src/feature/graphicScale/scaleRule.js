/*
 * How large the LZ/PZ's planning graphics are drawn as the map zooms out (docs/USER_GUIDE.md, *Planning graphics*).
 * A doghouse, a go-around or a unit is drawn at a fixed size on the screen, so zoomed out it came to cover the LZ and
 * the route it was there to explain. From the zoom at which the LZ is about BOUNDARY_PX across (the reference) they
 * shrink as the square root of the map's scale, half the size two levels out, and two levels out are put away; at the
 * reference and nearer they are drawn as they always were. A helicopter is drawn to scale already, and is not one.
 */
import { project0 } from "../missionOverlay/overlayPlan";

// The LZ's extent on the screen at the reference zoom, in CSS pixels: about a doghouse and a half.
export const BOUNDARY_PX = 100;
// The reference with no boundary to measure (an LZ/PZ not analysed yet), and how far one may be from it.
export const DEFAULT_REFERENCE = 17;
export const MIN_REFERENCE = 15;
export const MAX_REFERENCE = 18;
// Levels below the reference at which the graphics are put away (they are drawn at half their size there).
export const HIDE_BELOW = 2;

const finite = (value) => typeof value === "number" && Number.isFinite(value);

/** A boundary's corners as [lat, lon], however they came (`[lat, lon]`, `{lat, lon}` or `{lat, lng}`); those that are not, left out. */
const corners = (boundary) =>
  (Array.isArray(boundary) ? boundary : [])
    .map((corner) => (Array.isArray(corner) ? corner : [corner?.lat, corner?.lon ?? corner?.lng]))
    .filter(([lat, lon]) => finite(lat) && finite(lon));

/**
 * The zoom at which the boundary's longer side is BOUNDARY_PX on the screen, within MIN_REFERENCE to MAX_REFERENCE
 * and to a hundredth; DEFAULT_REFERENCE without a boundary of some size.
 */
export const referenceZoom = (boundary) => {
  const points = corners(boundary).map(([lat, lon]) => project0(lat, lon));
  if (points.length < 2) return DEFAULT_REFERENCE;
  const xs = points.map((p) => p.x);
  const ys = points.map((p) => p.y);
  const extent = Math.max(Math.max(...xs) - Math.min(...xs), Math.max(...ys) - Math.min(...ys));
  if (!(extent > 0)) return DEFAULT_REFERENCE;
  const zoom = Math.log2(BOUNDARY_PX / extent);
  return Math.round(Math.min(MAX_REFERENCE, Math.max(MIN_REFERENCE, zoom)) * 100) / 100;
};

/** At a map zoom, the graphics' scale (1 at the reference and nearer, to a thousandth) and whether they are put away. */
export const graphicScale = (zoom, reference = DEFAULT_REFERENCE) => {
  if (!finite(zoom)) return { scale: 1, hidden: false };
  const scale = Math.min(1, 2 ** ((zoom - reference) / 2));
  return { scale: Math.round(scale * 1000) / 1000, hidden: zoom < reference - HIDE_BELOW - 1e-9 };
};
