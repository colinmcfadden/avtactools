import { fireEvent, render, screen } from "@testing-library/react";
import React from "react";
import SlopeProfile, { SLOPE_PROFILE_STORAGE_KEY } from "./SlopeProfile";
import { slopeProfileFor } from "./groundProfile";
import MissionSummary from "../../components/MissionSummary";

// Ground rising east at 5°, measured along a line every 2° through the LZ's middle, a 200 m square LZ: as /api/terrain-analysis sends it.
const RAD = Math.PI / 180;
const profiles = (gapAt90 = false) => ({
  center: [34.5, -84.1],
  stepM: 30,
  halfLengthM: 300,
  headingStepDeg: 2,
  lines: Array.from({ length: 90 }, (_, k) => {
    const heading = 2 * k;
    const r = 100 / Math.max(Math.abs(Math.cos(heading * RAD)), Math.abs(Math.sin(heading * RAD)));
    return {
      heading,
      elevationsFt: Array.from({ length: 21 }, (__, i) => {
        if (gapAt90 && heading === 90 && i < 4) return null;
        const d = i * 30 - 300;
        return (1200 + d * Math.sin(heading * RAD) * Math.tan(5 * RAD)) * 3.28084;
      }),
      lz: [-r, r],
    };
  }),
});

const tile = (profile) => render(<SlopeProfile profile={profile} />).container;

beforeEach(() => window.localStorage.clear());

describe("the slope profile tile", () => {
  it("opens and closes from its header, and remembers which in this browser", () => {
    const { container, unmount } = render(<SlopeProfile profile={slopeProfileFor(profiles(), 90)} />);
    const header = screen.getByRole("button", { name: "Slope profile, heights in feet" });
    expect(header).toHaveAttribute("aria-expanded", "true");
    expect(header).toHaveAttribute("aria-controls", container.querySelector(".slope-profile__body").id);
    expect(container.querySelectorAll(".slope-profile__graph")).toHaveLength(2);
    fireEvent.click(header);
    expect(header).toHaveAttribute("aria-expanded", "false");
    expect(container.querySelectorAll(".slope-profile__graph")).toHaveLength(0);
    expect(window.localStorage.getItem(SLOPE_PROFILE_STORAGE_KEY)).toBe("closed");
    unmount();
    // Another LZ/PZ, or a reload: still closed.
    const view = render(<SlopeProfile profile={slopeProfileFor(profiles(), 270)} />);
    expect(view.container.querySelectorAll(".slope-profile__graph")).toHaveLength(0);
    fireEvent.click(screen.getByRole("button", { name: /slope profile/i }));
    expect(view.container.querySelectorAll(".slope-profile__graph")).toHaveLength(2);
    expect(window.localStorage.getItem(SLOPE_PROFILE_STORAGE_KEY)).toBeNull();
  });

  it("still opens and closes where storage is refused", () => {
    const get = jest.spyOn(Storage.prototype, "getItem").mockImplementation(() => { throw new Error("blocked"); });
    const set = jest.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new Error("blocked"); });
    const { container } = render(<SlopeProfile profile={slopeProfileFor(profiles(), 90)} />);
    expect(container.querySelectorAll(".slope-profile__graph")).toHaveLength(2);
    fireEvent.click(screen.getByRole("button", { name: /slope profile/i }));
    expect(container.querySelectorAll(".slope-profile__graph")).toHaveLength(0);
    get.mockRestore();
    set.mockRestore();
  });

  it("draws the ground along and across the landing heading, each with its words and its ends named", () => {
    const container = tile(slopeProfileFor(profiles(), 270));
    const words = [...container.querySelectorAll(".slope-profile__words")].map((w) => w.textContent);
    expect(words).toEqual(["Along 270°: falls 57 ft through the LZ, 5.0° on average", "Across, left to right: level through the LZ"]);
    const ends = [...container.querySelectorAll(".slope-profile__ends")].map((e) => [...e.children].map((c) => c.textContent));
    expect(ends).toEqual([["Approach", "Departure"], ["Left", "Right"]]);
    expect(container.querySelectorAll(".slope-profile__lz")).toHaveLength(2);
  });

  it("draws the ground falling from approach to departure when landing down the slope", () => {
    const container = tile(slopeProfileFor(profiles(), 270));
    const along = container.querySelector(".slope-profile--along .slope-profile__line").getAttribute("points").split(" ")
      .map((p) => p.split(",").map(Number));
    expect(along[0][0]).toBeLessThan(along[along.length - 1][0]);                 // left to right
    expect(along[0][1]).toBeLessThan(along[along.length - 1][1]);                 // high (small y) at the approach, low at the departure
  });

  it("leaves a gap where the terrain has no height, rather than draw a line across it", () => {
    const container = tile(slopeProfileFor(profiles(true), 90));
    const lines = container.querySelectorAll(".slope-profile--along .slope-profile__line");
    expect(lines).toHaveLength(1);
    expect(lines[0].getAttribute("points").split(" ")).toHaveLength(17);           // 21 heights, the first 4 missing
  });

  it("keeps gentle ground gentle: the height axis spans at least 30 ft", () => {
    const flat = { ...profiles(), lines: profiles().lines.map((l) => ({ ...l, elevationsFt: l.elevationsFt.map(() => 1500) })) };
    const container = tile(slopeProfileFor(flat, 0));
    const [high, low] = [...container.querySelectorAll(".slope-profile--along .slope-profile__axis")].map((a) => Number(a.textContent));
    expect(high - low).toBe(30);
  });

  it("is not drawn without a profile", () => {
    expect(tile(null).innerHTML).toBe("");
  });

  it("follows the landing doghouse in the mission summary", () => {
    const props = {
      detectedLZ: [[34.5, -84.1], [34.5, -84.09], [34.51, -84.095]], targetLocation: [34.505, -84.095], mapData: {},
      winds: { speed: "--", dir: 0, temp: "--", pressure: "--", station: "", slopeWind: null, at: null },
      terrainData: { stats: { maxDeg: 5 }, profiles: profiles() },
    };
    const { container, rerender } = render(<MissionSummary {...props} landingHdg="090°" />);
    expect(container.querySelector(".slope-profile__words").textContent).toBe("Along 090°: rises 57 ft through the LZ, 5.0° on average");
    rerender(<MissionSummary {...props} landingHdg="270°" />);
    expect(container.querySelector(".slope-profile__words").textContent).toBe("Along 270°: falls 57 ft through the LZ, 5.0° on average");
    rerender(<MissionSummary {...props} landingHdg="RWY" />);
    expect(container.querySelector(".slope-profile-block")).toBeNull();                // no landing heading, no profile
    rerender(<MissionSummary {...props} terrainData={{ stats: { maxDeg: 5 } }} landingHdg="090°" />);
    expect(container.querySelector(".slope-profile-block")).toBeNull();                // a server from before profiles
  });
});
