import { fromUtm, mgrsToLatLon, parseMgrs, toMgrs, toUtm, toUtmInZone } from "./mgrs";

// Expected grids from the backend's own conversion (PyGeodesy, exact transverse
// Mercator), so the cursor readout and /api/convert-to-mgrs agree to the metre.
// The same check was run at 5,255 points worldwide; all matched.
const REFERENCE = [
  ["an LZ in north Georgia", 34.783817, -84.08219, "16S GD 66993 52949"],
  ["another, in the next square east", 34.596407, -84.128098, "16S GD 63385 32037"],
  ["UTM zone 17, east of Georgia's zone line", 34.64815, -83.8613, "17S KU 37750 37750"],
  ["the southern hemisphere", -33.8688, 151.2093, "56H LH 34368 50948"],
  ["just north of the equator", 0.0001, 36.8219, "37N BA 57573 00011"],
  ["just south of the equator", -0.0001, 36.8219, "37M BV 57573 99988"],
  ["a single-digit zone, padded", 21.3069, -157.8583, "04Q FJ 18417 56542"],
  ["Norway's widened zone 32", 60.39, 5.32, "32V KN 97230 00510"],
  ["Svalbard's zone 33", 78.2232, 15.6267, "33X WG 14278 83355"],
  ["Svalbard's zone 31", 79.0, 5.0, "31X EH 42594 70703"],
  ["Svalbard's zone 35", 79.0, 25.0, "35X MH 57405 70703"],
  ["Svalbard's zone 37", 80.0, 35.0, "37X DJ 22516 84250"],
  ["the edge of the UTM range, 80S", -79.9999, 10.0, "32C NS 19384 18258"],
  ["the antimeridian", 52.0, 179.9999, "60U YC 05922 65287"],
  ["just across it", 52.0, -179.9999, "01U BT 94077 65287"],
  ["a zone boundary, west side", 34.5, -84.0001, "16S GD 75446 21683"],
  ["a zone boundary, east side", 34.5, -84.0, "17S KU 24544 21684"],
  ["a band boundary, below", 39.9999, -105.0, "13S EE 00000 27746"],
  ["a band boundary, above", 40.0, -105.0, "13T EE 00000 27757"],
];

describe("toMgrs", () => {
  it.each(REFERENCE)("matches the backend for %s", (_name, lat, lon, expected) => {
    expect(toMgrs(lat, lon)).toBe(expected);
  });

  it("truncates to coarser precision rather than rounding", () => {
    // 66993 / 52949 m name the 10 m square 6699 / 5294, not 6700 / 5295.
    expect(toMgrs(34.783817, -84.08219, { digits: 4 })).toBe("16S GD 6699 5294");
  });

  it("wraps a longitude Leaflet carries past the antimeridian", () => {
    // Pan the map east across 180° and Leaflet reports 180.0001 and beyond.
    expect(toMgrs(52.0, 180.0001)).toBe(toMgrs(52.0, -179.9999));
    expect(toMgrs(34.783817, -84.08219 + 360)).toBe("16S GD 66993 52949");
  });

  it("has no answer at the poles, which MGRS covers with a different projection", () => {
    expect(toMgrs(84, 0)).toBeNull();
    expect(toMgrs(-80.0001, 0)).toBeNull();
  });

  it("has no answer for a missing coordinate", () => {
    expect(toMgrs(undefined, -84)).toBeNull();
    expect(toMgrs(34.5, Number.NaN)).toBeNull();
  });
});

describe("toUtm", () => {
  it("gives the zone, band and full-precision metres", () => {
    const utm = toUtm(34.783817, -84.08219);
    expect(utm.zone).toBe(16);
    expect(utm.band).toBe("S");
    expect(utm.easting).toBeCloseTo(766993.9, 0);
    expect(utm.northing).toBeCloseTo(3852949.9, 0);
  });
});

