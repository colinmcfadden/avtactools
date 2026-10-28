import React from "react";
import { act, fireEvent, render, screen, within } from "@testing-library/react";
import SurveyBook from "./SurveyBook";
import TopBar from "../shell/TopBar";

const { readFixture } = require("../../contracts/readFixture");

// Leaflet draws nothing in jsdom; the map's own logic is surveyDiagram's, tested beside it.
jest.mock("./SurveyMap", () => ({ onSetTarget, onSetBoundary }) => (
  <div data-testid="survey-map">
    <button type="button" onClick={() => onSetTarget(34.596, -84.1278)}>map: target</button>
    <button type="button" onClick={() => onSetBoundary([[34.5965, -84.1283], [34.5965, -84.1273], [34.5955, -84.1273]])}>map: boundary</button>
  </div>
));

const DONOVAN = readFixture("surveys/donovan.json");

const COMPARISONS = [
  { key: "grid", label: "Grid", field: "16S GD 6341 3199", computed: "16S GD 63415 31994", apart: "1 m apart", disagrees: false, open: false, override: null, overrideStands: false, printed: "computed" },
  { key: "elevation", label: "Elevation", field: "1850", computed: "2061 ft MSL", apart: "211 ft apart", disagrees: true, open: true, override: null, overrideStands: false, printed: "computed" },
];

const survey = (changes = {}) => ({
  uuid: "s-1",
  hlz_id: "GA 034",
  name: "LZ DONOVAN",
  status: "draft",
  revision: 4,
  field: DONOVAN.field,
  overrides: {},
  diagram: { schemaVersion: 2, id: "survey-s-1", target: { lat: 34.596, lon: -84.1278, mgrs: "16S GD 63415 31994" }, analysis: {}, graphics: {} },
  snapshot: {
    grid: "16S GD 63415 31994",
    latLong: "N34 35.761 W084 07.668",
    elevation: { ft: 2061, m: 628.2, source: "local_highres_cog", dem: ["USGS_13_n35w085.tif"], resolutionM: 10.3, verticalDatum: "NAVD88" },
    size: { lengthFt: 610, widthFt: 380, axisDeg: 36, areaSqFt: 210000, areaAcres: 4.82 },
    fit: { aircraft: "UH-60L", count: 3 },
    slope: {
      headingDeg: 0, call: "within", exceeded: [], words: "Within limits at 000° (95th percentile): nose up 1.2°, nose down 0.8°, cross 4.1°.",
      limits: { noseUp: 15, noseDown: 6, cross: 15 },
      noseUp: { p95: 1.2, mean: 0.4, max: 3.0 }, noseDown: { p95: 0.8, mean: 0.3, max: 2.2 }, cross: { p95: 4.1, mean: 2.0, max: 16.6 },
      overall: { p95: 4.3, mean: 2.2, max: 16.6 },
    },
    terrain: null,
    problems: [],
    measuredAt: "2026-10-26T12:00:00Z",
    measuredBy: "CW3 Admin",
  },
  comparisons: COMPARISONS,
  disagreements: 1,
  assets: [],
  book: { uuid: "b-1", name: "AASF 1 HLZ SURVEY BOOK" },
  revisions: [],
  next_revision: 1,
  published_revision: null,
  suggested_changes: "New format. Grid refined; lat/long recomputed; elevation, size and slope from LZ/PZ.",
  updated_by: "CW3 Admin",
  archived: false,
  ...changes,
});

const BOOK = { uuid: "b-1", name: "AASF 1 HLZ SURVEY BOOK", unit_line: "AASF 1", team: null, can_edit: true, archived: false, published_count: 0, survey_count: 1, revision: 2, page_order: ["s-1"], updated_at: "2026-10-26T12:00:00Z" };
const ROW = { uuid: "s-1", hlz_id: "GA 034", name: "LZ DONOVAN", status: "draft", disagreements: 2, surveyed_on: "OCT 2023", grid: "16S GD 63415 31994", measured_at: "2026-10-26T12:00:00Z", updated_at: "2026-10-26T12:00:00Z", updated_by: "CW3 Admin", archived: false, published_revision: null };
const PAGE = "<!doctype html><html><body><div class=\"sheet\">LZ DONOVAN page</div></body></html>";

