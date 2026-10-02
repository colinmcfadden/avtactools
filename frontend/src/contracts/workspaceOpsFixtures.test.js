import {
  LZ_WORKSPACE_ACTIONS,
  createInitialLzWorkspace,
  lzWorkspaceReducer,
} from "../feature/lzWorkspace/useLzWorkspace";
import { createDefaultDoghouses } from "../feature/doghouses/useDoghouses";

const fs = require("fs");
const { UPDATE, dumps, fixturePath, writeFixture } = require("./fixtureIO");

// What happens to ONE diagram when the user analyses it, edits its graphics, changes its view or renames it: the web's
// reducer is the reference, and the native apps' `DiagramOps` is held to every case here. The reducer also stamps
// `updatedAt` from the clock and sets `dirty`; both are the persistence layer's business natively, so they are not
// compared (the cases strip them). What is compared is everything else, including the rules about when an action is
// refused: no graphics before analysis, no analysis without a target.

const GENERATED_BY = "frontend/src/contracts/workspaceOpsFixtures.test.js (UPDATE_CONTRACTS=1)";

const clean = (value) => JSON.parse(JSON.stringify(value, (_k, v) => (v === undefined ? null : v)));

const settle = (name, document) => {
  const file = fixturePath(name);
  const text = dumps(document);
  if (UPDATE) {
    writeFixture(name, text);
    return;
  }
  if (!fs.existsSync(file)) {
    throw new Error(`${name} is missing; run with UPDATE_CONTRACTS=1 to create it`);
  }
  expect(JSON.parse(fs.readFileSync(file, "utf8"))).toEqual(JSON.parse(text));
  expect(fs.readFileSync(file, "utf8")).toBe(text);
};

const T0 = "2026-09-01T12:00:00.000Z";
const T1 = "2026-09-02T08:30:00.000Z";

const HELO = { id: "h1", lat: 34.5, lon: -84.1, heading: 270, profileId: "uh60l", futureField: { keep: ["me"] } };
const HELO2 = { id: "h2", lat: 34.51, lon: -84.11, heading: 90, profileId: "uh60l" };
const SECTOR = { id: "s1", points: [[34.5, -84.1], [34.51, -84.09], [34.5, -84.08]], color: "#ff0000" };

const DRAFT = { id: "draft", createdAt: T0, updatedAt: T0, name: "Draft", status: "draft" };

const TARGETED = {
  id: "targeted", createdAt: T0, updatedAt: T1, name: "LZ HAWK", status: "targeted",
  target: { lat: 34.783817, lon: -84.08219, mgrs: "16S GD 66993 52949" },
  mapData: { zoom: 17 }, flightData: { callSign: "HAWK 6" },
};

const ANALYZED = {
  id: "analyzed", createdAt: T0, updatedAt: T1, name: "LZ CROW", status: "analyzed",
  target: { lat: 34.783817, lon: -84.08219, mgrs: "16S GD 66993 52949" },
  mapData: { zoom: 17 },
  flightData: { callSign: "CROW 2", landingHeading: 270 },
  analysis: {
    customLZ: [[34.7, -84.1], [34.7, -84.0], [34.8, -84.0]],
    detectedLZ: [[34.71, -84.09], [34.71, -84.01], [34.79, -84.01]],
    terrainData: "RASTER-1",
    results: { elevation: "4050" },
    gridElevation: "4050",
    latLong: "34.78382, -84.08219",
  },
  graphics: {
    doghouses: [{ id: "analyzed-sp1", role: "takeoff", heading: "000°" }],
    helicopters: [HELO, HELO2],
    pzMarkers: [{ id: "p1", lat: 34.5, lon: -84.1 }],
    sectorsOfFire: [SECTOR],
    goArounds: [{ id: "g1", side: "L" }],
    units: [{ id: "u1", sidc: "SFGPUCI----D---" }],
    measurements: [{ id: "m1", a: [34.5, -84.1], b: [34.6, -84.2] }],
    exportBox: { north: 34.8, south: 34.7, east: -84.0, west: -84.1 },
  },
  view: { mapStyle: "topo", showLZOutline: false, showHeatmap: true },
};

