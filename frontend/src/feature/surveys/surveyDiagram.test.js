import { FIELD_KEYS, FIELD_SECTIONS, fullField } from "./surveyFields";
import {
  approachArrow,
  boundaryOf,
  diagramToSave,
  headingOf,
  offsetPoint,
  withApproach,
  withBoundary,
  withTarget,
} from "./surveyDiagram";
import { flightDoghouses } from "../doghouses/doghouseFields";
import { serializeLzDiagram } from "../lzWorkspace/useLzWorkspace";

const { readFixture } = require("../../contracts/readFixture");

const DONOVAN = readFixture("surveys/donovan.json");
const TARGET = { lat: 34.59601685, lon: -84.12779028 };
const SQUARE = [[34.5965, -84.1283], [34.5965, -84.1273], [34.5955, -84.1273], [34.5955, -84.1283]];

describe("the survey's fields", () => {
  it("are the server's, as DONOVAN's page has them", () => {
    expect([...FIELD_KEYS].sort()).toEqual(Object.keys(DONOVAN.field).sort());
    expect(new Set(FIELD_KEYS).size).toBe(FIELD_KEYS.length);
    expect(FIELD_SECTIONS.map((s) => s.title)).toEqual([
      "Location", "Approach and capacity", "Ground", "Hazards and notes", "Survey profile", "Surveyed",
    ]);
  });

  it("are all there, blank where the survey has none", () => {
    const field = fullField({ grid: "16S GD 6341 3199", colour: "red", maxAcDay: 2 });
    expect(Object.keys(field)).toEqual(FIELD_KEYS);
    expect(field.grid).toBe("16S GD 6341 3199");
    expect(field.maxAcDay).toBe("");
    expect(field.colour).toBeUndefined();
    expect(fullField({ approachDeg: "360", exitDeg: "180" }).exitDeg).toBeUndefined();   // a best exit saved before: kept no more
  });
});

describe("the survey's diagram", () => {
  it("is, as the import makes it on the server, the web's own document", () => {
    // backend/survey/importing.py writes it; tests/test_survey_book_import.py holds the server to the same fixture.
    const { targeted, analyzed } = readFixture("surveys/imported_diagram.json");
    expect(serializeLzDiagram(targeted)).toEqual(targeted);
    expect(serializeLzDiagram(analyzed)).toEqual(analyzed);
    expect(boundaryOf(analyzed)).toEqual(analyzed.analysis.detectedLZ);
    expect(withApproach(targeted, { approachDeg: "360" }).graphics.doghouses).toHaveLength(2);
  });

  it("reads headings as the book writes them", () => {
    expect(headingOf("360")).toBe(0);
    expect(headingOf("045°")).toBe(45);
    expect(headingOf(" 89.6")).toBe(90);
    expect(headingOf("north")).toBeNull();
    expect(headingOf("400")).toBeNull();
    expect(headingOf(undefined)).toBeNull();
  });

  it("makes a diagram at the target, with its grid, and keeps the boundary when the target moves", () => {
    const made = withTarget(null, TARGET.lat, TARGET.lon, { id: "survey-1", name: "LZ DONOVAN" });
    expect(made.id).toBe("survey-1");
    // The book's 10 m square's centre, at 1 m (the server's PyGeodesy writes the same: survey/measure.py).
    expect(made.target.mgrs).toBe("16S GD 63415 31994");
    const bounded = withBoundary(made, SQUARE);
    expect(boundaryOf(bounded)).toEqual(SQUARE);
    expect(bounded.status).toBe("analyzed");
    const moved = withTarget(bounded, TARGET.lat + 0.0001, TARGET.lon);
    expect(boundaryOf(moved)).toEqual(SQUARE);
    expect(moved.id).toBe("survey-1");
    expect(boundaryOf(withBoundary(moved, null))).toBeNull();
  });

  it("measures the boundary the analysis has, else the one drawn", () => {
    expect(boundaryOf({ analysis: { detectedLZ: null, customLZ: SQUARE } })).toEqual(SQUARE);
    expect(boundaryOf({ analysis: { detectedLZ: [[1, 2], [3, 4]] } })).toBeNull();
    expect(boundaryOf(null)).toBeNull();
  });

  it("turns the landing doghouse to the field's best approach, the flight data with it, and leaves the takeoff one", () => {
    const diagram = withBoundary(withTarget(null, TARGET.lat, TARGET.lon, { id: "survey-1" }), SQUARE);
    const made = withApproach(diagram, {});                                    // the standard two, as made
    const turned = withApproach(diagram, { approachDeg: "045", exitDeg: "225" }); // a best exit saved before is not read
    const { landing, takeoff } = flightDoghouses(turned.graphics.doghouses);
    expect(landing.heading).toBe("045°");
    expect(takeoff).toEqual(flightDoghouses(made.graphics.doghouses).takeoff);
    expect(turned.flightData.landing_hdg).toBe("045°");
    // Nothing to change: the same object.
    expect(withApproach(turned, { approachDeg: "045" })).toBe(turned);
    // A heading not set leaves the doghouse as it is.
    expect(withApproach(turned, { approachDeg: "" })).toBe(turned);
    expect(withApproach(null, {})).toBeNull();
  });

  it("is saved without a slope raster", () => {
    const diagram = { ...withTarget(null, TARGET.lat, TARGET.lon), analysis: { detectedLZ: SQUARE, terrainData: { overlay: "data:" } } };
    const saved = diagramToSave(diagram, DONOVAN.field);
    expect(saved.analysis.terrainData).toBeNull();
    expect(saved.flightData.landing_hdg).toBe("000°");
    expect(diagramToSave(null, {})).toBeNull();
  });

  it("draws the approach flown into the target", () => {
    const north = offsetPoint(TARGET.lat, TARGET.lon, 0, 1000);
    expect(north[0] - TARGET.lat).toBeCloseTo(1000 / 111320, 9);
    expect(north[1]).toBeCloseTo(TARGET.lon, 9);
    const arrow = approachArrow(TARGET, { approachDeg: "360", exitDeg: "090" });
    // Flown north into the target: it starts south of it, its head at the target.
    expect(arrow.line[0][0]).toBeLessThan(TARGET.lat);
    expect(arrow.line[0][1]).toBeCloseTo(TARGET.lon, 9);
    expect(arrow.line[1]).toEqual([TARGET.lat, TARGET.lon]);
    expect(arrow.head[1]).toEqual([TARGET.lat, TARGET.lon]);
    expect(arrow.head[0][0]).toBeLessThan(TARGET.lat);
    expect(approachArrow(TARGET, { approachDeg: "" })).toBeNull();
    expect(approachArrow(null, DONOVAN.field)).toBeNull();
  });
});
