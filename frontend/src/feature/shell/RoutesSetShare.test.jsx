import { fireEvent, render, screen, within } from "@testing-library/react";
import React from "react";
import RoutesDockPanel from "./RoutesDockPanel";

// Sending a whole route set to ForeFlight from its card in the Routes panel: the button beside the
// pencil in the title row, and the same in the card's menu.

const point = (id, lat, lon, name = "") => ({ id, lat, lon, name });
const route = (id, name, points = 3, extra = {}) => ({
  id,
  name,
  color: "#ffd60a",
  points: Array.from({ length: points }, (_, i) => point(`${id}-${i}`, 34 + i * 0.1, -84 - i * 0.1, `P${i}`)),
  plan: {},
  ...extra,
});

const SET = { key: "set-1", kind: "sketch", routes: [route("a", "ARIEL"), route("b", "BEEMIN", 4, { visible: false })] };

const actionsWith = (extra = {}) => ({
  save: vi.fn(),
  saveAs: vi.fn(),
  rename: vi.fn(),
  close: vi.fn(),
  export: vi.fn(),
  share: vi.fn(),
  shareSet: vi.fn(),
  toggleVisibility: vi.fn(),
  removeRoute: vi.fn(),
  ...extra,
});

const panel = ({ sets = [SET], state = {}, actions = actionsWith(), pack = null } = {}) => {
  render(
    <RoutesDockPanel
      sets={sets}
      stateOf={() => ({ name: "260818 MULTI", link: { id: 4, name: "260818 MULTI" }, dirty: false, saving: false, savedAt: null, ...state })}
      actions={actions}
      plan={() => ({})}
      pack={pack}
      sketch={{ active: false, name: "ROUTE 3", points: 0, enabled: true, onStart: () => {}, onCancel: () => {}, onFinish: () => {} }}
    />,
  );
  return actions;
};

const card = (name = "260818 MULTI") => screen.getByRole("region", { name });
const titleRowButtons = (name) => [...card(name).querySelector(".shell-card__row").querySelectorAll("button")].map((button) => button.getAttribute("aria-label"));