describe("toUtmInZone", () => {
  it("is toUtm when the zone asked for is the point's own", () => {
    REFERENCE.forEach(([, lat, lon]) => {
      const own = toUtm(lat, lon);
      expect(toUtmInZone(lat, lon, own.zone)).toEqual(own);
    });
  });

  it("projects a point into the zone next door", () => {
    // 84°W is the edge between zones 16 and 17, 3° either side of their central
    // meridians, so the two eastings mirror each other and the northings agree.
    const in16 = toUtmInZone(34.5, -84, 16);
    const in17 = toUtmInZone(34.5, -84, 17);
    expect(toUtm(34.5, -84).zone).toBe(17);
    expect(in17).toEqual(toUtm(34.5, -84));
    expect(in16.easting).toBeGreaterThan(500000);
    expect(in17.easting).toBeLessThan(500000);
    expect(in17.easting).toBeCloseTo(1000000 - in16.easting, 6);
    expect(in17.northing).toBeCloseTo(in16.northing, 6);
    // A whisker west, in 16, the point is further out from 17's meridian than the edge.
    expect(toUtmInZone(34.5, -84.0001, 17).easting).toBeLessThan(in17.easting);
  });

  it("measures zone 60's east edge from its own side", () => {
    expect(toUtmInZone(52, 180, 60)).toEqual(toUtmInZone(52, -180, 60));
    expect(toUtmInZone(52, 180, 60).easting).toBeGreaterThan(500000);
    expect(toUtmInZone(52, -180, 1).easting).toBeLessThan(500000);
  });

  it("takes 84°N, the top of band X, and nothing outside the UTM range", () => {
    expect(toUtmInZone(84, 15, 33).band).toBe("X");
    expect(toUtmInZone(84.0001, 15, 33)).toBeNull();
    expect(toUtmInZone(-80.0001, 15, 33)).toBeNull();
    expect(toUtmInZone(34, -84, 0)).toBeNull();
    expect(toUtmInZone(34, -84, 61)).toBeNull();
    expect(toUtmInZone(34, -84, 16.5)).toBeNull();
    expect(toUtmInZone(Number.NaN, -84, 16)).toBeNull();
  });
});

describe("fromUtm", () => {
  it.each(REFERENCE)("undoes toUtm for %s", (_name, lat, lon) => {
    const utm = toUtm(lat, lon);
    const back = fromUtm(utm.zone, lat < 0 ? "S" : "N", utm.easting, utm.northing);
    expect(back.lat).toBeCloseTo(lat, 9);
    expect(back.lon).toBeCloseTo(lon, 9);
  });

  it("refuses a zone, hemisphere or coordinate it cannot use", () => {
    expect(fromUtm(0, "N", 500000, 0)).toBeNull();
    expect(fromUtm(61, "N", 500000, 0)).toBeNull();
    expect(fromUtm(16, "Q", 500000, 0)).toBeNull();
    expect(fromUtm(16, "N", Number.NaN, 0)).toBeNull();
    expect(fromUtm(16, "N", 500000, undefined)).toBeNull();
  });
});

describe("parseMgrs and mgrsToLatLon", () => {
  it("splits a grid into its parts, keeping leading zeros", () => {
    expect(parseMgrs(" 37n ba 57573 00011 ")).toEqual({
      zone: 37, band: "N", square: "BA", easting: "57573", northing: "00011",
    });
  });

  it("returns the centre of the named square", () => {
    // 16S GD 66993 52949 is the 1 m square the north Georgia LZ is in.
    const centre = mgrsToLatLon("16S GD 66993 52949");
    expect(toMgrs(centre.lat, centre.lon)).toBe("16S GD 66993 52949");
    expect(Math.abs(centre.lat - 34.783817)).toBeLessThan(0.00001);
    expect(Math.abs(centre.lon - -84.08219)).toBeLessThan(0.00001);
  });

  it("refuses what is not a grid", () => {
    expect(parseMgrs(null)).toBeNull();
    expect(mgrsToLatLon(undefined)).toBeNull();
    expect(mgrsToLatLon("16SGD123")).toBeNull();
  });
});
