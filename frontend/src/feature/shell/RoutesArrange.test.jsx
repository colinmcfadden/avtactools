import { fireEvent, render, screen, within } from "@testing-library/react";
import React from "react";
import RoutesDockPanel from "./RoutesDockPanel";

// Arranging routes from the Routes panel: a route's colour, two routes made one, a route moved to
// another set, and a new set.

const point = (id, lat, lon, name = "") => ({ id, lat, lon, name, kind: name ? "amps" : "shaping" });
const route = (id, name, color, points) => ({ id, name, color, points, plan: {}, elevations: {} });

const ARIEL = route("a", "ARIEL", "#FFD60A", [point("a1", 34.5, -84.5, "SP"), point("a2", 34.6, -84.4, "RP")]);
// Begins where ARIEL ends.
const BEEMIN = route("b", "BEEMIN", "#BF5AF2", [point("b1", 34.6, -84.4, "RP"), point("b2", 34.7, -84.3, "LZ")]);
const CARGO = route("c", "CARGO", "#0A84FF", [point("c1", 35, -85, "SP"), point("c2", 35.1, -85.1, "RP")]);
const DELTA = route("d", "DELTA", "#FF453A", [point("d1", 36, -86, "SP"), point("d2", 36.1, -86.1, "RP")]);

const SERIAL_1 = { key: "set-1", kind: "sketch", routes: [ARIEL, CARGO, BEEMIN] };
const SERIAL_2 = { key: "set-2", kind: "sketch", routes: [DELTA] };
const MISSION = { key: "msnxfile-1", kind: "mission", fileName: "GOAT SUCKER.msnx", routes: [route("m", "ROUTE 1", "#32D74B", [point("m1", 1, 1, "A"), point("m2", 2, 2, "B")])] };
const NAMES = { "set-1": "SERIAL 1", "set-2": "SERIAL 2", "msnxfile-1": "GOAT SUCKER", "uuid-1": "PACK ROUTES", "set-3": "ROUTES 3", "set-r2": "ROUTES 2" };

const actionsWith = (extra = {}) => ({
  save: vi.fn(), saveAs: vi.fn(), rename: vi.fn(), close: vi.fn(), export: vi.fn(), share: vi.fn(), shareSet: vi.fn(),
  toggleVisibility: vi.fn(), removeRoute: vi.fn(),
  recolor: vi.fn(), combineRoutes: vi.fn(), moveRoute: vi.fn(), newSet: vi.fn(), sketchInto: vi.fn(),
  ...extra,
});

const view = ({ sets = [SERIAL_1, SERIAL_2], actions = actionsWith(), pack = null } = {}) => (
  <RoutesDockPanel
    sets={sets}
    stateOf={(key) => ({ name: NAMES[key] ?? "SKETCHED ROUTES", link: null, dirty: true, saving: false, savedAt: null })}
    actions={actions}
    plan={() => ({})}
    pack={pack}
    sketch={{ active: false, name: "ROUTE 9", points: 0, enabled: true, onStart: () => {}, onCancel: () => {}, onFinish: () => {} }}
  />
);
const panel = (options = {}) => {
  const actions = options.actions ?? actionsWith();
  const rendered = render(view({ ...options, actions }));
  return { actions, ...rendered };
};

const card = (name) => screen.getByRole("region", { name });
const routeMenu = (name) => {
  fireEvent.click(screen.getByRole("button", { name: `More actions for ${name}` }));
  return screen.getByRole("menu", { name: `More actions for ${name}` });
};
const itemsOf = (menu) => within(menu).getAllByRole("menuitem").map((item) => item.textContent);

