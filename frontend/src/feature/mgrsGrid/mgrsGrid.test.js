import { fromUtm, toMgrs, toUtm, toUtmInZone, zoneFor } from "../../utils/mgrs";
import { GRID_ZONES, MIN_LINE_GAP_PX, gridSpacing, mgrsGrid, principalDigits } from "./mgrsGrid";

// The map's ground scale, as MgrsGridLayer measures it: Web Mercator at Leaflet's zoom.
const metresPerPixel = (lat, zoom) => (40075016.686 * Math.cos((lat * Math.PI) / 180)) / (256 * 2 ** zoom);
const mercatorY = (lat) => Math.log(Math.tan(Math.PI / 4 + (lat * Math.PI) / 360));
const mercatorLat = (y) => ((2 * Math.atan(Math.exp(y)) - Math.PI / 2) * 180) / Math.PI;

// The bounds Leaflet reports for a map of width x height pixels centred on lat, lon.
const viewAt = (lat, lon, zoom, width = 1400, height = 900) => {
  const world = 256 * 2 ** zoom;
  const halfY = ((height / 2) * 2 * Math.PI) / world;
  const halfX = ((width / 2) * 360) / world;
  const y = mercatorY(lat);
  return {
    bounds: { south: mercatorLat(y - halfY), north: mercatorLat(y + halfY), west: lon - halfX, east: lon + halfX },
    metresPerPixel: metresPerPixel(lat, zoom),
    width,
    height,
  };
};

const gridAt = (lat, lon, zoom, options, size = []) => {
  const view = viewAt(lat, lon, zoom, ...size);
  return { view, grid: mgrsGrid(view.bounds, view.metresPerPixel, options) };
};

const linesOf = (grid, kind) => grid.lines.filter((line) => !kind || line.kind === kind);
const hemisphereOf = (lat) => (lat < 0 ? "S" : "N");

// Where a vertex is, read in the line's own zone: the coordinate the line holds constant.
const heldValue = (line, [lat, lon]) => {
  const utm = toUtmInZone(lat, lon, line.zone);
  return line.axis === "easting" ? utm.easting : utm.northing;
};

// Everything a drawn grid line must agree with the readout on. Returns what disagrees.
const disagreements = (grid) => {
  const wrong = [];
  linesOf(grid).filter((line) => line.kind !== "zone").forEach((line) => {
    const label = `${line.kind} ${line.axis} ${line.value} in ${line.zone}`;
    line.points.forEach((point, i) => {
      // On the line, to a tenth of a millimetre.
      const off = Math.abs(heldValue(line, point) - line.value);
      if (off > 1e-4) wrong.push(`${label}: vertex ${i} is ${off} m off`);
      // Inside its own zone: the readout puts every vertex but the ends (which sit on an
      // edge, maybe the next zone's) in the line's zone.
      if (i > 0 && i < line.points.length - 1 && toUtm(point[0], point[1]).zone !== line.zone) {
        wrong.push(`${label}: vertex ${i} at ${point} is in zone ${toUtm(point[0], point[1]).zone}`);
      }
    });
    // Half a metre inside the line's square, the readout's digits are the line's value.
    if (line.points.length >= 3) {
      const [lat, lon] = line.points[Math.floor(line.points.length / 2)];
      const utm = toUtmInZone(lat, lon, line.zone);
      const probe = line.axis === "easting"
        ? fromUtm(line.zone, hemisphereOf(lat), line.value + 0.5, utm.northing)
        : fromUtm(line.zone, hemisphereOf(lat), utm.easting, line.value + 0.5);
      const [zoneBand, , easting, northing] = toMgrs(probe.lat, probe.lon).split(" ");
      const digits = line.axis === "easting" ? easting : northing;
      const expected = String(line.value % 100000).padStart(5, "0");
      if (Number(zoneBand.slice(0, 2)) !== line.zone || digits !== expected) {
        wrong.push(`${label}: reads ${toMgrs(probe.lat, probe.lon)}, not ${expected}`);
      }
    }
  });
  return wrong;
};

// The zone's own longitude range at a latitude, from the cells (and so from zoneFor).
const zoneSpan = (zone, lat) => GRID_ZONES.filter(
  (cell) => cell.zone === zone && lat >= cell.south - 1e-9 && lat <= cell.north + 1e-9,
);