describe("sending a whole route set to ForeFlight", () => {
  it("has a button in the title row, right beside the pencil, with the routes' own icon", () => {
    panel();
    expect(titleRowButtons()).toEqual(["Rename 260818 MULTI", "Send 260818 MULTI to ForeFlight", "More actions for 260818 MULTI"]);
    const send = within(card()).getByRole("button", { name: "Send 260818 MULTI to ForeFlight" });
    const rowSend = within(card()).getByRole("button", { name: "Send ARIEL to ForeFlight" });
    expect(send.querySelector("svg").innerHTML).toBe(rowSend.querySelector("svg").innerHTML);
    expect(send).toHaveAttribute("aria-disabled", "false");
    // Named once, as the pencil is: no title to be read out a second time.
    expect(send).not.toHaveAttribute("title");
    expect(send).not.toHaveAccessibleDescription();
  });

  it("sends the set and its name, every route of it, hidden or not", () => {
    const actions = panel();
    fireEvent.click(within(card()).getByRole("button", { name: "Send 260818 MULTI to ForeFlight" }));
    expect(actions.shareSet).toHaveBeenCalledTimes(1);
    expect(actions.shareSet).toHaveBeenCalledWith(SET, "260818 MULTI");
    expect(actions.shareSet.mock.calls[0][0].routes.map((r) => r.name)).toEqual(["ARIEL", "BEEMIN"]);
    // A route's own button is still the route's.
    expect(actions.share).not.toHaveBeenCalled();
    fireEvent.click(within(card()).getByRole("button", { name: "Send BEEMIN to ForeFlight" }));
    expect(actions.share).toHaveBeenCalledWith(SET.routes[1]);
    expect(actions.shareSet).toHaveBeenCalledTimes(1);
  });

  it("is in the card's menu too", () => {
    const actions = panel();
    fireEvent.click(screen.getByRole("button", { name: "More actions for 260818 MULTI" }));
    const menu = screen.getByRole("menu", { name: "More actions for 260818 MULTI" });
    const items = within(menu).getAllByRole("menuitem").map((item) => item.textContent);
    expect(items[0]).toBe("Send to ForeFlight");
    fireEvent.click(within(menu).getByRole("menuitem", { name: "Send to ForeFlight" }));
    expect(actions.shareSet).toHaveBeenCalledWith(SET, "260818 MULTI");
  });

  it("stays in a read-only pack, where the pencil goes: sending changes nothing", () => {
    const packSet = { ...SET, key: "uuid-1", pack: { finished: true, target: false } };
    const actions = panel({ sets: [packSet], state: { name: "ROUTES 1", link: null }, pack: { name: "OP DK", finished: true, readOnly: true } });
    expect(titleRowButtons("ROUTES 1")).toEqual(["Send ROUTES 1 to ForeFlight", "More actions for ROUTES 1"]);
    fireEvent.click(screen.getByRole("button", { name: "Send ROUTES 1 to ForeFlight" }));
    expect(actions.shareSet).toHaveBeenCalledWith(packSet, "ROUTES 1");
    fireEvent.click(screen.getByRole("button", { name: "More actions for ROUTES 1" }));
    expect(screen.getByRole("menuitem", { name: "Send to ForeFlight" })).toBeEnabled();
  });

  it("is there for a viewer of a live pack as well", () => {
    const packSet = { ...SET, key: "uuid-1", pack: { finished: true, target: false } };
    panel({ sets: [packSet], state: { name: "ROUTES 1", link: null }, pack: { name: "OP DK", role: "viewer", finished: false, readOnly: true } });
    expect(screen.getByRole("button", { name: "Send ROUTES 1 to ForeFlight" })).toHaveAttribute("aria-disabled", "false");
  });

  it("an imported mission is known by its file and sent by its name", () => {
    const mission = { key: "msnxfile-1", kind: "mission", fileName: "GOAT SUCKER.msnx", routes: [route("r1", "ROUTE 1")] };
    const actions = panel({ sets: [mission], state: { name: "GOAT SUCKER", link: null, dirty: true } });
    fireEvent.click(screen.getByRole("button", { name: "Send GOAT SUCKER.msnx to ForeFlight" }));
    expect(actions.shareSet).toHaveBeenCalledWith(mission, "GOAT SUCKER");
  });

  it("gives way to the name's field while the set is renamed", () => {
    panel();
    fireEvent.click(screen.getByRole("button", { name: "Rename 260818 MULTI" }));
    expect(screen.getByLabelText("Name of these routes")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Send 260818 MULTI to ForeFlight" })).toBeNull();
  });

  it("is not offered where the panel is given no way to send a set", () => {
    panel({ actions: actionsWith({ shareSet: undefined }) });
    expect(screen.queryByRole("button", { name: "Send 260818 MULTI to ForeFlight" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "More actions for 260818 MULTI" }));
    expect(screen.queryByRole("menuitem", { name: "Send to ForeFlight" })).toBeNull();
  });
});

describe("a set with no route that can be sent", () => {
  const off = (sets, why) => {
    const actions = panel({ sets });
    const send = within(card()).getByRole("button", { name: "Send 260818 MULTI to ForeFlight" });
    // Off, and it says why: to a pointer, to a screen reader, and to whoever taps it.
    expect(send).toHaveAttribute("aria-disabled", "true");
    expect(send).toHaveAttribute("title", why);
    expect(send).toHaveAccessibleDescription(why);
    expect(within(card()).queryByRole("status")).toBeNull();
    fireEvent.click(send);
    expect(actions.shareSet).not.toHaveBeenCalled();
    expect(within(card()).getByRole("status")).toHaveTextContent(`${why}, so there is nothing to send.`);

    fireEvent.click(screen.getByRole("button", { name: "More actions for 260818 MULTI" }));
    expect(screen.getByRole("menuitem", { name: "Send to ForeFlight" })).toBeDisabled();
    return actions;
  };

  it("says a route needs two points", () => {
    off([{ ...SET, routes: [route("s", "STUB", 1), route("e", "EMPTY", 0)] }], "No route of this set has two points yet");
  });

  it("says a set of only a FLOT has no routes", () => {
    off([{ ...SET, routes: [], flots: [{ id: "f1", points: [{ lat: 34, lon: -84 }, { lat: 34.1, lon: -84.1 }] }] }], "No routes in this set to send");
  });

  it("does not say why again, unasked, once it has been on in between", () => {
    const stub = { ...SET, routes: [route("s", "STUB", 1)] };
    const actions = actionsWith();
    const view = (sets) => (
      <RoutesDockPanel
        sets={sets}
        stateOf={() => ({ name: "260818 MULTI", link: null, dirty: true, saving: false, savedAt: null })}
        actions={actions}
        plan={() => ({})}
        sketch={{ active: false, name: "ROUTE 3", points: 0, enabled: true, onStart: () => {}, onCancel: () => {}, onFinish: () => {} }}
      />
    );
    const { rerender } = render(view([stub]));
    fireEvent.click(screen.getByRole("button", { name: "Send 260818 MULTI to ForeFlight" }));
    expect(within(card()).getByRole("status")).toBeInTheDocument();
    rerender(view([SET]));
    expect(within(card()).queryByRole("status")).toBeNull();
    rerender(view([stub]));
    expect(within(card()).queryByRole("status")).toBeNull();
    expect(screen.getByRole("button", { name: "Send 260818 MULTI to ForeFlight" })).toHaveAttribute("aria-disabled", "true");
  });

  it("is on again with one route of two points among the short ones", () => {
    const set = { ...SET, routes: [route("s", "STUB", 1), route("a", "ARIEL", 2)] };
    const actions = panel({ sets: [set] });
    const send = screen.getByRole("button", { name: "Send 260818 MULTI to ForeFlight" });
    expect(send).toHaveAttribute("aria-disabled", "false");
    expect(send).not.toHaveAccessibleDescription();
    fireEvent.click(send);
    expect(actions.shareSet).toHaveBeenCalledWith(set, "260818 MULTI");
    expect(within(card()).queryByRole("status")).toBeNull();
  });
});
