import React from "react";
import { fireEvent, render, screen, within } from "@testing-library/react";
import Obstacles, { obstacleWords, spotWords } from "./Obstacles";
import SurveyMap from "./SurveyMap";

jest.mock("react-leaflet", () => ({
  MapContainer: ({ children }) => <div>{children}</div>,
  TileLayer: () => null,
  Polygon: () => null,
  Polyline: ({ children }) => <div>{children}</div>,
  CircleMarker: ({ children }) => <div data-testid="marker">{children}</div>,
  ImageOverlay: ({ url, bounds }) => <img alt="What stands round the LZ" src={url} data-bounds={JSON.stringify(bounds)} />,
  Tooltip: ({ children }) => <span>{children}</span>,
  useMap: () => ({ setView: () => {}, getZoom: () => 16 }),
  useMapEvents: () => null,
}));
jest.mock("../terrain/SlopeLayer", () => () => null);
jest.mock("../ui/Toast", () => ({ useToast: () => ({ show: () => {} }) }));

const SPOT = { lat: 34.596, lon: -84.1278 };
const MEASURED = {
  status: "measured",
  words: null,
  survey: { name: "GA_Statewide_B3_2018", year: 2018 },
  approach: {
    headingDeg: 90, clearance: { angleDeg: 8.6, obstacle: { ...SPOT, heightFt: 98.4, kind: "vegetation", fromEdgeM: 198, offsetM: -4.2 } },
    fromTarget: { angleDeg: 5.9, obstacle: { ...SPOT, heightFt: 98.4, kind: "vegetation" } },
  },
  exit: { headingDeg: 270, clearance: { angleDeg: -1.2, obstacle: { ...SPOT, heightFt: 0, kind: "terrain", fromEdgeM: 3 } }, fromTarget: null },
  inside: { ...SPOT, heightFt: 39.4, kind: "vegetation", bearingDeg: 33.7, distanceM: 36.1 },
  around: { ...SPOT, heightFt: 26.2, kind: "building", bearingDeg: 0, distanceM: 110, fromEdgeM: 50 },
  standing: { overM: 2, count: 1234, cells: [] },
  trees: [{ ...SPOT, heightFt: 39.4 }],
  heightMap: { overlay: "data:image/png;base64,AAAA", bounds: [[34.59, -84.13], [34.6, -84.12]] },
};

describe("what the LiDAR found", () => {
  it("says each way's clearance from the LZ's edge and from the touchdown point, and what stands", () => {
    render(<Obstacles obstacles={MEASURED} />);
    const group = screen.getByRole("group", { name: "Standing on and round the LZ · LiDAR GA_Statewide_B3_2018" });
    const approach = within(group).getByRole("row", { name: /Approach 090°/ });
    expect(approach).toHaveTextContent("8.6°");
    expect(approach).toHaveTextContent("98 ft tree 198 m out, 4 m left of the track");
    expect(approach).toHaveTextContent("5.9°");
    expect(within(group).getByRole("row", { name: /Exit 270°/ })).toHaveTextContent("Nothing above the edge");
    expect(within(group).getByText("39 ft tree, 36 m at 034°")).toBeInTheDocument();
    expect(within(group).getByText("50 m outside the boundary")).toBeInTheDocument();
    expect(within(group).getByText("1,234 LiDAR points")).toBeInTheDocument();
    expect(within(group).getByText("1 of 5 m or more, the tallest 39 ft")).toBeInTheDocument();
    expect(within(group).getByText(/wires and poles seldom show in it/)).toBeInTheDocument();
  });

  it("says why there is nothing, and that a failed reading is to try again", () => {
    const { rerender } = render(<Obstacles obstacles={{ status: "none", words: "No USGS LiDAR covers this LZ, so nothing standing is measured." }} />);
    expect(screen.getByText("No USGS LiDAR covers this LZ, so nothing standing is measured.")).toHaveClass("survey-muted");
    rerender(<Obstacles obstacles={{ status: "failed", words: "The USGS LiDAR could not be read: measure the LZ again later." }} />);
    expect(screen.getByText(/measure the LZ again later/)).toHaveClass("survey-state--warn");
    rerender(<Obstacles obstacles={null} />);
    expect(screen.queryByRole("group")).toBeNull();
  });

  it("names a way that was not measured", () => {
    render(<Obstacles obstacles={{ ...MEASURED, exit: null }} />);
    expect(screen.getByText("Not measured: set the best exit and measure again.")).toBeInTheDocument();
  });

  it("words a point and an obstacle as the crew reads them", () => {
    expect(spotWords({ heightFt: 26.2, kind: "building", distanceM: 110.4, bearingDeg: 359.6 })).toBe("26 ft building, 110 m at 360°");
    expect(spotWords(null)).toBeNull();
    expect(obstacleWords({ kind: "terrain", fromEdgeM: 12, offsetM: 0.4 })).toBe("The ground 12 m out");
    expect(obstacleWords({ kind: "tower", heightFt: 164, fromEdgeM: 399, offsetM: 10 })).toBe("164 ft tower 399 m out, 10 m right of the track");
  });
});

describe("the survey's map with what the LiDAR found", () => {
  const props = { diagram: { target: { lat: 34.596, lon: -84.1278 } }, field: {}, terrain: null, onSetTarget: () => {}, onSetBoundary: () => {} };

  it("lays the heights on the map with their legend, marks the tallest, and hides them when asked", () => {
    render(<SurveyMap {...props} obstacles={MEASURED} />);
    const picture = screen.getByRole("img", { name: "What stands round the LZ" });
    expect(picture).toHaveAttribute("src", MEASURED.heightMap.overlay);
    expect(screen.getByText("Tallest in the LZ: 39 ft tree, 36 m at 034°")).toBeInTheDocument();
    expect(screen.getByText("Tallest within 100 m: 26 ft building, 110 m at 360°")).toBeInTheDocument();
    expect(within(screen.getByRole("list", { name: "Height above the ground" })).getAllByRole("listitem")).toHaveLength(4);
    fireEvent.click(screen.getByRole("checkbox", { name: "What stands (LiDAR)" }));
    expect(screen.queryByRole("img", { name: "What stands round the LZ" })).toBeNull();
    expect(screen.queryAllByTestId("marker").every((marker) => !/Tallest/.test(marker.textContent))).toBe(true);
  });

  it("offers no heights where nothing was measured", () => {
    render(<SurveyMap {...props} obstacles={{ status: "unavailable", words: "This server does not read the USGS LiDAR." }} />);
    expect(screen.queryByRole("checkbox", { name: "What stands (LiDAR)" })).toBeNull();
  });
});
