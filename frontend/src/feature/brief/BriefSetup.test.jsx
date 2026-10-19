import React from "react";
import { act, fireEvent, render, screen, within } from "@testing-library/react";
import BriefSetup from "./BriefSetup";
import BriefMenu from "./BriefMenu";
import { useBrief } from "./useBrief";
import * as briefApi from "./briefApi";

jest.mock("./briefApi", () => ({
  listBriefs: jest.fn(),
  getBrief: jest.fn(),
  createBrief: jest.fn(),
  updateBrief: jest.fn(),
  weatherAt: jest.fn(),
  printBrief: jest.fn(),
}));

const fixture = require("../../../../contracts/fixtures/brief/compile.json");

const OP_DK = fixture.cases[0].plan;
const DIAGRAMS = OP_DK.lzs.map((lz) => ({ ...lz.diagram, id: lz.id }));
const ROUTE_SETS = OP_DK.routeSets.map((set) => ({ key: set.id, routes: set.data.routes }));

// The brief as App holds it, and the last document it was given, so a test can say what a click changed.
let lastDoc = null;
const Harness = ({ onClose = () => {} }) => {
  const brief = useBrief({ enabled: true, active: true, user: { id: 7, name: "Colin M." }, session: { name: "", diagrams: DIAGRAMS, routeSets: ROUTE_SETS } });
  lastDoc = brief.doc;
  return <BriefSetup brief={brief} title="this session" threatCount={0} onClose={onClose} />;
};

beforeEach(() => {
  jest.useFakeTimers();
  localStorage.clear();
  lastDoc = null;
  Object.values(briefApi).forEach((fn) => fn?.mockReset?.());
  briefApi.weatherAt.mockResolvedValue(null);
  briefApi.createBrief.mockResolvedValue({ id: 3 });
  briefApi.updateBrief.mockResolvedValue({ id: 3 });
});
afterEach(() => jest.useRealTimers());


describe("brief setup, step 1", () => {
  it("lists the flow in route order and moves an item when asked", () => {
    render(<Harness />);
    expect(screen.getByRole("dialog", { name: "Brief setup · this session" })).toBeInTheDocument();
    const titles = () => [...document.querySelectorAll(".brief-flow__title")].map((n) => n.textContent);
    expect(titles().slice(0, 3)).toEqual(["ALDERAAN", "LZ BLUEBIRD", "LZ HAWK"]);
    fireEvent.click(screen.getByRole("button", { name: "Move LZ HAWK up" }));
    expect(titles().slice(0, 3)).toEqual(["ALDERAAN", "LZ HAWK", "LZ BLUEBIRD"]);
    expect(lastDoc.flow.slice(0, 3).map((f) => f.ref)).toEqual(["route:set-opdk/r-alderaan", "lz:lz-hawk", "lz:lz-bluebird"]);
    fireEvent.click(screen.getByRole("button", { name: "Reset to route order" }));
    expect(titles().slice(0, 3)).toEqual(["ALDERAAN", "LZ BLUEBIRD", "LZ HAWK"]);
    expect(lastDoc.flow).toBeUndefined();
  });

  it("changes an LZ/PZ's role from its badge", () => {
    render(<Harness />);
    fireEvent.change(screen.getByRole("combobox", { name: "Role of LZ DECOY" }), { target: { value: "FALSE_LZ" } });
    expect(lastDoc.flow.find((f) => f.ref === "lz:lz-decoy")).toEqual({ ref: "lz:lz-decoy", role: "FALSE_LZ" });
  });

  it("keeps a typed constant, removes one, and adds a suggestion", () => {
    render(<Harness />);
    const terrain = screen.getByRole("textbox", { name: "Terrain flight airspeed" });
    fireEvent.change(terrain, { target: { value: "100 KIAS" } });
    fireEvent.blur(terrain);
    expect(lastDoc.constants.find((c) => c.id === "terrainFlightAirspeed")).toEqual({ id: "terrainFlightAirspeed", value: "100 KIAS" });
    fireEvent.click(screen.getByRole("button", { name: "Remove Enroute formation" }));
    expect(lastDoc.constants.map((c) => c.id)).not.toContain("enrouteFormation");
    fireEvent.click(screen.getByRole("button", { name: "+ Bingo fuel" }));
    expect(lastDoc.constants.map((c) => c.id)).toContain("bingoFuel");
  });

  it("switches the format and keeps the answers", () => {
    render(<Harness />);
    fireEvent.click(screen.getByRole("radio", { name: "Hasty multi-ship brief" }));
    expect(lastDoc.template).toEqual({ id: "hasty", version: 1 });
    expect(screen.getByRole("dialog")).toHaveTextContent("Tactical hasty multi-ship brief");
  });
});

