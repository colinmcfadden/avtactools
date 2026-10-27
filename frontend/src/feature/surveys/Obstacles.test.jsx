import React from "react";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { typedAngle } from "./Disagreements";
import Obstacles, { inTheWay, obstacleWords, spotWords } from "./Obstacles";
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
const BINS = Array.from({ length: 100 }, () => ({ groundFt: 2200 }));
const MEASURED = {
  status: "measured",
  words: null,
  survey: { name: "GA_Statewide_B3_2018", year: 2018 },
  approach: {
    headingDeg: 90,
    start: { groundFt: 2200 },
    lengthM: 1000,
    binM: 10,
    bins: BINS,
    covered: 100,
    clearance: { angleDeg: 8.5, obstacle: { ...SPOT, heightFt: 98.4, riseFt: 98.6, kind: "vegetation", fromTargetM: 200, offsetM: -4.2 } },
    descent: { fromTargetM: 203.9, groundFt: 2200 },
    tall: [
      { fromM: 150, toM: 160, overFt: 104, obstacle: { ...SPOT, heightFt: 104, kind: "vegetation", fromTargetM: 152 } },
      { fromM: 600, toM: 610, overFt: 131.2, obstacle: { ...SPOT, heightFt: 131.2, kind: "vegetation", fromTargetM: 600 } },
      { fromM: 900, toM: 920, overFt: 110, obstacle: { ...SPOT, heightFt: 110, kind: "vegetation", fromTargetM: 905 } },
    ],
  },
  exit: { headingDeg: 270, start: { groundFt: 2200 }, bins: BINS, covered: 100, clearance: { angleDeg: 0.0, obstacle: null }, descent: null, tall: [] },
  inside: { ...SPOT, heightFt: 39.4, kind: "vegetation", bearingDeg: 33.7, distanceM: 36.1 },
  around: { ...SPOT, heightFt: 26.2, kind: "building", bearingDeg: 0, distanceM: 110, fromEdgeM: 50 },
  standing: { overM: 2, count: 1234 },
  trees: [{ ...SPOT, heightFt: 39.4 }],
  heightMap: { overlay: "data:image/png;base64,AAAA", bounds: [[34.59, -84.13], [34.6, -84.12]] },
};
const APPROACH_ROW = {
  key: "approach", label: "Approach angle", field: "8.7° (100 ft at 200 m)", computed: "8.5° (LiDAR: 98 ft tree 200 m out)",
  apart: "0.2° apart", disagrees: false, open: false, override: null, overrideStands: false, printed: "computed",
};

