import JSZip from "jszip";

const RELEASE_POINT_PATTERN = /^RP\d*$/i;
const TARGET_PATTERN = /^(LZ|PZ)/i;

export const MISSION_PATHS = {
  gpx: "mission.gpx",
  points: "mission/points.xml",
  legs: "mission/legs.xml",
  segments: "mission/segments.xml",
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

const parseXml = (text, label) => {
  // Strip the BOM and any leading XML declaration(s). The declaration is
  // optional, and files exported by earlier builds of this app carried a
  // doubled declaration (invalid XML) — stripping keeps those importable.
  const sanitized = text.replace(/^﻿?(?:\s*<\?xml[^>]*\?>)+\s*/, "");
  const doc = new DOMParser().parseFromString(sanitized, "application/xml");
  if (doc.querySelector("parsererror")) {
    throw new Error(`Failed to parse ${label} in this mission file.`);
  }
  return doc;
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

  const [gpxText, pointsText, legsText, segmentsText] = await Promise.all([
    entries.gpx.async("string"),
    entries.points.async("string"),
    entries.legs.async("string"),
    entries.segments.async("string"),
  ]);

  const docs = {
    gpx: parseXml(gpxText, "mission.gpx"),
    points: parseXml(pointsText, "points.xml"),
    legs: parseXml(legsText, "legs.xml"),
    segments: parseXml(segmentsText, "segments.xml"),
  };

  const rteElements = Array.from(docs.gpx.getElementsByTagName("rte"));
  if (rteElements.length === 0) {
    throw new Error("No routes found in this mission file.");
  }

  const pointInfo = buildPointInfoMap(docs.points);

  const routes = rteElements.map((rte) => {
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
        lat,
        lon,
        ele: eleText ? parseFloat(eleText) : null,
        name: pointName,
        // Authoritative designation from points.xml when available; the
        // name-based role remains as a fallback for rendering older data.
        kind: info.kind ?? null,
        ptType: info.ptType ?? null,
        role: classifyPoint(pointName, index),
      };
    });

    return { name, segmentId, points };
  });

  return { zip, docs, routes };
}
