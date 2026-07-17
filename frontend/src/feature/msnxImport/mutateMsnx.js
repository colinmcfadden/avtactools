import { MISSION_PATHS } from "./parseMsnx.js";
import { computeRoutePlan, defaultRoutePlan } from "./routeCalc.js";
import {
  formatAmpsAirspeed,
  formatAmpsWind,
  formatAmpsPlanAltitude,
  formatAmpsCmdAlt,
  formatAmpsElevation,
  formatAmpsClockTime,
} from "./ampsFormats.js";

export const GPX_NS = "http://www.topografix.com/GPX/1/1";
export const MSNX_NS = "http://www.xplan.com/MSNX/v1.0";
export const BOM = "﻿";

/**
 * Serializes a document to text with exactly one XML declaration. Browsers'
 * XMLSerializer preserves the declaration of the originally-parsed document
 * while jsdom omits it — blindly prepending one produced a doubled (invalid)
 * declaration in real browsers.
 */
export const serializeXmlPart = (doc, { bom = false, decl } = {}) => {
  const serialized = new XMLSerializer().serializeToString(doc);
  const body = serialized.startsWith("<?xml") ? serialized : decl + serialized;
  return (bom ? BOM : "") + body;
};

export const findDirectChild = (el, tagName) => {
  for (const child of el.children) {
    if (child.tagName === tagName) return child;
  }
  return null;
};

const clearChildren = (el) => {
  while (el.firstChild) el.removeChild(el.firstChild);
};

export const formatCoordinate = (lat, lon) => {
  const latHemi = lat >= 0 ? "N" : "S";
  const lonHemi = lon >= 0 ? "E" : "W";
  return `${Math.abs(lat)}${latHemi}/${Math.abs(lon)}${lonHemi}`;
};

const findPointById = (pointsDoc, id) => {
  for (const point of pointsDoc.getElementsByTagName("point")) {
    const idEl = findDirectChild(point, "id");
    if (idEl && idEl.textContent === id) return point;
  }
  return null;
};

const findLegById = (legsDoc, id) => {
  for (const leg of legsDoc.getElementsByTagName("leg")) {
    const idEl = findDirectChild(leg, "id");
    if (idEl && idEl.textContent === id) return leg;
  }
  return null;
};

const findSegmentById = (segmentsDoc, id) => {
  for (const segment of segmentsDoc.getElementsByTagName("segment")) {
    const idEl = findDirectChild(segment, "id");
    if (idEl && idEl.textContent === id) return segment;
  }
  return null;
};

export const findCoordinateValueEl = (pointEl) => {
  for (const item of pointEl.getElementsByTagName("item")) {
    const keyEl = findDirectChild(item, "key");
    if (keyEl && keyEl.textContent === "Coordinate") {
      const attributeEl = findDirectChild(item, "attribute");
      return attributeEl && findDirectChild(attributeEl, "value");
    }
  }
  return null;
};

export const setItemValue = (pointEl, key, newValue) => {
  for (const item of pointEl.getElementsByTagName("item")) {
    const keyEl = findDirectChild(item, "key");
    if (keyEl && keyEl.textContent === key) {
      const attributeEl = findDirectChild(item, "attribute");
      const valueEl = attributeEl && findDirectChild(attributeEl, "value");
      if (valueEl) valueEl.textContent = newValue;
      return;
    }
  }
};

const countStdPtCommands = (pointEl) => {
  let count = 0;
  for (const keyEl of pointEl.getElementsByTagName("key")) {
    if (keyEl.textContent === "CmdTypeID") count += 1;
  }
  return count;
};

const findGpxRteptByPointId = (gpxDoc, pointId) => {
  for (const rtept of gpxDoc.getElementsByTagName("rtept")) {
    const pointEl = rtept.getElementsByTagName("msnx:point")[0];
    if (pointEl && pointEl.getAttribute("msnx:id") === pointId) return rtept;
  }
  return null;
};

