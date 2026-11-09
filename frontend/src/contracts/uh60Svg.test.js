import fs from "fs";
import path from "path";
import { aircraftPreviewSvg, aircraftSvg } from "../feature/aircraft/aircraftIcons";

// The web draws the UH-60 from the Android app's own picture (feature-map's assets/aircraft/uh60.svg), the original EZ/PZ
// helicopter at the UH-60L/M's published dimensions, so the two must stay the same file. CRLF is folded so a Windows
// checkout compares as CI's does.

const ANDROID = path.resolve(__dirname, "../../../android/feature-map/src/main/assets/aircraft/uh60.svg");
const WEB = path.resolve(__dirname, "../feature/aircraft/uh60.svg");
const read = (file) => fs.readFileSync(file, "utf8").replace(/\r\n/g, "\n");

const parse = (markup) => new DOMParser().parseFromString(markup, "image/svg+xml").documentElement;

describe("the UH-60 picture", () => {
  it("is the Android app's file, byte for byte", () => {
    expect(read(WEB)).toBe(read(ANDROID));
  });

  it("draws its rotor at the size asked for, the frame round it overflowing evenly", () => {
    const svg = parse(aircraftSvg("uh60", 163.6));
    expect(svg.getAttribute("viewBox")).toBe("-118.3 -118.3 236.6 236.6");
    expect(Number(svg.getAttribute("width"))).toBeCloseTo(236.6, 6);
    expect(Number(svg.getAttribute("height"))).toBeCloseTo(236.6, 6);
    // (236.6 - 163.6) / 2 = 36.5 on every side, so the mast stays at the sprite's centre.
    expect(svg.getAttribute("style")).toContain("margin:-36.5px");
    // The rotor disc's radius, 81.8 units of the 236.6-unit frame, comes to the 163.6 px asked for.
    expect(svg.querySelector('circle[r="81.8"]')).not.toBeNull();
    expect(((2 * 81.8) / 236.6) * Number(svg.getAttribute("width"))).toBeCloseTo(163.6, 6);
  });

  it("is green, black and red only where Android draws them", () => {
    expect(aircraftSvg("uh60", 40)).toContain("#64F338");
    const red = aircraftSvg("uh60", 40, "violation");
    expect(red).toContain("#FF3B30");
    expect(red).not.toContain("#64F338");
  });

  it("fits whole in a picker's preview", () => {
    const svg = parse(aircraftPreviewSvg("uh60", 26));
    expect(svg.getAttribute("width")).toBe("26");
    expect(svg.getAttribute("style")).toBe("");
  });

  it("leaves the other airframes' silhouettes as they were", () => {
    expect(aircraftSvg("ch47", 40)).toContain('viewBox="0 0 100 100"');
    expect(aircraftSvg("ch47", 40)).not.toContain("#64F338");
  });
});
