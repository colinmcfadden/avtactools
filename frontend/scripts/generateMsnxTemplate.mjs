/**
 * Generates frontend/public/msnx_template.msnx — the minimal donor package used
 * by the in-app route-sketch exporter (src/feature/msnxImport/createMsnx.js).
 *
 * Strategy: rather than hand-writing the Mission X (AMPS) OPC package from
 * scratch, take the known-good sample mission from the repo root and strip it
 * down to ONE route with ONE leg. The kept leg is deliberately the sample's
 * SERPENTINE leg (.HTWR -> .GOLD with 25 CalcPtSerpentine shaping points), so
 * the runtime exporter has Mission-X-authored prototypes for everything it
 * clones: full AMPS route points, minimal serpentine calc points, a leg
 * carrying both CmdStdLeg and CmdSerpentine with a populated trackpointset,
 * and the gpx root manifest entries for all of it. Everything not touched here
 * (vehicles.xml with the UH-60L profile, datadictionary.xml, all .rels /
 * .FileInfo / [Content_Types].xml, the Vehicle Installations folder) stays
 * byte-for-byte as Mission X wrote it.
 *
 * Run from frontend/:  node scripts/generateMsnxTemplate.mjs
 */
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";
import JSZip from "jszip";
import jsdomPkg from "jsdom";

const { JSDOM } = jsdomPkg;
const dom = new JSDOM();
const DOMParser = dom.window.DOMParser;
const XMLSerializer = dom.window.XMLSerializer;

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SAMPLE_PATH = path.resolve(__dirname, "../../DNVN_ILLUM_OWEST_MGE_MULTI.msnx");
const OUTPUT_PATH = path.resolve(__dirname, "../public/msnx_template.msnx");

// GUIDs from the sample mission (see mission/*.xml in the sample):
const KEEP = {
  routeId: "2cbef5a8-efad-48b2-a178-bef74a78b419", // NEPTUNE
  segmentId: "f7047db5-527c-4c90-93dc-8fe3c373ec5a",
  vehicleId: "a96590d9-c835-4d95-bbb8-2c8bb40bd260",
  legId: "5cafd62e-c4dc-439f-8622-75e48697c259", // .HTWR -> .GOLD (serpentine leg)
  startPtId: "637a783e-95f1-4ba8-90d2-d826a8861b8a", // .HTWR (RtePtTurn)
  endPtId: "43d8296f-8d0e-406e-9c0c-852ee0eaad8f", // .GOLD (RtePtTurn)
};
const REMOVE_ROUTE_IDS = new Set([
  KEEP.routeId,
  "778ec1ae-d7c2-40c6-8ca1-0184d34569d0", // EARTH
]);

const BOM = "﻿";

const findDirectChild = (el, tagName) => {
  for (const child of el.children) {
    if (child.tagName === tagName) return child;
  }
  return null;
};

const directChildren = (el, tagName) =>
  Array.from(el.children).filter((c) => c.tagName === tagName);

const idOf = (el) => findDirectChild(el, "id")?.textContent;

/** Remove <item> children of an attributeset whose <key> matches predicate. */
const removeItems = (attributesetEl, predicate) => {
  if (!attributesetEl) return;
  for (const item of directChildren(attributesetEl, "item")) {
    const key = findDirectChild(item, "key")?.textContent;
    if (predicate(key)) attributesetEl.removeChild(item);
  }
};

const setItemValue = (containerEl, key, newValue) => {
  for (const item of containerEl.getElementsByTagName("item")) {
    const keyEl = findDirectChild(item, "key");
    if (keyEl && keyEl.textContent === key) {
      const attributeEl = findDirectChild(item, "attribute");
      const valueEl = attributeEl && findDirectChild(attributeEl, "value");
      if (valueEl) valueEl.textContent = newValue;
      return true;
    }
  }
  return false;
};

const parse = (text, label) => {
  const doc = new DOMParser().parseFromString(text, "application/xml");
  if (doc.querySelector("parsererror")) throw new Error(`Parse failure: ${label}`);
  return doc;
};

