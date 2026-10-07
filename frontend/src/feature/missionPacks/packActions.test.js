import { act, renderHook } from "@testing-library/react";
import api from "../auth/api";
import { copyFromLibrary, deleteItemOp, droppedVersions, myEditsName, saveVersionToLibrary } from "./packActions";
import { batchFailed, edit, nextBatch, openSession } from "./packSession";
import { changedSince, usePackSeen } from "./usePackSeen";

jest.mock("../auth/api", () => ({ get: jest.fn(), post: jest.fn(), put: jest.fn(), delete: jest.fn() }));

const T = "2026-10-07T12:00:00";
const PACK = {
  uuid: "p-1", name: "OP DK", description: "", status: "active", role: "editor", owner: { id: 1, name: "Colin" },
  team: null, head_seq: 3, seen_seq: 0, member_count: 1, item_count: 1, item_counts: { lz: 1, route: 0, pointset: 0 },
  finished_at: null, finished_by: null, created_at: T, updated_at: T, live_url: null, members: [],
  items: [{ uuid: "lz-1", kind: "lz", name: "LZ HAWK", revision: 1, seq: 3, created_by: null, updated_by: null,
    created_at: T, updated_at: T, source: null, data: { flightData: { landing_hdg: "090°" }, graphics: { helicopters: [{ id: 1, lat: 1 }] } } }],
};

beforeEach(() => {
  ["get", "post", "put", "delete"].forEach((m) => api[m].mockReset());
});

describe("keeping what a finished pack would not take", () => {
  // Edits made here, sent, and refused because the owner finished the pack meanwhile.
  const finishedUnder = (...ops) => {
    let n = 0;
    let session = openSession(PACK, 2);
    session = edit(session, ops, () => `op-${(n += 1)}`).session;
    session = nextBatch(session).session;
    return batchFailed(session, { status: 423, code: "pack_finished" });
  };

  it("rebuilds this person's version of each item from the pack's and their refused edits", () => {
    const session = finishedUnder(
      { type: "patch", item: "lz-1", path: ["flightData"], value: { landing_hdg: "270°" } },
      { type: "patch", item: "lz-1", path: ["graphics", "helicopters", { id: 1 }], value: { lat: 2 } },
      { type: "item.create", item: "ps-9", kind: "pointset", name: "NEW POINTS", data: [{ id: "a", lat: 1, lon: 1 }] },
      { type: "remove", item: "lz-1", path: ["graphics", "helicopters", { id: 99 }] },
    );
    expect(session.readOnly).toBe(true);
    expect(session.view["lz-1"].data.flightData.landing_hdg).toBe("090°"); // the pack's, on screen
    expect(droppedVersions(session)).toEqual([
      { uuid: "lz-1", kind: "lz", name: "LZ HAWK", data: { flightData: { landing_hdg: "270°" }, graphics: { helicopters: [{ id: 1, lat: 2 }] } } },
      { uuid: "ps-9", kind: "pointset", name: "NEW POINTS", data: [{ id: "a", lat: 1, lon: 1 }] },
    ]);
  });

  it("has nothing to keep when nothing was refused", () => {
    expect(droppedVersions(openSession(PACK, 2))).toEqual([]);
    expect(droppedVersions(null)).toEqual([]);
  });

  it("saves a version to the library as each kind is kept there", async () => {
    api.post.mockResolvedValue({ data: { id: 7 } });
    await saveVersionToLibrary({ kind: "lz", name: "LZ HAWK (my edits)", data: { status: "analyzed" } });
    expect(api.post).toHaveBeenLastCalledWith("/lz", { name: "LZ HAWK (my edits)", lz_data: { status: "analyzed" } });
    await saveVersionToLibrary({ kind: "pointset", name: "P", data: [{ id: "a" }] });
    expect(api.post).toHaveBeenLastCalledWith("/pointsets", { name: "P", points: [{ id: "a" }] });
    await expect(saveVersionToLibrary({ kind: "route", name: "R", data: { version: 1, routes: [{ id: "r" }] } })).resolves.toEqual({ id: 7 });
    const [path, form] = api.post.mock.calls[2];
    expect(path).toBe("/routes");
    expect([form.get("name"), form.get("kind"), JSON.parse(form.get("route_data"))]).toEqual(["R", "sketch", { version: 1, routes: [{ id: "r" }] }]);
    expect(myEditsName("LZ HAWK")).toBe("LZ HAWK (my edits)");
    expect(myEditsName("X".repeat(120))).toHaveLength(100);
  });
});

describe("pack actions", () => {
  it("removes an item with a sentence for the history", () => {
    expect(deleteItemOp({ uuid: "lz-1", name: "LZ HAWK", actor: "Sam B." })).toEqual({
      type: "item.delete", item: "lz-1", summary: 'Sam B. removed "LZ HAWK".',
    });
  });

  it("copies a library record in under a uuid chosen here, so a retry finds the same copy", async () => {
    api.post.mockResolvedValue({ data: { item: { uuid: "rt-x" } } });
    await copyFromLibrary("p-1", { kind: "route", record: { id: 4, name: "RED ROUTES" }, actor: "Sam B.", newId: () => "x" });
    expect(api.post).toHaveBeenCalledWith("/packs/p-1/items", {
      source: { kind: "route", id: 4 }, item: "rt-x", summary: 'Sam B. added "RED ROUTES" (a copy from their library).',
    });
  });
});

describe("usePackSeen", () => {
  beforeEach(() => jest.useFakeTimers());
  afterEach(() => jest.useRealTimers());

  it("moves the marker to the newest event while the person looks, and keeps 'new' where they were", async () => {
    const markSeen = jest.fn().mockResolvedValue({ seen_seq: 9, seen_at: T });
    const { result, rerender } = renderHook((props) => usePackSeen({ markSeen, ...props }), {
      initialProps: { packUuid: "p-1", headSeq: 9, seenSeq: 4, looking: false },
    });
    act(() => { jest.advanceTimersByTime(5000); });
    expect(markSeen).not.toHaveBeenCalled(); // not looking
    rerender({ packUuid: "p-1", headSeq: 9, seenSeq: 4, looking: true });
    expect(result.current.newSince).toBe(4);
    await act(async () => { jest.advanceTimersByTime(1500); });
    expect(markSeen).toHaveBeenCalledWith("p-1", 9);
    expect(result.current).toEqual({ seenSeq: 9, newSince: 4 });
  });

  it("starts again for another pack", () => {
    const markSeen = jest.fn().mockResolvedValue({ seen_seq: 0 });
    const { result, rerender } = renderHook((props) => usePackSeen({ markSeen, ...props }), {
      initialProps: { packUuid: "p-1", headSeq: 3, seenSeq: 3, looking: false },
    });
    rerender({ packUuid: "p-2", headSeq: 8, seenSeq: 5, looking: false });
    expect(result.current).toEqual({ seenSeq: 5, newSince: 5 });
  });

  it("marks an item changed by someone else since then", () => {
    expect(changedSince({ seq: 7, updated_by: { id: 3 } }, 4, 2)).toBe(true);
    expect(changedSince({ seq: 7, updated_by: { id: 2 } }, 4, 2)).toBe(false);
    expect(changedSince({ seq: 4, updated_by: { id: 3 } }, 4, 2)).toBe(false);
    expect(changedSince({ pendingCreate: true }, 4, 2)).toBe(false);
  });
});