// Ids of different kinds side by side: the number 1 is not the text "1", and a null id is an id.
const ODD_IDS = {
  ...ANALYZED,
  id: "odd",
  graphics: {
    ...ANALYZED.graphics,
    units: [{ id: null, tag: "null id" }, { id: 1, tag: "number one" }, { id: "1", tag: "text one" }, { tag: "no id" }],
  },
};

const ANALYSIS = {
  latLong: "34.78382, -84.08219",
  gridElevation: "4050",
  customLZ: null,
  detectedLZ: [[34.71, -84.09], [34.71, -84.01], [34.79, -84.01]],
  results: { status: "success", suggested_lz: [[34.71, -84.09]], elevation: "4050", message: "Field detected" },
};

/** Runs one reducer action on a one-diagram workspace and returns the diagram as the web leaves it, without the clock-stamped fields. */
const apply = (diagram, op, args) => {
  const types = {
    completeAnalysis: LZ_WORKSPACE_ACTIONS.COMPLETE_ANALYSIS,
    resetAnalysis: LZ_WORKSPACE_ACTIONS.RESET_ANALYSIS,
    setAnalysisDraft: LZ_WORKSPACE_ACTIONS.SET_ANALYSIS_DRAFT,
    setRuntimeTerrainData: LZ_WORKSPACE_ACTIONS.SET_RUNTIME_TERRAIN_DATA,
    setGraphics: LZ_WORKSPACE_ACTIONS.SET_GRAPHICS,
    setGraphicCollection: LZ_WORKSPACE_ACTIONS.SET_GRAPHIC_COLLECTION,
    upsertGraphic: LZ_WORKSPACE_ACTIONS.UPSERT_GRAPHIC,
    patchGraphic: LZ_WORKSPACE_ACTIONS.PATCH_GRAPHIC,
    removeGraphic: LZ_WORKSPACE_ACTIONS.REMOVE_GRAPHIC,
    setFlightData: LZ_WORKSPACE_ACTIONS.SET_FLIGHT_DATA,
    setView: LZ_WORKSPACE_ACTIONS.SET_VIEW,
    setName: LZ_WORKSPACE_ACTIONS.SET_DIAGRAM_NAME,
  };
  const state = createInitialLzWorkspace([diagram]);
  const id = state.activeDiagramId;
  const next = lzWorkspaceReducer(state, { type: types[op], diagramId: id, ...args });
  const { updatedAt, dirty, ...rest } = next.diagramsById[id]; // eslint-disable-line no-unused-vars
  return clean(rest);
};

/** The same, for the diagram as it was read, so a case shows what it started from. */
const start = (diagram) => {
  const { updatedAt, dirty, ...rest } = createInitialLzWorkspace([diagram]).diagramsById[diagram.id]; // eslint-disable-line no-unused-vars
  return clean(rest);
};

