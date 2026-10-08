import { fireEvent, render, screen } from "@testing-library/react";
import React from "react";
import Controls, { diagramReadiness } from "./Controls";

// The LZ/PZ cards need a map and terrain; the Routes card is what is under test.
const NO_LZ_TOOLS = { lz_pz_tools: false, exports: false };

const renderControls = (props = {}) =>
  render(
    <Controls
      aircraftProfiles={[]}
      onSelectAircraft={() => {}}
      onManageAircraft={() => {}}
      gridInput=""
      setGridInput={() => {}}
      handleSearch={() => {}}
      isSketching={false}
      toggleRouteSketch={() => {}}
      features={NO_LZ_TOOLS}
      {...props}
    />,
  );

describe("the sidebar's Routes card", () => {
  it("holds the Route tool only: mission files come in through the Import menu and its review", () => {
    const toggleRouteSketch = jest.fn();
    const { container } = renderControls({ toggleRouteSketch });
    expect(screen.getByText("Routes", { selector: ".ff-card-header" })).toBeInTheDocument();
    expect(screen.queryByText(/Routes \(\.msnx\)/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Import MSNX/i)).not.toBeInTheDocument();
    expect(container.querySelector('input[type="file"]')).toBeNull();

    fireEvent.click(screen.getByTitle("Sketch a route (click the map to add points)"));
    expect(toggleRouteSketch).toHaveBeenCalledTimes(1);
  });

  it("is not shown when routes are turned off, whether or not mission import is on", () => {
    const { container } = renderControls({ features: { ...NO_LZ_TOOLS, routes: false, msnx_import: true } });
    expect(container.querySelector(".ff-card-routes")).toBeNull();
  });
});

describe("the sidebar's Export card", () => {
  const analysed = {
    features: { lz_pz_tools: true, exports: true, routes: false },
    targetLocation: [34, -84],
    mapData: { mgrs: "16S GC 1 2" },
    enableExportMode: jest.fn(),
    setIsExporting: jest.fn(),
    showHeatmap: false,
    setShowHeatmap: () => {},
    showLZOutline: true,
    setShowLZOutline: () => {},
    performTerrainAnalysis: () => {},
    toggleDrawingMode: () => {},
    canAnalyze: false,
    canDrawBoundary: false,
    diagramStatus: "analyzed",
  };

  it("exports an analysed LZ/PZ that may not be changed (a finished pack), while its tools stay off", () => {
    const enableExportMode = jest.fn();
    const setIsExporting = jest.fn();
    renderControls({
      ...analysed,
      enableExportMode,
      setIsExporting,
      exportBox: [[34, -84], [34.01, -83.99]],
      canUseDiagramTools: false,
      canSaveDiagram: false,
      canExport: true,
    });
    expect(screen.getByText("Helo", { selector: ".btn-label" }).closest("button")).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Set Capture Area" }));
    expect(enableExportMode).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole("button", { name: "Export LZ Card" }));
    expect(setIsExporting).toHaveBeenCalledWith(true);
  });

  it("does not export what is not analysed", () => {
    renderControls({ ...analysed, diagramStatus: "targeted", canUseDiagramTools: false, canSaveDiagram: false, canExport: false });
    expect(screen.getByRole("button", { name: "Set Capture Area" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Export LZ Card" })).toBeDisabled();
  });
});

describe("the sidebar's LZ/PZ analysis card", () => {
  const card = (props) =>
    renderControls({
      features: { lz_pz_tools: true, exports: false, routes: false },
      targetLocation: [34, -84],
      mapData: { mgrs: "16S GC 1 2" },
      showHeatmap: false,
      setShowHeatmap: () => {},
      showLZOutline: true,
      setShowLZOutline: () => {},
      toggleDrawingMode: () => {},
      performTerrainAnalysis: () => {},
      canAnalyze: true,
      canDrawBoundary: true,
      ...props,
    });

  it("offers to analyse again once analysed, and says what that unlocked", () => {
    const performTerrainAnalysis = jest.fn();
    card({
      performTerrainAnalysis,
      diagramStatus: "analyzed",
      canUseDiagramTools: true,
      diagramReadinessText: diagramReadiness({ hasTarget: true, analyzed: true }),
    });
    expect(screen.queryByRole("button", { name: "Analyze the LZ" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Re-analyze" }));
    expect(performTerrainAnalysis).toHaveBeenCalledWith(34, -84);
    expect(screen.getByRole("status")).toHaveTextContent("Analyzed. Planning graphics, export and save are unlocked.");
  });

  it("asks for an analysis while there is only a target", () => {
    card({
      diagramStatus: "targeted",
      canUseDiagramTools: false,
      diagramReadinessText: diagramReadiness({ hasTarget: true, analyzed: false }),
    });
    expect(screen.getByRole("button", { name: "Analyze the LZ" })).toBeEnabled();
    expect(screen.getByRole("status")).toHaveTextContent("Target set. Analyze the LZ to unlock planning graphics, export, and save.");
  });
});

describe("what the analysis card says", () => {
  it("follows the LZ/PZ from no target to analysed, and never calls tools unlocked where they are not", () => {
    expect(diagramReadiness({ hasTarget: false, analyzed: false })).toBe("Set a target on the map to initialize an LZ/PZ diagram.");
    expect(diagramReadiness({ hasTarget: true, analyzed: false })).toBe("Target set. Analyze the LZ to unlock planning graphics, export, and save.");
    expect(diagramReadiness({ hasTarget: true, analyzed: true })).toBe("Analyzed. Planning graphics, export and save are unlocked.");
    expect(diagramReadiness({ hasTarget: true, analyzed: true, editable: false })).toBe("Analyzed. Read-only here: viewing and export still work.");
  });
});
