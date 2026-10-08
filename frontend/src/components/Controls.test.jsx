import { fireEvent, render, screen } from "@testing-library/react";
import React from "react";
import Controls from "./Controls";

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