describe("brief setup, step 2", () => {
  const toDetails = () => {
    render(<Harness />);
    fireEvent.click(screen.getByRole("button", { name: /Brief details/ }));
  };

  it("says what each paragraph has left to fill", () => {
    toDetails();
    const nav = screen.getByRole("navigation", { name: "Brief paragraphs" });
    expect(within(nav).getByRole("button", { name: /Questions/ })).toHaveTextContent("Nothing to fill");
    expect(within(nav).getByRole("button", { name: /Roll call/ })).toHaveTextContent("1 to add");
  });

  it("takes a contingency as standard, with the person's own values", () => {
    toDetails();
    fireEvent.click(screen.getByRole("button", { name: /Service support/ }));
    const iimc = screen.getByRole("radiogroup", { name: "IIMC" });
    fireEvent.click(within(iimc).getByRole("radio", { name: "Standard" }));
    expect(lastDoc.answers["5.a.v"]).toEqual({ mode: "standard" });
    const base = screen.getByRole("textbox", { name: "Base altitude" });
    fireEvent.change(base, { target: { value: "4,000 ft MSL" } });
    fireEvent.blur(base);
    expect(lastDoc.answers["5.a.v"]).toEqual({ mode: "standard", fields: { baseAltitude: "4,000 ft MSL" } });
    expect(document.querySelector(".brief-script__text")).toHaveTextContent("Base altitude 4,000 ft MSL");
  });

  it("sets every standard of the paragraph at once", () => {
    toDetails();
    fireEvent.click(screen.getByRole("button", { name: /Service support/ }));
    fireEvent.click(screen.getByRole("button", { name: "Use standard for all" }));
    expect(Object.keys(lastDoc.answers).sort()).toEqual(["5.a.i", "5.a.ii", "5.a.iii", "5.a.iv", "5.a.v", "5.a.vi"]);
  });

  it("keeps an answer for one LZ/PZ apart from another's", () => {
    toDetails();
    fireEvent.click(screen.getByRole("button", { name: /Execution/ }));
    const formations = screen.getAllByRole("textbox", { name: "Formation" });
    fireEvent.change(formations[1], { target: { value: "trail" } });
    fireEvent.blur(formations[1]);
    expect(lastDoc.answers).toEqual({ "4.e.ii@lz:lz-hawk": { value: "trail" } });
  });

  it("keeps a rewritten script until it is reset", () => {
    toDetails();
    fireEvent.click(screen.getByRole("button", { name: "Edit" }));
    const script = screen.getByRole("textbox", { name: "The script" });
    fireEvent.change(script, { target: { value: "My own words." } });
    fireEvent.blur(script);
    expect(lastDoc.scripts).toEqual({ 1: "My own words." });
    fireEvent.click(screen.getByRole("button", { name: "Reset script from fields" }));
    expect(lastDoc.scripts).toEqual({});
  });
});

