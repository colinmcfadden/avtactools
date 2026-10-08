import { fireEvent, render, screen } from "@testing-library/react";
import fs from "fs";
import path from "path";
import React from "react";
import MapStyleSwitcher from "./MapStyleSwitcher";
import { MAP_STYLES } from "./mapStyles";
import useMgrsGrid, { MGRS_GRID_STORAGE_KEY } from "../mgrsGrid/useMgrsGrid";

// The switcher as App composes it: the base map in state, the grid from its hook.
const Harness = ({ initialStyle = "satellite" }) => {
  const [mapStyle, setMapStyle] = React.useState(initialStyle);
  const { showMgrsGrid, setShowMgrsGrid } = useMgrsGrid();
  return (
    <>
      <MapStyleSwitcher
        mapStyle={mapStyle}
        setMapStyle={setMapStyle}
        showMgrsGrid={showMgrsGrid}
        setShowMgrsGrid={setShowMgrsGrid}
      />
      <output data-testid="map">{`${mapStyle} grid:${showMgrsGrid ? "on" : "off"}`}</output>
    </>
  );
};

const layersButton = () => screen.getByRole("button", { name: /^Layers/ });
const gridSwitch = () => screen.getByRole("switch", { name: "MGRS grid" });
const openLayers = () => fireEvent.click(layersButton());

afterEach(() => {
  jest.restoreAllMocks();
  window.localStorage.clear();
});

describe("the Layers control's MGRS grid switch", () => {
  it("is offered with whichever base map is chosen, and starts off", () => {
    MAP_STYLES.forEach(({ id }) => {
      const { unmount } = render(<Harness initialStyle={id} />);
      openLayers();
      expect(gridSwitch()).toHaveAttribute("aria-checked", "false");
      expect(gridSwitch()).toHaveTextContent("Off");
      unmount();
    });
  });

  it("turns the grid on and off, staying open, without changing the base map", () => {
    render(<Harness initialStyle="topo" />);
    openLayers();
    fireEvent.click(gridSwitch());
    expect(gridSwitch()).toHaveAttribute("aria-checked", "true");
    expect(gridSwitch()).toHaveTextContent("On");
    expect(screen.getByTestId("map")).toHaveTextContent("topo grid:on");
    fireEvent.click(gridSwitch());
    expect(gridSwitch()).toHaveAttribute("aria-checked", "false");
    expect(screen.getByTestId("map")).toHaveTextContent("topo grid:off");
  });

  it("keeps the grid when the base map changes", () => {
    render(<Harness />);
    openLayers();
    fireEvent.click(gridSwitch());
    fireEvent.click(screen.getByRole("button", { name: "VFR" }));
    expect(screen.queryByRole("switch")).toBeNull(); // picking a map closes the control
    expect(screen.getByTestId("map")).toHaveTextContent("vfr-sectional grid:on");
    openLayers();
    expect(gridSwitch()).toHaveAttribute("aria-checked", "true");
  });

  it("says on the closed Layers button that the grid is on", () => {
    const { container } = render(<Harness />);
    expect(layersButton()).toHaveAccessibleName("Layers");
    expect(container.querySelector(".map-style-toggle__badge")).toBeNull();
    openLayers();
    fireEvent.click(gridSwitch());
    fireEvent.click(layersButton());
    expect(screen.queryByRole("switch")).toBeNull();
    expect(layersButton()).toHaveAccessibleName("Layers, MGRS grid on");
    expect(layersButton()).toHaveAttribute("aria-expanded", "false");
    expect(container.querySelector(".map-style-toggle__badge")).toHaveTextContent("MGRS");
  });

  it("is remembered across a reload of the page", () => {
    const first = render(<Harness />);
    openLayers();
    fireEvent.click(gridSwitch());
    expect(window.localStorage.getItem(MGRS_GRID_STORAGE_KEY)).toBe("on");
    first.unmount();

    render(<Harness />);
    expect(layersButton()).toHaveAccessibleName("Layers, MGRS grid on");
  });

  it("still works when the browser refuses storage", () => {
    jest.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("SecurityError");
    });
    jest.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("QuotaExceededError");
    });
    render(<Harness />);
    openLayers();
    expect(gridSwitch()).toHaveAttribute("aria-checked", "false");
    fireEvent.click(gridSwitch());
    expect(gridSwitch()).toHaveAttribute("aria-checked", "true");
  });

  it("is not offered where no one can turn it on", () => {
    render(<MapStyleSwitcher mapStyle="satellite" setMapStyle={() => {}} />);
    openLayers();
    expect(screen.queryByRole("switch")).toBeNull();
    expect(layersButton()).toHaveAccessibleName("Layers");
  });

  it("sets its own text at 11 px or more (docs/MENU_REDESIGN.md §3)", () => {
    // The control's older labels predate the rule; what was added for the grid is held to it.
    const css = fs.readFileSync(path.join(__dirname, "MapStyleSwitcher.css"), "utf8")
      .replace(/\/\*[\s\S]*?\*\//g, "");
    const sizes = [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)]
      .filter(([, selector]) => /map-layer-switch|map-style-toggle__badge/.test(selector))
      .flatMap(([, selector, body]) => [...body.matchAll(/font-size:\s*([^;]+)/g)]
        .map(([, value]) => ({ selector: selector.trim(), value: value.trim() })));
    expect(sizes.length).toBeGreaterThan(0);
    sizes.forEach(({ selector, value }) => {
      const px = value.match(/^(\d+(?:\.\d+)?)px$/);
      expect({ selector, ok: Boolean(px) && Number(px[1]) >= 11 }).toEqual({ selector, ok: true });
    });
  });
});
