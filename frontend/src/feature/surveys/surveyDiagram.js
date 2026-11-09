import {
  LZ_DIAGRAM_STATUS,
  createLzDiagramFromTarget,
  normalizeLzDiagram,
  serializeLzDiagram,
} from "../lzWorkspace/useLzWorkspace";
import { createDefaultDoghouses } from "../doghouses/useDoghouses";
import { doghouseHeadingText, flightDataFromDoghouses, flightDoghouses } from "../doghouses/doghouseFields";
import { toMgrs } from "../../utils/mgrs";

/*
 * A survey's LZ/PZ diagram (docs/LZ_SURVEYS.md §3.1): the web's schema-2 document, so the same target, boundary and doghouses open
 * in the LZ/PZ tools. In a survey the boundary is the one measured (`analysis.detectedLZ`, found by the model or drawn) and the two
 * standard doghouses are the best approach (the landing one) and the best exit (the takeoff one), turned to the field's headings.
 * Pure: the editor keeps its state in useSurveys.
 */

/** A heading as the survey types it ("360", "045°") in whole degrees 0..359, or null. */
export const headingOf = (text) => {
  const match = /^\s*([0-9]+(?:\.[0-9]+)?)/.exec(String(text ?? ""));
  if (!match) return null;
  const value = Number(match[1]);
  if (!Number.isFinite(value) || value > 360) return null;
  return Math.round(value) % 360;
};

/** The boundary the survey measures: the analysis's, else one drawn and not analysed. */
export const boundaryOf = (diagram) => {
  const ring = (value) => (Array.isArray(value) && value.length >= 3 ? value : null);
  return ring(diagram?.analysis?.detectedLZ) ?? ring(diagram?.analysis?.customLZ);
};

/** The diagram with its target at [lat, lon] (a new diagram if there was none): the grid is worked out here, the boundary kept. */
export const withTarget = (diagram, lat, lon, { id, name } = {}) => {
  const mgrs = toMgrs(lat, lon);
  if (!diagram) return createLzDiagramFromTarget({ target: { lat, lon }, mgrs, id, name });
  return normalizeLzDiagram({ ...diagram, target: { lat, lon, mgrs }, mapData: { ...diagram.mapData, mgrs } });
};

/** The diagram with [points] as its measured boundary, or with none (points null). */
export const withBoundary = (diagram, points) => {
  if (!diagram) return diagram;
  const analysis = { ...diagram.analysis, detectedLZ: points, customLZ: null };
  return normalizeLzDiagram({
    ...diagram,
    analysis,
    status: points ? LZ_DIAGRAM_STATUS.ANALYZED : LZ_DIAGRAM_STATUS.TARGETED,
  });
};

/**
 * The diagram with its approach and exit doghouses turned to the field's best approach and exit (made where there are none yet),
 * and the flight data's landing and takeoff headings with them. Unchanged (the same object) when nothing would change.
 */
export const withApproachAndExit = (diagram, field) => {
  if (!diagram?.target) return diagram;
  const approach = headingOf(field?.approachDeg);
  const exit = headingOf(field?.exitDeg);
  const existing = diagram.graphics?.doghouses ?? [];
  const { landing, takeoff } = flightDoghouses(existing);
  let doghouses = landing || takeoff ? existing : createDefaultDoghouses(diagram.target, diagram.id);
  const marks = flightDoghouses(doghouses);
  doghouses = doghouses.map((dh) => {
    if (dh === marks.landing && approach !== null) return { ...dh, heading: doghouseHeadingText(approach) };
    if (dh === marks.takeoff && exit !== null) return { ...dh, heading: doghouseHeadingText(exit) };
    return dh;
  });
  const same = doghouses.length === existing.length && doghouses.every((dh, i) => JSON.stringify(dh) === JSON.stringify(existing[i]));
  if (same) return diagram;
  const flightData = flightDataFromDoghouses(doghouses, diagram.flightData ?? {});
  return { ...diagram, flightData, graphics: { ...diagram.graphics, doghouses } };
};

/** What is sent to the server: the document as saved records hold it (no slope raster), with its approach and exit set. */
export const diagramToSave = (diagram, field) =>
  diagram ? serializeLzDiagram(withApproachAndExit(diagram, field)) : null;

const METRES_PER_DEGREE = 111320;

/** The point [distanceM] from [lat, lon] along [bearingDeg] (true), on a flat earth: good to a metre over the few hundred drawn. */
export const offsetPoint = (lat, lon, bearingDeg, distanceM) => {
  const rad = (bearingDeg * Math.PI) / 180;
  const north = Math.cos(rad) * distanceM;
  const east = Math.sin(rad) * distanceM;
  return [lat + north / METRES_PER_DEGREE, lon + east / (METRES_PER_DEGREE * Math.cos((lat * Math.PI) / 180))];
};

/**
 * The arrows that show the best approach and exit on the survey's map: the approach flown into the target along its heading (from
 * [lengthM] out), the exit flown out of it. Each a line and its head, as [[lat, lon], …]; null for a heading not set.
 */
export const approachArrows = (target, field, lengthM = 250) => {
  if (!target) return { approach: null, exit: null };
  const arrow = (from, to, heading) => {
    const back = (offset) => offsetPoint(to[0], to[1], heading + 180 + offset, lengthM * 0.12);
    return { line: [from, to], head: [back(-25), to, back(25)] };
  };
  const here = [target.lat, target.lon];
  const approach = headingOf(field?.approachDeg);
  const exit = headingOf(field?.exitDeg);
  return {
    approach: approach === null ? null : arrow(offsetPoint(here[0], here[1], approach + 180, lengthM), here, approach),
    exit: exit === null ? null : arrow(here, offsetPoint(here[0], here[1], exit, lengthM), exit),
  };
};