const fakeClient = (overrides = {}) => ({
  listBooks: jest.fn(async () => ({ books: [BOOK], teams: [{ id: 3, name: "B CO 1-171 AVN" }], can_make_for_no_team: true })),
  getBook: jest.fn(async () => ({ book: BOOK, surveys: [ROW] })),
  createBook: jest.fn(async ({ name, teamId }) => ({ ...BOOK, uuid: "b-2", name, team: teamId ? { id: teamId, name: "B CO 1-171 AVN" } : null })),
  archiveBook: jest.fn(),
  restoreBook: jest.fn(),
  submitSurvey: jest.fn(async (uuid, revision) => survey({ status: "in_review", revision: revision + 1 })),
  backToDraft: jest.fn(async (uuid, revision) => survey({ status: "draft", revision: revision + 1 })),
  publishSurvey: jest.fn(async (uuid, revision) => ({
    survey: survey({ status: "published", revision: revision + 1, published_revision: 1 }),
    revision: { number: 1, published_at: "2026-10-27T12:00:00Z", changes: "New format.", signoff: { name: "J. Doe" } },
  })),
  fetchPage: jest.fn(async () => PAGE),
  fetchPdf: jest.fn(async () => new Blob(["%PDF"], { type: "application/pdf" })),
  getSurvey: jest.fn(async () => survey()),
  createSurvey: jest.fn(async ({ name, hlzId }) => survey({ uuid: "s-2", name, hlz_id: hlzId, field: {}, snapshot: null, comparisons: [], revision: 1 })),
  saveSurvey: jest.fn(async (uuid, revision, body) => survey({ ...body, revision: revision + 1 })),
  measureSurvey: jest.fn(),
  archiveSurvey: jest.fn(async () => survey({ archived: true, revision: 5 })),
  restoreSurvey: jest.fn(),
  updateBook: jest.fn(),
  uploadAsset: jest.fn(),
  updateAsset: jest.fn(),
  removeAsset: jest.fn(),
  importBook: jest.fn(),
  markImportChecked: jest.fn(),
  bookPrint: jest.fn(async () => ({ state: "empty", pages: 0, done_pages: 0, available: true })),
  printBook: jest.fn(),
  fetchBookPdf: jest.fn(),
  ...overrides,
});

const renderBook = async (client = fakeClient(), onClose = jest.fn(), props = {}) => {
  render(<SurveyBook client={client} onClose={onClose} {...props} />);
  const book = await screen.findByRole("button", { name: "AASF 1 HLZ SURVEY BOOK" });
  await act(async () => {
    fireEvent.click(book);
  });
  await screen.findByText("LZ DONOVAN");
  return { client, onClose };
};

const openDonovan = async (client) => {
  const setup = await renderBook(client);
  await act(async () => {
    fireEvent.click(screen.getByRole("button", { name: "LZ DONOVAN" }));
  });
  await screen.findByLabelText("Grid");
  return setup;
};

beforeEach(() => {
  jest.spyOn(window, "alert").mockImplementation(() => {});
  jest.spyOn(window, "confirm").mockImplementation(() => true);
  jest.spyOn(window, "prompt").mockImplementation(() => null);
});

afterEach(() => {
  expect(window.alert).not.toHaveBeenCalled();
  expect(window.confirm).not.toHaveBeenCalled();
  expect(window.prompt).not.toHaveBeenCalled();
  jest.restoreAllMocks();
});

describe("the survey book", () => {
  it("lists every LZ with its status, what to look at and when it was surveyed", async () => {
    await renderBook();
    expect(screen.getByRole("heading", { name: "AASF 1 HLZ SURVEY BOOK" })).toBeInTheDocument();
    expect(screen.getByText(/No team · everyone with LZ surveys reads it · AASF 1 · you edit it/)).toBeInTheDocument();
    const row = screen.getByRole("button", { name: "LZ DONOVAN" }).closest("tr");
    expect(within(row).getByText("GA 034")).toBeInTheDocument();
    expect(within(row).getByText("Draft")).toBeInTheDocument();
    expect(within(row).getByText("2 disagreements")).toBeInTheDocument();
    expect(within(row).getByText("OCT 2023")).toBeInTheDocument();
  });

  it("says a survey never measured is not measured, not that it has nothing to look at", async () => {
    const client = fakeClient({
      getBook: jest.fn(async () => ({
        book: BOOK,
        surveys: [
          { ...ROW, disagreements: 0, surveyed_on: "", grid: "", measured_at: null },
          { ...ROW, uuid: "s-2", hlz_id: "GA 035", name: "LZ EAGLE", disagreements: 0, surveyed_on: "", grid: "" },
        ],
      })),
    });
    await renderBook(client);
    const never = screen.getByRole("button", { name: "LZ DONOVAN" }).closest("tr");
    expect(within(never).getByText("Not measured")).toBeInTheDocument();
    expect(within(never).queryByText("None")).not.toBeInTheDocument();
    const agrees = screen.getByRole("button", { name: "LZ EAGLE" }).closest("tr");
    expect(within(agrees).getByText("None")).toBeInTheDocument();
  });

  it("makes a new survey and opens it", async () => {
    const { client } = await renderBook();
    fireEvent.click(screen.getByRole("button", { name: "New survey" }));
    fireEvent.change(screen.getByLabelText("HLZ ID"), { target: { value: "ga 035" } });
    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "lz eagle" } });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Make the survey" }));
    });
    expect(client.createSurvey).toHaveBeenCalledWith({ book: "b-1", name: "LZ EAGLE", hlzId: "GA 035", field: {}, diagram: null });
    expect(await screen.findByRole("heading", { name: "GA 035 · LZ EAGLE" })).toBeInTheDocument();
  });

  it("refuses a new survey with no name, in words", async () => {
    const { client } = await renderBook();
    fireEvent.click(screen.getByRole("button", { name: "New survey" }));
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Make the survey" }));
    });
    expect(screen.getByText(/Give the LZ its name/)).toBeInTheDocument();
    expect(client.createSurvey).not.toHaveBeenCalled();
  });
});

