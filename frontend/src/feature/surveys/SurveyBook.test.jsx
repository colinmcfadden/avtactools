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
  updated_by: "CW3 Admin",
  archived: false,
  ...changes,
});

const fakeClient = (overrides = {}) => ({
  listSurveys: jest.fn(async () => ({
    book: { name: "AASF 1 HLZ SURVEY BOOK", revision: 2, page_order: ["s-1"] },
    surveys: [{ uuid: "s-1", hlz_id: "GA 034", name: "LZ DONOVAN", status: "draft", disagreements: 2, surveyed_on: "OCT 2023", grid: "16S GD 63415 31994", measured_at: "2026-10-26T12:00:00Z", updated_at: "2026-10-26T12:00:00Z", updated_by: "CW3 Admin", archived: false }],
  })),
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
  ...overrides,
});

const renderBook = async (client = fakeClient(), onClose = jest.fn()) => {
  render(<SurveyBook client={client} onClose={onClose} />);
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
    const row = screen.getByRole("button", { name: "LZ DONOVAN" }).closest("tr");
    expect(within(row).getByText("GA 034")).toBeInTheDocument();
    expect(within(row).getByText("Draft")).toBeInTheDocument();
    expect(within(row).getByText("2 disagreements")).toBeInTheDocument();
    expect(within(row).getByText("OCT 2023")).toBeInTheDocument();
  });

  it("makes a new survey and opens it", async () => {
    const { client } = await renderBook();
    fireEvent.click(screen.getByRole("button", { name: "New survey" }));
    fireEvent.change(screen.getByLabelText("HLZ ID"), { target: { value: "ga 035" } });
    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "lz eagle" } });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Make the survey" }));
    });
    expect(client.createSurvey).toHaveBeenCalledWith({ name: "LZ EAGLE", hlzId: "GA 035", field: {}, diagram: null });
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
    expect(screen.getByRole("heading", { name: "Page preview" })).toBeInTheDocument();
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

  it("offers the survey book to an admin", () => {
    const onOpenSurveys = jest.fn();
    render(<TopBar user={{ name: "CW3 Admin", email: "a@example.mil" }} isAdmin onOpenSurveys={onOpenSurveys} />);
    openAccount();
    fireEvent.click(screen.getByRole("menuitem", { name: /LZ survey book/ }));
    expect(onOpenSurveys).toHaveBeenCalled();
  });

  it("not to anyone else", () => {
    render(<TopBar user={{ name: "Pilot", email: "p@example.mil" }} onOpenSurveys={jest.fn()} />);
    openAccount();
    expect(screen.queryByRole("menuitem", { name: /LZ survey book/ })).toBeNull();
  });
});