describe("a route's colour", () => {
  it("is changed from its dot: twelve colours by name, the one it has marked", () => {
    const { actions } = panel();
    fireEvent.click(screen.getByRole("button", { name: "Colour of ARIEL: Yellow. Change it" }));
    const menu = screen.getByRole("menu", { name: "Colour of ARIEL" });
    const choices = within(menu).getAllByRole("menuitemradio");
    expect(choices.map((choice) => choice.getAttribute("aria-label"))).toEqual(["Red", "Blue", "Green", "Yellow", "Purple", "Orange", "Cyan", "Pink", "White", "Teal", "Indigo", "Tan"]);
    expect(choices.filter((choice) => choice.getAttribute("aria-checked") === "true").map((choice) => choice.getAttribute("aria-label"))).toEqual(["Yellow"]);
    fireEvent.click(within(menu).getByRole("menuitemradio", { name: "Teal" }));
    expect(actions.recolor).toHaveBeenCalledWith(SERIAL_1, "a", "#00C7BE");
    expect(screen.queryByRole("menu", { name: "Colour of ARIEL" })).toBeNull();
  });

  it("is in the route's menu too, opening the same colours", () => {
    const { actions } = panel();
    fireEvent.click(within(routeMenu("CARGO")).getByRole("menuitem", { name: "Colour…" }));
    fireEvent.click(within(screen.getByRole("menu", { name: "Colour of CARGO" })).getByRole("menuitemradio", { name: "White" }));
    expect(actions.recolor).toHaveBeenCalledWith(SERIAL_1, "c", "#FFFFFF");
  });

  it("of an older save's own colour is named by no name, and still changes", () => {
    const odd = { ...SERIAL_2, routes: [{ ...DELTA, color: "#123456" }] };
    const { actions } = panel({ sets: [odd] });
    fireEvent.click(screen.getByRole("button", { name: "Colour of DELTA. Change it" }));
    expect(within(screen.getByRole("menu")).queryByRole("menuitemradio", { checked: true })).toBeNull();
    fireEvent.click(screen.getByRole("menuitemradio", { name: "Red" }));
    expect(actions.recolor).toHaveBeenCalledWith(odd, "d", "#FF453A");
  });

  it("an imported mission's route changes colour, and nothing else about which routes it has", () => {
    const { actions } = panel({ sets: [MISSION, SERIAL_2] });
    const mission = card("GOAT SUCKER.msnx");
    expect(within(mission).getByRole("button", { name: "Colour of ROUTE 1: Green. Change it" })).toBeInTheDocument();
    fireEvent.click(within(mission).getByRole("button", { name: "More actions for ROUTE 1" }));
    expect(itemsOf(screen.getByRole("menu", { name: "More actions for ROUTE 1" }))).toEqual(["Send to ForeFlight", "Colour…", "Remove route"]);
    fireEvent.click(screen.getByRole("menuitem", { name: "Colour…" }));
    fireEvent.click(screen.getByRole("menuitemradio", { name: "Pink" }));
    expect(actions.recolor).toHaveBeenCalledWith(MISSION, "m", "#FF375F");
  });
});

describe("a route's menu", () => {
  it("has the route's colour, combining and moving, between sending and removing", () => {
    panel();
    expect(itemsOf(routeMenu("ARIEL"))).toEqual(["Send to ForeFlight", "Colour…", "Combine with…", "Move to another set…", "Remove route"]);
  });

  it("offers no combining where the set has one route: there is nothing to join it to", () => {
    panel();
    expect(itemsOf(routeMenu("DELTA"))).toEqual(["Send to ForeFlight", "Colour…", "Move to another set…", "Remove route"]);
  });

  it("in a read-only pack sends and nothing else, and the dot is no button", () => {
    const packSet = { ...SERIAL_1, key: "uuid-1", pack: { finished: true, target: false } };
    panel({ sets: [packSet], pack: { name: "OP DK", finished: true, readOnly: true } });
    expect(screen.queryByRole("button", { name: /^Colour of ARIEL/ })).toBeNull();
    expect(itemsOf(routeMenu("ARIEL"))).toEqual(["Send to ForeFlight"]);
    expect(screen.queryByRole("button", { name: "New route set" })).toBeNull();
  });

  it("offers none of it where the panel is given no way to do it", () => {
    panel({ actions: actionsWith({ recolor: undefined, combineRoutes: undefined, moveRoute: undefined, newSet: undefined }) });
    expect(screen.queryByRole("button", { name: /^Colour of ARIEL/ })).toBeNull();
    expect(itemsOf(routeMenu("ARIEL"))).toEqual(["Send to ForeFlight", "Remove route"]);
    expect(screen.queryByRole("button", { name: "New route set" })).toBeNull();
  });
});