describe("the survey editor", () => {
  it("shows the field survey in the book's sections, and what EZ/PZ measured", async () => {
    await openDonovan(fakeClient());
    for (const section of ["Location", "Approach and capacity", "Ground", "Hazards and notes", "Survey profile", "Surveyed"]) {
      expect(screen.getByRole("group", { name: section })).toBeInTheDocument();
    }
    expect(screen.getByLabelText("Grid")).toHaveValue("16S GD 6341 3199");
    expect(screen.getByLabelText("Hazards")).toHaveValue("Tall dead trees in center of LZ");
    expect(screen.getByLabelText("PPR")).toHaveValue("5th RTB Ops 706-864-3327 x5233");
    expect(screen.getAllByText("2061 ft MSL")).toHaveLength(2);           // measured, and against the survey's
    expect(screen.getByText(/USGS_13_n35w085.tif · 10.3 m · NAVD88/)).toBeInTheDocument();
    expect(screen.getByText(/Within limits at 000°/)).toBeInTheDocument();
    expect(screen.getByText("3 UH-60Ls by area")).toBeInTheDocument();
    expect(screen.getByTestId("survey-map")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "The page as saved" })).toBeInTheDocument();
  });

  it("saves what is typed, naming the revision it was opened at", async () => {
    const client = fakeClient();
    await openDonovan(client);
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
    fireEvent.change(screen.getByLabelText("Surface"), { target: { value: "Grass, short" } });
    expect(screen.getByText("Changes not saved.")).toBeInTheDocument();
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Save" }));
    });
    expect(client.saveSurvey).toHaveBeenCalledWith("s-1", 4, expect.objectContaining({ field: expect.objectContaining({ surface: "Grass, short" }) }));
    expect(screen.getByText("Saved · revision 5")).toBeInTheDocument();
  });

  it("asks before leaving changes unsaved, and Don't save leaves them", async () => {
    const client = fakeClient();
    await openDonovan(client);
    fireEvent.change(screen.getByLabelText("Surface"), { target: { value: "Gravel" } });
    fireEvent.click(screen.getByRole("button", { name: "Book" }));
    expect(screen.getByRole("dialog", { name: "Save your changes to this survey?" })).toBeInTheDocument();
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Don't save" }));
    });
    expect(await screen.findByRole("button", { name: "New survey" })).toBeInTheDocument();
    expect(client.saveSurvey).not.toHaveBeenCalled();
  });

  it("puts a target and a boundary from the map into the diagram it saves", async () => {
    const client = fakeClient();
    await openDonovan(client);
    fireEvent.click(screen.getByRole("button", { name: "map: boundary" }));
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Save" }));
    });
    const body = client.saveSurvey.mock.calls[0][2];
    expect(body.diagram.analysis.detectedLZ).toHaveLength(3);
    expect(body.diagram.analysis.terrainData).toBeNull();
    expect(body.diagram.flightData.landing_hdg).toBe("000°");
  });

  it("shows field against computed, and settles a disagreement", async () => {
    const client = fakeClient();
    await openDonovan(client);
    const row = screen.getByRole("rowheader", { name: "Elevation" }).closest("tr");
    expect(within(row).getByText("211 ft apart")).toBeInTheDocument();
    expect(within(row).getByText("Disagree")).toBeInTheDocument();
    await act(async () => {
      fireEvent.click(within(row).getByRole("button", { name: "Accept computed" }));
    });
    expect(client.saveSurvey.mock.calls[0][2].overrides).toEqual({ elevation: { choice: "computed", reason: "" } });
  });

  it("asks for a reason to print the survey's value over the computed one", async () => {
    const client = fakeClient();
    await openDonovan(client);
    const row = screen.getByRole("rowheader", { name: "Elevation" }).closest("tr");
    fireEvent.click(within(row).getByRole("button", { name: "Print survey's…" }));
    const dialog = screen.getByRole("dialog", { name: "Print the survey's elevation" });
    await act(async () => {
      fireEvent.click(within(dialog).getByRole("button", { name: "Print the survey's" }));
    });
    expect(within(dialog).getByText(/Say why/)).toBeInTheDocument();
    expect(client.saveSurvey).not.toHaveBeenCalled();
    fireEvent.change(within(dialog).getByLabelText("Reason"), { target: { value: "Surveyed by GPS on the pad" } });
    await act(async () => {
      fireEvent.click(within(dialog).getByRole("button", { name: "Print the survey's" }));
    });
    expect(client.saveSurvey.mock.calls[0][2].overrides).toEqual({ elevation: { choice: "field", reason: "Surveyed by GPS on the pad" } });
  });

  it("says when someone else saved first, and keeps what was typed", async () => {
    const theirs = survey({ revision: 5, updated_by: "SFC Other", field: { ...DONOVAN.field, hazards: "Wires" } });
    const client = fakeClient({
      saveSurvey: jest.fn(async () => { throw Object.assign(new Error("409"), { response: { status: 409, data: { code: "revision_conflict", server: theirs } } }); }),
    });
    await openDonovan(client);
    fireEvent.change(screen.getByLabelText("Surface"), { target: { value: "Gravel" } });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Save" }));
    });
    expect(screen.getByRole("alert")).toHaveTextContent("SFC Other saved this survey while you were editing (revision 5: hazards). What you typed is still here.");
    expect(screen.getByLabelText("Surface")).toHaveValue("Gravel");
  });

  it("archives only after asking", async () => {
    const client = fakeClient();
    await openDonovan(client);
    fireEvent.click(screen.getByRole("button", { name: "Archive" }));
    const dialog = screen.getByRole("dialog", { name: "Archive LZ DONOVAN?" });
    expect(client.archiveSurvey).not.toHaveBeenCalled();
    await act(async () => {
      fireEvent.click(within(dialog).getByRole("button", { name: "Archive" }));
    });
    expect(client.archiveSurvey).toHaveBeenCalledWith("s-1");
    expect(screen.getByText(/Archived: kept, but not in the book/)).toBeInTheDocument();
  });
});

