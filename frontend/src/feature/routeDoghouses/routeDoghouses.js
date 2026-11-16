/*
 * A doghouse on each leg of a route, between two of its route points (a checkpoint, the SP or RP, the target), as a crew
 * draws them on a strip map (docs/USER_GUIDE.md, *Route doghouses*). What it says is the route's plan, the one the route
 * panel shows (routeCalc.computeRoutePlan): in the roof the point the leg flies to, then the leg's true course, its time
 * as minutes+seconds, its length in km and its planned airspeed. It stands at the middle of the leg's longest straight
 * stretch (a leg bent by shaping points has more than one), clear of the line on the right of the way it is flown, and is turned to that way, as an
 * LZ's doghouses are turned to their heading. It is shown only where the leg is long enough on the screen to hold it.
 */
import { computeRoutePlan, defaultRoutePlan, planPoints } from "../msnxImport/routeCalc";
import { project0 } from "../missionOverlay/overlayPlan";

// The leg on the screen, in CSS pixels, below which its doghouse is not drawn: the box is about 90 px along the leg.
export const MIN_LEG_PX = 160;
// The box's half-length along the leg (roof and four rows, 115 px) and half-width across it (60 px), in CSS pixels,
// and the gap kept between it and the line.
export const HALF_LENGTH_PX = 58;
export const HALF_WIDTH_PX = 30;
export const GAP_PX = 14;
const NM_TO_KM = 1.852;

const finite = (value) => typeof value === "number" && Number.isFinite(value);

/** Zoom-0 pixels back to a place: the inverse of project0. */
const unproject0 = ({ x, y }) => ({
  lat: (Math.atan(Math.sinh(Math.PI * (1 - (2 * y) / 256))) * 180) / Math.PI,
  lon: (x / 256) * 360 - 180,
});

/** "CP3" for ".CP3": a point's name as its label on the map has it. */
export const roofName = (name) => String(name ?? "").replace(/^\./, "").trim();

/** A leg's time as the doghouse's two halves, "07" and "05" for 425 s; "--" for each with no time. */
export const legTime = (seconds) => {
  if (!finite(seconds)) return { minutes: "--", seconds: "--" };
  const total = Math.round(seconds);
  return { minutes: String(Math.floor(total / 60)).padStart(2, "0"), seconds: String(total % 60).padStart(2, "0") };
};

/** A true course as the route panel writes it: "045". */
export const courseText = (degrees) => (finite(degrees) ? String(Math.round(degrees)).padStart(3, "0") : "---");

/**
 * Where a leg's doghouse stands, in zoom-0 pixels: the middle of its longest straight stretch (the first of equal ones),
 * so a leg bent by a shaping point does not have it on the bend; that stretch's bearing on the screen; and the leg's
 * whole length as it is drawn.
 */
const placeOnLeg = (points) => {
  const pixels = points.map((p) => project0(p.lat, p.lon));
  let length = 0;
  let longest = -1;
  let at = pixels[0];
  let bearing = 0;
  for (let i = 1; i < pixels.length; i += 1) {
    const a = pixels[i - 1];
    const b = pixels[i];
    const stretch = Math.hypot(b.x - a.x, b.y - a.y);
    length += stretch;
    if (stretch > longest) {
      longest = stretch;
      at = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
      bearing = (Math.atan2(b.x - a.x, a.y - b.y) * 180) / Math.PI;
    }
  }
  return { at, bearing, length };
};

/**
 * Where the box's middle stands from the line, in CSS pixels in the box's own frame (turned to the leg's course, x across
 * it to the right, y along it backwards): on the right of the stretch it stands beside, far enough that the box, turned
 * to the course, clears the line however far the stretch runs from the course. A straight leg's is HALF_WIDTH_PX + GAP_PX
 * across.
 */
export const sideOffset = (courseDeg, stretchDeg) => {
  const off = ((stretchDeg - courseDeg) * Math.PI) / 180;
  const reach = HALF_LENGTH_PX * Math.abs(Math.sin(off)) + HALF_WIDTH_PX * Math.abs(Math.cos(off)) + GAP_PX;
  const round = (value) => Math.round(value * 10) / 10 + 0;
  return { x: round(reach * Math.cos(off)), y: round(reach * Math.sin(off)) };
};

/**
 * Each leg's doghouse for the routes shown (a route hidden on the map has none): its place, which way it is turned
 * (the leg's true course, a screen's own on the conformal map), the leg's length on the screen at zoom 0 (`lengthPx0`,
 * doubled each zoom), and what it says.
 */
export const routeDoghouses = (routes = []) => {
  const doghouses = [];
  for (const route of Array.isArray(routes) ? routes : []) {
    if (!route || route.visible === false || !Array.isArray(route.points)) continue;
    if (!route.points.every((p) => finite(p?.lat) && finite(p?.lon))) continue;
    const amps = planPoints(route);
    if (amps.length < 2) continue;
    const plan = computeRoutePlan(route, { ...defaultRoutePlan(), ...route.plan }, route.elevations || {});
    plan.legs.forEach((leg, i) => {
      const from = route.points.indexOf(amps[i]);
      const to = route.points.indexOf(amps[i + 1]);
      if (from < 0 || to <= from) return;
      const { at, bearing, length } = placeOnLeg(route.points.slice(from, to + 1));
      if (!(length > 0)) return;
      const { lat, lon } = unproject0(at);
      const speed = leg.airspeed?.value;
      const rotation = finite(leg.courseTrueDeg) ? leg.courseTrueDeg : 0;
      doghouses.push({
        key: `${route.id}:${i}:${leg.fromId}:${leg.toId}`,
        lat,
        lon,
        rotation,
        offset: sideOffset(rotation, bearing),
        lengthPx0: length,
        box: {
          id: roofName(amps[i + 1].name) || roofName(leg.toName),
          heading: courseText(leg.courseTrueDeg),
          ...legTime(leg.timeSec),
          distance: finite(leg.distNm) ? (leg.distNm * NM_TO_KM).toFixed(1) : "--",
          airspeed: finite(speed) ? String(Math.round(speed)) : "--",
        },
      });
    });
  }
  return doghouses;
};

/** Whether a leg's doghouse is drawn at a map zoom. */
export const fitsAt = (doghouse, zoom) => finite(zoom) && doghouse.lengthPx0 * 2 ** zoom >= MIN_LEG_PX;
