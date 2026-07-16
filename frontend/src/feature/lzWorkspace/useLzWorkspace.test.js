import {
  createInitialLzWorkspace,
  createLzDiagramFromTarget,
  LZ_WORKSPACE_ACTIONS,
  lzWorkspaceReducer,
  normalizeLegacyLzSnapshot,
  serializeLzDiagram,
} from "./useLzWorkspace";
import { createDefaultDoghouses } from "../doghouses/useDoghouses";

const polygon = [
  [34.0, -84.0],
  [34.0, -84.001],
  [33.999, -84.001],
];

describe("LZ/PZ workspace", () => {
  it("keeps diagram A graphics when a new target starts diagram B", () => {
    const diagramA = createLzDiagramFromTarget({
      id: "alpha",
      target: [34.0, -84.0],
      mgrs: "16S GC 11111 22222",
    });
    const diagramB = createLzDiagramFromTarget({
      id: "bravo",
      target: [34.1, -84.1],
      mgrs: "16S GC 33333 44444",
    });

    let workspace = createInitialLzWorkspace();
    workspace = lzWorkspaceReducer(workspace, {
      type: LZ_WORKSPACE_ACTIONS.CREATE_DIAGRAM,
      diagram: diagramA,
    });
    workspace = lzWorkspaceReducer(workspace, {
      type: LZ_WORKSPACE_ACTIONS.COMPLETE_ANALYSIS,
      diagramId: diagramA.id,
      analysis: { detectedLZ: polygon },
    });

    const doghouses = createDefaultDoghouses([34.0, -84.0], diagramA.id);
    workspace = lzWorkspaceReducer(workspace, {
      type: LZ_WORKSPACE_ACTIONS.SET_GRAPHIC_COLLECTION,
      diagramId: diagramA.id,
      collection: "doghouses",
      items: doghouses,
    });

    workspace = lzWorkspaceReducer(workspace, {
      type: LZ_WORKSPACE_ACTIONS.CREATE_DIAGRAM,
      diagram: diagramB,
    });

    expect(workspace.activeDiagramId).toBe(diagramB.id);
    expect(workspace.diagramsById[diagramA.id].graphics.doghouses).toEqual(
      doghouses,
    );
    expect(workspace.diagramsById[diagramB.id].graphics.doghouses).toEqual([]);

    const savedA = serializeLzDiagram(workspace.diagramsById[diagramA.id]);
    expect(savedA.target.mgrs).toBe("16S GC 11111 22222");
    expect(savedA.graphics.doghouses).toEqual(doghouses);
    expect(savedA.analysis.terrainData).toBeNull();
  });

  it("normalizes a legacy full-screen snapshot into one diagram", () => {
    const diagram = normalizeLegacyLzSnapshot(
      {
        targetLocation: [34.0, -84.0],
        gridInput: "16S GC 11111 22222",
        detectedLZ: polygon,
        doghouses: [{ id: "dh1", lat: 34.0, lon: -84.003 }],
        helicopters: [{ id: "helo-1", lat: 34.0, lon: -84.0 }],
      },
      { savedId: 42, name: "Legacy LZ" },
    );

    expect(diagram.savedId).toBe(42);
    expect(diagram.name).toBe("Legacy LZ");
    expect(diagram.status).toBe("analyzed");
    expect(diagram.target.mgrs).toBe("16S GC 11111 22222");
    expect(diagram.graphics.doghouses).toHaveLength(1);
    expect(diagram.graphics.helicopters).toHaveLength(1);
  });

  it("does not mark a saved diagram dirty when terrain is regenerated or flight data is unchanged", () => {
    const diagram = createLzDiagramFromTarget({
      id: "saved-diagram",
      target: [34.0, -84.0],
      mgrs: "16S GC 11111 22222",
    });

    let workspace = createInitialLzWorkspace();
    workspace = lzWorkspaceReducer(workspace, {
      type: LZ_WORKSPACE_ACTIONS.CREATE_DIAGRAM,
      diagram,
    });
    workspace = lzWorkspaceReducer(workspace, {
      type: LZ_WORKSPACE_ACTIONS.MARK_SAVED,
      diagramId: diagram.id,
      savedId: 7,
      name: "Saved LZ",
    });
    workspace = lzWorkspaceReducer(workspace, {
      type: LZ_WORKSPACE_ACTIONS.SET_RUNTIME_TERRAIN_DATA,
      diagramId: diagram.id,
      terrainData: { image: "runtime-only" },
    });
    workspace = lzWorkspaceReducer(workspace, {
      type: LZ_WORKSPACE_ACTIONS.SET_FLIGHT_DATA,
      diagramId: diagram.id,
      flightData: {},
    });

    expect(workspace.diagramsById[diagram.id].dirty).toBe(false);
    expect(workspace.diagramsById[diagram.id].analysis.terrainData).toEqual({
      image: "runtime-only",
    });
  });
});