describe("the account menu", () => {
  const openAccount = () => fireEvent.click(screen.getByRole("button", { name: /Account:/ }));

  it("offers the survey books to whoever the app gives the way in to (an admin, or LZ surveys ticked)", () => {
    const onOpenSurveys = jest.fn();
    render(<TopBar user={{ name: "Pilot", email: "p@example.mil" }} onOpenSurveys={onOpenSurveys} />);
    openAccount();
    fireEvent.click(screen.getByRole("menuitem", { name: /LZ survey books/ }));
    expect(onOpenSurveys).toHaveBeenCalled();
  });

  it("not to anyone else", () => {
    render(<TopBar user={{ name: "Pilot", email: "p@example.mil" }} isAdmin={false} />);
    openAccount();
    expect(screen.queryByRole("menuitem", { name: /LZ survey book/ })).toBeNull();
  });
});

describe("the books", () => {
  it("lists the books with who they are for and what this person may do in each", async () => {
    const client = fakeClient({
      listBooks: jest.fn(async () => ({
        books: [BOOK, { ...BOOK, uuid: "b-9", name: "B CO HLZ BOOK", team: { id: 3, name: "B CO 1-171 AVN" }, can_edit: false, published_count: 7 }],
        teams: [], can_make_for_no_team: false,
      })),
    });
    render(<SurveyBook client={client} onClose={jest.fn()} />);
    const row = (await screen.findByRole("button", { name: "B CO HLZ BOOK" })).closest("tr");
    expect(within(row).getByText("B CO 1-171 AVN")).toBeInTheDocument();
    expect(within(row).getByText("Read")).toBeInTheDocument();
    expect(within(row).getByText("7")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "New book" })).toBeNull();     // no team to make one for
  });

  it("makes a book for a team and opens it", async () => {
    const client = fakeClient();
    render(<SurveyBook client={client} onClose={jest.fn()} isAdmin />);
    fireEvent.click(await screen.findByRole("button", { name: "New book" }));
    const dialog = screen.getByRole("dialog", { name: "New survey book" });
    fireEvent.change(within(dialog).getByLabelText("Name"), { target: { value: "b co hlz book" } });
    fireEvent.change(within(dialog).getByLabelText("For"), { target: { value: "3" } });
    expect(within(dialog).getByText(/B CO 1-171 AVN's owner and admins make and publish its surveys/)).toBeInTheDocument();
    await act(async () => {
      fireEvent.click(within(dialog).getByRole("button", { name: "Make the book" }));
    });
    expect(client.createBook).toHaveBeenCalledWith({ name: "B CO HLZ BOOK", unitLine: "", teamId: 3 });
    expect(client.getBook).toHaveBeenCalledWith("b-2", { archived: false });
  });
});

