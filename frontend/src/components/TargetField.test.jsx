import React, { useState } from "react";
import { fireEvent, render, screen } from "@testing-library/react";
import TargetField from "./TargetField";
import { formatMGRS } from "../utils/Helpers";

// A field as the app holds it: the text in state, every choice recorded.
const Harness = ({ initial = "", near = null, onChoose }) => {
  const [value, setValue] = useState(initial);
  return <TargetField value={value} onChange={setValue} onChoose={onChoose} near={near} />;
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
