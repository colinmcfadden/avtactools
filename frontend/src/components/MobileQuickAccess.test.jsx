import { fireEvent, render, screen, within } from "@testing-library/react";
import fs from "fs";
import path from "path";
import React from "react";
import MobileQuickAccess from "./MobileQuickAccess";
import { RAIL } from "../feature/phoneFrame/phoneFrame";

const renderQuickAccess = (props = {}) =>
  render(
    <MobileQuickAccess
      addHelo={() => {}}
      enableExportMode={() => {}}
      onDownloadClick={() => {}}
      isExporting={false}
      exportProgress={0}
      {...props}
    />,
  );

const rail = () => screen.getByRole("group", { name: "Map tools" });
const railNames = () => within(rail()).getAllByRole("button").map((button) => button.getAttribute("aria-label") ?? button.textContent.trim());

// The owner, 2026-11-11: the tools appear in the order the app is used, not all at once. The rules are
// phoneFrame.js's (and tested there); this is what the rail draws for them.
describe("the phone's rail, a step at a time", () => {
  it("offers only the route tool and Set Target before there is a target, and sets it at the crosshair", () => {
    const onSetTarget = vi.fn();
    renderQuickAccess({ onSetTarget });
    expect(railNames()).toEqual(["Sketch route", "Set Target"]);
    fireEvent.click(screen.getByRole("button", { name: "Set Target" }));
    expect(onSetTarget).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole("button", { name: "Analyze" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Add helicopter" })).toBeNull();
  });

  it("offers Analyze and Draw LZ, in words, once a target is set, and runs them", () => {
    const onAnalyze = vi.fn();
    const onToggleDrawing = vi.fn();
    renderQuickAccess({ hasTarget: true, onAnalyze, onToggleDrawing });
    expect(railNames()).toEqual(["Sketch route", "Set Target", "Analyze", "Draw LZ"]);
    const analyze = screen.getByRole("button", { name: "Analyze" });
    expect(analyze).toBeEnabled();
    expect(analyze).toHaveClass("qa-pill--primary");
    fireEvent.click(analyze);
    expect(onAnalyze).toHaveBeenCalledTimes(1);
    const draw = screen.getByRole("button", { name: "Draw LZ" });
    expect(draw).toHaveAttribute("aria-pressed", "false");
    fireEvent.click(draw);
    expect(onToggleDrawing).toHaveBeenCalledTimes(1);
  });

  it("greys Analyze out while the boundary is drawn, until Finish LZ", () => {
    const onAnalyze = vi.fn();
    const onToggleDrawing = vi.fn();
    renderQuickAccess({ hasTarget: true, isDrawingLZ: true, onAnalyze, onToggleDrawing });
    const analyze = screen.getByRole("button", { name: "Analyze" });
    expect(analyze).toBeDisabled();
    expect(analyze).toHaveAttribute("title", expect.stringMatching(/Finish the LZ\/PZ boundary/));
    fireEvent.click(analyze);
    expect(onAnalyze).not.toHaveBeenCalled();
    const finish = screen.getByRole("button", { name: "Finish LZ" });
    expect(finish).toHaveAttribute("aria-pressed", "true");
    fireEvent.click(finish);
    expect(onToggleDrawing).toHaveBeenCalledTimes(1);
  });

  it("offers the planning tools once analysed, each by name, and Re-analyze", () => {
    const addHelo = vi.fn();
    const onToggleBackstop = vi.fn();
    renderQuickAccess({ hasTarget: true, analyzed: true, addHelo, onToggleBackstop });
    expect(railNames()).toEqual([
      "Sketch route", "Set Target", "Re-analyze", "Draw LZ",
      "Add helicopter", "Add PZ marker", "Add sector of fire", "Draw a backstop", "Add unit", "Add go-around",
    ]);
    fireEvent.click(screen.getByRole("button", { name: "Add helicopter" }));
    expect(addHelo).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole("button", { name: "Draw a backstop" }));
    expect(onToggleBackstop).toHaveBeenCalledTimes(1);
  });

  it("asks which unit and which side of a go-around in a menu", () => {
    const addUnit = vi.fn();
    const addGoAround = vi.fn();
    const onOpenUnitBuilder = vi.fn();
    renderQuickAccess({ hasTarget: true, analyzed: true, addUnit, addGoAround, onOpenUnitBuilder });
    fireEvent.click(screen.getByRole("button", { name: "Add go-around" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Go-around left" }));
    expect(addGoAround).toHaveBeenCalledWith("left");
    fireEvent.click(screen.getByRole("button", { name: "Add unit" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Light Infantry" }));
    expect(addUnit).toHaveBeenCalledWith(expect.objectContaining({ id: "light_infantry" }));
    fireEvent.click(screen.getByRole("button", { name: "Add unit" }));
    fireEvent.click(screen.getByRole("menuitem", { name: /Build a MIL-STD symbol/ }));
    expect(onOpenUnitBuilder).toHaveBeenCalledTimes(1);
  });

  it("offers no tool where nothing may be changed (a finished pack), and still exports", () => {
    const enableExportMode = vi.fn();
    const onDownloadClick = vi.fn();
    renderQuickAccess({ hasTarget: true, analyzed: true, editable: false, canExport: true, exportBox: [[34, -84], [34.01, -83.99]], enableExportMode, onDownloadClick });
    expect(screen.queryByRole("group", { name: "Map tools" })).toBeNull();
    expect(screen.queryByRole("button", { name: /tools/ })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: /Capture/ }));
    expect(enableExportMode).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole("button", { name: /Export/ }));
    expect(onDownloadClick).toHaveBeenCalledTimes(1);
  });
});

describe("what a short map has room for", () => {
  const rows = (count) => RAIL.top + RAIL.foot + count * (RAIL.size + RAIL.gap) - RAIL.gap;

  it("puts the earliest steps behind More, by name, and runs them from there", () => {
    const toggleRouteSketch = vi.fn();
    renderQuickAccess({ hasTarget: true, analyzed: true, mapHeight: rows(4), toggleRouteSketch });
    expect(railNames()).toEqual(["More tools", "Add helicopter", "Add PZ marker", "Add sector of fire", "Draw a backstop", "Add unit", "Add go-around"]);
    fireEvent.click(screen.getByRole("button", { name: "More tools" }));
    const menu = screen.getByRole("menu", { name: "More tools" });
    expect(within(menu).getAllByRole("menuitem").map((item) => item.textContent.trim())).toEqual(["Sketch route", "Set Target", "Re-analyze", "Draw LZ"]);
    fireEvent.click(within(menu).getByRole("menuitem", { name: "Sketch route" }));
    expect(toggleRouteSketch).toHaveBeenCalledTimes(1);
  });

  it("draws nothing of the rail where not even a row fits", () => {
    renderQuickAccess({ hasTarget: true, analyzed: true, mapHeight: 60 });
    expect(within(rail()).queryAllByRole("button")).toEqual([]);
  });
});

describe("the row under the rail", () => {
  it("folds the tools away and brings them back, and says which it will do", () => {
    const onToggleFolded = vi.fn();
    const { rerender } = renderQuickAccess({ onToggleFolded });
    const hide = screen.getByRole("button", { name: "Hide tools" });
    expect(hide).toHaveAttribute("aria-expanded", "true");
    expect(hide).toHaveAttribute("aria-controls", rail().id);
    fireEvent.click(hide);
    expect(onToggleFolded).toHaveBeenCalledTimes(1);

    rerender(<MobileQuickAccess folded onToggleFolded={onToggleFolded} />);
    const show = screen.getByRole("button", { name: "Show tools" });
    expect(show).toHaveAttribute("aria-expanded", "false");
    expect(show).toHaveTextContent("Tools");
    expect(document.getElementById("phone-rail")).not.toBeVisible();
  });

  it("has undo and redo only while there is something to take back or put back", () => {
    const undo = { undo: vi.fn(), redo: vi.fn(), canUndo: false, canRedo: false, undoTitle: "Undo", redoTitle: "Redo" };
    const { rerender } = renderQuickAccess({ undo });
    expect(screen.queryByRole("button", { name: "Undo" })).toBeNull();
    rerender(<MobileQuickAccess undo={{ ...undo, canUndo: true, undoTitle: "Undo: Move helicopter" }} />);
    fireEvent.click(screen.getByRole("button", { name: "Undo: Move helicopter" }));
    expect(undo.undo).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button", { name: "Redo" })).toBeDisabled();
  });

  it("is out of reach with the rest while the frame is put away", () => {
    renderQuickAccess({ hasTarget: true, hidden: true });
    expect(document.getElementById("phone-rail")).toHaveAttribute("inert");
    expect(document.querySelector(".phone-foot")).toHaveAttribute("inert");
  });
});

describe("the phone's LZ card export, in its order too", () => {
  it("offers nothing before the LZ/PZ is analysed", () => {
    renderQuickAccess({ hasTarget: true, canExport: false });
    expect(screen.queryByRole("button", { name: /Capture/ })).toBeNull();
    expect(screen.queryByRole("button", { name: /Export/ })).toBeNull();
  });

  it("offers Capture once analysed, and Export once a capture area is set", () => {
    const { rerender } = renderQuickAccess({ hasTarget: true, analyzed: true, canExport: true });
    expect(screen.getByRole("button", { name: /Capture/ })).toBeEnabled();
    expect(screen.queryByRole("button", { name: /Export/ })).toBeNull();
    rerender(<MobileQuickAccess hasTarget analyzed canExport exportBox={[[34, -84], [34.01, -83.99]]} enableExportMode={() => {}} onDownloadClick={() => {}} />);
    expect(screen.getByRole("button", { name: /Export/ })).toBeEnabled();
  });

  it("does not start a second export while one is being made", () => {
    const onDownloadClick = vi.fn();
    renderQuickAccess({ hasTarget: true, analyzed: true, canExport: true, exportBox: [[34, -84], [34.01, -83.99]], isExporting: true, exportProgress: 40, onDownloadClick });
    const exporting = screen.getByRole("button", { name: /Processing/ });
    expect(exporting).toBeDisabled();
    fireEvent.click(exporting);
    expect(onDownloadClick).not.toHaveBeenCalled();
  });
});

// jsdom applies no stylesheet, so the rules themselves are read. A media block holds rules one level deep.
const MEDIA = /@media([^{]+)\{((?:[^{}]*\{[^{}]*\})*[^{}]*)\}/g;
const stylesheet = (file) => fs.readFileSync(path.join(__dirname, file), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
const rules = (css, selector) =>
  [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)].filter(([, sel]) => sel.trim() === selector).map(([, , body]) => body);
const px = (body, property) => Number(body.match(new RegExp(`${property}: (?:calc\\()?(\\d+)px`))?.[1]);
const phoneRules = (css, selector) =>
  [...css.matchAll(MEDIA)].filter(([, query]) => query.trim() === "(max-width: 850px)").flatMap(([, , body]) => rules(body, selector));

describe("where the phone's tools stand", () => {
  const css = stylesheet("MobileQuickAccess.css");
  const wide = css.replace(MEDIA, "");

  // AGENTS.md §14: a child's z-index counts only inside its parent's stacking context, and the rail's once lay
  // under the bottom sheet. Everything here is a child of the map area itself, placed from its edges.
  it("is placed in the map, each part its own child of it, never fixed to the window", () => {
    const { container } = renderQuickAccess({ hasTarget: true, analyzed: true, canExport: true, undo: { canUndo: true, canRedo: false, undoTitle: "Undo", redoTitle: "Redo" } });
    for (const part of [".phone-rail", ".phone-foot", ".mobile-export-container"]) {
      expect([part, container.querySelector(part)?.parentElement]).toEqual([part, container]);
    }
    expect(css).not.toMatch(/position: fixed/);
    for (const part of [".phone-rail", ".phone-foot", ".mobile-export-container"]) {
      const [box] = phoneRules(css, part);
      expect([part, /position: absolute;/.test(box), /bottom: /.test(box)]).toEqual([part, true, true]);
    }
  });

  it("is a phone's alone", () => {
    expect(rules(wide, ".mobile-export-container")).toEqual([expect.stringMatching(/display: none;/)]);
    expect(rules(wide, ".phone-rail,\n.phone-foot")).toEqual([expect.stringMatching(/display: none;/)]);
  });

  it("keeps the LZ card's buttons above the Layers button, at the left, clear of the rail", () => {
    const phone = phoneRules(css, ".mobile-export-container");
    expect(phone).toHaveLength(1);
    const [box] = phone;
    expect(box).toMatch(/left: \d+px;/);
    expect(box).not.toMatch(/right:/);
    const layersCss = stylesheet("../feature/mapStyles/MapStyleSwitcher.css");
    const [layers] = rules(layersCss, ".map-style-switcher");
    const [toggle] = rules(layersCss, ".map-style-toggle");
    expect(px(box, "left")).toBe(px(layers, "left"));
    expect(px(box, "bottom")).toBeGreaterThan(px(layers, "bottom") + px(toggle, "height"));
  });

  it("stands the rail on the row under it, at the right, by the measures the fit is worked out with", () => {
    const [foot] = phoneRules(css, ".phone-foot");
    const [column] = phoneRules(css, ".phone-rail");
    expect(foot).toMatch(/right: \d+px;/);
    expect(px(column, "right")).toBe(px(foot, "right"));
    // The rail's bottom: the row's offset, a button, and the gap between them (phoneFrame.js's RAIL.foot).
    const [, offset, gap] = column.match(/bottom: calc\((\d+)px \+ var\(--rail-size, 48px\) \+ (\d+)px/).map(Number);
    expect(offset).toBe(px(foot, "bottom"));
    expect(offset + RAIL.size + gap).toBe(RAIL.foot);
    const [button] = phoneRules(css, ".qa-btn");
    expect(button).toMatch(/width: var\(--rail-size, 48px\);/);
    // A finger's reach, and a glove's.
    expect(RAIL.size).toBeGreaterThanOrEqual(44);
  });
});
