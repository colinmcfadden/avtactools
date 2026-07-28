import JSZip from "jszip";
import { defaultRoutePlan } from "./routeCalc";
import { createMissionDocs, extractLegPlanData } from "./msnxDocs";
import {
  FT_PER_M,
  parseAmpsAirspeed,
  parseAmpsWind,
  parseAmpsMeters,
  parseAmpsClock,
} from "./ampsParse";

const RELEASE_POINT_PATTERN = /^RP\d*$/i;
const TARGET_PATTERN = /^(LZ|PZ)/i;

export const MISSION_PATHS = {
  gpx: "mission.gpx",
  points: "mission/points.xml",
  legs: "mission/legs.xml",
  segments: "mission/segments.xml",
};

// Optional: present in real AMPS missions, absent from hand-built fixtures, so
// it is read separately from the required MISSION_PATHS above.
const VEHICLES_PATH = "mission/vehicles.xml";

// e.g. "Air:Rotary Wing:H60:9856:Default:1.0014:UH-60L" — the trailing field is
// the airframe designation.
const VEHICLE_DESCRIPTION = /<vehicledescription>([\s\S]*?)<\/vehicledescription>/i;

/**
 * The airframe this mission was planned for, as AMPS records it.
 *
 * Returns `{ description, designation }`, or null when the file carries no
 * vehicle data. Read from raw text rather than a parsed DOM because
 * vehicles.xml runs to hundreds of KB and this is the only field we need.
 */
export const readMissionAircraft = (vehiclesText) => {
  if (!vehiclesText) return null;
  const match = VEHICLE_DESCRIPTION.exec(vehiclesText);
  const description = match?.[1]?.trim();
  if (!description) return null;
  const parts = description.split(":");
  return {
    description,
    designation: (parts[parts.length - 1] || "").trim() || null,
  };
};

const getChildText = (el, tagName) => {
  for (const child of el.children) {
    if (child.tagName === tagName) return child.textContent?.trim() ?? "";
  }
  return "";
};

const classifyPoint = (name, index) => {
  if (index === 0) return "start";
  const stripped = name.replace(/^\./, "");
  if (RELEASE_POINT_PATTERN.test(stripped)) return "release";
  if (TARGET_PATTERN.test(stripped)) return "target";
  return "waypoint";
};

const getItemValue = (el, key) => {
  for (const item of el.getElementsByTagName("item")) {
    const keyEl = getDirectChild(item, "key");
    if (keyEl && keyEl.textContent === key) {
      const attributeEl = getDirectChild(item, "attribute");
      const valueEl = attributeEl && getDirectChild(attributeEl, "value");
      return valueEl?.textContent ?? null;
    }
  }
  return null;
};

const getDirectChild = (el, tagName) => {
  for (const child of el.children) {
    if (child.tagName === tagName) return child;
  }
  return null;
};

/**
 * points.xml is the authority on what each point IS — `PtType` distinguishes
 * real AMPS route points (RtePtTurn/IP/Target/STTO) from calc-only shaping
 * points (CalcPtSerpentine etc., IsCalcPt=True). Reading it means re-imported
 * files keep the exact designation/symbol they were exported with, instead of
 * guessing from point names.
 */
const buildPointInfoMap = (pointsDoc) => {
  const map = new Map();
  for (const point of pointsDoc.getElementsByTagName("point")) {
    const id = getDirectChild(point, "id")?.textContent;
    if (!id) continue;
    const ptTypeValue = getItemValue(point, "PtType") || "";
    const isCalc =
      getItemValue(point, "IsCalcPt") === "True" || ptTypeValue.startsWith("CalcPt");

    let kind = null;
    let ptType = null;
    if (isCalc) {
      kind = "shaping";
    } else if (ptTypeValue) {
      kind = "amps";
      if (ptTypeValue.startsWith("RtePtIP")) ptType = "ip";
      else if (ptTypeValue.startsWith("RtePtTarget")) ptType = "target";
      else ptType = "turn"; // RtePtTurn, RtePtSTTO, and anything unrecognized
    }
    map.set(id, { kind, ptType });
  }
  return map;
};