describe("brief setup, step 3", () => {
  it("shows each page as it will print, and keeps the options", () => {
    render(<Harness />);
    fireEvent.click(screen.getByRole("button", { name: /Review/ }));
    const pages = screen.getByRole("navigation", { name: "Pages" });
    fireEvent.click(within(pages).getByRole("button", { name: /4\.e Landing sequence · LZ HAWK/ }));
    const sheet = screen.getByRole("article");
    expect(sheet).toHaveTextContent("UNCLASSIFIED");
    expect(sheet).toHaveTextContent("Landing 203°");
    fireEvent.click(screen.getByRole("switch", { name: /Nav logs/ }));
    expect(lastDoc.options).toEqual({ navLogs: false });
  });

  it("offers the strip charts with their own map and scale, and lists their pages", async () => {
    render(<Harness />);
    fireEvent.click(screen.getByRole("button", { name: /Review/ }));
    const pages = screen.getByRole("navigation", { name: "Pages" });
    expect(within(pages).getByRole("button", { name: /Strip charts · ALDERAAN \(1–3 of 6\)/ })).toBeInTheDocument();
    const stripMaps = screen.getByRole("radiogroup", { name: "Map on the strip charts" });
    expect(within(stripMaps).getByRole("radio", { name: "Topo" })).toHaveAttribute("aria-checked", "true");
    expect(screen.getByText(/12 strips, each 21.5 by 5.5 km of ground/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("radio", { name: "1:250,000" }));
    expect(lastDoc.options).toEqual({ stripScale: 250000 });
    expect(screen.getByRole("radio", { name: "1:250,000" })).toHaveAttribute("aria-checked", "true");
    expect(screen.getByText(/each 53.75 by 13.75 km of ground/)).toBeInTheDocument();
    fireEvent.click(within(stripMaps).getByRole("radio", { name: "Sectional" }));
    expect(lastDoc.options).toEqual({ stripScale: 250000, stripMap: "sectional" });
    fireEvent.click(screen.getByRole("switch", { name: /Strip charts/ }));
    expect(within(pages).queryByRole("button", { name: /Strip charts/ })).toBeNull();
    expect(screen.queryByRole("radiogroup", { name: "Map on the strip charts" })).toBeNull();
  });

  it("prints the brief the review shows, and says why when it cannot", async () => {
    window.URL.createObjectURL = jest.fn(() => "blob:brief");
    window.URL.revokeObjectURL = jest.fn();
    briefApi.printBrief.mockResolvedValueOnce(new Blob(["%PDF"], { type: "application/pdf" }));
    render(<Harness />);
    fireEvent.click(screen.getByRole("button", { name: /Review/ }));
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Download PDF" }));
    });
    const [model] = briefApi.printBrief.mock.calls[0];
    expect(model.format).toBe("ezpz-brief-model");
    expect(model.geometry.routes["route:set-opdk/r-alderaan"].log[0]).toMatchObject({ from: "3MILE", to: "TRACK" });
    expect(window.URL.createObjectURL).toHaveBeenCalled();

    briefApi.printBrief.mockRejectedValueOnce(new Error("The PDF is not available on this server yet."));
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Download PDF" }));
    });
    expect(screen.getByRole("dialog")).toHaveTextContent("The PDF is not available on this server yet.");
  });

  it("says what it could not brief", () => {
    render(<Harness />);
    fireEvent.click(screen.getByRole("button", { name: /Review/ }));
    expect(screen.getByRole("region", { name: "Not briefed" })).toHaveTextContent("No route in the flow reaches FARP BUCEES");
  });
});

describe("saving", () => {
  it("writes what waits when the setup closes", async () => {
    const onClose = jest.fn();
    render(<Harness onClose={onClose} />);
    fireEvent.change(screen.getByRole("combobox", { name: "Role of LZ DECOY" }), { target: { value: "HA" } });
    expect(briefApi.createBrief).not.toHaveBeenCalled();
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Close" }));
    });
    expect(briefApi.createBrief).toHaveBeenCalledTimes(1);
    expect(onClose).toHaveBeenCalled();
  });
});

describe("the Brief menu", () => {
  it("says what the brief is built from and opens the setup", () => {
    const onGenerate = jest.fn();
    render(<BriefMenu packName="OP DK" summary="5 LZ/PZs, 2 route sets" onGenerate={onGenerate} />);
    fireEvent.click(screen.getByRole("button", { name: /Brief/ }));
    expect(screen.getByRole("menu")).toHaveTextContent("Built from everything in OP DK: 5 LZ/PZs, 2 route sets.");
    fireEvent.click(screen.getByRole("menuitem", { name: /Generate brief/ }));
    expect(onGenerate).toHaveBeenCalled();
  });
});