const pickTemplatePointId = (route, docs) => {
  for (const p of route.points) {
    if (!p.id) continue;
    const pointEl = findPointById(docs.points, p.id);
    if (pointEl && countStdPtCommands(pointEl) === 1) return p.id;
  }
  return route.points.find((p) => p.id)?.id || null;
};

const pointToSegmentDistanceSq = (px, py, ax, ay, bx, by) => {
  const dx = bx - ax;
  const dy = by - ay;
  const lengthSq = dx * dx + dy * dy;
  if (lengthSq === 0) return (px - ax) ** 2 + (py - ay) ** 2;
  let t = ((px - ax) * dx + (py - ay) * dy) / lengthSq;
  t = Math.max(0, Math.min(1, t));
  const projX = ax + t * dx;
  const projY = ay + t * dy;
  return (px - projX) ** 2 + (py - projY) ** 2;
};

/**
 * Finds the nearest pair of *consecutive, rendered* points to a click. This
 * deliberately searches in on-screen drawing order rather than geometric
 * distance across the whole route's legs — routes with serpentine/looping
 * geometry can have distant legs that pass close to each other, and a
 * route-wide nearest-leg search would happily "snap" a click to the wrong,
 * far-away leg. Adjacency in the rendered point list is what the user is
 * actually looking at when they right-click a specific spot on the line.
 */
export const findNearestAdjacentIndex = (route, lat, lon) => {
  let bestIndex = -1;
  let bestDist = Infinity;
  for (let i = 0; i < route.points.length - 1; i++) {
    const a = route.points[i];
    const c = route.points[i + 1];
    if (a.lat == null || c.lat == null) continue;
    const d = pointToSegmentDistanceSq(lon, lat, a.lon, a.lat, c.lon, c.lat);
    if (d < bestDist) {
      bestDist = d;
      bestIndex = i;
    }
  }
  return bestIndex;
};

/** All (legIdEl, legEl, startId, endId) tuples for a route's segment, in order. */
const getSegmentLegs = (docs, route) => {
  const segmentEl = findSegmentById(docs.segments, route.segmentId);
  if (!segmentEl) throw new Error("Couldn't find this route's segment data.");

  const legsListEl = findDirectChild(segmentEl, "legs");
  const legIdEls = legsListEl
    ? Array.from(legsListEl.children).filter((c) => c.tagName === "id")
    : [];

  const legs = legIdEls.map((legIdEl) => {
    const legEl = findLegById(docs.legs, legIdEl.textContent);
    return {
      legIdEl,
      legEl,
      startId: legEl && findDirectChild(legEl, "startpt")?.textContent,
      endId: legEl && findDirectChild(legEl, "endpt")?.textContent,
    };
  });

  return { legsListEl, legs };
};

/**
 * Many rendered GPX points (serpentine-maneuver shaping points, command-only
 * duplicates like ".STTO") aren't real vertices in the leg graph at all — the
 * whole visual wiggle can be a single leg between two named waypoints. This
 * returns the set of point ids that genuinely start or end some leg.
 */
const collectLegGraphPointIds = (legs) => {
  const ids = new Set();
  for (const leg of legs) {
    if (leg.startId) ids.add(leg.startId);
    if (leg.endId) ids.add(leg.endId);
  }
  return ids;
};

/** Finds the real leg (and its slot in the segment's ordered leg list) connecting two specific points. */
const findLegBetweenPoints = (legs, legsListEl, pointAId, pointCId) => {
  for (const leg of legs) {
    if (leg.legEl && leg.startId === pointAId && leg.endId === pointCId) {
      return { legEl: leg.legEl, legIdEl: leg.legIdEl, legsListEl };
    }
  }
  return { legEl: null, legIdEl: null, legsListEl };
};

