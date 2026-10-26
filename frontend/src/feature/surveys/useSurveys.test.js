import { act, renderHook } from "@testing-library/react";
import useSurveys, { changedBetween, editableOf } from "./useSurveys";

const { readFixture } = require("../../contracts/readFixture");

const DONOVAN = readFixture("surveys/donovan.json");

const surveyAt = (revision, changes = {}) => ({
  uuid: "s-1",
  hlz_id: DONOVAN.hlzId,
  name: DONOVAN.name,
  status: "draft",
  revision,
  field: DONOVAN.field,
  overrides: {},
  diagram: { schemaVersion: 2, id: "survey-s-1", target: { lat: 34.596, lon: -84.1278, mgrs: "" }, analysis: {}, graphics: {} },
  snapshot: null,
  comparisons: [],
  assets: [],
  updated_by: "CW3 Admin",
  ...changes,
});

const conflict = (server) => Object.assign(new Error("409"), { response: { status: 409, data: { code: "revision_conflict", server } } });

const fakeClient = (overrides = {}) => ({
  listSurveys: jest.fn(async () => ({ book: { name: "HLZ SURVEY BOOK", revision: 1, page_order: ["s-1"] }, surveys: [{ uuid: "s-1", name: DONOVAN.name }] })),
  getSurvey: jest.fn(async () => surveyAt(1)),
  createSurvey: jest.fn(async ({ name, hlzId }) => surveyAt(1, { name, hlz_id: hlzId, field: {} })),
  saveSurvey: jest.fn(async (uuid, revision, body) => surveyAt(revision + 1, { ...body, field: body.field })),
  measureSurvey: jest.fn(async (uuid, { apply }) => ({
    applied: apply,
    snapshot: { grid: "16S GD 63415 31994" },
    changes: [{ label: "Grid", before: null, after: "16S GD 63415 31994" }],
    survey: apply ? surveyAt(3, { snapshot: { grid: "16S GD 63415 31994" } }) : null,
  })),
  archiveSurvey: jest.fn(async () => surveyAt(2, { archived: true })),
  restoreSurvey: jest.fn(async () => surveyAt(3, { archived: false })),
  updateBook: jest.fn(async (revision, patch) => ({ name: patch.name, revision: revision + 1, page_order: [] })),
  uploadAsset: jest.fn(async () => ({ id: 7, kind: "photo_north", url: "/surveys/s-1/assets/7" })),
  updateAsset: jest.fn(async (uuid, id, meta) => ({ id, kind: "photo_north", ...meta })),
  removeAsset: jest.fn(async () => ({ removed: 7 })),
  ...overrides,
});

const opened = async (client) => {
  const hook = renderHook(() => useSurveys({ client }));
  await act(async () => {
    await hook.result.current.openSurvey("s-1");
  });
  return hook;
};

