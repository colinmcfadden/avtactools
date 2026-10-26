import { FIELD_KEYS, FIELD_SECTIONS, fullField } from "./surveyFields";
import {
  approachArrows,
  boundaryOf,
  diagramToSave,
  headingOf,
  offsetPoint,
  withApproachAndExit,
  withBoundary,
  withTarget,
} from "./surveyDiagram";
import { flightDoghouses } from "../doghouses/doghouseFields";

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
  });
});

describe("the survey's diagram", () => {
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

  it("turns the approach and exit doghouses to the field's headings, and the flight data with them", () => {
    const diagram = withBoundary(withTarget(null, TARGET.lat, TARGET.lon, { id: "survey-1" }), SQUARE);
    const turned = withApproachAndExit(diagram, { approachDeg: "045", exitDeg: "225" });
    const { landing, takeoff } = flightDoghouses(turned.graphics.doghouses);
    expect(landing.heading).toBe("045°");
    expect(takeoff.heading).toBe("225°");
    expect(turned.flightData).toMatchObject({ landing_hdg: "045°", takeoff_hdg: "225°" });
    // Nothing to change: the same object.
    expect(withApproachAndExit(turned, { approachDeg: "045", exitDeg: "225" })).toBe(turned);
    // A heading not set leaves its doghouse as it is.
    const half = withApproachAndExit(turned, { approachDeg: "090", exitDeg: "" });
    expect(flightDoghouses(half.graphics.doghouses).takeoff.heading).toBe("225°");
    expect(withApproachAndExit(null, {})).toBeNull();
  });

  it("is saved without a slope raster", () => {
    const diagram = { ...withTarget(null, TARGET.lat, TARGET.lon), analysis: { detectedLZ: SQUARE, terrainData: { overlay: "data:" } } };
    const saved = diagramToSave(diagram, DONOVAN.field);
    expect(saved.analysis.terrainData).toBeNull();
    expect(saved.flightData.landing_hdg).toBe("000°");
    expect(diagramToSave(null, {})).toBeNull();
  });

  it("draws the approach flown into the target and the exit flown out", () => {
    const north = offsetPoint(TARGET.lat, TARGET.lon, 0, 1000);
    expect(north[0] - TARGET.lat).toBeCloseTo(1000 / 111320, 9);
    expect(north[1]).toBeCloseTo(TARGET.lon, 9);
    const { approach, exit } = approachArrows(TARGET, { approachDeg: "360", exitDeg: "090" });
    // Flown north into the target: it starts south of it.
    expect(approach.line[0][0]).toBeLessThan(TARGET.lat);
    expect(approach.line[1]).toEqual([TARGET.lat, TARGET.lon]);
    // Flown east out of it: it ends east of it, its head there.
    expect(exit.line[1][1]).toBeGreaterThan(TARGET.lon);
    expect(exit.head[1]).toEqual(exit.line[1]);
    expect(approachArrows(TARGET, { approachDeg: "" }).approach).toBeNull();
    expect(approachArrows(null, DONOVAN.field)).toEqual({ approach: null, exit: null });
  });
});