describe("what the LiDAR found", () => {
  it("says each way's angle from the touchdown point, where its path is 100 ft up, what sets it, and what stands", () => {
    render(<Obstacles obstacles={MEASURED} />);
    const group = screen.getByRole("group", { name: "Standing on and round the LZ · LiDAR GA_Statewide_B3_2018" });
    const approach = within(group).getByRole("row", { name: /Approach 090°/ });
    expect(approach).toHaveTextContent("8.5°");
    expect(approach).toHaveTextContent("204 m out");
    expect(approach).toHaveTextContent("98 ft tree 200 m out, 4 m left of the track");
    const exit = within(group).getByRole("row", { name: /Exit 270°/ });
    expect(exit).toHaveTextContent("Nothing in the way");
    expect(exit).toHaveTextContent("—");
    expect(within(group).getByText("39 ft tree, 36 m at 034°")).toBeInTheDocument();
    expect(within(group).getByText("50 m outside the boundary")).toBeInTheDocument();
    expect(within(group).getByText("1,234 LiDAR points")).toBeInTheDocument();
    expect(within(group).getByText("1 of 5 m or more, the tallest 39 ft")).toBeInTheDocument();
    expect(within(group).getByText(/wires and poles seldom show in it/)).toBeInTheDocument();
  });

  it("flags what stands over 100 ft past the descent, and not what the path clears before it", () => {
    render(<Obstacles obstacles={MEASURED} />);
    expect(screen.getByText(
      "Over 100 ft AGL past the approach's descent from 100 ft AGL: a tree 600 m out stands 131 ft above the track's ground, and 1 more place.",
    )).toHaveClass("survey-state--warn");
    expect(inTheWay(MEASURED.approach).map((place) => place.fromM)).toEqual([600, 900]);
    expect(inTheWay({ ...MEASURED.approach, descent: { fromTargetM: null } })).toEqual([]);   // all of the lane is under the path
    expect(inTheWay({ ...MEASURED.approach, descent: null })).toEqual([]);
  });

  it("says a descent past the lane, and a lane measured by an earlier EZ/PZ", () => {
    const { rerender } = render(<Obstacles obstacles={{ ...MEASURED, approach: { ...MEASURED.approach, descent: { fromTargetM: null } } }} />);
    expect(screen.getByRole("row", { name: /Approach 090°/ })).toHaveTextContent("Past 1,000 m");
    const { start: _start, ...earlier } = MEASURED.approach;
    rerender(<Obstacles obstacles={{ ...MEASURED, approach: earlier }} />);
    expect(screen.getByRole("row", { name: /Approach 090°/ })).toHaveTextContent("Measured by an earlier EZ/PZ: measure the LZ again.");
  });

  it("says why there is nothing, and that a failed reading is to try again", () => {
    const { rerender } = render(<Obstacles obstacles={{ status: "none", words: "No USGS LiDAR covers this LZ, so nothing standing is measured." }} />);
    expect(screen.getByText("No USGS LiDAR covers this LZ, so nothing standing is measured.")).toHaveClass("survey-muted");
    rerender(<Obstacles obstacles={{ status: "failed", words: "The USGS LiDAR could not be read: measure the LZ again later." }} />);
    expect(screen.getByText(/measure the LZ again later/)).toHaveClass("survey-state--warn");
    rerender(<Obstacles obstacles={null} />);
    expect(screen.queryByRole("group")).toBeNull();
  });

  it("names a way that was not measured, and why a measured one has no angle", () => {
    const { rerender } = render(<Obstacles obstacles={{ ...MEASURED, exit: null }} />);
    expect(screen.getByText("Not measured: set the best exit and measure again.")).toBeInTheDocument();
    const none = { headingDeg: 270, start: { groundFt: null }, clearance: null, descent: null, tall: [] };
    rerender(<Obstacles obstacles={{ ...MEASURED, exit: { ...none, missing: "no_target_ground" } }} />);
    expect(screen.getByRole("row", { name: /Exit 270°/ })).toHaveTextContent("No ground at the touchdown point in the LiDAR");
    rerender(<Obstacles obstacles={{ ...MEASURED, exit: { ...none, missing: "no_returns" } }} />);
    expect(screen.getByRole("row", { name: /Exit 270°/ })).toHaveTextContent("No LiDAR returns in its lane");
  });

  it("says how much of a lane the survey covers, where it is not all of it", () => {
    const { rerender } = render(<Obstacles obstacles={{ ...MEASURED, approach: { ...MEASURED.approach, covered: 31 } }} />);
    expect(screen.getByText("LiDAR over 310 m of the approach's 1,000 m: its angle is over that part.")).toHaveClass("survey-state--warn");
    rerender(<Obstacles obstacles={MEASURED} />);
    expect(screen.queryByText(/LiDAR over/)).toBeNull();
  });

  it("lets the editor type the approach's angle over the LiDAR's, and says it is printed", async () => {
    const onDecide = jest.fn(async () => ({}));
    const onUndecide = jest.fn();
    const { rerender } = render(<Obstacles obstacles={MEASURED} approach={APPROACH_ROW} onDecide={onDecide} onUndecide={onUndecide} />);
    fireEvent.click(screen.getByRole("button", { name: "Type the approach's angle…" }));
    const dialog = screen.getByRole("dialog", { name: "Type the approach's angle" });
    expect(dialog).toHaveTextContent("The LiDAR's: 8.5° (LiDAR: 98 ft tree 200 m out)");
    fireEvent.change(within(dialog).getByLabelText("Angle (°)"), { target: { value: "90" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Print this angle" }));
    expect(within(dialog).getByText(/over 0 and under 90/)).toBeInTheDocument();
    fireEvent.change(within(dialog).getByLabelText("Angle (°)"), { target: { value: "12,04" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Print this angle" }));
    expect(within(dialog).getByText(/Say why/)).toBeInTheDocument();
    expect(onDecide).not.toHaveBeenCalled();
    fireEvent.change(within(dialog).getByLabelText("Reason"), { target: { value: "  The tree line was cut in 2024.  " } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Print this angle" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(onDecide).toHaveBeenCalledWith("approach", "typed", "The tree line was cut in 2024.", 12);
    const typed = { ...APPROACH_ROW, override: { choice: "typed", value: 12, reason: "The tree line was cut in 2024.", by: "CW3 Admin" },
      overrideStands: true, printed: "typed" };
    rerender(<Obstacles obstacles={MEASURED} approach={typed} onDecide={onDecide} onUndecide={onUndecide} />);
    expect(screen.getByText("The page prints 12.0° for the approach, typed by CW3 Admin: “The tree line was cut in 2024.”"))
      .toHaveClass("survey-state--info");
    expect(screen.getByRole("row", { name: /Approach 090°/ })).toHaveTextContent("8.5°");          // the LiDAR's, beside it
    fireEvent.click(screen.getByRole("button", { name: "Print the LiDAR's" }));
    expect(onUndecide).toHaveBeenCalledWith("approach");
    rerender(<Obstacles obstacles={MEASURED} approach={{ ...typed, overrideStands: false, printed: "computed", open: true }} onDecide={onDecide} />);
    expect(screen.getByText("Your 12.0° was typed against other values and is no longer printed: look again.")).toHaveClass("survey-state--warn");
    fireEvent.click(screen.getByRole("button", { name: "Change your angle…" }));
    expect(screen.getByLabelText("Angle (°)")).toHaveValue("12");                                // offered again as it was typed
  });

  it("offers no typing where the editor cannot decide, nor where the LiDAR has no angle", () => {
    const { rerender } = render(<Obstacles obstacles={MEASURED} approach={APPROACH_ROW} />);
    expect(screen.queryByRole("button", { name: /angle/ })).toBeNull();
    rerender(<Obstacles obstacles={MEASURED} approach={{ ...APPROACH_ROW, computed: "" }} onDecide={jest.fn()} />);
    expect(screen.queryByRole("button", { name: /angle/ })).toBeNull();
    // An angle typed before the LiDAR lost its own (measured again where it has none): it can be let go, not typed again.
    const typed = { ...APPROACH_ROW, computed: "", override: { choice: "typed", value: 12, reason: "Cut", by: "CW3 Admin" }, open: true };
    rerender(<Obstacles obstacles={MEASURED} approach={typed} onDecide={jest.fn()} onUndecide={jest.fn()} />);
    expect(screen.getByRole("button", { name: "Change your angle…" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Print the LiDAR's" })).toBeEnabled();
  });

  it("reads an angle as the server keeps it", () => {
    expect([typedAngle("12"), typedAngle(" 12.04 "), typedAngle("12,5"), typedAngle(".5"), typedAngle("89.95")]).toEqual([12, 12, 12.5, 0.5, null]);
    expect([typedAngle(""), typedAngle("0.04"), typedAngle("-3"), typedAngle("90"), typedAngle("12°"), typedAngle("1e1"), typedAngle(null)])
      .toEqual([null, null, null, null, null, null, null]);
  });

  it("words a point and an obstacle as the crew reads them", () => {
    expect(spotWords({ heightFt: 26.2, kind: "building", distanceM: 110.4, bearingDeg: 359.6 })).toBe("26 ft building, 110 m at 360°");
    expect(spotWords(null)).toBeNull();
    expect(obstacleWords({ kind: "terrain", fromTargetM: 12, offsetM: 0.4 })).toBe("The ground 12 m out");
    expect(obstacleWords({ kind: "terrain", fromTargetM: 295, offsetM: 5, riseFt: 65 })).toBe("The ground 295 m out, 65 ft above the touchdown point, 5 m right of the track");
    expect(obstacleWords({ kind: "tower", heightFt: 164, fromTargetM: 399, offsetM: 10 })).toBe("164 ft tower 399 m out, 10 m right of the track");
    // Its top over the touchdown point's ground, where that is not its height over its own: ground rising away from the LZ.
    expect(obstacleWords({ kind: "vegetation", heightFt: 49.2, riseFt: 114, fromTargetM: 198, offsetM: 0 })).toBe("49 ft tree 198 m out (114 ft above the touchdown point)");
    expect(spotWords({ heightFt: 9.8, kind: "vegetation", distanceM: 36, bearingDeg: 34 })).toBe("10 ft brush, 36 m at 034°");
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