/** Patches lat/lon for an existing point in both mission.gpx and points.xml. */
export function updatePointCoordinate(docs, pointId, lat, lon) {
  const rtept = findGpxRteptByPointId(docs.gpx, pointId);
  if (rtept) {
    rtept.setAttribute("lat", String(lat));
    rtept.setAttribute("lon", String(lon));
  }

  const newCoord = formatCoordinate(lat, lon);
  let oldCoord = null;

  const pointEl = findPointById(docs.points, pointId);
  if (pointEl) {
    const valueEl = findCoordinateValueEl(pointEl);
    if (valueEl) {
      oldCoord = valueEl.textContent;
      valueEl.textContent = newCoord;
    }
  }

  // Serpentine display points mirror a trackpoint in their leg's
  // trackpointset — the authoritative geometry AMPS calculates from. Keep it
  // in sync when a serpentine point is dragged (matched by its old
  // coordinate string; trackpoints have their own ids).
  //
  // Queued rather than applied directly: reading `docs.legs` here would parse
  // the whole (often tens-of-MB) legs document on every drag. The swap is
  // replayed if and when that DOM is ever built.
  if (oldCoord && oldCoord !== newCoord) {
    docs.queueTrackpointSwap?.(oldCoord, newCoord);
  }
}

/**
 * Inserts a brand-new point at (lat, lon) on the leg of `route` nearest to that
 * location, splitting that leg in two. Clones an existing simple point/leg as a
 * template so the new entries stay schema-valid; all cloned calculated data
 * (fuel/timing/elevation) is left stale, to be recalculated in AMPS.
 */
