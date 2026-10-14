import { render } from "@testing-library/react";
import React from "react";
import MissionSummary from "./MissionSummary";

// An LZ of 8° rising east, as /api/terrain-analysis measures it for every heading.
const RAD = Math.PI / 180;
const tenth = (v) => Math.round(v * 10) / 10 + 0;
const EAST8 = {
  stats: { maxDeg: 8 },
  byHeading: Array.from({ length: 360 }, (_, heading) => {
    const along = Math.atan(Math.tan(8 * RAD) * Math.cos((heading - 90) * RAD)) / RAD;
    return {
      heading,
      noseHighMaxDeg: tenth(Math.max(along, 0)),
      noseLowMaxDeg: tenth(Math.max(-along, 0)),
      crossSlopeMaxDeg: tenth(Math.abs(Math.atan(Math.tan(8 * RAD) * Math.sin((heading - 90) * RAD)) / RAD)),
    };
  }),
};

const LZ = [[34.5, -84.1], [34.5, -84.09], [34.51, -84.095]];
const TARGET = [34.505, -84.095];
const NO_WEATHER = { speed: "--", dir: 0, temp: "--", pressure: "--", station: "AWAITING ANALYSIS", slopeWind: null, at: null };
const weatherAt = (at, speedKts, fromDeg) => ({
  speed: speedKts, dir: fromDeg, temp: 21, pressure: 30.02, station: "KRYY",
  slopeWind: { speedKts, fromDeg, variable: false }, at,
});

const slopeTile = (props) => {
  const { container } = render(
    <MissionSummary detectedLZ={LZ} targetLocation={TARGET} mapData={{ elevation: "1200" }} winds={NO_WEATHER} loadingWeather={false} {...props} />,
  );
  return container.querySelector(".slope-alert-tile");
};
const advice = (tile) => [...tile.querySelectorAll(".slope-advice")].map((line) => line.textContent);

describe("the slope tile", () => {
  it("calls 8° nose down at the landing heading over the 6° limit, and 8° nose up within its 15°", () => {
    expect(slopeTile({ terrainData: EAST8, landingHdg: "270°" })).toHaveClass("status-danger");
    expect(slopeTile({ terrainData: EAST8, landingHdg: "090°" })).toHaveClass("status-safe");
  });

  it("says under the slope which limit the landing heading is over, and that the limits were not reduced with no wind report", () => {
    expect(advice(slopeTile({ terrainData: EAST8, landingHdg: "270°" }))).toEqual([
      "Landing 270°: nose down 8.0°, limit 6°",
      "No wind report: limits not reduced for wind",
    ]);
  });

  it("reduces the limits for this LZ/PZ's wind", () => {
    const tile = slopeTile({ terrainData: EAST8, landingHdg: "090°", winds: weatherAt(TARGET, 20, 90) });
    expect(tile).toHaveClass("status-danger");                                       // 8° nose up is over 15° less 8° for 20 kt
    expect(advice(tile)).toEqual(["Landing 090°: nose up 8.0°, limit 7°", "Wind 20 kt: limits −8°, nose down prohibited above 15 kt"]);
  });

  it("does not take another LZ/PZ's weather for this one's", () => {
    const tile = slopeTile({ terrainData: EAST8, landingHdg: "090°", winds: weatherAt([35.2, -83.4], 20, 90) });
    expect(tile).toHaveClass("status-safe");
    expect(advice(tile)[1]).toBe("No wind report: limits not reduced for wind");
  });

  it("follows the landing doghouse as it turns", () => {
    const { container, rerender } = render(
      <MissionSummary detectedLZ={LZ} targetLocation={TARGET} mapData={{}} winds={NO_WEATHER} terrainData={EAST8} landingHdg="270°" />,
    );
    expect(container.querySelector(".slope-alert-tile")).toHaveClass("status-danger");
    rerender(<MissionSummary detectedLZ={LZ} targetLocation={TARGET} mapData={{}} winds={NO_WEATHER} terrainData={EAST8} landingHdg="225°" />);
    expect(container.querySelector(".slope-alert-tile")).toHaveClass("status-safe");
  });

  it("says an older server's analysis was not judged by heading", () => {
    const tile = slopeTile({ terrainData: { stats: { maxDeg: 15 } }, landingHdg: "090°" });
    expect(tile).toHaveClass("status-warning");
    expect(advice(tile)).toEqual(["Heading limits not checked (server not updated)"]);
  });
});