describe("gridSpacing", () => {
  it("picks the finest spacing whose lines are 70 px or more apart", () => {
    expect(MIN_LINE_GAP_PX).toBe(70);
    expect(gridSpacing(1)).toBe(100);
    expect(gridSpacing(100 / 70)).toBe(100);
    expect(gridSpacing(100 / 69)).toBe(1000);
    expect(gridSpacing(1000 / 70)).toBe(1000);
    expect(gridSpacing(10000 / 69)).toBe(100000);
    expect(gridSpacing(100000 / 70)).toBe(100000);
  });

  it("never goes finer than 100 m, and past 100 km draws zones only", () => {
    expect(gridSpacing(0.01)).toBe(100);
    expect(gridSpacing(100000 / 69)).toBeNull();
    expect(gridSpacing(0)).toBeNull();
    expect(gridSpacing(Number.NaN)).toBeNull();
  });

  it("writes a line's value in its principal digits", () => {
    expect(principalDigits(766000, 1000)).toBe("66");
    expect(principalDigits(766900, 100)).toBe("669");
    expect(principalDigits(760000, 10000)).toBe("6");
    expect(principalDigits(603000, 1000)).toBe("03");
    expect(principalDigits(9700000, 1000)).toBe("00");
  });
});

describe("the grid zones", () => {
  it("tile each band from 180°W to 180°E without a gap", () => {
    const bands = new Map();
    GRID_ZONES.forEach((cell) => bands.set(cell.band, [...(bands.get(cell.band) || []), cell]));
    expect(bands.size).toBe(20);
    bands.forEach((cells) => {
      expect(cells[0].west).toBe(-180);
      expect(cells[cells.length - 1].east).toBe(180);
      cells.slice(1).forEach((cell, i) => expect(cell.west).toBe(cells[i].east));
    });
  });

  it("are the readout's zones, Norway and Svalbard included", () => {
    const cell = (zone, band) => GRID_ZONES.find((c) => c.zone === zone && c.band === band);
    expect(cell(31, "V")).toMatchObject({ west: 0, east: 3 });
    expect(cell(32, "V")).toMatchObject({ west: 3, east: 12 });
    expect(cell(32, "U")).toMatchObject({ west: 6, east: 12 });
    expect(cell(31, "X")).toMatchObject({ west: 0, east: 9 });
    expect(cell(33, "X")).toMatchObject({ west: 9, east: 21 });
    expect(cell(35, "X")).toMatchObject({ west: 21, east: 33 });
    expect(cell(37, "X")).toMatchObject({ west: 33, east: 42, north: 84 });
    [32, 34, 36].forEach((zone) => expect(cell(zone, "X")).toBeUndefined());
    // And at points scattered over every cell, zoneFor agrees.
    GRID_ZONES.forEach((c) => {
      [[0.1, 0.1], [0.5, 0.5], [0.9, 0.95]].forEach(([fy, fx]) => {
        const lat = c.south + (c.north - c.south) * fy;
        const lon = c.west + (c.east - c.west) * fx;
        expect(zoneFor(lat, lon)).toBe(c.zone);
      });
    });
  });
});

describe("zone and band boundaries", () => {
  const meridians = (grid, lon) => linesOf(grid, "zone")
    .filter((line) => line.axis === "meridian" && Math.abs(line.value - lon) < 1e-9)
    .map((line) => [line.points[0][0], line.points[1][0]]);
  const covers = (spans, lat) => spans.some(([s, n]) => lat >= s && lat <= n);

  it("follow Norway's widened zone 32 in band V", () => {
    const { grid } = gridAt(60, 6, 5);
    expect(covers(meridians(grid, 3), 60)).toBe(true);
    expect(covers(meridians(grid, 3), 55)).toBe(false);
    expect(covers(meridians(grid, 3), 65)).toBe(false);
    expect(covers(meridians(grid, 6), 60)).toBe(false);
    expect(covers(meridians(grid, 6), 55)).toBe(true);
    expect(covers(meridians(grid, 6), 65)).toBe(true);
    expect(linesOf(grid, "zone").some((l) => l.axis === "parallel" && l.value === 56)).toBe(true);
    expect(linesOf(grid, "zone").some((l) => l.axis === "parallel" && l.value === 64)).toBe(true);
  });

  it("follow Svalbard's zones in band X, up to 84°N", () => {
    const { grid } = gridAt(76, 21, 3);
    [9, 21, 33].forEach((lon) => expect(covers(meridians(grid, lon), 78)).toBe(true));
    [12, 18, 24, 30].forEach((lon) => expect(covers(meridians(grid, lon), 78)).toBe(false));
    expect(covers(meridians(grid, 18), 70)).toBe(true);
    expect(linesOf(grid, "zone").some((l) => l.axis === "parallel" && l.value === 84)).toBe(true);
    // Nothing is drawn past 84°N, where UPS takes over.
    expect(grid.lines.every((line) => line.points.every(([lat]) => lat <= 84))).toBe(true);
  });

  it("are all a zoomed-out map shows, labelled with their designators", () => {
    const { grid } = gridAt(34.78, -84.08, 5);
    expect(grid.spacing).toBeNull();
    expect(linesOf(grid).every((line) => line.kind === "zone")).toBe(true);
    const zones = grid.labels.filter((label) => label.kind === "gzd");
    expect(zones.map((label) => label.text)).toEqual(expect.arrayContaining(["16S", "17S", "16T"]));
    zones.forEach((label) => expect(toMgrs(label.lat, label.lon).startsWith(label.text)).toBe(true));
  });
});

