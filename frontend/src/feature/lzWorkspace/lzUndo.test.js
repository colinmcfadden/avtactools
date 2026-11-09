import { createInitialLzWorkspace, LZ_WORKSPACE_ACTIONS as A, lzWorkspaceReducer, normalizeLzDiagram } from "./useLzWorkspace";
import { LZ_UNDO_ACTIONS, withLzUndo } from "./lzUndo";

const reduce = withLzUndo(lzWorkspaceReducer, A);
const ID = "lz1";

const start = () =>
  createInitialLzWorkspace({
    diagrams: [
      normalizeLzDiagram({
        id: ID,
        target: { lat: 34, lon: -84, mgrs: "16S" },
        status: "analyzed",
        analysis: { detectedLZ: [[34, -84], [34.1, -84], [34.1, -84.1]], results: { area: 1 } },
        graphics: {
          doghouses: [{ id: "dh", role: "landing", heading: "010°", lat: 34, lon: -84 }],
          helicopters: [],
        },
      }),
    ],
    activeDiagramId: ID,
  });

let seq = 100;
const step = (more = {}) => ({ seq: (seq += 1), key: null, label: null, ...more });
const undo = (state) => reduce(state, { type: LZ_UNDO_ACTIONS.UNDO, diagramId: ID, seq: (seq += 1) });
const redo = (state) => reduce(state, { type: LZ_UNDO_ACTIONS.REDO, diagramId: ID, seq: (seq += 1) });
const graphics = (state) => state.diagramsById[ID].graphics;
const diagram = (state) => state.diagramsById[ID];
const place = (state, helicopters) =>
  reduce(state, { type: A.SET_GRAPHIC_COLLECTION, diagramId: ID, collection: "helicopters", items: helicopters, step: step() });
const HELO = { id: "h1", lat: 34, lon: -84 };