const OPS = [
  // -- Analysis ---------------------------------------------------------------------------------------------
  ["analysis completes a targeted diagram", TARGETED, "completeAnalysis", { analysis: ANALYSIS }],
  ["analysis completes an analyzed one again, replacing what it found", ANALYZED, "completeAnalysis", { analysis: { ...ANALYSIS, gridElevation: "4100" } }],
  ["analysis keeps the fields it was not given", ANALYZED, "completeAnalysis", { analysis: { gridElevation: "5000" } }],
  ["analysis of a draft with no target is refused", DRAFT, "completeAnalysis", { analysis: ANALYSIS }],
  ["analysis with nothing in it still marks the diagram analyzed", TARGETED, "completeAnalysis", { analysis: {} }],
  ["a non-object analysis adds nothing but still completes", TARGETED, "completeAnalysis", { analysis: "nonsense" }],
  ["a draft of an analysis (a hand-drawn boundary) leaves the status alone", TARGETED, "setAnalysisDraft", { analysis: { customLZ: [[34.7, -84.1], [34.7, -84.0], [34.8, -84.0]] } }],
  ["a draft on an analyzed diagram keeps it analyzed", ANALYZED, "setAnalysisDraft", { analysis: { customLZ: [[1, 2], [3, 4], [5, 6]] } }],
  ["a draft with no target is refused", DRAFT, "setAnalysisDraft", { analysis: { customLZ: [[1, 2]] } }],
  ["resetting analysis returns to targeted, empties the analysis and keeps the graphics", ANALYZED, "resetAnalysis", {}],
  ["resetting a draft with no target does nothing", DRAFT, "resetAnalysis", {}],
  ["resetting a targeted diagram changes nothing that matters", TARGETED, "resetAnalysis", {}],
  ["the terrain raster is set as runtime data", ANALYZED, "setRuntimeTerrainData", { terrainData: "RASTER-2" }],
  ["the same raster again changes nothing", ANALYZED, "setRuntimeTerrainData", { terrainData: "RASTER-1" }],
  ["a raster can be cleared", ANALYZED, "setRuntimeTerrainData", { terrainData: null }],
  ["no raster is set on a diagram with no target", DRAFT, "setRuntimeTerrainData", { terrainData: "RASTER-2" }],

  // -- Graphics: refused until the diagram is analyzed -------------------------------------------------------------
  ["graphics cannot be set before analysis", TARGETED, "setGraphics", { graphics: { helicopters: [HELO] } }],
  ["graphics cannot be set on a draft", DRAFT, "setGraphics", { graphics: { helicopters: [HELO] } }],
  ["setting graphics replaces only the collections given", ANALYZED, "setGraphics", { graphics: { helicopters: [HELO2], units: [] } }],
  ["setting graphics can replace the export box", ANALYZED, "setGraphics", { graphics: { exportBox: { north: 1, south: 0, east: 1, west: 0 } } }],
  ["setting graphics can clear the export box", ANALYZED, "setGraphics", { graphics: { exportBox: null } }],
  ["a legacy collection name in a patch is not applied", ANALYZED, "setGraphics", { graphics: { pzMarker: [{ id: "old" }], goAround: [{ id: "old" }] } }],
  ["a patch with a collection that is not an array empties it", ANALYZED, "setGraphics", { graphics: { helicopters: "nope" } }],
  ["a collection is set whole", ANALYZED, "setGraphicCollection", { collection: "helicopters", items: [HELO2] }],
  ["non-object items in a collection are kept", ANALYZED, "setGraphicCollection", { collection: "units", items: [{ id: "a" }, 7, "x", null] }],
  ["a collection can be emptied", ANALYZED, "setGraphicCollection", { collection: "sectorsOfFire", items: [] }],
  ["a collection that does not exist is refused", ANALYZED, "setGraphicCollection", { collection: "nope", items: [HELO] }],
  ["a collection cannot be set before analysis", TARGETED, "setGraphicCollection", { collection: "helicopters", items: [HELO] }],
  ["a new graphic is added at the end", ANALYZED, "upsertGraphic", { collection: "helicopters", item: { id: "h3", lat: 1, lon: 2 } }],
  ["a graphic with a known id is merged, keeping fields it did not mention", ANALYZED, "upsertGraphic", { collection: "helicopters", item: { id: "h1", heading: 180 } }],
  ["a graphic with no id is always added", ANALYZED, "upsertGraphic", { collection: "helicopters", item: { lat: 1, lon: 2 } }],
  ["an item that is not an object is refused", ANALYZED, "upsertGraphic", { collection: "helicopters", item: "h9" }],
  ["a graphic in a collection that does not exist is refused", ANALYZED, "upsertGraphic", { collection: "nope", item: { id: "x" } }],
  ["a graphic cannot be added before analysis", TARGETED, "upsertGraphic", { collection: "helicopters", item: { id: "h3" } }],
  ["a graphic is patched by id", ANALYZED, "patchGraphic", { collection: "helicopters", id: "h2", patch: { heading: 45, label: "TWO" } }],
  ["patching an id that is not there changes nothing", ANALYZED, "patchGraphic", { collection: "helicopters", id: "zz", patch: { heading: 45 } }],
  ["a patch that is not an object changes nothing", ANALYZED, "patchGraphic", { collection: "helicopters", id: "h2", patch: "nope" }],
  ["patching with no id is refused", ANALYZED, "patchGraphic", { collection: "helicopters", patch: { heading: 45 } }],
  ["a graphic is removed by id", ANALYZED, "removeGraphic", { collection: "helicopters", id: "h1" }],
  ["removing an id that is not there changes nothing", ANALYZED, "removeGraphic", { collection: "helicopters", id: "zz" }],
  ["removing from a collection that does not exist is refused", ANALYZED, "removeGraphic", { collection: "nope", id: "h1" }],

  ["a patch by number does not touch the text with the same digits", ODD_IDS, "patchGraphic", { collection: "units", id: 1, patch: { hit: true } }],
  ["a patch by text does not touch the number with the same digits", ODD_IDS, "patchGraphic", { collection: "units", id: "1", patch: { hit: true } }],
  ["an item with a null id is found by a null id", ODD_IDS, "patchGraphic", { collection: "units", id: null, patch: { hit: true } }],
  ["patching with no id is refused even when an item has a null id", ODD_IDS, "patchGraphic", { collection: "units", patch: { hit: true } }],
  ["removing by number leaves the text with the same digits", ODD_IDS, "removeGraphic", { collection: "units", id: 1 }],
  ["a graphic with no id is added, and does not merge into an item whose id is null", ODD_IDS, "upsertGraphic", { collection: "units", item: { tag: "another" } }],
  ["a graphic with a null id merges into the item with a null id", ODD_IDS, "upsertGraphic", { collection: "units", item: { id: null, tag: "merged" } }],
  ["a null plural in a patch falls back to the legacy name", ANALYZED, "setGraphics", { graphics: { pzMarkers: null, pzMarker: [{ id: "from-old" }], goArounds: null, goAround: [{ id: "go-old" }] } }],
  ["a view change keeps the fields it does not mention", ANALYZED, "setView", { view: { mapStyle: "vfr-sectional" } }],
  ["an analysis that names a field as null clears it", ANALYZED, "setAnalysisDraft", { analysis: { customLZ: null } }],

  // -- Flight data, view, name: allowed at any stage ------------------------------------------------------------
  ["flight data is merged", ANALYZED, "setFlightData", { flightData: { callSign: "CROW 3", frequency: "251.0" } }],
  ["flight data on a draft is allowed", DRAFT, "setFlightData", { flightData: { callSign: "X" } }],
  ["flight data that changes nothing changes nothing", ANALYZED, "setFlightData", { flightData: { callSign: "CROW 2" } }],
  ["the view is merged", ANALYZED, "setView", { view: { mapStyle: "vfr-sectional", showHeatmap: true } }],
  ["a view change on a draft is allowed", DRAFT, "setView", { view: { mapStyle: "topo" } }],
  ["the name is set", ANALYZED, "setName", { name: "LZ EAGLE" }],
  ["a null name is an empty name", ANALYZED, "setName", { name: null }],
];