describe("grid lines agree with the cursor readout", () => {
  it("on the 66 km easting line of 16S GD, at 1 km spacing", () => {
    const { grid } = gridAt(34.78, -84.08, 14);
    expect(grid.spacing).toBe(1000);
    const line = linesOf(grid, "minor").find((l) => l.zone === 16 && l.axis === "easting" && l.value === 766000);
    expect(line).toBeDefined();
    line.points.forEach(([lat, lon]) => {
      const [zoneBand, square, easting] = toMgrs(lat, lon).split(" ");
      expect(`${zoneBand} ${square}`).toBe("16S GD");
      expect(Math.round(toUtm(lat, lon).easting)).toBe(766000);
      expect(["66000", "65999"]).toContain(easting); // a point exactly on the line, truncated
    });
    // Half a metre east of the line the readout says 66000 exactly.
    expect(disagreements(grid)).toEqual([]);
  });

  it.each([
    ["north Georgia, 100 m", 34.7838, -84.0822, 17],
    ["north Georgia, 10 km", 34.78, -84.08, 11],
    ["north Georgia, 100 km", 34.78, -84.08, 8],
    ["the southern hemisphere (Sydney)", -33.87, 151.21, 13],
    ["across the equator", 0.02, 36.82, 10],
    ["across the 16/17 zone edge", 34.5, -84, 12],
    ["Norway's 32V", 60.39, 5.32, 9],
    ["the 31V/32V edge at 3°E", 60, 3, 9],
    ["where zone 32 narrows at 64°N", 64, 4.5, 9],
    ["Svalbard's 33X", 78.22, 15.63, 9],
    ["the 33X/35X edge at 21°E", 78, 21, 9],
    ["Svalbard at 100 km", 78, 20, 6],
    ["just under 84°N", 83.8, 15, 9],
    ["just above 80°S", -79.8, 10, 9],
    ["the antimeridian", 52, 180, 10],
    ["the antimeridian, a world to the west", 52, -180, 10],
  ])("at %s", (_name, lat, lon, zoom) => {
    const { grid } = gridAt(lat, lon, zoom, { pad: 0.25 });
    expect(linesOf(grid).some((line) => line.kind !== "zone")).toBe(true);
    expect(disagreements(grid)).toEqual([]);
  });
});