describe("a reader", () => {
  const READER_BOOK = { ...BOOK, can_edit: false, published_count: 1 };
  const PUBLISHED = { uuid: "s-1", hlz_id: "GA 034", name: "LZ DONOVAN", status: "published", published_revision: 2, published_at: "2026-10-27T12:00:00Z", signoff: { rank: "CW4", name: "J. Doe", title: "SIP", date: "27 OCT 2026" }, changes: "Surface regraded." };

  const readerClient = () => fakeClient({
    listBooks: jest.fn(async () => ({ books: [READER_BOOK], teams: [], can_make_for_no_team: false })),
    getBook: jest.fn(async () => ({ book: READER_BOOK, surveys: [PUBLISHED] })),
    getSurvey: jest.fn(async () => ({ ...PUBLISHED, book: { uuid: "b-1", name: BOOK.name }, revisions: [
      { number: 1, published_at: "2026-10-01T12:00:00Z", published_by: "CW3 Admin", signoff: { name: "J. Doe" }, changes: "New format." },
      { number: 2, published_at: "2026-10-27T12:00:00Z", published_by: "CW3 Admin", signoff: PUBLISHED.signoff, changes: "Surface regraded." },
    ] })),
  });

  it("sees the published pages, never a draft, and opens one on its page", async () => {
    const client = readerClient();
    await renderBook(client);
    expect(screen.queryByRole("button", { name: "New survey" })).toBeNull();
    const row = screen.getByRole("button", { name: "LZ DONOVAN" }).closest("tr");
    expect(within(row).getByText("REV 2")).toBeInTheDocument();
    expect(within(row).getByText("CW4 J. Doe")).toBeInTheDocument();
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "LZ DONOVAN" }));
    });
    const frame = await screen.findByTitle("LZ DONOVAN, REV 2");
    expect(frame).toHaveAttribute("sandbox", "");
    expect(frame.getAttribute("srcdoc")).toContain("LZ DONOVAN page");
    expect(client.fetchPage).toHaveBeenLastCalledWith("s-1", expect.objectContaining({ rev: 2 }));
    await act(async () => {
      fireEvent.click(screen.getAllByRole("button", { name: "Page" })[1]);   // the revisions are newest first
    });
    expect(client.fetchPage).toHaveBeenLastCalledWith("s-1", expect.objectContaining({ rev: 1 }));
    expect(screen.queryByLabelText("Grid")).toBeNull();
  });

  it("never shows a page asked for before another revision was chosen", async () => {
    const pending = {};
    const client = readerClient();
    client.fetchPage = jest.fn((uuid, { rev }) => new Promise((resolve) => { pending[rev] = resolve; }));
    await renderBook(client);
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "LZ DONOVAN" }));
    });
    await screen.findByText("Drawing the page…");
    await act(async () => {
      fireEvent.click(screen.getAllByRole("button", { name: "Page" })[1]);   // REV 1, while REV 2 is still drawing
    });
    await act(async () => {
      pending[1]("<p>REV ONE</p>");
    });
    await act(async () => {
      pending[2]("<p>REV TWO</p>");
    });
    const frame = screen.getByTitle("LZ DONOVAN, REV 1");
    expect(frame.getAttribute("srcdoc")).toBe("<p>REV ONE</p>");
    expect(screen.queryByTitle("LZ DONOVAN, REV 2")).toBeNull();
  });

  it("is shown when the book last published, not when its drafts last changed", async () => {
    const client = readerClient();
    const { revision: _r, updated_at: _u, updated_by: _b, page_order: _o, survey_count: _c, ...readerBook } = READER_BOOK;
    client.listBooks = jest.fn(async () => ({ books: [{ ...readerBook, published_at: "2026-10-27T12:00:00Z" }], teams: [], can_make_for_no_team: false }));
    render(<SurveyBook client={client} onClose={jest.fn()} />);
    const row = (await screen.findByRole("button", { name: "AASF 1 HLZ SURVEY BOOK" })).closest("tr");
    expect(within(row).getByText("2026-10-27")).toBeInTheDocument();
  });
});