describe("combining two routes", () => {
  it("asks which route of the set goes onto the end, the one that begins where this ends first in mind", () => {
    const { actions } = panel();
    fireEvent.click(within(routeMenu("ARIEL")).getByRole("menuitem", { name: "Combine with…" }));
    const dialog = screen.getByRole("dialog", { name: "Combine with ARIEL" });
    expect(dialog).toHaveAccessibleDescription("The route you choose is added after ARIEL's last point. The one route keeps ARIEL's name and colour.");
    const choices = within(dialog).getAllByRole("radio");
    // The set's other routes, in the panel's order; never the route itself, nor another set's.
    expect(choices.map((choice) => choice.closest("label").querySelector(".ui-choice__title").textContent)).toEqual(["CARGO", "BEEMIN"]);
    expect(choices.map((choice) => choice.checked)).toEqual([false, true]);
    expect(within(dialog).getByText(/begins where ARIEL ends/).closest("label")).toHaveTextContent("BEEMIN");
    // Two points and two, less the one they share.
    expect(within(dialog).getByText("3 points in all. Undo takes it back.")).toBeInTheDocument();

    fireEvent.click(choices[0]);
    expect(within(dialog).getByText("4 points in all. Undo takes it back.")).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", { name: "Combine" }));
    expect(actions.combineRoutes).toHaveBeenCalledWith(SERIAL_1, "a", "c");
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("cancelled changes nothing", () => {
    const { actions } = panel();
    fireEvent.click(within(routeMenu("CARGO")).getByRole("menuitem", { name: "Combine with…" }));
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(actions.combineRoutes).not.toHaveBeenCalled();
  });

  it("is put away when the route asked about has gone", () => {
    const { actions, rerender } = panel();
    fireEvent.click(within(routeMenu("ARIEL")).getByRole("menuitem", { name: "Combine with…" }));
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    rerender(view({ sets: [{ ...SERIAL_1, routes: [CARGO, BEEMIN] }, SERIAL_2], actions }));
    expect(screen.queryByRole("dialog")).toBeNull();
  });
});

describe("moving a route to another set", () => {
  it("asks where: the other sets that take routes, or a new one", () => {
    const { actions } = panel({ sets: [SERIAL_1, MISSION, SERIAL_2] });
    fireEvent.click(within(routeMenu("CARGO")).getByRole("menuitem", { name: "Move to another set…" }));
    const dialog = screen.getByRole("dialog", { name: "Move CARGO" });
    expect(dialog).toHaveAccessibleDescription("It leaves SERIAL 1 and keeps its points, its plan and its colour.");
    const titles = within(dialog).getAllByRole("radio").map((choice) => choice.closest("label").querySelector(".ui-choice__title").textContent);
    // Not the set it is in, and not the imported mission: its routes are its file's.
    expect(titles).toEqual(["SERIAL 2", "A new route set"]);
    expect(within(dialog).getByText("1 route · 2 points")).toBeInTheDocument();
    expect(within(dialog).queryByLabelText("Name of the new set")).toBeNull();
    fireEvent.click(within(dialog).getByRole("button", { name: "Move" }));
    expect(actions.moveRoute).toHaveBeenCalledWith(SERIAL_1, "c", { key: "set-2" });
    expect(screen.queryByRole("dialog")).toBeNull();
  });

  it("offers no set of control measures: it holds lines and takes no route", () => {
    const line = [{ lat: 34.5, lon: -84.3 }, { lat: 34.5, lon: -84.1 }];
    const measures = { key: "measures", kind: "sketch", measures: true, routes: [], flots: [{ id: "flot-1", points: line }], phaseLines: [{ id: "pl-1", name: "RED", points: line }] };
    // A route set with a FLOT saved inside it, from before control measures were kept on their own, is still a route set.
    const withFlot = { ...SERIAL_2, flots: [{ id: "flot-2", points: line }] };
    panel({ sets: [SERIAL_1, withFlot, measures] });
    fireEvent.click(within(routeMenu("CARGO")).getByRole("menuitem", { name: "Move to another set…" }));
    const dialog = screen.getByRole("dialog", { name: "Move CARGO" });
    const titles = within(dialog).getAllByRole("radio").map((choice) => choice.closest("label").querySelector(".ui-choice__title").textContent);
    expect(titles).toEqual(["SERIAL 2", "A new route set"]);
    expect(within(dialog).getByText("1 route · 2 points · 1 FLOT")).toBeInTheDocument();
  });

  it("to a new set, named there, in capitals", () => {
    const { actions } = panel();
    fireEvent.click(within(routeMenu("ARIEL")).getByRole("menuitem", { name: "Move to another set…" }));
    const dialog = screen.getByRole("dialog", { name: "Move ARIEL" });
    fireEvent.click(within(dialog).getByRole("radio", { name: /A new route set/ }));
    const name = within(dialog).getByLabelText("Name of the new set");
    // A name no set here has.
    expect(name).toHaveValue("ROUTES 3");
    fireEvent.change(name, { target: { value: "  serial 3 " } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Move" }));
    expect(actions.moveRoute).toHaveBeenCalledWith(SERIAL_1, "a", { newName: "SERIAL 3" });
  });

  it("a new set with no name is refused in words, and nothing moves", () => {
    const { actions } = panel();
    fireEvent.click(within(routeMenu("ARIEL")).getByRole("menuitem", { name: "Move to another set…" }));
    const dialog = screen.getByRole("dialog");
    fireEvent.click(within(dialog).getByRole("radio", { name: /A new route set/ }));
    fireEvent.change(within(dialog).getByLabelText("Name of the new set"), { target: { value: "   " } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Move" }));
    expect(within(dialog).getByText("Give the new set a name.")).toBeInTheDocument();
    expect(actions.moveRoute).not.toHaveBeenCalled();
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });

  it("with no other set to go to, a new one is what is offered", () => {
    const { actions } = panel({ sets: [SERIAL_1] });
    fireEvent.click(within(routeMenu("ARIEL")).getByRole("menuitem", { name: "Move to another set…" }));
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getAllByRole("radio")).toHaveLength(1);
    expect(within(dialog).getByLabelText("Name of the new set")).toHaveValue("ROUTES 2");
    fireEvent.click(within(dialog).getByRole("button", { name: "Move" }));
    expect(actions.moveRoute).toHaveBeenCalledWith(SERIAL_1, "a", { newName: "ROUTES 2" });
  });

  it("a finished pack's set is nowhere to move a route to", () => {
    const live = { ...SERIAL_1, key: "uuid-1", pack: { finished: false, target: true } };
    const done = { ...SERIAL_2, key: "uuid-2", pack: { finished: true, target: false } };
    panel({ sets: [live, done], pack: { name: "OP DK", finished: false, readOnly: false } });
    fireEvent.click(within(routeMenu("ARIEL")).getByRole("menuitem", { name: "Move to another set…" }));
    expect(within(screen.getByRole("dialog")).getAllByRole("radio")).toHaveLength(1);
  });
});

describe("a new route set", () => {
  it("is made from the foot of the panel, with a name no set here has to start from", () => {
    const { actions } = panel();
    fireEvent.click(screen.getByRole("button", { name: "New route set" }));
    expect(actions.newSet).toHaveBeenCalledWith("ROUTES 3");
  });

  it("is at the foot with Sketch a route and Import .msnx, and no FLOT: the lines are drawn from the left panel", () => {
    const { container } = panel();
    const foot = container.querySelector(".shell-dock__foot");
    expect(within(foot).getAllByRole("button").map((button) => button.textContent)).toEqual(["Sketch a route", "Import .msnx", "New route set"]);
  });

  it("is numbered among the route sets: the control measures are not one of them", () => {
    const measures = { key: "measures", kind: "sketch", measures: true, routes: [], flots: [{ id: "flot-1", points: [{ lat: 34.5, lon: -84.3 }, { lat: 34.5, lon: -84.1 }] }] };
    const { actions } = panel({ sets: [SERIAL_1, SERIAL_2, measures] });
    fireEvent.click(screen.getByRole("button", { name: "New route set" }));
    expect(actions.newSet).toHaveBeenCalledWith("ROUTES 3");
  });

  it("with one route set and the control measures there is still nothing to choose between for sketches", () => {
    const measures = { key: "measures", kind: "sketch", measures: true, routes: [], flots: [{ id: "flot-1", points: [{ lat: 34.5, lon: -84.3 }, { lat: 34.5, lon: -84.1 }] }] };
    panel({ sets: [SERIAL_1, measures] });
    fireEvent.click(screen.getByRole("button", { name: "More actions for SERIAL 1" }));
    const items = within(screen.getByRole("menu", { name: "More actions for SERIAL 1" })).getAllByRole("menuitem").map((item) => item.textContent);
    expect(items.some((text) => text.startsWith("Sketch a route into this set"))).toBe(false);
  });

  it("is not offered a name a set here already has", () => {
    const { actions } = panel({ sets: [{ ...SERIAL_2, key: "set-r2" }] });
    fireEvent.click(screen.getByRole("button", { name: "New route set" }));
    expect(actions.newSet).toHaveBeenCalledWith("ROUTES 3");
  });

  it("with no routes says so, cannot be saved yet, and has nothing to export", () => {
    const empty = { key: "set-3", kind: "sketch", routes: [], target: true };
    panel({ sets: [SERIAL_1, empty] });
    const made = card("ROUTES 3");
    expect(within(made).getByText("No routes in this set yet. Sketch one, or move one here from another set.")).toBeInTheDocument();
    expect(within(made).getByText("0 routes · 0 points")).toBeInTheDocument();
    expect(within(made).getByRole("button", { name: "Save" })).toBeDisabled();
    expect(within(made).queryByRole("button", { name: "Export .msnx" })).toBeNull();
    expect(within(card("SERIAL 1")).getByRole("button", { name: "Save" })).toBeEnabled();
  });

  it("is where new sketches go, and the card says so; another set is chosen from its menu", () => {
    const empty = { key: "set-3", kind: "sketch", routes: [], target: true };
    const { actions } = panel({ sets: [SERIAL_1, empty] });
    expect(within(card("ROUTES 3")).getByText("New routes you sketch go into this set.")).toBeInTheDocument();
    expect(within(card("SERIAL 1")).queryByText("New routes you sketch go into this set.")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "More actions for SERIAL 1" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Sketch a route into this set" }));
    expect(actions.sketchInto).toHaveBeenCalledWith(SERIAL_1);
  });

  it("with one set of this session's there is nothing to choose, and no such entry", () => {
    panel({ sets: [SERIAL_1, MISSION] });
    fireEvent.click(screen.getByRole("button", { name: "More actions for SERIAL 1" }));
    expect(screen.queryByRole("menuitem", { name: "Sketch a route into this set" })).toBeNull();
    // Never into an imported mission.
    fireEvent.click(screen.getByRole("button", { name: "More actions for GOAT SUCKER.msnx" }));
    expect(screen.queryByRole("menuitem", { name: "Sketch a route into this set" })).toBeNull();
  });
});