describe("each line stays in its zone", () => {
  it("stops on the edge, where the neighbouring zone's lines meet it", () => {
    const { grid } = gridAt(34.5, -84, 12);
    const lines = linesOf(grid).filter((line) => line.kind !== "zone");
    const strays = lines.flatMap((line) => line.points
      .filter(([, lon]) => (line.zone === 16 ? lon > -84 : lon < -84))
      .map((point) => `${line.zone}: ${point}`));
    expect(strays).toEqual([]);
    // A northing runs on into the next zone: the two lines meet on 84°W, to a centimetre.
    const atEdge = (zone, value) => lines
      .filter((l) => l.zone === zone && l.axis === "northing" && l.value === value)
      .flatMap((l) => [l.points[0], l.points[l.points.length - 1]])
      .find(([, lon]) => lon === -84);
    const shared = lines.filter((l) => l.zone === 16 && l.axis === "northing").map((l) => l.value);
    expect(shared.length).toBeGreaterThan(0);
    shared.forEach((value) => {
      const west = atEdge(16, value);
      const east = atEdge(17, value);
      expect(west).toBeDefined();
      expect(east).toBeDefined();
      expect(Math.abs(west[0] - east[0])).toBeLessThan(1e-7);
    });
  });

  it.each([
    ["Norway", 60, 3, 9],
    ["where zone 32 narrows", 64, 4.5, 9],
    ["Svalbard", 78, 21, 8],
  ])("keeps to the exception zones' shapes in %s", (_name, lat, lon, zoom) => {
    const { grid } = gridAt(lat, lon, zoom, { pad: 0.25 });
    linesOf(grid).filter((line) => line.kind !== "zone").forEach((line) => {
      line.points.forEach(([pLat, pLon]) => {
        const inside = zoneSpan(line.zone, pLat)
          .some((cell) => pLon >= cell.west - 1e-9 && pLon <= cell.east + 1e-9);
        expect(inside).toBe(true);
      });
    });
  });
});

describe("drawn curves", () => {
  // The middle of each drawn segment, in map pixels, is within half a pixel of the true line.
  const worstStray = (grid, lat0, zoom) => {
    const world = 256 * 2 ** zoom;
    let worst = 0;
    linesOf(grid).filter((line) => line.kind !== "zone").forEach((line) => {
      line.points.slice(1).forEach(([lat, lon], i) => {
        const [lat1, lon1] = line.points[i];
        const midLat = mercatorLat((mercatorY(lat) + mercatorY(lat1)) / 2);
        const midLon = (lon + lon1) / 2;
        const groundPerPixel = (40075016.686 * Math.cos((midLat * Math.PI) / 180)) / world;
        worst = Math.max(worst, Math.abs(heldValue(line, [midLat, midLon]) - line.value) / groundPerPixel);
      });
    });
    expect(lat0).toBeDefined();
    return worst;
  };

  it.each([
    ["Svalbard at 100 km", 78, 20, 6],
    ["Norway at 100 km", 62, 8, 6],
    ["north Georgia at 10 km", 34.78, -84.08, 10],
    ["a zone edge at 1 km", 34.5, -84, 13],
  ])("follow the true line to within half a pixel: %s", (_name, lat, lon, zoom) => {
    const { grid } = gridAt(lat, lon, zoom);
    expect(worstStray(grid, lat, zoom)).toBeLessThan(0.5);
  });
});

