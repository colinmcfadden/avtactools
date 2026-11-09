import {
  backstopAnchor,
  backstopPoints,
  backstopReading,
  createBackstop,
  moveBackstopPoints,
  rotateBackstopPoints,
} from "./backstops";

const LZ = [34.5, -84.1];
const north = (km) => 34.5 + km / 110.574;

describe("backstopAnchor", () => {
  it("is the middle of the line by distance, not by the number of points", () => {
    const anchor = backstopAnchor([
      { lat: 34.0, lon: -84.0 },
      { lat: 34.001, lon: -84.0 },
      { lat: 34.01, lon: -84.0 },
    ]);
    expect(anchor.lat).toBeCloseTo(34.005, 6);
    expect(anchor.lon).toBeCloseTo(-84.0, 9);
  });

  it("copes with a line of no length, and none at all", () => {
    expect(backstopAnchor([{ lat: 1, lon: 2 }, { lat: 1, lon: 2 }])).toEqual({ lat: 1, lon: 2 });
    expect(backstopAnchor([])).toBeNull();
  });
});

describe("backstopReading", () => {
  it("reads the azimuth back to the LZ/PZ and the distance in km", () => {
    const reading = backstopReading(
      [{ lat: north(2), lon: -84.11 }, { lat: north(2), lon: -84.09 }],
      LZ,
    );
    expect(reading.azimuth).toBe(180);
    expect(reading.distanceKm).toBeCloseTo(2, 1);
    expect(reading.text).toBe("180°T · 2.0 km");
  });

  it("pads the azimuth to three digits and calls north 000", () => {
    const line = (lat) => [{ lat, lon: -84.1001 }, { lat, lon: -84.0999 }];
    expect(backstopReading(line(north(-3)), LZ).text.slice(0, 5)).toBe("000°T");
    const east = backstopReading([{ lat: 34.5, lon: -84.13 }, { lat: 34.5, lon: -84.13 }], LZ);
    expect(east.text.slice(0, 5)).toBe("090°T");
  });

  it("gives nothing without a place to point back to", () => {
    const line = [{ lat: 34, lon: -84 }, { lat: 34.1, lon: -84 }];
    expect(backstopReading(line, null)).toBeNull();
    expect(backstopReading(line, [NaN, -84])).toBeNull();
    expect(backstopReading([], LZ)).toBeNull();
  });
});

describe("moving and turning", () => {
  const line = [{ lat: 34.5, lon: -84.1 }, { lat: 34.51, lon: -84.1 }];

  it("moves every point by the same amount", () => {
    expect(moveBackstopPoints(line, 0.01, -0.02)[1].lat).toBeCloseTo(34.52, 9);
    expect(moveBackstopPoints(line, 0.01, -0.02)[1].lon).toBeCloseTo(-84.12, 9);
  });

  it("turns clockwise about the point given, keeping the length on the ground", () => {
    const [centre, tip] = rotateBackstopPoints(line, line[0], 90);
    expect(centre.lat).toBeCloseTo(line[0].lat, 9);
    expect(centre.lon).toBeCloseTo(line[0].lon, 9);
    // A point due north, turned a quarter clockwise, is due east, the same distance off.
    expect(tip.lat).toBeCloseTo(34.5, 9);
    expect(tip.lon).toBeGreaterThan(-84.1);
    expect((tip.lon + 84.1) * Math.cos((34.5 * Math.PI) / 180)).toBeCloseTo(0.01, 6);
  });

  it("is undone by the opposite turn", () => {
    const about = { lat: 34.505, lon: -84.1 };
    const back = rotateBackstopPoints(rotateBackstopPoints(line, about, 37), about, -37);
    expect(back[1].lat).toBeCloseTo(line[1].lat, 9);
    expect(back[1].lon).toBeCloseTo(line[1].lon, 9);
  });
});

describe("a backstop as it is kept", () => {
  it("holds only its id and its points' places", () => {
    expect(createBackstop([{ lat: 1, lon: 2, extra: "x" }], "bs-1")).toEqual({ id: "bs-1", points: [{ lat: 1, lon: 2 }] });
  });

  it("reads past anything in the list that is not a place", () => {
    expect(backstopPoints({ points: [{ lat: 1, lon: 2 }, { lat: "x" }, null] })).toEqual([{ lat: 1, lon: 2 }]);
    expect(backstopPoints({})).toEqual([]);
  });
});