describe("publishing", () => {
  it("sends a measured draft for review, then signs it off as REV 1", async () => {
    const client = fakeClient();
    await openDonovan(client);
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Send for review" }));
    });
    expect(client.submitSurvey).toHaveBeenCalledWith("s-1", 4);
    fireEvent.click(screen.getByRole("button", { name: "Publish…" }));
    const dialog = screen.getByRole("dialog", { name: "Publish LZ DONOVAN as REV 1" });
    expect(within(dialog).getByLabelText("CHANGES line")).toHaveValue(survey().suggested_changes);
    await act(async () => {
      fireEvent.click(within(dialog).getByRole("button", { name: "Publish REV 1" }));
    });
    expect(within(dialog).getByText(/Say who signed it off/)).toBeInTheDocument();
    expect(client.publishSurvey).not.toHaveBeenCalled();
    fireEvent.change(within(dialog).getByLabelText("Rank"), { target: { value: "cw4" } });
    fireEvent.change(within(dialog).getByLabelText("Approver's name"), { target: { value: "J. Doe" } });
    await act(async () => {
      fireEvent.click(within(dialog).getByRole("button", { name: "Publish REV 1" }));
    });
    const [uuid, revision, body] = client.publishSurvey.mock.calls[0];
    expect([uuid, revision]).toEqual(["s-1", 5]);
    expect(body.signoff).toEqual(expect.objectContaining({ name: "J. Doe", rank: "CW4" }));
    expect(body.signoff.date).toMatch(/^\d{2} [A-Z]{3} \d{4}$/);
    expect(body.opacity).toBe(55);
    expect(await screen.findByText(/Published as REV 1: readers see this page/)).toBeInTheDocument();
  });

  it("holds a survey measured before it changed back from review and publishing, and says why", async () => {
    const stale = ["The best approach changed after it was measured: measured at 090°, now 270°."];
    await openDonovan(fakeClient({ getSurvey: jest.fn(async () => survey({ stale })) }));
    expect(screen.getByRole("button", { name: "Send for review" })).toBeDisabled();
    expect(screen.getByText(`Measure the LZ again first. ${stale[0]}`)).toBeInTheDocument();
    expect(screen.getByText(/Measured before the survey changed: measure it again/)).toBeInTheDocument();
  });

  it("does not publish a survey under review that was measured before it changed", async () => {
    const stale = ["The target moved after it was measured."];
    await openDonovan(fakeClient({ getSurvey: jest.fn(async () => survey({ status: "in_review", stale })) }));
    expect(screen.getByRole("button", { name: "Publish…" })).toBeDisabled();
    expect(screen.getByText(`Measure the LZ again first. ${stale[0]}`)).toBeInTheDocument();
  });

  it("does not send an unmeasured draft for review, and says why", async () => {
    await openDonovan(fakeClient({ getSurvey: jest.fn(async () => survey({ snapshot: null, comparisons: [] })) }));
    expect(screen.getByRole("button", { name: "Send for review" })).toBeDisabled();
    expect(screen.getByText("Measure the LZ first.")).toBeInTheDocument();
  });

  it("shows the page as saved, and says plainly when the server cannot print the PDF", async () => {
    const pdfUnavailable = Object.assign(new Error("503"), {
      response: { status: 503, data: new Blob([JSON.stringify({ code: "pdf_unavailable" })], { type: "application/json" }) },
    });
    const client = fakeClient({ fetchPdf: jest.fn(async () => { throw pdfUnavailable; }) });
    await openDonovan(client);
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Show the page" }));
    });
    expect(screen.getByTitle("LZ DONOVAN, the page as saved").getAttribute("srcdoc")).toContain("LZ DONOVAN page");
    await act(async () => {
      fireEvent.click(screen.getAllByRole("button", { name: "Download PDF" })[0]);
    });
    expect(await screen.findByText(/PDFs are not printed on this server yet/)).toBeInTheDocument();
  });
});