describe("LZ/PZ undo", () => {
  it("takes back a graphic placed, and puts it back", () => {
    const placed = place(start(), [HELO]);
    expect(placed.history[ID].undo[0].label).toBe("Add helicopter");
    const undone = undo(placed);
    expect(graphics(undone).helicopters).toEqual([]);
    expect(graphics(redo(undone)).helicopters).toEqual([HELO]);
  });

  it("names a move, a turn and a delete", () => {
    let state = place(start(), [HELO]);
    state = place(state, [{ ...HELO, lat: 35 }]);
    state = place(state, [{ ...HELO, lat: 35, heading: 90 }]);
    state = place(state, []);
    expect(state.history[ID].undo.map((s) => s.label)).toEqual(["Add helicopter", "Move helicopter", "Turn helicopter", "Delete helicopter"]);
  });

  it("makes no step of the same list in a new array", () => {
    const placed = place(start(), [HELO]);
    const again = place(placed, [{ ...HELO }]);
    expect(again.history[ID].undo).toHaveLength(1);
  });

  it("makes a doghouse and the heading it gives one step", () => {
    const turned = reduce(start(), {
      type: A.SET_GRAPHIC_COLLECTION,
      diagramId: ID,
      collection: "doghouses",
      items: [{ ...graphics(start()).doghouses[0], heading: "120°" }],
      step: step(),
    });
    expect(diagram(turned).flightData.landing_hdg).toBe("120°");
    const undone = undo(turned);
    expect(graphics(undone).doghouses[0].heading).toBe("010°");
    expect(diagram(undone).flightData.landing_hdg).toBe(diagram(start()).flightData.landing_hdg);
  });

  it("leaves redo standing through the flight data follow-up an undo sets off", () => {
    const placed = place(start(), [HELO]);
    const undone = undo(placed);
    const followed = reduce(undone, { type: A.SET_FLIGHT_DATA, diagramId: ID, flightData: { landing_hdg: "010°" } });
    expect(followed.history[ID].redo).toHaveLength(1);
  });

  it("never undoes an analysis, and lays it over every version", () => {
    const placed = place(start(), [HELO]);
    const analysed = reduce(placed, { type: A.COMPLETE_ANALYSIS, diagramId: ID, analysis: { results: { area: 2 } } });
    const undone = undo(analysed);
    expect(diagram(undone).analysis.results).toEqual({ area: 2 });
    expect(graphics(undone).helicopters).toEqual([]);
    expect(diagram(undone).status).toBe("analyzed");
  });

  it("does not clear redo for a change that is nobody's step", () => {
    const undone = undo(place(start(), [HELO]));
    const again = reduce(undone, { type: A.SET_ANALYSIS_DRAFT, diagramId: ID, analysis: { gridElevation: 900 } });
    expect(again.history[ID].redo).toHaveLength(1);
    expect(diagram(redo(again)).analysis.gridElevation).toBe(900);
  });

  it("brings the analysis back when a boundary drawn over it is undone, as one step", () => {
    let state = start();
    const raster = { png: "x" };
    state = reduce(state, { type: A.SET_RUNTIME_TERRAIN_DATA, diagramId: ID, terrainData: raster });
    const key = "boundary-1";
    state = reduce(state, { type: A.RESET_ANALYSIS, diagramId: ID, step: step({ key, label: "Draw boundary" }) });
    state = reduce(state, { type: A.SET_ANALYSIS_DRAFT, diagramId: ID, analysis: { customLZ: null }, step: step({ key }) });
    const drawn = [[1, 1], [2, 2], [3, 1]];
    state = reduce(state, { type: A.SET_ANALYSIS_DRAFT, diagramId: ID, analysis: { customLZ: drawn }, step: step({ key }) });
    expect(state.history[ID].undo).toHaveLength(1);
    expect(diagram(state).status).toBe("targeted");

    const undone = undo(state);
    expect(diagram(undone).status).toBe("analyzed");
    expect(diagram(undone).analysis.results).toEqual({ area: 1 });
    expect(diagram(undone).analysis.customLZ).toBeNull();
    expect(diagram(undone).analysis.terrainData).toBeNull(); // measured again, from the boundary brought back
    expect(diagram(redo(undone)).analysis.customLZ).toEqual(drawn);
  });

  it("keeps the raster and the boundary's object when a step did not touch them", () => {
    let state = start();
    const raster = { png: "x" };
    state = reduce(state, { type: A.SET_RUNTIME_TERRAIN_DATA, diagramId: ID, terrainData: raster });
    const detected = diagram(state).analysis.detectedLZ;
    const undone = undo(place(state, [HELO]));
    expect(diagram(undone).analysis.terrainData).toBe(raster);
    expect(diagram(undone).analysis.detectedLZ).toBe(detected);
  });

  it("keeps the view and the capture area out of it", () => {
    let state = place(start(), [HELO]);
    state = reduce(state, { type: A.SET_VIEW, diagramId: ID, view: { showHeatmap: true } });
    state = reduce(state, { type: A.SET_GRAPHICS, diagramId: ID, graphics: { exportBox: { n: 1 } } });
    const undone = undo(state);
    expect(diagram(undone).view.showHeatmap).toBe(true);
    expect(graphics(undone).exportBox).toEqual({ n: 1 });
  });

  it("forgets a diagram's history with the diagram", () => {
    const placed = place(start(), [HELO]);
    const removed = reduce(placed, { type: A.REMOVE_DIAGRAM, diagramId: ID });
    expect(removed.history[ID]).toBeUndefined();
  });

  it("carries a parked workspace's history out and back", () => {
    const placed = place(start(), [HELO]);
    const parked = reduce(placed, { type: A.HYDRATE_WORKSPACE, workspace: { diagramsById: {}, diagramOrder: [], activeDiagramId: null } });
    expect(parked.history).toEqual({});
    const back = reduce(parked, { type: A.HYDRATE_WORKSPACE, workspace: placed });
    expect(back.history[ID].undo).toHaveLength(1);
    expect(graphics(undo(back)).helicopters).toEqual([]);
  });

  it("rebases someone else's change over every version, and drops history on a put-back", () => {
    const placed = place(start(), [HELO]);
    const incoming = { ...diagram(placed), name: "X", graphics: { ...graphics(placed), units: [{ id: "u" }] } };
    const rebase = (version) => ({ ...version, graphics: { ...version.graphics, units: [{ id: "u" }] } });
    const theirs = reduce(placed, { type: A.APPLY_REMOTE_DIAGRAM, diagramId: ID, diagram: incoming, rebase });
    const undone = undo(theirs);
    expect(graphics(undone).units).toEqual([{ id: "u" }]);
    expect(graphics(undone).helicopters).toEqual([]);

    const putBack = reduce(placed, { type: A.APPLY_REMOTE_DIAGRAM, diagramId: ID, diagram: incoming, dropHistory: true });
    expect(putBack.history[ID]).toBeUndefined();
  });
});