const findById = (doc, tagName, id) => {
  for (const el of doc.getElementsByTagName(tagName)) {
    if (getDirectChild(el, "id")?.textContent === id) return el;
  }
  return null;
};

/**
 * Reconstructs the inline plan (per-point altitudes/clock, per-leg airspeed/
 * wind as "to" values on the arriving point) and ground elevations from an
 * imported mission's points.xml / legs.xml / segments.xml. This is what lets
 * an imported route drive the same inline plan editor a sketched route does.
 */
const buildRoutePlanFromDocs = (docs, route, pointById, legPlan) => {
  const plan = defaultRoutePlan();
  plan.perPoint = {};
  const elevations = {};
  const ampsPoints = route.points.filter((p) => p.kind !== "shaping" && p.id);

  let firstAlt = null;
  const clockByPoint = new Map();
  for (const p of ampsPoints) {
    const el = pointById.get(p.id);
    if (!el) continue;

    const groundM = parseAmpsMeters(getItemValue(el, "Elevation"));
    if (groundM != null) elevations[p.id] = Math.round(groundM * FT_PER_M);

    // CmdAlt is the planned altitude, in meters MSL.
    const mslM = parseAmpsMeters(getItemValue(el, "CmdAlt"));
    if (mslM != null) {
      const mslFt = Math.round(mslM * FT_PER_M);
      plan.perPoint[p.id] = {
        ...plan.perPoint[p.id],
        altitude: { value: mslFt, ref: "msl" },
      };
      if (!firstAlt) firstAlt = { value: mslFt, ref: "msl" };
    }

    const clock = parseAmpsClock(getItemValue(el, "CmdClockTime"));
    if (clock) clockByPoint.set(p.id, clock);
  }
  if (firstAlt) plan.altitude = firstAlt;

  // TOT/clock detection: with no time-on-target AMPS leaves every point sharing
  // one midnight placeholder (ManualTiming). A real timing plan — including one
  // this app exported — gives the points distinct clock times, so treat that as
  // an anchored plan and pin it to the first point (the rolling times AMPS/this
  // app recompute reproduce the rest identically regardless of which point holds
  // the anchor).
  const distinctClocks = new Set(
    [...clockByPoint.values()].map((c) => `${c.date}T${c.time}`),
  );
  if (distinctClocks.size >= 2) {
    const anchorId = ampsPoints.find((p) => clockByPoint.has(p.id))?.id;
    if (anchorId) {
      const anchor = clockByPoint.get(anchorId);
      plan.perPoint[anchorId] = { ...plan.perPoint[anchorId], clock: anchor.time };
      plan.date = anchor.date;
    }
  }

  // --- per-leg airspeed / wind, assigned to the leg's arrival point ---
  const segmentEl = findById(docs.segments, "segment", route.segmentId);
  const legsListEl = segmentEl && getDirectChild(segmentEl, "legs");
  const legIds = legsListEl
    ? Array.from(legsListEl.children).filter((c) => c.tagName === "id").map((c) => c.textContent)
    : [];

  let firstSpd = null;
  let firstWind = null;
  for (const legId of legIds) {
    const leg = legPlan.get(legId);
    const endId = leg?.endpt;
    if (!endId) continue;

    const airspeed = parseAmpsAirspeed(leg.airspeed);
    if (airspeed) {
      plan.perPoint[endId] = { ...plan.perPoint[endId], airspeed };
      if (!firstSpd) firstSpd = airspeed;
    }
    const wind = parseAmpsWind(leg.wind);
    if (wind) {
      plan.perPoint[endId] = { ...plan.perPoint[endId], wind };
      if (!firstWind) firstWind = wind;
    }
  }
  if (firstSpd) plan.airspeed = firstSpd;
  if (firstWind) plan.wind = firstWind;

  return { plan, elevations };
};