describe("importing the old book", () => {
  const IMPORTED = {
    source: "LZ-SURVEYS-OCT-2023.pdf", page: 5, state: "done", reason: null, to_check: 3, checked: false,
    notes: { unread: ["Best Exit"], blank: ["Surface Condition"], warnings: ["The book's grid and lat/long are 527 m apart: the target is at the grid. Check which is right."] },
    imported_by: "CW3 Admin", imported_at: "2026-10-27T01:00:00Z", checked_by: null, checked_at: null,
  };

  it("sends the importer's bundle to the book and says what it made", async () => {
    const client = fakeClient({
      importBook: jest.fn(async () => ({
        source: "LZ-SURVEYS-OCT-2023.pdf",
        created: [{ uuid: "s-2" }, { uuid: "s-3" }],
        skipped: [{ page: 5, hlz_id: "GA 034", name: "LZ DONOVAN", reason: "GA 034 is in the book already: left as it is." }],
      })),
    });
    await renderBook(client);
    fireEvent.click(screen.getByRole("button", { name: "Import the old book" }));
    const dialog = screen.getByRole("dialog", { name: "Import the old book" });
    expect(within(dialog).getByText(/tools\/import_survey_book\.py/)).toBeInTheDocument();
    await act(async () => {
      fireEvent.click(within(dialog).getByRole("button", { name: "Import" }));
    });
    expect(within(dialog).getByText("Choose the bundle the importer wrote.")).toBeInTheDocument();
    expect(client.importBook).not.toHaveBeenCalled();
    const bundle = new File(["PK"], "book-bundle.zip", { type: "application/zip" });
    fireEvent.change(within(dialog).getByLabelText("Bundle"), { target: { files: [bundle] } });
    const reads = client.getBook.mock.calls.length;
    await act(async () => {
      fireEvent.click(within(dialog).getByRole("button", { name: "Import" }));
    });
    expect(client.importBook).toHaveBeenCalledWith("b-1", bundle);
    const done = screen.getByRole("dialog", { name: "The old book is imported" });
    expect(within(done).getByText(/2 pages/)).toBeInTheDocument();
    expect(within(done).getByText(/LZ-SURVEYS-OCT-2023\.pdf are drafts in AASF 1 HLZ SURVEY BOOK/)).toBeInTheDocument();
    expect(within(done).getByText(/Page 5, GA 034 LZ DONOVAN: GA 034 is in the book already/)).toBeInTheDocument();
    expect(client.getBook.mock.calls.length).toBeGreaterThan(reads);           // the list is read again
    expect(within(done).getByRole("button", { name: "Done" })).toHaveFocus();   // not lost with the Import button it replaced
    fireEvent.click(within(done).getByRole("button", { name: "Done" }));
    expect(screen.queryByRole("dialog", { name: "The old book is imported" })).toBeNull();
  });

  it("lists two warnings that read alike, each once", async () => {
    const twice = { ...IMPORTED, notes: { unread: [], blank: [], warnings: ["A photo could not be read (bad) and was left out.",
      "A photo could not be read (bad) and was left out."] } };
    const errors = jest.spyOn(console, "error").mockImplementation(() => {});
    try {
      await openDonovan(fakeClient({ getSurvey: jest.fn(async () => survey({ imported: twice })) }));
      const panel = screen.getByRole("region", { name: "From the old book" });
      expect(within(panel).getAllByText("A photo could not be read (bad) and was left out.")).toHaveLength(2);
      expect(errors.mock.calls.some((call) => String(call[0]).includes("same key"))).toBe(false);
    } finally {
      errors.mockRestore();
    }
  });

  it("says why a bundle was refused, in the server's words for it", async () => {
    const refused = Object.assign(new Error("400"), {
      response: { status: 400, data: { code: "invalid_bundle", error: "That is not a bundle from tools/import_survey_book.py: it is not a zip." } },
    });
    const client = fakeClient({ importBook: jest.fn(async () => { throw refused; }) });
    await renderBook(client);
    fireEvent.click(screen.getByRole("button", { name: "Import the old book" }));
    const dialog = screen.getByRole("dialog", { name: "Import the old book" });
    fireEvent.change(within(dialog).getByLabelText("Bundle"), { target: { files: [new File(["%PDF"], "book.pdf")] } });
    await act(async () => {
      fireEvent.click(within(dialog).getByRole("button", { name: "Import" }));
    });
    expect(within(dialog).getByRole("alert")).toHaveTextContent("it is not a zip");
  });

  it("shows how each imported survey stands while the server measures it", async () => {
    const rows = [
      { ...ROW, measured_at: null, disagreements: 0, imported: { ...IMPORTED, state: "queued", to_check: 1 } },
      { ...ROW, uuid: "s-2", name: "LZ BIG STAMP", measured_at: null, disagreements: 0, imported: { ...IMPORTED, state: "no_boundary", to_check: 0 } },
      { ...ROW, uuid: "s-3", name: "LZ CAVENDER", disagreements: 0, imported: { ...IMPORTED, to_check: 2 } },
    ];
    await renderBook(fakeClient({ getBook: jest.fn(async () => ({ book: BOOK, surveys: rows })) }));
    const cell = (name) => screen.getByRole("button", { name }).closest("tr");
    expect(within(cell("LZ DONOVAN")).getByText("Waiting to be measured")).toBeInTheDocument();
    expect(within(cell("LZ DONOVAN")).getByText("1 import note to check")).toBeInTheDocument();
    expect(within(cell("LZ DONOVAN")).queryByText("Not measured")).toBeNull();
    expect(within(cell("LZ BIG STAMP")).getByText("No boundary found: draw it")).toBeInTheDocument();
    expect(within(cell("LZ CAVENDER")).getByText("2 import notes to check")).toBeInTheDocument();
    expect(within(cell("LZ CAVENDER")).queryByText("None")).toBeNull();
  });

  it("shows a survey's notes from the old book in its editor, until they are checked", async () => {
    const client = fakeClient({
      getSurvey: jest.fn(async () => survey({ imported: IMPORTED })),
      markImportChecked: jest.fn(async () => survey({
        revision: 9, imported: { ...IMPORTED, to_check: 0, checked: true, checked_by: "CW3 Admin", checked_at: "2026-10-27T02:00:00Z" },
      })),
    });
    await openDonovan(client);
    const panel = screen.getByRole("region", { name: "From the old book" });
    expect(within(panel).getByText(/Page 5 of LZ-SURVEYS-OCT-2023\.pdf, imported 2026-10-27 by CW3 Admin/)).toBeInTheDocument();
    expect(within(panel).getByText("Not on the page: Best Exit.")).toBeInTheDocument();
    expect(within(panel).getByText("Blank on the page: Surface Condition.")).toBeInTheDocument();
    expect(within(panel).getByText(/527 m apart/)).toBeInTheDocument();
    await act(async () => {
      fireEvent.click(within(panel).getByRole("button", { name: "Mark as checked" }));
    });
    expect(client.markImportChecked).toHaveBeenCalledWith("s-1");
    expect(within(panel).getByText("Checked by CW3 Admin on 2026-10-27.")).toHaveFocus();   // the button it replaces had it
    expect(within(panel).queryByRole("button", { name: "Mark as checked" })).toBeNull();
    expect(screen.getByText(/Saved · revision 4/)).toBeInTheDocument();          // not a change to the survey: its revision stays
  });
});