describe("useSurveys", () => {
  it("lists the book", async () => {
    const client = fakeClient();
    const { result } = renderHook(() => useSurveys({ client }));
    await act(async () => {
      await result.current.refresh();
    });
    expect(result.current.list.status).toBe("ready");
    expect(result.current.list.book.name).toBe("HLZ SURVEY BOOK");
    expect(client.listSurveys).toHaveBeenCalledWith({ archived: false });
    await act(async () => {
      result.current.toggleArchived();
    });
    expect(client.listSurveys).toHaveBeenLastCalledWith({ archived: true });
  });

  it("says why the list could not be loaded, in its own words", async () => {
    const client = fakeClient({ listSurveys: jest.fn(async () => { throw Object.assign(new Error("x"), { response: { status: 403, data: { code: "admin_required" } } }); }) });
    const { result } = renderHook(() => useSurveys({ client }));
    await act(async () => {
      await result.current.refresh();
    });
    expect(result.current.list).toMatchObject({ status: "error", error: "Only admins can work on LZ surveys." });
  });

  it("saves what was typed on the revision it was opened at", async () => {
    const client = fakeClient();
    const { result } = await opened(client);
    expect(result.current.dirty).toBe(false);
    act(() => result.current.setField("surface", "Grass, short"));
    expect(result.current.dirty).toBe(true);
    await act(async () => {
      await result.current.save();
    });
    const [uuid, revision, body] = client.saveSurvey.mock.calls[0];
    expect([uuid, revision]).toEqual(["s-1", 1]);
    expect(body.field.surface).toBe("Grass, short");
    // The approach and exit go with the diagram as its doghouses.
    expect(body.diagram.flightData.landing_hdg).toBe("000°");
    expect(result.current.survey.revision).toBe(2);
    expect(result.current.dirty).toBe(false);
  });

  it("keeps what was typed when someone else saved first, until the admin chooses", async () => {
    const theirs = surveyAt(2, { field: { ...DONOVAN.field, hazards: "Wires on the north edge" }, updated_by: "SFC Other" });
    let calls = 0;
    const client = fakeClient({
      saveSurvey: jest.fn(async (uuid, revision, body) => {
        calls += 1;
        if (calls === 1) throw conflict(theirs);
        return surveyAt(revision + 1, body);
      }),
    });
    const { result } = await opened(client);
    act(() => result.current.setField("surface", "Gravel"));
    await act(async () => {
      expect(await result.current.save()).toBeNull();
    });
    expect(result.current.conflict.revision).toBe(2);
    expect(result.current.conflictChanges).toEqual(["hazards"]);
    expect(result.current.draft.field.surface).toBe("Gravel");          // nothing typed is lost
    expect(result.current.error).toBeNull();
    await act(async () => {
      await result.current.keepMine();
    });
    expect(client.saveSurvey).toHaveBeenLastCalledWith("s-1", 2, expect.objectContaining({ field: expect.objectContaining({ surface: "Gravel" }) }));
    expect(result.current.conflict).toBeNull();
    expect(result.current.survey.revision).toBe(3);
  });

  it("can load theirs instead", async () => {
    const theirs = surveyAt(2, { field: { ...DONOVAN.field, hazards: "Wires" } });
    const client = fakeClient({ saveSurvey: jest.fn(async () => { throw conflict(theirs); }) });
    const { result } = await opened(client);
    act(() => result.current.setField("surface", "Gravel"));
    await act(async () => {
      await result.current.save();
    });
    act(() => result.current.takeTheirs());
    expect(result.current.draft.field).toMatchObject({ surface: "Grass", hazards: "Wires" });
    expect(result.current.dirty).toBe(false);
  });

  it("saves before measuring, shows what would change, and keeps it only when asked", async () => {
    const client = fakeClient();
    const { result } = await opened(client);
    act(() => result.current.setField("approachDeg", "045"));
    await act(async () => {
      await result.current.measure();
    });
    expect(client.saveSurvey).toHaveBeenCalledTimes(1);
    expect(client.measureSurvey).toHaveBeenCalledWith("s-1", { apply: false });
    expect(result.current.preview.changes).toHaveLength(1);
    expect(result.current.survey.snapshot).toBeNull();
    await act(async () => {
      await result.current.applyMeasure();
    });
    expect(client.measureSurvey).toHaveBeenLastCalledWith("s-1", { apply: true, revision: 2 });
    expect(result.current.survey.snapshot.grid).toBe("16S GD 63415 31994");
    expect(result.current.preview).toBeNull();
  });

  it("does not measure when the save before it fails", async () => {
    const client = fakeClient({ saveSurvey: jest.fn(async () => { throw Object.assign(new Error("x"), { response: { status: 500, data: {} } }); }) });
    const { result } = await opened(client);
    act(() => result.current.setField("approachDeg", "045"));
    await act(async () => {
      await result.current.measure();
    });
    expect(client.measureSurvey).not.toHaveBeenCalled();
    expect(result.current.error).toBe("The survey could not be saved.");
  });

  it("settles a disagreement by saving the choice", async () => {
    const client = fakeClient();
    const { result } = await opened(client);
    await act(async () => {
      await result.current.decide("elevation", "field", "Surveyed by GPS");
    });
    expect(client.saveSurvey.mock.calls[0][2].overrides).toEqual({ elevation: { choice: "field", reason: "Surveyed by GPS" } });
    await act(async () => {
      await result.current.undecide("elevation");
    });
    expect(client.saveSurvey.mock.calls[1][2].overrides).toEqual({});
  });

  it("keeps photos as they come, without a new revision", async () => {
    const client = fakeClient();
    const { result } = await opened(client);
    await act(async () => {
      await result.current.uploadPhoto(new Blob(["x"]), { kind: "photo_north" });
    });
    expect(result.current.survey.assets.map((a) => a.id)).toEqual([7]);
    expect(result.current.survey.revision).toBe(1);
    expect(result.current.dirty).toBe(false);
    await act(async () => {
      await result.current.updatePhoto(7, { caption: "North" });
    });
    expect(result.current.survey.assets[0].caption).toBe("North");
    await act(async () => {
      await result.current.removePhoto(7);
    });
    expect(result.current.survey.assets).toEqual([]);
  });

  it("names what someone else changed", () => {
    const base = surveyAt(1);
    expect(changedBetween(base, surveyAt(2))).toEqual([]);
    expect(changedBetween(base, surveyAt(2, { name: "LZ D", snapshot: { grid: "x" } }))).toEqual(["name", "measurements"]);
    expect(editableOf(null).field.grid).toBe("");
  });
});
