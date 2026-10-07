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