describe("the book's PDF", () => {
  const READY = { state: "ready", pages: 2, done_pages: 0, size_bytes: 3355443, printed_at: "2026-10-27T01:00:00Z", available: true };

  it("says it holds the published pages when nothing is published", async () => {
    await renderBook();
    const panel = screen.getByRole("region", { name: "The book's PDF" });
    expect(within(panel).getByText(/nothing is published yet/)).toBeInTheDocument();
    expect(within(panel).queryByRole("button")).toBeNull();
  });

  it("is printed when asked, said while it prints, and downloaded once printed", async () => {
    const answers = [{ state: "none", pages: 2, done_pages: 0, available: true }];
    const client = fakeClient({
      bookPrint: jest.fn(async () => answers[answers.length - 1]),
      printBook: jest.fn(async () => ({ state: "printing", pages: 2, done_pages: 0, available: true })),
      fetchBookPdf: jest.fn(async () => new Blob(["%PDF"], { type: "application/pdf" })),
    });
    await renderBook(client);
    const panel = await screen.findByRole("region", { name: "The book's PDF" });
    await act(async () => {
      fireEvent.click(within(panel).getByRole("button", { name: "Print the book (2 pages)" }));
    });
    expect(client.printBook).toHaveBeenCalledWith("b-1");
    expect(within(panel).getByText("Printing the book: 2 pages to draw…")).toBeInTheDocument();
    answers.push(READY);
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Refresh the list" }));
    });
    expect(within(panel).getByText(/2 pages with its cover and index · 3.2 MB · printed 2026-10-27/)).toBeInTheDocument();
    const created = jest.fn(() => "blob:book");
    Object.assign(URL, { createObjectURL: created, revokeObjectURL: jest.fn() });
    const clicked = jest.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});   // jsdom does not navigate
    await act(async () => {
      fireEvent.click(within(panel).getByRole("button", { name: "Download the book" }));
    });
    expect(client.fetchBookPdf).toHaveBeenCalledWith("b-1");
    expect(created).toHaveBeenCalled();
    expect(clicked).toHaveBeenCalled();
  });

  it("says why it could not be printed, and offers to try again", async () => {
    const client = fakeClient({
      bookPrint: jest.fn(async () => ({ state: "failed", pages: 2, done_pages: 0, error: "The printer could not print the book. Try again in a moment.", available: true })),
      printBook: jest.fn(async () => { throw Object.assign(new Error("503"), { response: { status: 503, data: { code: "pdf_unavailable" } } }); }),
    });
    await renderBook(client);
    const panel = await screen.findByRole("region", { name: "The book's PDF" });
    expect(within(panel).getByText("The printer could not print the book. Try again in a moment.")).toBeInTheDocument();
    await act(async () => {
      fireEvent.click(within(panel).getByRole("button", { name: "Try again" }));
    });
    expect(within(panel).getByText(/PDFs are not printed on this server yet/)).toBeInTheDocument();
  });

  it("says when the server prints no PDFs", async () => {
    await renderBook(fakeClient({ bookPrint: jest.fn(async () => ({ state: "none", pages: 1, done_pages: 0, available: false })) }));
    const panel = await screen.findByRole("region", { name: "The book's PDF" });
    expect(within(panel).getByText(/not printed on this server yet/)).toBeInTheDocument();
    expect(within(panel).queryByRole("button")).toBeNull();
  });

  it("lets a reader download the book once printed", async () => {
    const client = fakeClient({
      getBook: jest.fn(async () => ({ book: { ...BOOK, can_edit: false }, surveys: [{ ...ROW, status: "published", published_revision: 1 }] })),
      bookPrint: jest.fn(async () => READY),
      fetchBookPdf: jest.fn(async () => new Blob(["%PDF"], { type: "application/pdf" })),
    });
    await renderBook(client);
    const panel = await screen.findByRole("region", { name: "The book's PDF" });
    Object.assign(URL, { createObjectURL: jest.fn(() => "blob:book"), revokeObjectURL: jest.fn() });
    const clicked = jest.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
    await act(async () => {
      fireEvent.click(within(panel).getByRole("button", { name: "Download the book" }));
    });
    expect(client.fetchBookPdf).toHaveBeenCalledWith("b-1");
    expect(clicked).toHaveBeenCalled();
  });

  it("says why how the PDF stands could not be read", async () => {
    await renderBook(fakeClient({ bookPrint: jest.fn(async () => { throw new Error("offline"); }) }));
    const panel = await screen.findByRole("region", { name: "The book's PDF" });
    expect(within(panel).getByText("No connection to the server. Try again when you have one.")).toBeInTheDocument();
  });
});

