import { fireEvent, render, screen } from "@testing-library/react";
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
    const enableExportMode = jest.fn();
    const onDownloadClick = jest.fn();
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
    const onAnalyze = jest.fn();
    renderQuickAccess({ ...targeted, onAnalyze, onToggleDrawing: () => {} });
    const analyze = screen.getByRole("button", { name: "Analyze" });
    expect(analyze).toBeEnabled();
    fireEvent.click(analyze);
    expect(onAnalyze).toHaveBeenCalledTimes(1);
  });

  it("offers drawing the boundary by hand, and finishing it", () => {
    const onToggleDrawing = jest.fn();
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
    const onAnalyze = jest.fn();
    const onToggleDrawing = jest.fn();
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