export function insertPointOnRoute(docs, route, lat, lon) {
  const index = findNearestAdjacentIndex(route, lat, lon);
  if (index === -1) {
    throw new Error("Couldn't find a route segment near this location to split.");
  }

  const { legsListEl, legs } = getSegmentLegs(docs, route);
  const legGraphIds = collectLegGraphPointIds(legs);

  // Many rendered points between the clicked pair aren't real leg vertices
  // (serpentine shaping points, command-only duplicates like ".STTO") — the
  // whole visual wiggle can be a single leg between two named waypoints.
  // Walk outward until both sides land on a point that's a genuine leg
  // endpoint, then split whichever real leg connects them.
  let aIdx = index;
  let cIdx = index + 1;
  while (aIdx > 0 && !legGraphIds.has(route.points[aIdx].id)) aIdx -= 1;
  while (cIdx < route.points.length - 1 && !legGraphIds.has(route.points[cIdx].id)) cIdx += 1;

  const pointA = route.points[aIdx];
  const pointC = route.points[cIdx];
  const match = findLegBetweenPoints(legs, legsListEl, pointA.id, pointC.id);

  if (!match.legEl) {
    throw new Error(
      "Couldn't find the connecting leg between these two points — this spot on the line may not be directly editable.",
    );
  }

  const { legEl, legIdEl } = match;

  const newPointId = crypto.randomUUID();
  const newLegId = crypto.randomUUID();

  // 1. Split the leg: clone it for the second half (new point -> C), then
  //    repoint the original leg's endpoint at the new point (A -> new point).
  const legClone = legEl.cloneNode(true);
  findDirectChild(legClone, "id").textContent = newLegId;
  findDirectChild(legClone, "startpt").textContent = newPointId;
  legEl.parentNode.insertBefore(legClone, legEl.nextSibling);
  findDirectChild(legEl, "endpt").textContent = newPointId;

  // 2. Register the new leg in the segment's ordered leg sequence.
  const newLegIdEl = docs.segments.createElement("id");
  newLegIdEl.textContent = newLegId;
  legsListEl.insertBefore(newLegIdEl, legIdEl.nextSibling);

  // 3. Clone a template point to build the new point entry.
  const templateId = pickTemplatePointId(route, docs);
  const templatePointEl = templateId && findPointById(docs.points, templateId);
  if (!templatePointEl) {
    throw new Error("Couldn't find a point template to clone for the new point.");
  }

  const pointClone = templatePointEl.cloneNode(true);
  findDirectChild(pointClone, "id").textContent = newPointId;

  const incoming = findDirectChild(pointClone, "incominglegs");
  clearChildren(incoming);
  const incId = docs.points.createElement("id");
  incId.textContent = findDirectChild(legEl, "id").textContent;
  incoming.appendChild(incId);

  const outgoing = findDirectChild(pointClone, "outgoinglegs");
  clearChildren(outgoing);
  const outId = docs.points.createElement("id");
  outId.textContent = newLegId;
  outgoing.appendChild(outId);

  const coordValueEl = findCoordinateValueEl(pointClone);
  if (coordValueEl) coordValueEl.textContent = formatCoordinate(lat, lon);

  setItemValue(pointClone, "PtDbLookup", "");
  setItemValue(pointClone, "PtNameFix", ".NEWPT");
  setItemValue(pointClone, "PtDesc", "");

  templatePointEl.parentNode.appendChild(pointClone);

  // 4. Repoint C's incoming leg reference from the original leg to the new one.
  const pointCEl = findPointById(docs.points, pointC.id);
  if (pointCEl) {
    const cIncoming = findDirectChild(pointCEl, "incominglegs");
    if (cIncoming) {
      const originalLegId = findDirectChild(legEl, "id").textContent;
      for (const idEl of Array.from(cIncoming.children)) {
        if (idEl.tagName === "id" && idEl.textContent === originalLegId) {
          idEl.textContent = newLegId;
        }
      }
    }
  }

  // 5. Insert a matching <rtept> into mission.gpx, right after the point the
  //    user actually clicked nearest to — not necessarily `pointA`, which may
  //    be the real leg's endpoint several cosmetic (e.g. serpentine) points
  //    away. Splicing at the true click-adjacent spot keeps the polyline's
  //    visual shape correct; the underlying leg graph doesn't care where in
  //    that cosmetic run the new point's rtept physically sits.
  const anchorPoint = route.points[index];
  const aRtept = findGpxRteptByPointId(docs.gpx, anchorPoint.id);
  const templateRtept = findGpxRteptByPointId(docs.gpx, templateId);

  const newRtept = docs.gpx.createElementNS(GPX_NS, "rtept");
  newRtept.setAttribute("lat", String(lat));
  newRtept.setAttribute("lon", String(lon));

  const eleEl = docs.gpx.createElementNS(GPX_NS, "ele");
  eleEl.textContent = String(anchorPoint.ele ?? 0);
  newRtept.appendChild(eleEl);

  const nameEl = docs.gpx.createElementNS(GPX_NS, "name");
  nameEl.textContent = ".NEWPT";
  newRtept.appendChild(nameEl);

  const extEl = docs.gpx.createElementNS(GPX_NS, "extensions");
  const msnxPointEl = docs.gpx.createElementNS(MSNX_NS, "msnx:point");
  msnxPointEl.setAttributeNS(MSNX_NS, "msnx:id", newPointId);

  const templateMsnxPoint = templateRtept?.getElementsByTagName("msnx:point")[0];
  const templateCommandsEl = templateMsnxPoint?.getElementsByTagName("msnx:commands")[0];
  const msnxCommandsEl = docs.gpx.createElementNS(MSNX_NS, "msnx:commands");
  if (templateCommandsEl) {
    for (const cmd of Array.from(templateCommandsEl.children)) {
      msnxCommandsEl.appendChild(cmd.cloneNode(true));
    }
  }
  msnxPointEl.appendChild(msnxCommandsEl);
  extEl.appendChild(msnxPointEl);
  newRtept.appendChild(extEl);

  if (aRtept) {
    aRtept.parentNode.insertBefore(newRtept, aRtept.nextSibling);
  }

  return {
    afterPointId: anchorPoint.id,
    point: {
      id: newPointId,
      lat,
      lon,
      ele: anchorPoint.ele ?? null,
      name: ".NEWPT",
      // The inserted point is a full route point in the XML (cloned from a
      // standard-point template), so present it as one.
      kind: "amps",
      ptType: "turn",
      role: "waypoint",
    },
  };
}

