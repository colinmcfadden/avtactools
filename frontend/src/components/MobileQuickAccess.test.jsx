import { fireEvent, render, screen } from "@testing-library/react";
import fs from "fs";
import path from "path";
import React from "react";
import MobileQuickAccess from "./MobileQuickAccess";

const renderQuickAccess = (props = {}) =>
  render(
    <MobileQuickAccess
      features={{ routes: false }}
      addHelo={() => {}}
      enableExportMode={() => {}}
      onDownloadClick={() => {}}
      exportBox={[[34, -84], [34.01, -83.99]]}
      isExporting={false}
      exportProgress={0}
      diagramStatus="analyzed"
      {...props}
    />,
  );

describe("the phone's quick access", () => {
  it("exports an analysed LZ/PZ that may not be changed (a finished pack), while its tools stay off", () => {
    const enableExportMode = vi.fn();
    const onDownloadClick = vi.fn();
    renderQuickAccess({ enableExportMode, onDownloadClick, canUseDiagramTools: false, canSaveDiagram: false, canExport: true });
    expect(screen.getByAltText("Helo").closest("button")).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: /Capture/ }));
    expect(enableExportMode).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole("button", { name: /Export/ }));
    expect(onDownloadClick).toHaveBeenCalledTimes(1);
  });

  it("does not export what is not analysed", () => {
    renderQuickAccess({ diagramStatus: "targeted", canUseDiagramTools: false, canSaveDiagram: false, canExport: false });
    expect(screen.getByRole("button", { name: /Capture/ })).toBeDisabled();
    expect(screen.getByRole("button", { name: /Export/ })).toBeDisabled();
  });
});

// On a phone the sidebar that holds Analyze and Draw LZ is behind the top bar's menu button, so once a
// target is set the next step has to be on the map's own tools (the owner, 2026-10-19).
describe("the phone's quick access, once a target is set", () => {
  const targeted = { diagramStatus: "targeted", canAnalyze: true, canDrawBoundary: true, canUseDiagramTools: false, canSaveDiagram: false, canExport: false };

  it("offers Analyze, and runs it", () => {
    const onAnalyze = vi.fn();
    renderQuickAccess({ ...targeted, onAnalyze, onToggleDrawing: () => {} });
    const analyze = screen.getByRole("button", { name: "Analyze" });
    expect(analyze).toBeEnabled();
    fireEvent.click(analyze);
    expect(onAnalyze).toHaveBeenCalledTimes(1);
  });

  it("offers drawing the boundary by hand, and finishing it", () => {
    const onToggleDrawing = vi.fn();
    const { rerender } = renderQuickAccess({ ...targeted, onAnalyze: () => {}, onToggleDrawing });
    const draw = screen.getByRole("button", { name: "Draw LZ" });
    expect(draw).toHaveAttribute("aria-pressed", "false");
    fireEvent.click(draw);
    expect(onToggleDrawing).toHaveBeenCalledTimes(1);

    rerender(
      <MobileQuickAccess
        features={{ routes: false }}
        addHelo={() => {}}
        enableExportMode={() => {}}
        onDownloadClick={() => {}}
        isExporting={false}
        exportProgress={0}
        {...targeted}
        onAnalyze={() => {}}
        onToggleDrawing={onToggleDrawing}
        isDrawingLZ
      />,
    );
    const finish = screen.getByRole("button", { name: "Finish LZ" });
    expect(finish).toHaveAttribute("aria-pressed", "true");
    fireEvent.click(finish);
    expect(onToggleDrawing).toHaveBeenCalledTimes(2);
  });

  it("reads Re-analyze once analysed", () => {
    renderQuickAccess({ canAnalyze: true, canDrawBoundary: true, onAnalyze: () => {}, onToggleDrawing: () => {} });
    expect(screen.getByRole("button", { name: "Re-analyze" })).toBeEnabled();
  });

  it("offers neither before a target is set, and says why", () => {
    const onAnalyze = vi.fn();
    const onToggleDrawing = vi.fn();
    renderQuickAccess({ ...targeted, diagramStatus: "draft", canAnalyze: false, canDrawBoundary: false, onAnalyze, onToggleDrawing });
    const analyze = screen.getByRole("button", { name: "Analyze" });
    const draw = screen.getByRole("button", { name: "Draw LZ" });
    expect(analyze).toBeDisabled();
    expect(draw).toBeDisabled();
    expect(analyze).toHaveAttribute("title", expect.stringMatching(/Set a target/));
    fireEvent.click(analyze);
    fireEvent.click(draw);
    expect(onAnalyze).not.toHaveBeenCalled();
    expect(onToggleDrawing).not.toHaveBeenCalled();
  });

  it("changes nothing where the LZ/PZ may not be changed (a finished pack)", () => {
    renderQuickAccess({ canAnalyze: false, canDrawBoundary: false, canUseDiagramTools: false, canSaveDiagram: false, canExport: true, onAnalyze: () => {}, onToggleDrawing: () => {} });
    expect(screen.getByRole("button", { name: "Re-analyze" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Draw LZ" })).toBeDisabled();
  });
});

// jsdom applies no stylesheet, so the rules themselves are read. A media block holds rules one level deep.
const MEDIA = /@media([^{]+)\{((?:[^{}]*\{[^{}]*\})*[^{}]*)\}/g;
const stylesheet = (file) => fs.readFileSync(path.join(__dirname, file), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
const rules = (css, selector) =>
  [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)].filter(([, sel]) => sel.trim() === selector).map(([, , body]) => body);
const px = (body, property) => Number(body.match(new RegExp(`${property}: (?:calc\\()?(\\d+)px`))?.[1]);

describe("the phone's LZ card export", () => {
  it("is not inside the rail, whose stacking context lies under the bottom sheet", () => {
    const { container } = renderQuickAccess();
    const capture = screen.getByRole("button", { name: /Capture/ });
    expect(capture.closest(".mobile-quick-access-container")).toBeNull();
    // Beside the rail in the map, as the Layers button is: the map ends where the sheet begins.
    expect(capture.closest(".mobile-export-container").parentElement).toBe(container);
  });

  it("stands in the map above the Layers button, at the left, clear of the rail, and only below 850 px", () => {
    const css = stylesheet("MobileQuickAccess.css");
    const wide = css.replace(MEDIA, "");
    const phone = [...css.matchAll(MEDIA)]
      .filter(([, query]) => query.trim() === "(max-width: 850px)")
      .flatMap(([, , body]) => rules(body, ".mobile-export-container"));
    expect(rules(wide, ".mobile-export-container")).toEqual([expect.stringMatching(/display: none;/)]);
    expect(phone).toHaveLength(1);
    const [box] = phone;
    // Fixed, a bottom offset is the window's, under the sheet whatever its height.
    expect(css).not.toMatch(/position: fixed/);
    expect(box).toMatch(/position: absolute;/);
    expect(box).toMatch(/left: \d+px;/);
    expect(box).not.toMatch(/right:/);

    const layersCss = stylesheet("../feature/mapStyles/MapStyleSwitcher.css");
    const [layers] = rules(layersCss, ".map-style-switcher");
    const [toggle] = rules(layersCss, ".map-style-toggle");
    expect(px(box, "left")).toBe(px(layers, "left"));
    expect(px(box, "bottom")).toBeGreaterThan(px(layers, "bottom") + px(toggle, "height"));
  });
});
