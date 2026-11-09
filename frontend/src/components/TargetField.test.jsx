import React, { useState } from "react";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import TargetField from "./TargetField";
import { formatMGRS } from "../utils/Helpers";

// A field as the app holds it: the text in state, every choice recorded.
const Harness = ({ initial = "", near = null, onChoose, onShare = null }) => {
  const [value, setValue] = useState(initial);
  return <TargetField value={value} onChange={setValue} onChoose={onChoose} onShare={onShare} near={near} />;
};

const setup = (props = {}) => {
  const onChoose = vi.fn();
  render(<Harness onChoose={onChoose} {...props} />);
  const input = screen.getByRole("combobox");
  return { onChoose, input, type: (text) => fireEvent.change(input, { target: { value: text } }) };
};

const statusText = () => document.querySelector(".target-field__status").textContent;
const messageText = () => document.querySelector(".target-field__message")?.textContent;
const optionTexts = () => screen.queryAllByRole("option").map((o) => o.querySelector(".target-field__main").textContent);

describe("TargetField", () => {
  it("shows a grid and its lat/long as two rows while the grid is typed", () => {
    const { input, type } = setup();
    input.focus();
    type("16sgc2886455349");
    expect(optionTexts()).toEqual(["16S GC 28864 55349", "33.91372° N, 84.52451° W"]);
    expect(screen.getByText("MGRS grid, to 1 m")).toBeInTheDocument();
    expect(input).toHaveAttribute("aria-expanded", "true");
  });

  it("converts a coordinate in any notation to its grid", () => {
    const { input, type } = setup();
    input.focus();
    type("34°32'44.4″N 84°07'24.4″W");
    expect(optionTexts()).toEqual(["16S GD 63972 26420", "34.54567° N, 84.12344° W"]);
    expect(screen.getByText("Lat/long, read as degrees, minutes and seconds")).toBeInTheDocument();
  });

  it("chooses a row on a click, and closes", () => {
    const { input, type, onChoose } = setup();
    input.focus();
    type("34.5457, -84.1234");
    fireEvent.click(screen.getAllByRole("option")[1]);
    expect(onChoose).toHaveBeenCalledWith(expect.objectContaining({ mgrs: "16S GD 63976 26424", lat: 34.5457, lon: -84.1234 }));
    expect(screen.queryAllByRole("option")).toHaveLength(0);
  });

  it("keeps the focus in the field when a row is pressed", () => {
    const { input, type } = setup();
    input.focus();
    type("34.5457, -84.1234");
    const pressed = fireEvent.mouseDown(screen.getAllByRole("option")[0]);
    expect(pressed).toBe(false); // the default (moving the focus) was prevented
  });

  it("chooses with the arrow keys and Enter, naming the row as the active descendant", () => {
    const { input, type, onChoose } = setup();
    input.focus();
    type("16S GC 288 553");
    fireEvent.keyDown(input, { key: "ArrowDown" });
    fireEvent.keyDown(input, { key: "ArrowDown" });
    const second = screen.getAllByRole("option")[1];
    expect(second).toHaveAttribute("aria-selected", "true");
    expect(input).toHaveAttribute("aria-activedescendant", second.id);
    fireEvent.keyDown(input, { key: "ArrowDown" }); // wraps to the first
    expect(screen.getAllByRole("option")[0]).toHaveAttribute("aria-selected", "true");
    fireEvent.keyDown(input, { key: "Enter" });
    expect(onChoose).toHaveBeenCalledWith(expect.objectContaining({ mgrs: "16S GC 288 553" }));
  });

  it("GO and Enter choose the one place there is", () => {
    const { input, type, onChoose } = setup();
    type("16S GC 28864 55349");
    fireEvent.keyDown(input, { key: "Enter" });
    expect(onChoose).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole("button", { name: "GO" }));
    expect(onChoose).toHaveBeenCalledTimes(2);
  });

  it("offers an unsigned pair's readings, nearest the map first, and never guesses between them", () => {
    const { input, type, onChoose } = setup({ near: { lat: 34.5, lon: -84 } });
    input.focus();
    type("34 84");
    expect(messageText()).toMatch(/could be east or west/);
    expect(optionTexts()).toEqual(["34.00000° N, 84.00000° W", "34.00000° N, 84.00000° E"]);
    fireEvent.click(screen.getByRole("button", { name: "GO" }));
    fireEvent.keyDown(input, { key: "Enter" });
    expect(onChoose).not.toHaveBeenCalled();
    fireEvent.click(screen.getAllByRole("option")[0]);
    expect(onChoose).toHaveBeenCalledWith(expect.objectContaining({ lat: 34, lon: -84, mgrs: "17S KT 22908 66214" }));
  });

  it("says in words what is not a place yet, and GO does nothing but say it again", () => {
    const { input, type, onChoose } = setup();
    input.focus();
    type("16S GC 288");
    expect(messageText()).toBe("Keep typing: a grid has as many northing digits as easting digits.");
    expect(screen.queryAllByRole("option")).toHaveLength(0);
    const before = statusText();
    fireEvent.click(screen.getByRole("button", { name: "GO" }));
    expect(onChoose).not.toHaveBeenCalled();
    expect(statusText()).not.toBe(before); // read again
    expect(statusText()).toContain("as many northing digits");
  });

  it("tells a screen reader what the list holds", () => {
    const { input, type } = setup();
    input.focus();
    type("16S GC 28864 55349");
    expect(statusText()).toBe("16S GC 28864 55349, 33.91372° N, 84.52451° W. Press Enter to go there.");
  });

  it("closes on Escape and on leaving the field, and opens again on typing", () => {
    const { input, type } = setup();
    input.focus();
    type("34.5457, -84.1234");
    fireEvent.keyDown(input, { key: "Escape" });
    expect(screen.queryAllByRole("option")).toHaveLength(0);
    expect(input).toHaveAttribute("aria-expanded", "false");
    type("34.5457, -84.12");
    expect(screen.getAllByRole("option")).toHaveLength(2);
    fireEvent.blur(input);
    expect(screen.queryAllByRole("option")).toHaveLength(0);
  });

  it("GO with nothing to go to takes the focus back to the field, so the list closes as it always does", () => {
    const { input, type } = setup();
    type("34 84");
    expect(document.activeElement).not.toBe(input);
    fireEvent.click(screen.getByRole("button", { name: "GO" }));
    expect(document.activeElement).toBe(input);
    expect(screen.getAllByRole("option")).toHaveLength(2);
    fireEvent.keyDown(input, { key: "Escape" });
    expect(screen.queryAllByRole("option")).toHaveLength(0);
  });

  it("a tap on a row puts the keyboard away; Enter leaves the focus in the field", () => {
    const { input, type } = setup();
    input.focus();
    type("16S GC 28864 55349");
    fireEvent.click(screen.getAllByRole("option")[0]);
    expect(document.activeElement).not.toBe(input);
    input.focus();
    type("16S GC 2886 5534");
    fireEvent.keyDown(input, { key: "Enter" });
    expect(document.activeElement).toBe(input);
  });

  it("shares a row's place from the button at its end, without choosing it", () => {
    const onShare = vi.fn();
    const { input, type, onChoose } = setup({ onShare });
    input.focus();
    type("34 84");
    // A pointer's alone: no control inside an option, so a screen reader reads each row as one choice.
    const west = document.querySelector('[data-share="17S KT 22908 66214"]');
    expect(west).toHaveAttribute("aria-hidden", "true");
    expect(within(screen.getAllByRole("option")[1]).queryByRole("button")).toBeNull();
    expect(fireEvent.mouseDown(west)).toBe(false); // the focus stays in the field
    fireEvent.click(west);
    expect(onShare).toHaveBeenCalledWith(expect.objectContaining({ mgrs: "17S KT 22908 66214", lon: -84 }));
    expect(onChoose).not.toHaveBeenCalled();
    expect(document.activeElement).not.toBe(input); // the field is put away, so closing the dialog opens no list
  });

  it("the field's share button shares the one place there is, and waits while there is none", () => {
    const onShare = vi.fn();
    const { type } = setup({ onShare });
    const button = () => screen.getByRole("button", { name: /^Share grid/ });
    expect(button()).toBeDisabled();
    type("34 84");
    expect(button()).toBeDisabled(); // two readings: never a guess
    type("16S GC 28864 55349");
    expect(button()).toHaveAccessibleName("Share grid 16S GC 28864 55349");
    fireEvent.click(button());
    expect(onShare).toHaveBeenCalledWith(expect.objectContaining({ mgrs: "16S GC 28864 55349" }));
  });

  it("the field's share button shares the reading the arrow keys are on", () => {
    const onShare = vi.fn();
    const { input, type } = setup({ onShare, near: { lat: 34, lon: -84 } });
    input.focus();
    type("34 84");
    fireEvent.keyDown(input, { key: "ArrowDown" });
    fireEvent.keyDown(input, { key: "ArrowDown" });
    const button = screen.getByRole("button", { name: /^Share grid/ });
    // Tab from the field to the button: the reading picked stays picked.
    fireEvent.blur(input, { relatedTarget: button });
    button.focus();
    expect(button).toBeEnabled();
    expect(button).toHaveAccessibleName(/^Share grid 45S /);
    fireEvent.click(button);
    expect(onShare).toHaveBeenCalledWith(expect.objectContaining({ lon: 84 }));
  });

  it("offers no share buttons without onShare, and asks to search by grid or lat/long", () => {
    const { input, type } = setup();
    input.focus();
    type("16S GC 28864 55349");
    expect(screen.queryByRole("button", { name: /^Share/ })).toBeNull();
    expect(input).toHaveAttribute("placeholder", "Search by grid or lat/long");
  });

  it("shows nothing for an empty field", () => {
    const { input } = setup();
    input.focus();
    expect(input).toHaveAttribute("aria-expanded", "false");
    expect(statusText()).toBe("");
  });
});

