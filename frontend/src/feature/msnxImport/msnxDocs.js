/**
 * Lazy document set for an imported mission.
 *
 * A real AMPS mission spends almost all of its bytes on data this app never
 * reads: legs.xml carries per-leg `segmenttransitions` calc blobs (one leg in a
 * 4-route mission measured 1.9 MB on its own). Parsing that into a live DOM cost
 * ~840 MB of heap and ~6 s on import, to reach three short values per leg
 * (endpt / AirspeedValue / CruiseWind — about 8.6 KB in total).
 *
 * So legs.xml is kept as text and only parsed when something actually needs the
 * DOM: inserting a point, or serializing on export. `docs.legs` is a getter, so
 * every existing call site reads unchanged — it just pays the parse on first
 * touch instead of on import.
 *
 * The one edit that would otherwise force that parse on a plain drag is the
 * serpentine trackpoint sync, so it is queued as a coordinate swap and replayed
 * when (and only if) the DOM is ever materialized.
 */

export const parseXml = (text, label) => {
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

const findDirectChild = (el, tagName) => {
  for (const child of el.children) {
    if (child.tagName === tagName) return child;
  }
  return null;
};

const findTrackCoordinateValueEl = (trackpointEl) => {
  for (const item of trackpointEl.getElementsByTagName("item")) {
    const keyEl = findDirectChild(item, "key");
    if (keyEl && keyEl.textContent === "TrackPtCoordinate") {
      const attributeEl = findDirectChild(item, "attribute");
      return attributeEl && findDirectChild(attributeEl, "value");
    }
  }
  return null;
};

/** Rewrites the first trackpoint whose coordinate matches `oldCoord`. */
const applyTrackpointSwap = (legsDoc, { oldCoord, newCoord }) => {
  for (const trackpointEl of legsDoc.getElementsByTagName("trackpoint")) {
    const valueEl = findTrackCoordinateValueEl(trackpointEl);
    if (valueEl && valueEl.textContent === oldCoord) {
      valueEl.textContent = newCoord;
      return;
    }
  }
};

/**
 * Per-leg plan values, read straight out of the legs.xml text.
 *
 * Mirrors what a DOM walk would find: the leg's `endpt`, and the first
 * AirspeedValue / CruiseWind item anywhere inside it (they live at
 * commandbase/commands/item/command/attributeset/item, and each key occurs
 * exactly once per leg). Verified to return results identical to the DOM path
 * on a real 4-route AMPS mission, without allocating the DOM.
 *
 * @returns {Map<string, {endpt: string|null, airspeed: string|null, wind: string|null}>}
 */
export const extractLegPlanData = (legsText) => {
  const byId = new Map();
  const legPattern = /<leg>([\s\S]*?)<\/leg>/g;
  let match;
  while ((match = legPattern.exec(legsText)) !== null) {
    const chunk = match[1];
    const id = /<id>([^<]*)<\/id>/.exec(chunk)?.[1];
    if (!id) continue;
    const value = (key) =>
      new RegExp(`<key>${key}</key>[\\s\\S]*?<value>([^<]*)</value>`).exec(chunk)?.[1] ?? null;
    byId.set(id, {
      endpt: /<endpt>([^<]*)<\/endpt>/.exec(chunk)?.[1] ?? null,
      airspeed: value("AirspeedValue"),
      wind: value("CruiseWind"),
    });
  }
  return byId;
};

/**
 * Builds the mission's document set. gpx/points/segments are parsed eagerly
 * (they are small and edited directly); legs is deferred behind a getter.
 */
export const createMissionDocs = ({ gpxText, pointsText, segmentsText, legsText }) => {
  let legsDoc = null;
  const pendingTrackpointSwaps = [];

  const docs = {
    gpx: parseXml(gpxText, "mission.gpx"),
    points: parseXml(pointsText, "points.xml"),
    segments: parseXml(segmentsText, "segments.xml"),

    get legs() {
      if (!legsDoc) {
        legsDoc = parseXml(legsText, "legs.xml");
        // Replay edits taken while the DOM was still deferred, so the first
        // reader sees exactly what an eagerly-parsed doc would have.
        for (const swap of pendingTrackpointSwaps.splice(0)) {
          applyTrackpointSwap(legsDoc, swap);
        }
      }
      return legsDoc;
    },

    /** True once the legs DOM exists — lets callers avoid forcing the parse. */
    isLegsMaterialized: () => legsDoc !== null,

    /**
     * Serpentine display points mirror a trackpoint in their leg's
     * trackpointset. Queue the swap rather than reading `docs.legs`, so a plain
     * point drag never triggers the multi-hundred-megabyte parse.
     */
    queueTrackpointSwap: (oldCoord, newCoord) => {
      if (legsDoc) applyTrackpointSwap(legsDoc, { oldCoord, newCoord });
      else pendingTrackpointSwaps.push({ oldCoord, newCoord });
    },
  };

  return docs;
};