describe("labels", () => {
  const overlap = (a, b) => a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3];

  it("put eastings along the top and northings down the left, where each line crosses", () => {
    const { view, grid } = gridAt(34.78, -84.08, 14);
    const eastings = grid.labels.filter((l) => l.kind === "easting");
    const northings = grid.labels.filter((l) => l.kind === "northing");
    expect(eastings.length).toBeGreaterThan(5);
    expect(northings.length).toBeGreaterThan(3);
    eastings.forEach((label) => {
      expect(label.lat).toBe(view.bounds.north);
      expect(label.text).toBe(principalDigits(label.value, 1000));
      expect(Math.abs(toUtmInZone(label.lat, label.lon, label.zone).easting - label.value)).toBeLessThan(1e-3);
    });
    northings.forEach((label) => {
      expect(label.lon).toBe(view.bounds.west);
      expect(label.text).toBe(principalDigits(label.value, 1000));
      expect(Math.abs(toUtmInZone(label.lat, label.lon, label.zone).northing - label.value)).toBeLessThan(1e-3);
    });
    expect(eastings.map((l) => l.text)).toContain("66");
  });

  it("name the 100 km square with its zone where the lines are finer", () => {
    const { grid } = gridAt(34.78, -84.08, 14);
    const squares = grid.labels.filter((l) => l.kind === "square");
    expect(squares.map((l) => l.text)).toEqual(["16S GD"]);
    squares.forEach((label) => expect(toMgrs(label.lat, label.lon).startsWith(label.text)).toBe(true));
  });

  it("name every square on its own at 100 km, where the readout agrees", () => {
    const { grid } = gridAt(34.78, -84.08, 8);
    const squares = grid.labels.filter((l) => l.kind === "square");
    expect(squares.length).toBeGreaterThan(10);
    expect(squares.map((l) => l.text)).toContain("GD");
    squares.forEach((label) => {
      const read = toMgrs(label.lat, label.lon);
      expect(Number(read.slice(0, 2))).toBe(label.zone);
      expect(read.slice(4, 6)).toBe(label.text);
    });
    expect(grid.labels.filter((l) => l.kind === "gzd").map((l) => l.text)).toEqual(
      expect.arrayContaining(["16S", "17S"]),
    );
  });

  it.each([
    ["at 1 km", 34.78, -84.08, 14],
    ["at 10 km over a zone edge", 34.5, -84, 11],
    ["at 100 km", 34.78, -84.08, 8],
    ["zoomed out", 40, -100, 5],
    ["in the south", -33.87, 151.21, 13],
  ])("never overlap and stay in the view %s", (_name, lat, lon, zoom) => {
    const { view, grid } = gridAt(lat, lon, zoom);
    grid.labels.forEach((label, i) => {
      const [x0, y0, x1, y1] = label.box;
      expect(x0).toBeGreaterThanOrEqual(0);
      expect(y0).toBeGreaterThanOrEqual(0);
      expect(x1).toBeLessThanOrEqual(view.width + 1e-6);
      expect(y1).toBeLessThanOrEqual(view.height + 1e-6);
      grid.labels.slice(i + 1).forEach((other) => expect(overlap(label.box, other.box)).toBe(false));
    });
  });

  it("keep off the boxes they are told to avoid, such as the map's controls", () => {
    const avoid = [[0, 0, 400, 300]];
    const { grid } = gridAt(34.78, -84.08, 14, { avoid });
    expect(grid.labels.length).toBeGreaterThan(0);
    grid.labels.forEach((label) => expect(overlap(label.box, avoid[0])).toBe(false));
  });

  it("label eastings along the bottom when the top is covered, as a phone's search bar covers it", () => {
    // A 390 x 700 phone view whose top 60 px are the search bar and zoom buttons.
    const avoid = [[0, 0, 390, 60]];
    const { view, grid } = gridAt(34.78, -84.08, 14, { avoid }, [390, 700]);
    const eastings = grid.labels.filter((l) => l.kind === "easting");
    expect(eastings.length).toBeGreaterThan(1);
    eastings.forEach((label) => {
      expect(label.lat).toBe(view.bounds.south);
      expect(label.box[3]).toBeLessThanOrEqual(view.height);
      expect(label.edge).toBe("bottom");
      expect(Math.abs(toUtmInZone(label.lat, label.lon, label.zone).easting - label.value)).toBeLessThan(1e-3);
    });
    expect(grid.labels.some((l) => l.kind === "northing")).toBe(true);
  });

  it("keep eastings along the top, and add none along the bottom, when the top is clear", () => {
    const { view, grid } = gridAt(34.78, -84.08, 14, {}, [390, 700]);
    const eastings = grid.labels.filter((l) => l.kind === "easting");
    expect(eastings.length).toBeGreaterThan(1);
    eastings.forEach((label) => {
      expect(label.lat).toBe(view.bounds.north);
      expect(label.edge).toBe("top");
    });
  });

  it("label northings down the right when the left is covered", () => {
    const avoid = [[0, 0, 60, 700]];
    const { view, grid } = gridAt(34.78, -84.08, 14, { avoid }, [390, 700]);
    const northings = grid.labels.filter((l) => l.kind === "northing");
    expect(northings.length).toBeGreaterThan(1);
    northings.forEach((label) => {
      expect(label.box[2]).toBeGreaterThan(view.width - 60);
      expect(label.box[2]).toBeLessThanOrEqual(view.width);
      expect(label.edge).toBe("right");
      expect(Math.abs(toUtmInZone(label.lat, label.lon, label.zone).northing - label.value)).toBeLessThan(1e-3);
    });
  });

  it("stop at the cap, the finest first", () => {
    const { grid } = gridAt(34.78, -84.08, 8, { maxLabels: 5 });
    expect(grid.labels).toHaveLength(5);
    expect(grid.labels.every((l) => l.kind === "gzd" || l.kind === "square")).toBe(true);
    expect(grid.labels.slice(0, 2).map((l) => l.kind)).toEqual(["gzd", "gzd"]);
  });
});