describe("formatMGRS leaves coordinates alone", () => {
  // The mobile field reformats every keystroke. Before this, pasting a
  // coordinate stripped its punctuation — "34.5, -84.2" became "345842".
  it.each([
    "34.5457, -84.1234",
    "34.5, -84.2",
    "N34.5457 W084.1234",
    "34°32.740'N 084°07.407'W",
    "34.5457",          // mid-paste, not yet a pair
    "34.5457, -",       // mid-typing
    "-84",
    "34N 84E",          // E and S once read as grid letters, so this was reformatted
    "34S",
    "34 ",              // the space after a first number, typed on the way to "34 84"
    "34 84",            // an unsigned pair, offered as its readings
    "34 32 44",
  ])("passes %s through untouched", (text) => {
    expect(formatMGRS(text)).toBe(text);
  });

  it("still formats a grid as it is typed", () => {
    expect(formatMGRS("16sgc2886455349")).toBe("16S GC 28864 55349");
    expect(formatMGRS("16sgc")).toBe("16S GC");
  });

  it("still handles an empty field", () => {
    expect(formatMGRS("")).toBe("");
  });
});

describe("TargetField with the place search", () => {
  const PLACES = [
    { id: "nasr:apt:21530.", kind: "airport", kindWords: "Airport", name: "BLAIRSVILLE", ident: "DZJ", city: "BLAIRSVILLE", state: "GA", lat: 34.85508, lon: -83.99733, elevationFt: 1907 },
    { id: "nasr:apt:34601.", kind: "airport", kindWords: "Airport", name: "BLAIRSTOWN", ident: "1N7", city: "BLAIRSTOWN", state: "NJ", lat: 40.97111, lon: -74.9975, elevationFt: 372 },
    { id: "nasr:apt:21528.1", kind: "heliport", kindWords: "Heliport, private", name: "UNION GENERAL HOSPITAL", ident: "1GA3", city: "BLAIRSVILLE", state: "GA", lat: 34.8817, lon: -83.9624, elevationFt: 1900 },
    { id: "nasr:apt:1", kind: "heliport", kindWords: "Heliport, private", name: "BLAIRS PAD", ident: "9GA1", city: "BLAIRSVILLE", state: "GA", lat: 34.88, lon: -83.96, elevationFt: null },
    { id: "nasr:fix:BLAIR", kind: "fix", kindWords: "Fix", name: null, ident: "BLAIR", city: null, state: "GA", lat: 34.7, lon: -84.0, elevationFt: null },
  ];
  const NEAR = { lat: 34.8, lon: -83.9 };

  const PlaceHarness = ({ onChoose, searchPlaces, onShare = null }) => {
    const [value, setValue] = useState("");
    return <TargetField value={value} onChange={setValue} onChoose={onChoose} onShare={onShare} near={NEAR} searchPlaces={searchPlaces} />;
  };

  const placeSetup = ({ answer = { available: true, places: PLACES }, onShare = null } = {}) => {
    const onChoose = vi.fn();
    const searchPlaces = vi.fn(async () => (typeof answer === "function" ? answer() : answer));
    render(<PlaceHarness onChoose={onChoose} searchPlaces={searchPlaces} onShare={onShare} />);
    const input = screen.getByRole("combobox");
    input.focus();
    return { onChoose, searchPlaces, input, type: (text) => fireEvent.change(input, { target: { value: text } }) };
  };

  it("lists the first three places a name finds, nearest first, with a row for the rest", async () => {
    const { type, searchPlaces } = placeSetup();
    type("blairs");
    expect(await screen.findByText("DZJ · BLAIRSVILLE")).toBeInTheDocument();
    expect(searchPlaces).toHaveBeenCalledWith("blairs", { lat: 34.8, lon: -83.9 }, 20);
    expect(optionTexts()).toEqual(["DZJ · BLAIRSVILLE", "1N7 · BLAIRSTOWN", "1GA3 · UNION GENERAL HOSPITAL", "Show 2 more places"]);
    expect(screen.getByText("Airport · BLAIRSVILLE, GA · 1,907 ft")).toBeInTheDocument();
    expect(document.querySelector(".target-field__end").textContent).toBe("6 nm");
    // A name is no grid: the reader's sentence about grids is not shown.
    expect(messageText()).toBeUndefined();
    expect(statusText()).toContain("3 places by that name, and 2 more.");
  });

  it("shows the rest when asked, with the arrow keys and Enter, keeping the row picked", async () => {
    const { input, type } = placeSetup();
    type("blairs");
    await screen.findByText("DZJ · BLAIRSVILLE");
    for (let i = 0; i < 4; i += 1) fireEvent.keyDown(input, { key: "ArrowDown" });
    fireEvent.keyDown(input, { key: "Enter" });
    expect(optionTexts()).toEqual(["DZJ · BLAIRSVILLE", "1N7 · BLAIRSTOWN", "1GA3 · UNION GENERAL HOSPITAL", "9GA1 · BLAIRS PAD", "BLAIR"]);
    expect(screen.getAllByRole("option")[3]).toHaveAttribute("aria-selected", "true");
    // Typing again folds it back to the first few.
    type("blairsv");
    expect(optionTexts()).toContain("Show 2 more places");
  });

  it("chooses a place as a reading is chosen, with its label", async () => {
    const { type, onChoose } = placeSetup();
    type("dzj");
    fireEvent.click(await screen.findByText("DZJ · BLAIRSVILLE"));
    expect(onChoose).toHaveBeenCalledWith(expect.objectContaining({
      lat: 34.85508, lon: -83.99733, label: "BLAIRSVILLE (DZJ)", place: expect.objectContaining({ ident: "DZJ" }),
    }));
    expect(onChoose.mock.calls[0][0].mgrs).toMatch(/^17S /);
  });

  it("chooses the first place on GO, once the list is the answer for the text", async () => {
    const { type, onChoose } = placeSetup();
    type("blairs");
    await screen.findByText("DZJ · BLAIRSVILLE");
    fireEvent.click(screen.getByText("GO"));
    expect(onChoose).toHaveBeenCalledWith(expect.objectContaining({ label: "BLAIRSVILLE (DZJ)" }));
  });

  it("says when no place has that name", async () => {
    const empty = placeSetup({ answer: { available: true, places: [] } });
    empty.type("zzzq");
    await waitFor(() => expect(messageText()).toBe("No airport, heliport, navaid or fix by that name or identifier."));
  });

  it("lists a GNIS name with its county, and credits the sources under the places", async () => {
    const bald = { id: "gnis:354520", source: "gnis", kind: "summit", kindWords: "Summit", name: "Brasstown Bald", ident: null,
      city: null, county: "Towns", state: "GA", lat: 34.8742, lon: -83.8107, elevationFt: null };
    const { type } = placeSetup({ answer: { available: true, nasrCycle: "2026-10-29", gnisDate: "2026-11-02", places: [bald] } });
    type("brasstown");
    expect(await screen.findByText("Brasstown Bald")).toBeInTheDocument();
    expect(screen.getByText("Summit · Towns County, GA")).toBeInTheDocument();
    expect(document.querySelector(".target-field__credit").textContent).toBe("Places: FAA NASR 2026-10-29 · USGS GNIS 2026-11-02");
    // The credit is no row to pick.
    expect(optionTexts()).toEqual(["Brasstown Bald"]);
  });

  it("says named places are searched too when the server has GNIS", async () => {
    const empty = placeSetup({ answer: { available: true, gnisDate: "2026-11-02", places: [] } });
    empty.type("zzzq");
    await waitFor(() => expect(messageText()).toBe("No airport, heliport, navaid, fix or named place by that name or identifier."));
    expect(document.querySelector(".target-field__credit")).toBeNull();
  });

  it("says the search failed, and that grids still work", async () => {
    const failing = placeSetup({ answer: () => { throw new Error("down"); } });
    failing.type("blairs");
    await waitFor(() => expect(messageText()).toBe("The place search is not answering. Grids and lat/longs still work."));
  });

  it("asks nothing for a grid, and offers places under a digit-led identifier", async () => {
    const { type, searchPlaces } = placeSetup();
    type("16sgc2886455349");
    expect(optionTexts()).toEqual(["16S GC 28864 55349", "33.91372° N, 84.52451° W"]);
    await new Promise((resolve) => setTimeout(resolve, 200));
    expect(searchPlaces).not.toHaveBeenCalled();
    type("1ga3");
    expect(await screen.findByText("DZJ · BLAIRSVILLE")).toBeInTheDocument();
    expect(searchPlaces).toHaveBeenCalledWith("1ga3", expect.anything(), 20);
  });

  it("drops an answer for text typed before", async () => {
    let release;
    const slow = new Promise((resolve) => { release = resolve; });
    const answers = [slow, Promise.resolve({ available: true, places: [PLACES[4]] })];
    const { type } = placeSetup({ answer: () => answers.shift() });
    type("blai");
    await new Promise((resolve) => setTimeout(resolve, 200));
    type("blair");
    expect(await screen.findByText("BLAIR")).toBeInTheDocument();
    release({ available: true, places: PLACES });
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(optionTexts()).toEqual(["BLAIR"]);
  });

  it("shares a place's grid with its name", async () => {
    const onShare = vi.fn();
    const { type } = placeSetup({ onShare });
    type("dzj");
    await screen.findByText("DZJ · BLAIRSVILLE");
    fireEvent.click(screen.getByRole("button", { name: /^Share grid 17S/ }));
    expect(onShare).toHaveBeenCalledWith(expect.objectContaining({ label: "BLAIRSVILLE (DZJ)" }));
  });

  it("says it searches places in its placeholder only when it does", () => {
    placeSetup();
    expect(screen.getByRole("combobox")).toHaveAttribute("placeholder", "Search by grid, lat/long or place");
  });
});
