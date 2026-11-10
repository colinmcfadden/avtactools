import {
  aimBackstopArrow,
  backstopAnchor,
  backstopArrow,
  backstopPoints,
  backstopReading,
  createBackstop,
  moveBackstopArrow,
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

describe("backstopArrow", () => {
  const points = [{ lat: north(2), lon: -84.11 }, { lat: north(2), lon: -84.09 }];

  it("runs from the middle of the line to the target until it is moved", () => {
    const arrow = backstopArrow({ points }, LZ);
    expect(arrow.tail.lat).toBeCloseTo(north(2), 9);
    expect(arrow.tail.lon).toBeCloseTo(-84.1, 9);
    expect(arrow.tip).toEqual({ lat: 34.5, lon: -84.1 });
    expect(arrow.onTarget).toBe(true);
  });

  it("is where it was put once moved, whatever the line and the target do", () => {
    const moved = { tail: { lat: 34.6, lon: -84.2 }, tip: { lat: 34.61, lon: -84.21 } };
    expect(backstopArrow({ points, arrow: moved }, LZ)).toEqual({ ...moved, onTarget: false });
  });

  it("follows the target while its tip is on it", () => {
    const tail = { lat: 34.6, lon: -84.2 };
    expect(backstopArrow({ points, arrow: { tail } }, [35, -85]).tip).toEqual({ lat: 35, lon: -85 });
    // A pack sends a field that went as null.
    expect(backstopArrow({ points, arrow: { tail, tip: null } }, [35, -85]).onTarget).toBe(true);
  });

  it("gives nothing without a place to start or point at", () => {
    expect(backstopArrow({ points }, null)).toBeNull();
    expect(backstopArrow({ points }, [NaN, -84])).toBeNull();
    expect(backstopArrow({ points: [] }, LZ)).toBeNull();
  });
});

describe("backstopReading", () => {
  const read = (points) => backstopReading(backstopArrow({ points }, LZ));

  it("reads the azimuth back to the LZ/PZ and the distance in km", () => {
    const reading = read([{ lat: north(2), lon: -84.11 }, { lat: north(2), lon: -84.09 }]);
    expect(reading.azimuth).toBe(180);
    expect(reading.distanceKm).toBeCloseTo(2, 1);
    expect(reading.text).toBe("180°T · 2.0 km");
  });

  it("pads the azimuth to three digits and calls north 000", () => {
    const line = (lat) => [{ lat, lon: -84.1001 }, { lat, lon: -84.0999 }];
    expect(read(line(north(-3))).text.slice(0, 5)).toBe("000°T");
    expect(read([{ lat: 34.5, lon: -84.13 }, { lat: 34.5, lon: -84.13 }]).text.slice(0, 5)).toBe("090°T");
  });

  it("reads the arrow as it is drawn once it is moved", () => {
    const arrow = { tail: { lat: north(1), lon: -84.3 }, tip: { lat: north(4), lon: -84.3 }, onTarget: false };
    expect(backstopReading(arrow).text).toBe("000°T · 3.0 km");
  });

  it("says nothing of no arrow", () => {
    expect(backstopReading(null)).toBeNull();
  });
});

describe("moving the arrow whole", () => {
  it("moves both ends by the same amount, taking the tip off the target", () => {
    const arrow = backstopArrow({ points: [{ lat: 34.6, lon: -84.2 }, { lat: 34.6, lon: -84.2 }] }, LZ);
    const moved = moveBackstopArrow(arrow, 0.01, -0.02);
    expect(moved.tail.lat).toBeCloseTo(34.61, 9);
    expect(moved.tail.lon).toBeCloseTo(-84.22, 9);
    expect(moved.tip.lat).toBeCloseTo(34.51, 9);
    expect(moved.tip.lon).toBeCloseTo(-84.12, 9);
    expect(backstopArrow({ points: [], arrow: moved }, LZ).onTarget).toBe(false);
  });
});

describe("dragging the tip", () => {
  const arrow = { tail: { lat: 34.6, lon: -84.2 }, tip: { lat: 34.5, lon: -84.1 }, onTarget: false };
  const at = { lat: 34.55, lon: -84.15 };
  // Everything far off unless the case says otherwise.
  const pixels = (nearby) => (place) => nearby.get(place) ?? 500;

  it("moves the tip and leaves the tail, changing the arrow's length and heading", () => {
    expect(aimBackstopArrow(arrow, at, LZ, pixels(new Map()))).toEqual({ tail: arrow.tail, tip: at });
  });

  it("goes back onto the target when dropped near it, and no nearer than that stays free", () => {
    const toTarget = (px) => (place) => (place.lat === LZ[0] && place.lon === LZ[1] ? px : 500);
    expect(aimBackstopArrow(arrow, at, LZ, toTarget(20))).toEqual({ tail: arrow.tail });
    expect(aimBackstopArrow(arrow, at, LZ, toTarget(21))).toEqual({ tail: arrow.tail, tip: at });
  });

  it("will not leave an arrow too short to pick up again", () => {
    expect(aimBackstopArrow(arrow, at, LZ, pixels(new Map([[arrow.tail, 23]])))).toBeNull();
    expect(aimBackstopArrow(arrow, at, LZ, pixels(new Map([[arrow.tail, 24]])))).toEqual({ tail: arrow.tail, tip: at });
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
