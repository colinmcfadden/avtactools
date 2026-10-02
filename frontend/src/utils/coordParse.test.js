import { formatDecimal, looksLikeMgrs, parseCoordinate } from "./coordParse";

// One real point, written every way it plausibly arrives:
// Dahlonega, GA — 34°32'44.4"N 084°07'24.4"W
const LAT = 34.5457;
const LON = -84.1234;

const near = (actual, expected, tolerance = 1e-4) =>
  Math.abs(actual - expected) <= tolerance;

const expectPoint = (text, lat = LAT, lon = LON, tolerance) => {
  const parsed = parseCoordinate(text);
  expect(parsed).not.toBeNull();
  if (!near(parsed.lat, lat, tolerance) || !near(parsed.lon, lon, tolerance)) {
    throw new Error(
      `${JSON.stringify(text)} -> ${parsed.lat}, ${parsed.lon} (expected ${lat}, ${lon})`,
    );
  }
  return parsed;
};

describe("decimal degrees", () => {
  it.each([
    "34.5457, -84.1234",
    "34.5457,-84.1234",
    "34.5457 -84.1234",
    "  34.5457 ,  -84.1234  ",
    "34.5457N 84.1234W",
    "N34.5457 W84.1234",
    "34.5457° N, 84.1234° W",
    "34.5457°N 084.1234°W",
    "+34.5457, -84.1234",
    "34.5457\t-84.1234",
    "34.5457\n-84.1234",
    "34.5457; -84.1234",
    "34.5457 / -84.1234",
  ])("parses %s", (text) => {
    expect(expectPoint(text).format).toBe("decimal");
  });
});

describe("degrees and decimal minutes", () => {
  it.each([
    "34°32.740'N 084°07.407'W",
    "N34°32.740' W084°07.407'",
    "34 32.740 N, 84 07.407 W",
    "34°32.740′N 084°07.407′W",   // prime marks
    "34 32.740N 84 07.407W",
  ])("parses %s", (text) => {
    expect(expectPoint(text).format).toBe("ddm");
  });
});

describe("degrees, minutes, seconds", () => {
  it.each([
    "34°32'44.4\"N 84°07'24.4\"W",
    "34°32'44.4″N 84°07'24.4″W",   // double-prime
    "34 32 44.4 N, 84 07 24.4 W",
    "N34 32 44.4 W084 07 24.4",
    "34°32'44.4\"N, 84°07'24.4\"W",
  ])("parses %s", (text) => {
    expect(expectPoint(text).format).toBe("dms");
  });

  it("handles curly quotes from word processors", () => {
    expectPoint("34°32’44.4”N 84°07’24.4”W");
  });
});

describe("packed flight-plan formats", () => {
  it("parses DDMM.mmm / DDDMM.mmm", () => {
    // Leading zeros matter: 08407.407 is DDD MM.mmm, not a big number.
    expect(expectPoint("3432.740N 08407.407W").format).toBe("packed-ddm");
  });

  it("parses DDMMSS / DDDMMSS", () => {
    expect(expectPoint("343244N 0840724W", 34.5456, -84.1234).format).toBe("packed-dms");
  });

  it("does not mistake a zero-padded degree value for a packed one", () => {
    // "0034" is 34 degrees written with padding, not 00°34'.
    expectPoint("0034 -0084", 34, -84);
  });
});

describe("axis assignment", () => {
  it("defaults to latitude first", () => {
    expectPoint("34.5457 -84.1234");
  });

  it("takes an unlabelled pair as latitude first, per convention", () => {
    // GeoJSON order (lon,lat) is NOT auto-detected: both orders are valid
    // coordinates and nothing distinguishes them, so guessing would risk a
    // silently wrong target. Convention wins.
    expectPoint("-84.1234, 34.5457", -84.1234, 34.5457);
  });

  it("attaches a hemisphere to the number it is written against", () => {
    // The W is glued to the longitude that follows it, so it must not be read
    // as a suffix on the latitude before it.
    expectPoint("34.5457 W084.1234");
    expectPoint("34.5457N 084.1234W");
  });

  it("honours hemispheres given in either order", () => {
    expectPoint("W084.1234 N34.5457");
    expectPoint("84.1234W 34.5457N");
  });

  it("keeps southern and eastern hemispheres signed correctly", () => {
    expectPoint("S34.5457 E084.1234", -LAT, 84.1234);
    expectPoint("34.5457S, 84.1234E", -LAT, 84.1234);
  });
});

describe("refuses things that are not coordinate pairs", () => {
  it.each([
    ["", "empty"],
    ["   ", "whitespace"],
    ["34.5457", "a single value"],
    ["34.5457 -84.1234 12.3", "three values"],
    ["Dahlonega", "a place name"],
    ["hello world", "prose"],
    ["34N 84N", "two latitudes"],
    ["91.0, -84.1234", "latitude out of range — not silently read as lon,lat"],
    ["34.5457, -181.5", "longitude out of range"],
    ["34 75.5 N, 84 07 W", "minutes >= 60"],
    ["34 32 61 N, 84 07 24 W", "seconds >= 60"],
  ])("rejects %s (%s)", (text) => {
    expect(parseCoordinate(text)).toBeNull();
  });
});

describe("MGRS is never hijacked", () => {
  const grids = [
    "16S GC 28864 55349",
    "16SGC2886455349",
    "18T WL 123 456",
    "4QFJ12345678",
    "16S GC",
    "16sgc2886455349",
  ];

  it.each(grids)("treats %s as a grid, not a coordinate", (grid) => {
    expect(looksLikeMgrs(grid)).toBe(true);
    expect(parseCoordinate(grid)).toBeNull();
  });

  it("does not confuse a coordinate for a grid", () => {
    // "34N 084W" starts digit+letter like a grid but has no 100 km square.
    expect(looksLikeMgrs("34N 084W")).toBe(false);
    expect(looksLikeMgrs("34.5457, -84.1234")).toBe(false);
    expectPoint("34N 084W", 34, -84);
  });

  it("ignores grid band letters I and O, which do not exist", () => {
    expect(looksLikeMgrs("16I GC 12345 67890")).toBe(false);
  });
});

describe("real-world pastes", () => {
  it("reads a Google Maps copy", () => {
    expectPoint("34.5457, -84.1234");
  });

  it("reads a ForeFlight-style coordinate", () => {
    expectPoint("N34°32.74' W084°07.41'", 34.545667, -84.12350, 1e-3);
  });

  it("reads a spreadsheet row", () => {
    expectPoint("34.5457\t-84.1234");
  });

  it("survives a trailing period from a sentence", () => {
    expectPoint("34.5457, -84.1234 ");
  });

  it("reports which format it recognised", () => {
    expect(parseCoordinate("34.5457, -84.1234").label).toBe("decimal degrees");
    expect(parseCoordinate("34°32.740'N 084°07.407'W").label).toBe(
      "degrees/decimal minutes",
    );
  });
});

describe("formatDecimal", () => {
  it("renders a compact confirmation string", () => {
    expect(formatDecimal(34.545678, -84.123456)).toBe("34.54568, -84.12346");
  });
});