const DOGHOUSE_CASES = [
  ["an array target", [34.5, -84.1], "diagram-1"],
  ["an object target with lat and lon", { lat: 34.5, lon: -84.1 }, "diagram-1"],
  ["an object target with lat and lng", { lat: 34.5, lng: -84.1 }, "diagram-1"],
  ["numeric strings", ["34.5", "-84.1"], "diagram-1"],
  ["a namespace with odd characters is made safe", [34.5, -84.1], "a b/c:d.é"],
  // JavaScript's regex works on UTF-16 units: a character outside the basic plane is two of them, so it becomes two underscores.
  ["a namespace with a character outside the basic plane", [34.5, -84.1], "heli\u{1F681}x"],
  ["no namespace falls back to the position to six places", [34.5, -84.1], undefined],
  ["an empty namespace falls back to the position", [34.5, -84.1], ""],
  ["a numeric namespace", [34.5, -84.1], 12],
  // 0.0078125 is exactly 1/128, so six places is a true tie: JavaScript's toFixed takes the larger, away from zero for a negative.
  ["a tie in the sixth place rounds away from zero", [0.0078125, -0.0078125], undefined],
  ["a longitude near the date line", [10, 179.999999], "dl"],
  ["a target that is not a position gives none", ["x", 1], "n"],
  ["no target gives none", null, "n"],
  ["a short array gives none", [34.5], "n"],
];