describe("limits", () => {
  it("drops the finest level whole when the lines would pass the cap, never a wrong line", () => {
    const full = gridAt(34.5, -84, 12, { pad: 0.25 }).grid;
    const zones = linesOf(full, "zone").length;
    const majors = linesOf(full, "major").length;
    expect(full.spacing).toBe(10000);
    expect(majors).toBeGreaterThan(0);

    const coarser = gridAt(34.5, -84, 12, { pad: 0.25, maxLines: zones + majors + 1 }).grid;
    expect(coarser.dropped).toEqual(["minor"]);
    expect(coarser.spacing).toBe(100000);
    expect(linesOf(coarser, "minor")).toHaveLength(0);
    expect(linesOf(coarser, "major")).toHaveLength(majors);
    expect(disagreements(coarser)).toEqual([]);

    const zonesOnly = gridAt(34.5, -84, 12, { pad: 0.25, maxLines: zones }).grid;
    expect(zonesOnly.dropped).toEqual(["major", "minor"]);
    expect(zonesOnly.spacing).toBeNull();
    expect(linesOf(zonesOnly).every((l) => l.kind === "zone")).toBe(true);
    expect(linesOf(zonesOnly)).toHaveLength(zones);
  });

  it("drops the finest level when its points would pass the cap", () => {
    const full = gridAt(34.5, -84, 12, { pad: 0.25 }).grid;
    const points = (kinds) => full.lines
      .filter((l) => kinds.includes(l.kind)).reduce((sum, l) => sum + l.points.length, 0);
    const { grid } = gridAt(34.5, -84, 12, { pad: 0.25, maxPoints: points(["zone", "major"]) + 1 });
    expect(grid.dropped).toEqual(["minor"]);
    expect(linesOf(grid, "major").length).toBeGreaterThan(0);
  });

  it("draws nothing when the zones would be a few pixels wide", () => {
    expect(gridAt(0, 0, 1).grid).toEqual({ spacing: null, lines: [], labels: [], dropped: [] });
  });

  it("draws the zones of every world in view when zoomed far out, and nothing else", () => {
    const { view, grid } = gridAt(0, 0, 2, {}, [4000, 2000]);
    expect(view.bounds.west).toBeLessThan(-360);
    expect(grid.lines.length).toBeGreaterThan(0);
    expect(grid.lines.every((l) => l.kind === "zone")).toBe(true);
    const lons = grid.lines.flatMap((l) => l.points.map(([, lon]) => lon));
    expect(Math.min(...lons)).toBeLessThan(-360);
    expect(Math.max(...lons)).toBeGreaterThan(360);
    expect(grid.lines.length).toBeLessThan(1000);
  });

  it("carries lines past the antimeridian, where the readout reads them as the next zone", () => {
    const { grid } = gridAt(52, 180, 10);
    const past = linesOf(grid).filter((l) => l.kind !== "zone" && l.points.some(([, lon]) => lon > 180));
    expect(past.length).toBeGreaterThan(0);
    past.forEach((line) => expect(line.zone).toBe(1));
  });

  it("draws nothing for a view it cannot use", () => {
    const empty = { spacing: null, lines: [], labels: [], dropped: [] };
    expect(mgrsGrid({ south: 34, west: -84, north: 34, east: -83 }, 10)).toEqual(empty);
    expect(mgrsGrid({ south: 34, west: -84, north: 35, east: -83 }, 0)).toEqual(empty);
    expect(mgrsGrid({ south: Number.NaN, west: -84, north: 35, east: -83 }, 10)).toEqual(empty);
    expect(mgrsGrid(undefined, 10)).toEqual(empty);
    // Wholly over the polar cap, past 84°N.
    expect(mgrsGrid({ south: 84.5, west: 0, north: 85, east: 2 }, 20)).toEqual(empty);
  });

  it("covers a margin round the view with lines when asked, and labels only the view", () => {
    const { view, grid } = gridAt(34.78, -84.08, 14, { pad: 0.25 });
    const lats = grid.lines.flatMap((l) => l.points.map(([lat]) => lat));
    expect(Math.max(...lats)).toBeGreaterThan(view.bounds.north);
    expect(Math.min(...lats)).toBeLessThan(view.bounds.south);
    grid.labels.forEach((label) => {
      expect(label.lat).toBeLessThanOrEqual(view.bounds.north);
      expect(label.lat).toBeGreaterThanOrEqual(view.bounds.south);
    });
  });
});
