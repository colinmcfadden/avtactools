import { act, fireEvent, render, screen } from "@testing-library/react";
import fs from "fs";
import path from "path";
import React from "react";
import Dock, { useDock } from "./Dock";

// jsdom has no matchMedia, which tells the dock the window is narrow enough for the bottom sheet, and
// no PointerEvent, which carries the drag's clientY. jsdom's window is 1024 x 768: the sheet starts at
// half, round(0.48 x 768) = 369 px; the peek is 132 px and full is 768 - 56 = 712 px.
let narrow;
let mediaListeners;
const OriginalPointerEvent = window.PointerEvent;
beforeAll(() => {
  window.PointerEvent = class extends MouseEvent {
    constructor(type, init = {}) {
      super(type, init);
      this.pointerId = init.pointerId ?? 1;
    }
  };
});
afterAll(() => {
  window.PointerEvent = OriginalPointerEvent;
});
beforeEach(() => {
  narrow = true;
  mediaListeners = new Set();
  window.matchMedia = jest.fn(() => ({
    get matches() {
      return narrow;
    },
    addEventListener: (_, fn) => mediaListeners.add(fn),
    removeEventListener: (_, fn) => mediaListeners.delete(fn),
  }));
});
afterEach(() => {
  delete window.matchMedia;
  jest.useRealTimers();
});
const widen = () =>
  act(() => {
    narrow = false;
    mediaListeners.forEach((fn) => fn());
  });

const items = [
  { key: "lz", label: "LZ/PZ", icon: "hexagon" },
  { key: "threats", label: "Threats", icon: "diamond" },
];
// As in App.js: the map and the dock are siblings in the map area.
const Harness = () => {
  const dock = useDock("lz");
  return (
    <div data-testid="map-area">
      <div className="shell-map" />
      <Dock items={items} dock={dock}>
        <div>panel {dock.panel}</div>
      </Dock>
    </div>
  );
};
const area = () => screen.getByTestId("map-area");
const sheetHeight = () => area().style.getPropertyValue("--sheet-height");
const mapBottom = () => area().style.getPropertyValue("--map-bottom");

describe("the bottom sheet on a narrow window", () => {
  it("ends the map where the sheet begins, so a target set by grid lands in view", () => {
    render(<Harness />);
    expect(sheetHeight()).toBe("369px");
    expect(mapBottom()).toBe("369px");
  });

  it("grows the map as soon as the sheet lowers, and shrinks it only once the sheet is up", () => {
    jest.useFakeTimers();
    render(<Harness />);
    fireEvent.click(screen.getByRole("button", { name: "LZ/PZ" })); // its own tab lowers the sheet to the peek
    expect(sheetHeight()).toBe("132px");
    expect(mapBottom()).toBe("132px");

    fireEvent.click(screen.getByRole("button", { name: "Threats" })); // another tab raises it to half
    expect(sheetHeight()).toBe("369px");
    expect(mapBottom()).toBe("132px"); // the sheet slides up over the map
    act(() => jest.advanceTimersByTime(200));
    expect(mapBottom()).toBe("369px");
  });

  it("while the handle is dragged, slides the sheet over a map that reaches down to the peek", () => {
    jest.useFakeTimers();
    render(<Harness />);
    const handle = screen.getByRole("button", { name: "Raise the panel" });
    const sheet = screen.getByRole("complementary", { name: "Panels" });
    fireEvent.pointerDown(handle, { clientY: 400 });
    fireEvent.pointerMove(handle, { clientY: 300 });
    expect(sheetHeight()).toBe("469px");
    expect(mapBottom()).toBe("132px");
    expect(sheet).toHaveStyle({ transition: "none" }); // the sheet follows the finger

    fireEvent.pointerUp(handle, { clientY: 300 }); // nearest to 469 px is half
    expect(sheetHeight()).toBe("369px");
    expect(sheet.style.transition).toBe("");
    expect(mapBottom()).toBe("132px");
    act(() => jest.advanceTimersByTime(200));
    expect(mapBottom()).toBe("369px");
  });

  it("puts the map back beside the dock when the window widens", () => {
    render(<Harness />);
    widen();
    expect(sheetHeight()).toBe("");
    expect(mapBottom()).toBe("");
  });

  it("is what shell.css sizes the map and the sheet from, below 1100 px only", () => {
    // jsdom applies no stylesheet, so the rules themselves are read.
    const css = fs.readFileSync(path.join(__dirname, "shell.css"), "utf8");
    const wide = css.slice(0, css.indexOf("@media (max-width: 1100px)"));
    const narrowRules = css.slice(css.indexOf("@media (max-width: 1100px)"));
    expect(wide).toMatch(/\.shell-map \{[^}]*bottom: 0;/);
    expect(narrowRules).toMatch(/\.shell-map--dock-collapsed \{[^}]*bottom: var\(--map-bottom, 132px\)/);
    expect(narrowRules).toMatch(/\.shell-dock \{[^}]*height: var\(--sheet-height, 132px\)/);
  });
});