/** Renames an existing point in points.xml (PtNameFix) and mission.gpx. */
export function updatePointName(docs, pointId, name) {
  const pointEl = findPointById(docs.points, pointId);
  if (pointEl) {
    setItemValue(pointEl, "PtNameFix", name);
    setItemValue(pointEl, "PtDesc", name);
  }
  const rtept = findGpxRteptByPointId(docs.gpx, pointId);
  if (rtept) {
    const nameEl = rtept.getElementsByTagName("name")[0];
    if (nameEl) nameEl.textContent = name;
  }
}

/**
 * Writes an imported route's (edited) inline plan back into its mission docs:
 * per-point planned altitude / elevation / clock into points.xml, and per-leg
 * airspeed / wind into legs.xml (assigned to the leg arriving at each point).
 * Mirrors how the sketch exporter emits these values so a re-exported mission
 * carries the user's planning edits; AMPS recomputes the derived data on Calc.
 */
export function applyPlanToMsnxDocs(docs, route) {
  if (!route?.plan) return;
  const plan = { ...defaultRoutePlan(), ...route.plan };
  const result = computeRoutePlan(route, plan, route.elevations || {});

  for (const rp of result.points) {
    const pointEl = findPointById(docs.points, rp.id);
    if (!pointEl) continue;
    setItemValue(pointEl, "PlanAltitudeValue", formatAmpsPlanAltitude(rp.value, rp.ref));
    if (rp.mslFt != null) {
      setItemValue(pointEl, "CmdAlt", formatAmpsCmdAlt(rp.mslFt));
      setItemValue(pointEl, "CmdAltValid", "True");
    }
    if (rp.groundFt != null) {
      setItemValue(pointEl, "Elevation", formatAmpsElevation(rp.groundFt));
    }
    if (rp.clockTime) {
      setItemValue(pointEl, "CmdClockTime", formatAmpsClockTime(rp.clockTime));
    }
  }

  let legs;
  try {
    ({ legs } = getSegmentLegs(docs, route));
  } catch {
    return; // no segment/leg data — points were still updated above
  }
  const legByEnd = new Map();
  for (const leg of legs) {
    if (leg.endId && leg.legEl) legByEnd.set(leg.endId, leg.legEl);
  }
  for (const leg of result.legs) {
    const legEl = legByEnd.get(leg.toId);
    if (!legEl) continue;
    setItemValue(legEl, "AirspeedValue", formatAmpsAirspeed(leg.airspeed));
    const wind = formatAmpsWind(leg.wind?.dirTrue ?? 0, leg.wind?.speedKts ?? 0);
    setItemValue(legEl, "CruiseWind", wind);
    setItemValue(legEl, "ClimbDescentWind", wind);
  }
}

export const downloadBlob = (blob, filename) => {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
};

/** Serializes the mutated docs back into the retained zip as a .msnx Blob. */
export async function buildMsnxBlob(zip, docs) {
  const noFolders = { createFolders: false };
  const missionDecl = '<?xml version="1.0" encoding="utf-8"?>';

  zip.file(MISSION_PATHS.gpx, serializeXmlPart(docs.gpx, { decl: '<?xml version="1.0"?>' }));
  zip.file(
    MISSION_PATHS.points,
    serializeXmlPart(docs.points, { bom: true, decl: missionDecl }),
    noFolders,
  );
  zip.file(
    MISSION_PATHS.legs,
    serializeXmlPart(docs.legs, { bom: true, decl: missionDecl }),
    noFolders,
  );
  zip.file(
    MISSION_PATHS.segments,
    serializeXmlPart(docs.segments, { bom: true, decl: missionDecl }),
    noFolders,
  );

  return zip.generateAsync({
    type: "blob",
    compression: "DEFLATE",
    compressionOptions: { level: 6 },
  });
}

/** Serializes the mutated docs back into the retained zip and downloads it. */
export async function exportMsnxFile(zip, docs, filename) {
  const blob = await buildMsnxBlob(zip, docs);
  downloadBlob(blob, filename);
}
