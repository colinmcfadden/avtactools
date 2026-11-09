import { act, renderHook } from "@testing-library/react";
import useSurveys, { IMPORT_POLL_MS, PRINT_POLL_MS, changedBetween, editableOf, rebaseDraft } from "./useSurveys";

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
  listBooks: vi.fn(async () => ({ books: [BOOK], teams: [{ id: 3, name: "B CO" }], can_make_for_no_team: true })),
  getBook: vi.fn(async () => ({ book: BOOK, surveys: [{ uuid: "s-1", name: DONOVAN.name }] })),
  createBook: vi.fn(async ({ name }) => ({ ...BOOK, uuid: "b-2", name })),
  submitSurvey: vi.fn(async (uuid, revision) => surveyAt(revision + 1, { status: "in_review" })),
  backToDraft: vi.fn(async (uuid, revision) => surveyAt(revision + 1, { status: "draft" })),
  publishSurvey: vi.fn(async (uuid, revision) => ({ survey: surveyAt(revision + 1, { status: "published" }), revision: { number: 1 } })),
  fetchPage: vi.fn(async () => "<html></html>"),
  fetchPdf: vi.fn(async () => new Blob(["%PDF"])),
  getSurvey: vi.fn(async () => surveyAt(1)),
  createSurvey: vi.fn(async ({ name, hlzId }) => surveyAt(1, { name, hlz_id: hlzId, field: {} })),
  saveSurvey: vi.fn(async (uuid, revision, body) => surveyAt(revision + 1, { ...body, field: body.field })),
  measureSurvey: vi.fn(async (uuid, { apply }) => ({
    applied: apply,
    snapshot: { grid: "16S GD 63415 31994" },
    changes: [{ label: "Grid", before: null, after: "16S GD 63415 31994" }],
    survey: apply ? surveyAt(3, { snapshot: { grid: "16S GD 63415 31994" } }) : null,
  })),
  archiveSurvey: vi.fn(async () => surveyAt(2, { archived: true })),
  restoreSurvey: vi.fn(async () => surveyAt(3, { archived: false })),
  updateBook: vi.fn(async (revision, patch) => ({ name: patch.name, revision: revision + 1, page_order: [] })),
  uploadAsset: vi.fn(async () => ({ id: 7, kind: "photo_north", url: "/surveys/s-1/assets/7" })),
  updateAsset: vi.fn(async (uuid, id, meta) => ({ id, kind: "photo_north", ...meta })),
  removeAsset: vi.fn(async () => ({ removed: 7 })),
  bookPrint: vi.fn(async () => ({ state: "empty", pages: 0, available: true })),
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
    const client = fakeClient({ listBooks: vi.fn(async () => { throw Object.assign(new Error("x"), { response: { status: 403, data: { code: "feature_disabled" } } }); }) });
    const { result } = renderHook(() => useSurveys({ client }));
    await act(async () => {
      await result.current.refreshBooks();
    });
    expect(result.current.books).toMatchObject({ status: "error", error: "Your account cannot open the LZ survey books. An admin can give you access." });
  });

  it("says why a survey would not open, and opens nothing", async () => {
    const client = fakeClient({ getSurvey: vi.fn(async () => { throw Object.assign(new Error("x"), { response: { status: 404, data: { code: "not_found" } } }); }) });
    const { result } = renderHook(() => useSurveys({ client }));
    let failure;
    await act(async () => {
      failure = await result.current.openSurvey("s-9");
    });
    expect(failure).toBe("That is no longer there, or you cannot open it.");
    expect(result.current.survey).toBeNull();
  });

  it("opens a published survey for a reader on its page, not in the editor", async () => {
    const client = fakeClient({ getSurvey: vi.fn(async () => ({ uuid: "s-1", name: "LZ DONOVAN", status: "published", published_revision: 2, revisions: [] })) });
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
    const client = fakeClient({ publishSurvey: vi.fn(async () => { throw Object.assign(new Error("x"), { response: { status: 409, data: { code: "invalid_status" } } }); }) });
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
      saveSurvey: vi.fn(async (uuid, revision, body) => {
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
    const client = fakeClient({ saveSurvey: vi.fn(async () => { throw conflict(theirs); }) });
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
    const client = fakeClient({ saveSurvey: vi.fn(async () => { throw Object.assign(new Error("x"), { response: { status: 500, data: {} } }); }) });
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
    await act(async () => {
      await result.current.decide("approach", "typed", "Tree line cut in 2024", 12.5);
    });
    expect(client.saveSurvey.mock.calls[2][2].overrides).toEqual({ approach: { choice: "typed", reason: "Tree line cut in 2024", value: 12.5 } });
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

  it("takes the draft a photo makes of a published survey, but only on the revision it held", async () => {
    const published = surveyAt(4, { status: "published" });
    const client = fakeClient({
      getSurvey: vi.fn(async () => published),
      uploadAsset: vi.fn(async () => ({ id: 8, kind: "photo_other", survey: { status: "draft", revision: 5, was: 4 } })),
      updateAsset: vi.fn(async (uuid, id, meta) => ({ id, kind: "photo_other", ...meta, survey: { status: "draft", revision: 7, was: 6 } })),
    });
    const { result } = await opened(client);
    await act(async () => {
      await result.current.uploadPhoto(new Blob(["x"]), { kind: "photo_other" });
    });
    expect(result.current.survey).toMatchObject({ status: "draft", revision: 5 });
    expect(result.current.survey.assets).toEqual([{ id: 8, kind: "photo_other" }]);      // the survey's state is not kept as a photo
    // Someone else's save came between (6): this copy keeps 5, and its next save meets theirs as a conflict.
    await act(async () => {
      await result.current.updatePhoto(8, { caption: "South" });
    });
    expect(result.current.survey.revision).toBe(5);
    expect(result.current.survey.assets[0].caption).toBe("South");
  });

  it("lets go of a survey that answers once another was asked for", async () => {
    let first;
    const client = fakeClient({
      getSurvey: vi.fn((uuid) => (uuid === "s-1" ? new Promise((resolve) => { first = resolve; }) : Promise.resolve(surveyAt(1, { uuid: "s-2", name: "LZ OTHER" })))),
    });
    const { result } = renderHook(() => useSurveys({ client }));
    let slow;
    await act(async () => {
      slow = result.current.openSurvey("s-1");
      await result.current.openSurvey("s-2");
    });
    await act(async () => {
      first(surveyAt(1));
      await slow;
    });
    expect(result.current.survey.uuid).toBe("s-2");
    expect(result.current.busy).toBeNull();
  });

  it("is not left opening when the survey being opened is closed", async () => {
    const client = fakeClient({ getSurvey: vi.fn(() => new Promise(() => {})) });
    const { result } = renderHook(() => useSurveys({ client }));
    act(() => {
      result.current.openSurvey("s-1");
    });
    expect(result.current.busy).toBe("opening");
    await act(async () => {
      result.current.closeSurvey();
    });
    expect(result.current.busy).toBeNull();
  });

  it("does not put a book's archiving into another book opened meanwhile", async () => {
    let answer;
    const other = { ...BOOK, uuid: "b-2", name: "OTHER BOOK" };
    const client = fakeClient({
      archiveBook: vi.fn(() => new Promise((resolve) => { answer = resolve; })),
      getBook: vi.fn(async (uuid) => ({ book: uuid === "b-2" ? other : BOOK, surveys: [] })),
    });
    const { result } = renderHook(() => useSurveys({ client }));
    await act(async () => {
      await result.current.openBook("b-1");
    });
    let archiving;
    await act(async () => {
      archiving = result.current.setBookArchived(true);
      await result.current.openBook("b-2");
    });
    await act(async () => {
      answer({ ...BOOK, archived: true });
      await archiving;
    });
    expect(result.current.bookUuid).toBe("b-2");
    expect(result.current.list.book.name).toBe("OTHER BOOK");
  });

  it("closes the book it archived while it is still the one open", async () => {
    const client = fakeClient({ archiveBook: vi.fn(async () => ({ ...BOOK, archived: true })) });
    const { result } = renderHook(() => useSurveys({ client }));
    await act(async () => {
      await result.current.openBook("b-1");
    });
    await act(async () => {
      await result.current.setBookArchived(true);
    });
    expect(result.current.bookUuid).toBeNull();
  });

  it("imports the old book into the open book, then reads the book again", async () => {
    const answer = { created: [{ uuid: "s-2" }], skipped: [], source: "book.pdf" };
    const client = fakeClient({ importBook: vi.fn(async () => answer) });
    const { result } = renderHook(() => useSurveys({ client }));
    await act(async () => {
      await result.current.openBook("b-1");
    });
    const file = new Blob(["PK"]);
    let got;
    await act(async () => {
      got = await result.current.importPages(file);
    });
    expect(got).toBe(answer);
    expect(client.importBook).toHaveBeenCalledWith("b-1", file);
    expect(client.getBook).toHaveBeenCalledTimes(2);
  });

  it("reads the book again while the server measures imported surveys, and stops when they are measured", async () => {
    vi.useFakeTimers();
    try {
      const measuring = { surveys: [{ uuid: "s-1", name: DONOVAN.name, imported: { state: "queued" } }] };
      const measured = { surveys: [{ uuid: "s-1", name: DONOVAN.name, imported: { state: "done" } }] };
      const client = fakeClient({ getBook: vi.fn(async () => ({ book: BOOK, ...measuring })) });
      const { result } = renderHook(() => useSurveys({ client }));
      await act(async () => {
        await result.current.openBook("b-1");
      });
      expect(client.getBook).toHaveBeenCalledTimes(1);
      client.getBook.mockImplementation(async () => ({ book: BOOK, ...measured }));
      await act(async () => {
        vi.advanceTimersByTime(IMPORT_POLL_MS);
      });
      expect(client.getBook).toHaveBeenCalledTimes(2);
      await act(async () => {
        vi.advanceTimersByTime(IMPORT_POLL_MS * 3);
      });
      expect(client.getBook).toHaveBeenCalledTimes(2);                          // measured: no more reading
    } finally {
      vi.useRealTimers();
    }
  });

  it("follows the open survey while its import is measured, keeping what was typed meanwhile", async () => {
    vi.useFakeTimers();
    try {
      const queued = surveyAt(1, { imported: { state: "queued" } });
      const boundary = { ...queued.diagram, status: "analyzed", analysis: { detectedLZ: [[34.6, -84.13], [34.6, -84.12], [34.59, -84.12]] } };
      const measured = surveyAt(3, { diagram: boundary, snapshot: { grid: "16S GD 63415 31995" }, updated_by: "EZ/PZ (import)",
        imported: { state: "done" } });
      const client = fakeClient({ getSurvey: vi.fn(async () => queued) });
      const { result } = await opened(client);
      act(() => result.current.setField("ppr", "Typed while it was measured"));
      client.getSurvey.mockImplementation(async () => measured);
      await act(async () => {
        vi.advanceTimersByTime(IMPORT_POLL_MS);
      });
      expect(result.current.survey.revision).toBe(3);                          // the next save is on the measuring's revision
      expect(result.current.draft.diagram).toEqual(boundary);
      expect(result.current.draft.field.ppr).toBe("Typed while it was measured");
      expect(result.current.dirty).toBe(true);
      const reads = client.getSurvey.mock.calls.length;
      await act(async () => {
        vi.advanceTimersByTime(IMPORT_POLL_MS * 3);
      });
      expect(client.getSurvey).toHaveBeenCalledTimes(reads);                    // measured: no more reading
    } finally {
      vi.useRealTimers();
    }
  });

  it("leaves the draft alone when the measuring and this person changed the same part", async () => {
    vi.useFakeTimers();
    try {
      const queued = surveyAt(1, { imported: { state: "queued" } });
      const client = fakeClient({ getSurvey: vi.fn(async () => queued) });
      const { result } = await opened(client);
      const drawn = { ...queued.diagram, analysis: { customLZ: [[1, 2], [3, 4], [5, 6]] } };
      act(() => result.current.setDiagram(drawn));
      client.getSurvey.mockImplementation(async () => surveyAt(2, { diagram: { ...queued.diagram, analysis: { detectedLZ: [[7, 8]] } },
        imported: { state: "measuring" } }));
      await act(async () => {
        vi.advanceTimersByTime(IMPORT_POLL_MS);
      });
      expect(result.current.survey.revision).toBe(1);                          // the next save meets theirs, and says so
      expect(result.current.draft.diagram).toEqual(drawn);
    } finally {
      vi.useRealTimers();
    }
  });

  it("lays a draft over a newer save part by part", () => {
    const base = surveyAt(1);
    const mine = { ...editableOf(base), name: "LZ MINE", field: { ...editableOf(base).field, ppr: "mine" } };
    const theirs = surveyAt(2, { field: { ...DONOVAN.field, surface: "gravel" }, snapshot: { grid: "x" } });
    const merged = rebaseDraft(base, mine, theirs);
    expect([merged.name, merged.field.ppr, merged.field.surface]).toEqual(["LZ MINE", "mine", "gravel"]);
    expect(rebaseDraft(base, mine, surveyAt(2, { name: "LZ THEIRS" }))).toBeNull();
    expect(rebaseDraft(base, mine, surveyAt(2, { name: "LZ MINE" })).name).toBe("LZ MINE");   // the same change on both sides
  });

  it("marks the import's notes checked without taking the rest of the answer as its copy", async () => {
    const client = fakeClient({
      getSurvey: vi.fn(async () => surveyAt(1, { imported: { state: "done", checked: false, to_check: 2 } })),
      markImportChecked: vi.fn(async () => surveyAt(5, { name: "SOMEONE ELSE'S", imported: { state: "done", checked: true, to_check: 0 } })),
    });
    const { result } = await opened(client);
    await act(async () => {
      await result.current.markImportChecked();
    });
    expect(result.current.survey.imported.checked).toBe(true);
    expect(result.current.survey.revision).toBe(1);
    expect(result.current.survey.name).toBe(DONOVAN.name);
  });

  it("asks how the book's PDF stands as the book opens, and again only while the server prints it", async () => {
    vi.useFakeTimers();
    try {
      const answers = [{ state: "none", pages: 1, available: true }];
      const client = fakeClient({
        bookPrint: vi.fn(async () => answers[answers.length - 1]),
        printBook: vi.fn(async () => ({ state: "printing", pages: 1, done_pages: 0, available: true })),
      });
      const { result } = renderHook(() => useSurveys({ client }));
      await act(async () => {
        await result.current.openBook("b-1");
      });
      expect(client.bookPrint).toHaveBeenCalledWith("b-1");
      expect(result.current.bookPrint.print.state).toBe("none");
      const asked = client.bookPrint.mock.calls.length;
      await act(async () => {
        vi.advanceTimersByTime(PRINT_POLL_MS * 2);
      });
      expect(client.bookPrint.mock.calls.length).toBe(asked);                     // not printing: nobody asks
      await act(async () => {
        await result.current.printBook();
      });
      expect(result.current.bookPrint.print.state).toBe("printing");
      answers.push({ state: "ready", pages: 1, size_bytes: 10, printed_at: "2026-10-27T00:00:00Z", available: true });
      await act(async () => {
        vi.advanceTimersByTime(PRINT_POLL_MS);
      });
      expect(result.current.bookPrint.print.state).toBe("ready");
      const after = client.bookPrint.mock.calls.length;
      await act(async () => {
        vi.advanceTimersByTime(PRINT_POLL_MS * 3);
      });
      expect(client.bookPrint.mock.calls.length).toBe(after);
    } finally {
      vi.useRealTimers();
    }
  });

  it("does not let a look at the PDF asked before Print undo it", async () => {
    let answerEarlier;
    const client = fakeClient({ printBook: vi.fn(async () => ({ state: "printing", pages: 1, done_pages: 0, available: true })) });
    const { result } = renderHook(() => useSurveys({ client }));
    await act(async () => {
      await result.current.openBook("b-1");
    });
    client.bookPrint.mockImplementation(() => new Promise((resolve) => { answerEarlier = resolve; }));
    let earlier;
    act(() => {
      earlier = result.current.refreshBookPrint();                           // a list refresh's look, on its way
    });
    await act(async () => {
      await result.current.printBook();
    });
    await act(async () => {
      answerEarlier({ state: "none", pages: 1, available: true });
      await earlier;
    });
    expect(result.current.bookPrint.print.state).toBe("printing");
  });

  it("stops asking about the PDF of a book that has gone, and says so", async () => {
    vi.useFakeTimers();
    try {
      const client = fakeClient({
        bookPrint: vi.fn(async () => ({ state: "printing", pages: 1, done_pages: 0, available: true })),
      });
      const { result } = renderHook(() => useSurveys({ client }));
      await act(async () => {
        await result.current.openBook("b-1");
      });
      client.bookPrint.mockImplementation(async () => { throw Object.assign(new Error("404"), { response: { status: 404, data: { code: "not_found" } } }); });
      await act(async () => {
        vi.advanceTimersByTime(PRINT_POLL_MS);
      });
      expect(result.current.bookPrint.status).toBe("gone");
      expect(result.current.bookPrint.error).toBe("That is no longer there, or you cannot open it.");
      const asked = client.bookPrint.mock.calls.length;
      await act(async () => {
        vi.advanceTimersByTime(PRINT_POLL_MS * 5);
      });
      expect(client.bookPrint.mock.calls.length).toBe(asked);
    } finally {
      vi.useRealTimers();
    }
  });

  it("asks about the PDF again once the book's own words change", async () => {
    const client = fakeClient();
    const { result } = renderHook(() => useSurveys({ client }));
    await act(async () => {
      await result.current.openBook("b-1");
    });
    const asked = client.bookPrint.mock.calls.length;
    await act(async () => {
      await result.current.renameBook("NEW NAME");
    });
    expect(client.bookPrint.mock.calls.length).toBe(asked + 1);
  });

  it("keeps a 3D capture with its camera, in place of the last", async () => {
    const pose = { headingDeg: 90, pitchDeg: -20, rangeM: 550, survey: { name: "GA_Statewide_B3_2018", year: 2018 } };
    const client = fakeClient({
      getSurvey: vi.fn(async () => surveyAt(1, { assets: [{ id: 2, kind: "view_3d" }, { id: 3, kind: "photo_north" }] })),
      uploadAsset: vi.fn(async () => ({ id: 7, kind: "view_3d", pose, survey: { status: "draft", revision: 1, was: 1 } })),
    });
    const { result } = await opened(client);
    const blob = new Blob(["png"], { type: "image/png" });
    await act(async () => {
      await result.current.captureView(blob, pose);
    });
    const [uuid, file, meta] = client.uploadAsset.mock.calls[0];
    expect(uuid).toBe("s-1");
    expect([file.name, file.type]).toEqual(["view-3d.png", "image/png"]);
    expect(meta).toEqual({ kind: "view_3d", caption: "", heading_deg: 90, taken_on: expect.stringMatching(/^\d{1,2} [A-Z]{3} \d{4}$/),
                           pose: JSON.stringify(pose) });
    expect(result.current.survey.assets.map((a) => a.id)).toEqual([3, 7]);       // the last capture is taken off, as on the server
  });

  it("names what someone else changed", () => {
    const base = surveyAt(1);
    expect(changedBetween(base, surveyAt(2))).toEqual([]);
    expect(changedBetween(base, surveyAt(2, { name: "LZ D", snapshot: { grid: "x" } }))).toEqual(["name", "measurements"]);
    expect(editableOf(null).field.grid).toBe("");
  });
});