const main = async () => {
  const zip = await JSZip.loadAsync(fs.readFileSync(SAMPLE_PATH));
  const read = (p) => zip.file(p).async("string");

  const docs = {
    gpx: parse(await read("mission.gpx"), "mission.gpx"),
    points: parse(await read("mission/points.xml"), "points.xml"),
    legs: parse(await read("mission/legs.xml"), "legs.xml"),
    segments: parse(await read("mission/segments.xml"), "segments.xml"),
    routes: parse(await read("mission/routes.xml"), "routes.xml"),
    mission: parse(await read("mission/mission.xml"), "mission.xml"),
    missionsummary: parse(await read("mission/missionsummary.xml"), "missionsummary.xml"),
  };

  // --- mission.gpx first: keep NEPTUNE's rte, trimmed to the HTWR..GOLD
  // span (start point, 25 serpentine calc points, end point). Collect the
  // kept point ids while walking — they drive the points.xml trim below. ---
  const gpxRoot = docs.gpx.getElementsByTagName("gpx")[0];
  const keptPointIds = new Set();
  for (const rte of Array.from(gpxRoot.getElementsByTagName("rte"))) {
    const name = findDirectChild(rte, "name")?.textContent;
    if (name !== "NEPTUNE") {
      rte.parentNode.removeChild(rte);
      continue;
    }
    let inSpan = false;
    for (const rtept of Array.from(rte.getElementsByTagName("rtept"))) {
      const msnxPoint = rtept.getElementsByTagName("msnx:point")[0];
      const pid = msnxPoint?.getAttribute("msnx:id");
      if (pid === KEEP.startPtId) inSpan = true;
      if (inSpan) keptPointIds.add(pid);
      const keep = inSpan;
      if (pid === KEEP.endPtId) inSpan = false;
      if (!keep) rtept.parentNode.removeChild(rtept);
    }
  }
  if (!keptPointIds.has(KEEP.startPtId) || !keptPointIds.has(KEEP.endPtId)) {
    throw new Error("Failed to locate the HTWR..GOLD span in the sample gpx.");
  }
  console.log(`Kept ${keptPointIds.size} gpx points (expected 27).`);

  // --- gpx root manifest (direct <extensions> child of <gpx>): trim the
  // vehicles/segments/legs lists to the kept content. Earlier template builds
  // shipped the original mission's full 19-leg manifest here — stale refs. ---
  const rootExtensions = directChildren(gpxRoot, "extensions")[0];
  const msnxMission = rootExtensions.getElementsByTagName("msnx:mission")[0];

  const vehiclesEl = msnxMission.getElementsByTagName("msnx:vehicles")[0];
  for (const vehicle of Array.from(vehiclesEl.children)) {
    if (vehicle.getAttribute("msnx:id") !== KEEP.vehicleId) {
      vehiclesEl.removeChild(vehicle);
    }
  }

  const manifestSegmentsEl = directChildren(msnxMission, "msnx:segments")[0];
  for (const segment of Array.from(manifestSegmentsEl.children)) {
    if (segment.getAttribute("msnx:id") !== KEEP.segmentId) {
      manifestSegmentsEl.removeChild(segment);
      continue;
    }
    const legList = segment.getElementsByTagName("msnx:legs")[0];
    for (const leg of Array.from(legList.children)) {
      if (leg.getAttribute("msnx:id") !== KEEP.legId) legList.removeChild(leg);
    }
  }

  const manifestLegsEl = directChildren(msnxMission, "msnx:legs")[0];
  for (const leg of Array.from(manifestLegsEl.children)) {
    if (leg.getAttribute("msnx:id") !== KEEP.legId) manifestLegsEl.removeChild(leg);
  }

  // The manifest's mission name carries the original file's full path —
  // scrub it (the runtime exporter overwrites it per export anyway).
  msnxMission.setAttribute("msnx:name", "EZPZ_SKETCH_TEMPLATE");

  // --- routes.xml: keep NEPTUNE only; drop stale calc reports ---
  const routesRoot = docs.routes.getElementsByTagName("routes")[0];
  for (const route of directChildren(routesRoot, "route")) {
    if (idOf(route) !== KEEP.routeId) {
      routesRoot.removeChild(route);
      continue;
    }
    const attrset = findDirectChild(route, "attributeset");
    removeItems(attrset, (key) => key === "CalcRouteReport");
    setItemValue(route, "RouteCalcState", "NotCalculated");
  }

  // --- segments.xml: keep NEPTUNE's segment; trim its leg list to one ---
  const segmentsRoot = docs.segments.getElementsByTagName("segments")[0];
  for (const segment of directChildren(segmentsRoot, "segment")) {
    if (idOf(segment) !== KEEP.segmentId) {
      segmentsRoot.removeChild(segment);
      continue;
    }
    const legsList = findDirectChild(segment, "legs");
    for (const legIdEl of directChildren(legsList, "id")) {
      if (legIdEl.textContent !== KEEP.legId) legsList.removeChild(legIdEl);
    }
    setItemValue(segment, "SegmentCalcState", "NotCalculated");
  }

  // --- legs.xml: keep the serpentine leg; drop its per-route calc state ---
  const legsRoot = docs.legs.getElementsByTagName("legs")[0];
  for (const leg of directChildren(legsRoot, "leg")) {
    if (idOf(leg) !== KEEP.legId) {
      legsRoot.removeChild(leg);
      continue;
    }
    const attrset = findDirectChild(leg, "attributeset");
    removeItems(attrset, (key) => REMOVE_ROUTE_IDS.has(key));
  }

  // --- points.xml: keep the span's points; prune refs to removed legs ---
  const pointsRoot = docs.points.getElementsByTagName("points")[0];
  for (const point of directChildren(pointsRoot, "point")) {
    if (!keptPointIds.has(idOf(point))) {
      pointsRoot.removeChild(point);
      continue;
    }
    for (const listName of ["incominglegs", "outgoinglegs"]) {
      const list = findDirectChild(point, listName);
      if (!list) continue;
      for (const legIdEl of directChildren(list, "id")) {
        if (legIdEl.textContent !== KEEP.legId) list.removeChild(legIdEl);
      }
    }
    const attrset = findDirectChild(point, "attributeset");
    removeItems(attrset, (key) => REMOVE_ROUTE_IDS.has(key));
  }

  // --- missionsummary.xml: keep NEPTUNE's summary entry only ---
  const summaryRoutes = docs.missionsummary.getElementsByTagName("routes")[0];
  for (const route of directChildren(summaryRoutes, "route")) {
    if (idOf(route) !== KEEP.routeId) summaryRoutes.removeChild(route);
  }

  // --- mission.xml: neutral name, reset calc status, focus on kept content ---
  const missionEl = docs.mission.getElementsByTagName("mission")[0];
  setItemValue(missionEl, "MissionName", "EZPZ_SKETCH_TEMPLATE");
  setItemValue(missionEl, "MissionCalcState", "NotCalculated");
  setItemValue(missionEl, "FocusRtePt", KEEP.startPtId);
  setItemValue(
    missionEl,
    "CalcStatus",
    '<?xml version="1.0" encoding="utf-8"?><MissionStatus><RouteStatuses /></MissionStatus>',
  );

  // --- write back (mission/*.xml carry a UTF-8 BOM; mission.gpx does not) ---
  const serializer = new XMLSerializer();
  const noFolders = { createFolders: false };
  const decl = '<?xml version="1.0" encoding="utf-8"?>';
  const withDecl = (doc, d) => {
    const s = serializer.serializeToString(doc);
    return s.startsWith("<?xml") ? s : d + s;
  };
  zip.file("mission.gpx", withDecl(docs.gpx, '<?xml version="1.0"?>'));
  zip.file("mission/points.xml", BOM + withDecl(docs.points, decl), noFolders);
  zip.file("mission/legs.xml", BOM + withDecl(docs.legs, decl), noFolders);
  zip.file("mission/segments.xml", BOM + withDecl(docs.segments, decl), noFolders);
  zip.file("mission/routes.xml", BOM + withDecl(docs.routes, decl), noFolders);
  zip.file("mission/mission.xml", BOM + withDecl(docs.mission, decl), noFolders);
  zip.file("mission/missionsummary.xml", BOM + withDecl(docs.missionsummary, decl), noFolders);

  // The OPC core-properties part stores the original mission's full file
  // path as its dc:identifier — scrub it (personal path, and the template
  // ships publicly with the frontend).
  const psmdcpPath = Object.keys(zip.files).find((p) => p.endsWith(".psmdcp"));
  if (psmdcpPath) {
    const psmdcp = await zip.file(psmdcpPath).async("string");
    zip.file(
      psmdcpPath,
      psmdcp.replace(
        /<dc:identifier>[^<]*<\/dc:identifier>/,
        "<dc:identifier>EZPZ_SKETCH_TEMPLATE</dc:identifier>",
      ),
      noFolders,
    );
  }

  const out = await zip.generateAsync({
    type: "nodebuffer",
    compression: "DEFLATE",
    compressionOptions: { level: 6 },
  });
  fs.writeFileSync(OUTPUT_PATH, out);
  console.log(`Wrote ${OUTPUT_PATH} (${out.length} bytes)`);
};

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