export async function parseMsnxFile(file) {
  let zip;
  try {
    zip = await JSZip.loadAsync(file);
  } catch (err) {
    throw new Error("Couldn't open this file as a .msnx mission archive.");
  }

  const entries = {};
  for (const [key, path] of Object.entries(MISSION_PATHS)) {
    const entry = zip.file(path);
    if (!entry) {
      throw new Error(
        "This doesn't look like a valid .msnx mission file (missing expected mission data).",
      );
    }
    entries[key] = entry;
  }

  const vehiclesEntry = zip.file(VEHICLES_PATH);
  const [gpxText, pointsText, legsText, segmentsText, vehiclesText] = await Promise.all([
    entries.gpx.async("string"),
    entries.points.async("string"),
    entries.legs.async("string"),
    entries.segments.async("string"),
    vehiclesEntry ? vehiclesEntry.async("string") : Promise.resolve(""),
  ]);

  const aircraft = readMissionAircraft(vehiclesText);

  // legs.xml is the bulk of a real mission (tens of MB of calc data this app
  // never reads). Pull the few plan values out of its text and leave the DOM
  // unbuilt until an edit or export actually needs it.
  const legPlan = extractLegPlanData(legsText);
  const docs = createMissionDocs({ gpxText, pointsText, segmentsText, legsText });

  const rteElements = Array.from(docs.gpx.getElementsByTagName("rte"));
  if (rteElements.length === 0) {
    throw new Error("No routes found in this mission file.");
  }

  const pointInfo = buildPointInfoMap(docs.points);

  // Index points.xml once for every route, rather than re-walking it per route.
  const pointById = new Map();
  for (const el of docs.points.getElementsByTagName("point")) {
    const idEl = getDirectChild(el, "id");
    if (idEl) pointById.set(idEl.textContent, el);
  }

  const routes = rteElements.map((rte, routeIndex) => {
    const name = getChildText(rte, "name") || "Unnamed Route";
    const segmentEl = rte.getElementsByTagName("msnx:segment")[0];
    const segmentId = segmentEl?.getAttribute("msnx:id") || null;

    const rteptElements = Array.from(rte.getElementsByTagName("rtept"));
    const points = rteptElements.map((rtept, index) => {
      const lat = parseFloat(rtept.getAttribute("lat"));
      const lon = parseFloat(rtept.getAttribute("lon"));
      const eleText = getChildText(rtept, "ele");
      const pointName = getChildText(rtept, "name");
      const pointEl = rtept.getElementsByTagName("msnx:point")[0];
      const id = pointEl?.getAttribute("msnx:id") || null;

      const info = (id && pointInfo.get(id)) || {};

      return {
        id,
        // `id` is the AMPS/XML identifier and is legitimately absent on some
        // legacy or geometry-only GPX points. Keep a separate, stable identity
        // for React so multiple anonymous points do not all render as `null`.
        uiId: id ?? `msnx-point-${segmentId ?? routeIndex}-${index}`,
        lat,
        lon,
        ele: eleText ? parseFloat(eleText) : null,
        name: pointName,
        // Authoritative designation from points.xml when available; the
        // name-based role remains as a fallback for rendering older data.
        //
        // AMPS emits calculated serpentine geometry two different ways: as real
        // points flagged CalcPtSerpentine/IsCalcPt (classified above), or — on
        // some routes — as bare "<route>.rtptN.trkN" rtepts carrying no
        // <extensions> at all. Those have no AMPS point behind them, so they
        // are shaping geometry too, not plannable route points.
        kind: info.kind ?? (id ? null : "shaping"),
        ptType: info.ptType ?? null,
        role: classifyPoint(pointName, index),
      };
    });

    const route = { name, segmentId, points };
    // Read the mission's planned performance data into the same plan shape the
    // sketched routes use, so imported routes get the inline editor too.
    const { plan, elevations } = buildRoutePlanFromDocs(docs, route, pointById, legPlan);
    route.plan = plan;
    route.elevations = elevations;
    return route;
  });

  return { zip, docs, routes, aircraft };
}
