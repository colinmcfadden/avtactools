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

const BOOK = { uuid: "b-1", name: "HLZ SURVEY BOOK", revision: 1, page_order: ["s-1"], can_edit: true };

const fakeClient = (overrides = {}) => ({
  listBooks: jest.fn(async () => ({ books: [BOOK], teams: [{ id: 3, name: "B CO" }], can_make_for_no_team: true })),
  getBook: jest.fn(async () => ({ book: BOOK, surveys: [{ uuid: "s-1", name: DONOVAN.name }] })),
  createBook: jest.fn(async ({ name }) => ({ ...BOOK, uuid: "b-2", name })),
  submitSurvey: jest.fn(async (uuid, revision) => surveyAt(revision + 1, { status: "in_review" })),
  backToDraft: jest.fn(async (uuid, revision) => surveyAt(revision + 1, { status: "draft" })),
  publishSurvey: jest.fn(async (uuid, revision) => ({ survey: surveyAt(revision + 1, { status: "published" }), revision: { number: 1 } })),
  fetchPage: jest.fn(async () => "<html></html>"),
  fetchPdf: jest.fn(async () => new Blob(["%PDF"])),
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
  const view = renderHook(() => useSurveys({ client }));
  await act(async () => {
    await view.result.current.openSurvey("s-1");
  });
  return view;
};

describe("useSurveys", () => {
  it("lists the books, then one book's surveys", async () => {
    const client = fakeClient();
    const { result } = renderHook(() => useSurveys({ client }));
    await act(async () => {
      await result.current.refreshBooks();
    });
    expect(result.current.books).toMatchObject({ status: "ready", canMakeForNoTeam: true, teams: [{ id: 3, name: "B CO" }] });
    await act(async () => {
      await result.current.openBook("b-1");
    });
    expect(result.current.list.status).toBe("ready");
    expect(result.current.list.book.name).toBe("HLZ SURVEY BOOK");
    expect(client.getBook).toHaveBeenCalledWith("b-1", { archived: false });
    await act(async () => {
      result.current.toggleArchived();
    });
    expect(client.getBook).toHaveBeenLastCalledWith("b-1", { archived: true });
    await act(async () => {
      await result.current.create({ name: "LZ EAGLE", hlzId: "GA 035" });
    });
    expect(client.createSurvey).toHaveBeenCalledWith(expect.objectContaining({ book: "b-1", name: "LZ EAGLE" }));
  });

  it("says why the books could not be loaded, in its own words", async () => {
    const client = fakeClient({ listBooks: jest.fn(async () => { throw Object.assign(new Error("x"), { response: { status: 403, data: { code: "feature_disabled" } } }); }) });
    const { result } = renderHook(() => useSurveys({ client }));
    await act(async () => {
      await result.current.refreshBooks();
    });
    expect(result.current.books).toMatchObject({ status: "error", error: "Your account cannot open the LZ survey books. An admin can give you access." });
  });

  it("says why a survey would not open, and opens nothing", async () => {
    const client = fakeClient({ getSurvey: jest.fn(async () => { throw Object.assign(new Error("x"), { response: { status: 404, data: { code: "not_found" } } }); }) });
    const { result } = renderHook(() => useSurveys({ client }));
    let failure;
    await act(async () => {
      failure = await result.current.openSurvey("s-9");
    });
    expect(failure).toBe("That is no longer there, or you cannot open it.");
    expect(result.current.survey).toBeNull();
  });

  it("opens a published survey for a reader on its page, not in the editor", async () => {
    const client = fakeClient({ getSurvey: jest.fn(async () => ({ uuid: "s-1", name: "LZ DONOVAN", status: "published", published_revision: 2, revisions: [] })) });
    const { result } = renderHook(() => useSurveys({ client }));
    await act(async () => {
      await result.current.openSurvey("s-1");
    });
    expect(result.current.survey).toBeNull();
    expect(result.current.reading).toMatchObject({ published_revision: 2 });
  });

  it("sends for review, publishes with the sign-off and this browser's slope opacity", async () => {
    const client = fakeClient();
    const { result } = await opened(client);
    await act(async () => {
      await result.current.setReview("review");
    });
    expect(client.submitSurvey).toHaveBeenCalledWith("s-1", 1);
    expect(result.current.survey.status).toBe("in_review");
    let revision;
    await act(async () => {
      revision = await result.current.publish({ signoff: { name: "J. Doe", date: "27 OCT 2026" }, changes: "" });
    });
    expect(revision).toEqual({ number: 1 });
    expect(client.publishSurvey).toHaveBeenCalledWith("s-1", 2, { signoff: { name: "J. Doe", date: "27 OCT 2026" }, changes: "", opacity: 55 });
    expect(result.current.survey.status).toBe("published");
  });

  it("says a refused publish in words", async () => {
    const client = fakeClient({ publishSurvey: jest.fn(async () => { throw Object.assign(new Error("x"), { response: { status: 409, data: { code: "invalid_status" } } }); }) });
    const { result } = await opened(client);
    await expect(act(async () => {
      await result.current.publish({ signoff: { name: "J", date: "x" } });
    })).rejects.toThrow("The survey has moved on since you opened it. Reload it.");
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
