import { normalizeLzDiagram, serializeLzDiagram } from "../lzWorkspace/useLzWorkspace";
import { sameData } from "./packDiff";

/*
 * An LZ/PZ diagram as a mission pack item. The item's data is the diagram document the
 * library saves (serializeLzDiagram), less what is each person's own:
 *
 *  - their view of it (base map, LZ outline, slope map), so one person switching to the
 *    sectional does not switch everyone;
 *  - which of their own records it was saved as, whether they have unsaved changes, times;
 *  - the slope raster, which is redrawn from the boundary and is never saved anywhere;
 *  - its name, which is the item's own (`item.rename`).
 *
 * A diagram in the workspace that is a pack item has an id naming the pack and the item.
 */

const OWN_FIELDS = ["id", "savedId", "dirty", "createdAt", "updatedAt", "view", "name"];

export const packDiagramId = (packUuid, itemUuid) => `pack:${packUuid}:${itemUuid}`;

/** { pack, item } for a diagram that is a pack item, else null. */
export const packDiagramRef = (diagramId) => {
  const match = /^pack:([^:]+):(.+)$/.exec(String(diagramId ?? ""));
  return match ? { pack: match[1], item: match[2] } : null;
};

/** The part of a diagram document a pack shares, as plain JSON. */
export const sharedLzData = (data) => {
  const shared = JSON.parse(JSON.stringify(data ?? {}));
  OWN_FIELDS.forEach((key) => delete shared[key]);
  if (shared.analysis && typeof shared.analysis === "object") delete shared.analysis.terrainData;
  return shared;
};

/** A workspace diagram's data as its pack item has it. */
export const lzItemData = (diagram) => sharedLzData(serializeLzDiagram(diagram));

/** A workspace diagram for a pack item (`item` as useMissionPack lists it: { uuid, name, data }). */
export const lzDiagramFromItem = (packUuid, item) =>
  normalizeLzDiagram(
    { ...(item.data ?? {}), id: packDiagramId(packUuid, item.uuid), name: item.name, savedId: null },
    { dirty: false },
  );

// -- What a change was, in words, for the pack's history -----------------------------------

const COLLECTIONS = {
  helicopters: (element, index) => `Chalk ${index + 1}`,
  pzMarkers: () => "a PZ marker",
  sectorsOfFire: (element, index) => `sector ${element?.name || index + 1}`,
  goArounds: () => "a go-around",
  units: (element) => (element?.uniqueDesignation ? `unit ${element.uniqueDesignation}` : "a unit"),
  doghouses: (element) => (element?.label ? `doghouse ${element.label}` : "a doghouse"),
  measurements: () => "a measurement",
};

const POSITION_KEYS = ["lat", "lon", "lng", "position", "points", "center", "start", "end", "tip", "anchor"];
const TURN_KEYS = ["heading", "rotation", "direction", "bearing"];

const byId = (list) => new Map((Array.isArray(list) ? list : []).filter((e) => e && e.id !== undefined).map((e, i) => [e.id, { element: e, index: i }]));

const changedKeys = (before, after) => {
  const keys = new Set([...Object.keys(before ?? {}), ...Object.keys(after ?? {})]);
  return [...keys].filter((key) => !sameData(before?.[key], after?.[key]));
};

const graphicPhrases = (before, after, name) => {
  const phrases = [];
  Object.entries(COLLECTIONS).forEach(([collection, noun]) => {
    const was = byId(before?.graphics?.[collection]);
    const now = byId(after?.graphics?.[collection]);
    now.forEach(({ element, index }, id) => {
      if (!was.has(id)) {
        phrases.push(`added ${noun(element, index)} to ${name}`);
        return;
      }
      const keys = changedKeys(was.get(id).element, element);
      if (keys.length === 0) return;
      const what = noun(element, index);
      if (keys.some((key) => POSITION_KEYS.includes(key))) phrases.push(`moved ${what} on ${name}`);
      else if (keys.some((key) => TURN_KEYS.includes(key))) {
        const heading = TURN_KEYS.map((key) => element[key]).find((value) => Number.isFinite(Number(value)) && value !== null && value !== "");
        phrases.push(heading !== undefined ? `turned ${what} to ${Math.round(Number(heading))}° on ${name}` : `turned ${what} on ${name}`);
      } else phrases.push(`changed ${what} on ${name}`);
    });
    was.forEach(({ element, index }, id) => {
      if (!now.has(id)) phrases.push(`removed ${noun(element, index)} from ${name}`);
    });
  });
  return phrases;
};

const flightPhrases = (before, after, name) => {
  const keys = changedKeys(before?.flightData, after?.flightData);
  if (keys.length === 0) return [];
  const value = (key) => after?.flightData?.[key];
  if (keys.includes("landing_hdg") && value("landing_hdg")) return [`changed the landing heading to ${value("landing_hdg")} on ${name}`];
  if (keys.includes("takeoff_hdg") && value("takeoff_hdg")) return [`changed the takeoff heading to ${value("takeoff_hdg")} on ${name}`];
  return [`changed the flight data on ${name}`];
};

/**
 * One sentence for the pack's history about a change to an LZ item: what the person did, in the
 * words they would use ("Sam B. moved Chalk 2 on LZ IBIS."). `before` and `after` are the item's
 * shared data. The most telling change is named; anything else it came with is left out.
 */
export const describeLzChange = (before, after, { name, actor }) => {
  const lz = name || "the LZ/PZ";
  const phrases = [];
  if (before?.status !== "analyzed" && after?.status === "analyzed") phrases.push(`analyzed ${lz}`);
  if (!sameData(before?.target, after?.target)) {
    phrases.push(after?.target ? `moved the target of ${lz}` : `cleared the target of ${lz}`);
  }
  if (!sameData(before?.analysis?.customLZ, after?.analysis?.customLZ)) phrases.push(`drew the boundary of ${lz}`);
  else if (!sameData(before?.analysis?.detectedLZ, after?.analysis?.detectedLZ)) phrases.push(`changed the boundary of ${lz}`);
  phrases.push(...graphicPhrases(before, after, lz));
  phrases.push(...flightPhrases(before, after, lz));
  if (!sameData(before?.graphics?.exportBox, after?.graphics?.exportBox)) phrases.push(`set the LZ card area on ${lz}`);
  const what = phrases[0] ?? `edited ${lz}`;
  return `${actor || "Someone"} ${what}.`.slice(0, 300);
};