// What App.js does when an analysis comes back (`handleAnalysisComplete`): complete it, and make the standard doghouses once, only
// if the diagram has none. A later analysis, or a second target, never regenerates them.
const afterAnalysis = (diagram, analysis) => {
  let state = createInitialLzWorkspace([diagram]);
  const id = state.activeDiagramId;
  const before = state.diagramsById[id];
  if (!before.target) return start(diagram);
  state = lzWorkspaceReducer(state, { type: LZ_WORKSPACE_ACTIONS.COMPLETE_ANALYSIS, diagramId: id, analysis });
  if (!before.graphics?.doghouses?.length) {
    state = lzWorkspaceReducer(state, {
      type: LZ_WORKSPACE_ACTIONS.SET_GRAPHIC_COLLECTION,
      diagramId: id,
      collection: "doghouses",
      items: createDefaultDoghouses([before.target.lat, before.target.lon], id),
    });
  }
  const { updatedAt, dirty, ...rest } = state.diagramsById[id]; // eslint-disable-line no-unused-vars
  return clean(rest);
};

const AFTER = [
  ["a first analysis makes the SP and RP doghouses", TARGETED, ANALYSIS],
  ["a second analysis does not make them again", afterAnalysis(TARGETED, ANALYSIS), ANALYSIS],
  ["an analyzed diagram that already has doghouses keeps its own", ANALYZED, ANALYSIS],
  ["a draft with no target is left alone", DRAFT, ANALYSIS],
];

const opsFixture = () => ({
  description: "What happens to one LZ diagram when it is analysed, its graphics are edited, or its view or name change, as "
    + "frontend/src/feature/lzWorkspace/useLzWorkspace.js's reducer does it (the reference), with `updatedAt` and `dirty` left out "
    + "because the native apps stamp those when they save. `diagram` is what the case starts from, `expected` what the diagram is "
    + "afterwards. A refused action leaves `expected` equal to `diagram`.",
  generatedBy: GENERATED_BY,
  ops: OPS.map(([name, diagram, op, args]) => ({
    name, op, args: clean(args), diagram: start(diagram), expected: apply(diagram, op, args),
  })),
  defaultDoghouses: DOGHOUSE_CASES.map(([name, target, namespace]) => ({
    name, target, namespace: namespace === undefined ? null : namespace, expected: clean(createDefaultDoghouses(target, namespace)),
  })),
  afterAnalysis: AFTER.map(([name, diagram, analysis]) => ({
    name, analysis: clean(analysis), diagram: start(diagram), expected: afterAnalysis(diagram, analysis),
  })),
});

describe("workspace operations fixture", () => {
  it("workspace/ops.json", () => settle("workspace/ops.json", opsFixture()));

  it("covers the refusals as well as the successes", () => {
    const f = opsFixture();
    const refused = f.ops.filter((c) => JSON.stringify(c.diagram) === JSON.stringify(c.expected));
    const changed = f.ops.filter((c) => JSON.stringify(c.diagram) !== JSON.stringify(c.expected));
    expect(refused.length).toBeGreaterThanOrEqual(8);
    expect(changed.length).toBeGreaterThanOrEqual(25);
    // The rule the native apps most need to keep: no graphics until the diagram is analyzed.
    expect(f.ops.find((c) => c.name === "graphics cannot be set before analysis").expected)
      .toEqual(f.ops.find((c) => c.name === "graphics cannot be set before analysis").diagram);
  });

  it("makes the doghouses once", () => {
    const f = opsFixture();
    const first = f.afterAnalysis[0].expected.graphics.doghouses;
    expect(first.map((d) => d.id_val)).toEqual(["[SP1]", "[RP1]"]);
    expect(f.afterAnalysis[1].expected.graphics.doghouses).toEqual(first);
    expect(f.afterAnalysis[2].expected.graphics.doghouses).toEqual(ANALYZED.graphics.doghouses);
  });
});
