import fs from "fs";
import path from "path";
import { TextDecoder as NodeTextDecoder, TextEncoder as NodeTextEncoder } from "util";
import { buildGpxXml, buildSetGpxXml, downloadFpl, downloadGpx, downloadSetGpx, exportableRoutes } from "./foreflight";
import { downloadBlob } from "./mutateMsnx.js";
import { NO_ROUTE, parseRouteText } from "./parseRouteFile";

vi.mock("./mutateMsnx.js", () => ({ downloadBlob: vi.fn() }));

beforeAll(() => {
  if (typeof global.TextDecoder === "undefined") global.TextDecoder = NodeTextDecoder;
  if (typeof global.TextEncoder === "undefined") global.TextEncoder = NodeTextEncoder;
});

const FIXTURE = JSON.parse(fs.readFileSync(path.join(__dirname, "../../../../contracts/fixtures/routes/handoff-set.json"), "utf8"));

let counter;
beforeEach(() => {
  counter = 0;
  downloadBlob.mockClear();
});
const options = (fileName = "set.gpx") => ({ fileName, newPointId: () => `p${counter++}` });

// What the file can say of a point: the place to six decimals (see parseRouteFile.test.js).
const places = (points) => points.map((p) => [Number(p.lat.toFixed(6)) + 0, Number(p.lon.toFixed(6)) + 0]);

const textOf = (blob) =>
  new Promise((resolve) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result);
    reader.readAsText(blob);
  });

// Routes in the shape the sketch keeps them, with names a GPX keeps whole (an identifier is letters and digits)
// and types their names give (neither format records a type: parseRouteFile.js).
const point = (id, lat, lon, name = "", ptType = null) => ({ id, lat, lon, ele: null, role: "waypoint", kind: name ? "amps" : "shaping", ptType: name ? ptType ?? "turn" : null, name });
const ARIEL = {
  id: "sketch-a",
  name: "ARIEL",
  color: "#ffd60a",
  visible: true,
  elevations: {},
  plan: {},
  points: [point("a1", 34.78382, -84.08219, "SP", "ip"), point("a2", 34.8, -84.1), point("a3", 34.85, -84.15), point("a4", 34.9, -84.2, "CP1"), point("a5", 35.0, -84.3, "TGT", "target")],
};
const BEEMIN = {
  id: "sketch-b",
  name: "BEEMIN",
  color: "#bf5af2",
  visible: false,
  elevations: {},
  plan: {},
  points: [point("b1", 35.0, -84.3, "TGT", "target"), point("b2", 34.95, -84.4), point("b3", 34.9, -84.35, "RP", "ip"), point("b4", 34.78382, -84.08219, "LZ1", "target")],
};
const STUB = { id: "sketch-c", name: "STUB", points: [point("c1", 34.5, -84.5, "ONLY")] };

describe("which routes of a set a file holds", () => {
  it("is every route of two points or more, in the set's order, hidden on the map or not", () => {
    expect(exportableRoutes([ARIEL, STUB, BEEMIN])).toEqual([ARIEL, BEEMIN]);
    expect(exportableRoutes([BEEMIN, ARIEL])).toEqual([BEEMIN, ARIEL]);
    expect(exportableRoutes([{ ...ARIEL, visible: false }])).toHaveLength(1);
  });

  it("is none for a set with no route, or with nothing to read", () => {
    expect(exportableRoutes([])).toEqual([]);
    expect(exportableRoutes(undefined)).toEqual([]);
    expect(exportableRoutes([STUB, { name: "NONE", points: [] }, { name: "NO POINTS AT ALL" }, null])).toEqual([]);
  });
});

describe("a set's GPX", () => {
  it("is one file with a route for each of the set's routes, in order", () => {
    const xml = buildSetGpxXml([ARIEL, BEEMIN]);
    expect(xml.match(/<gpx /g)).toHaveLength(1);
    expect(xml.match(/<rte>/g)).toHaveLength(2);
    expect(xml.indexOf("<name>ARIEL</name>")).toBeLessThan(xml.indexOf("<name>BEEMIN</name>"));
    expect(buildSetGpxXml([BEEMIN, ARIEL]).indexOf("<name>BEEMIN</name>")).toBeLessThan(buildSetGpxXml([BEEMIN, ARIEL]).indexOf("<name>ARIEL</name>"));
    expect(xml.match(/<rtept /g)).toHaveLength(ARIEL.points.length + BEEMIN.points.length);
  });

  it("of one route is that route's own file, byte for byte", () => {
    expect(buildSetGpxXml([ARIEL])).toBe(buildGpxXml(ARIEL));
    expect(buildSetGpxXml([STUB, BEEMIN])).toBe(buildGpxXml(BEEMIN));
  });

  it("leaves out a route too short to be one, and says nothing of it", () => {
    const xml = buildSetGpxXml([ARIEL, STUB, BEEMIN]);
    expect(xml).toBe(buildSetGpxXml([ARIEL, BEEMIN]));
    expect(xml).not.toContain("STUB");
    expect(xml).not.toContain("ONLY");
  });

  it("escapes what XML would read as markup, in a route's name and nowhere lets a name close a route", () => {
    const xml = buildSetGpxXml([
      { name: 'A&B <C> "D"', points: [point("1", 34.5, -84.5, "R&D <site>"), point("2", 34.6, -84.6, "X")] },
      { name: "</rte><rte>", points: [point("3", 1, 1, "<name>"), point("4", 2, 2, "Y")] },
    ]);
    expect(xml).toContain('<name>A&amp;B &lt;C&gt; "D"</name>');
    expect(xml).toContain("<name>&lt;/RTE&gt;&lt;RTE&gt;</name>");
    expect(xml.match(/<rte>/g)).toHaveLength(2);
    expect(xml.match(/<\/rte>/g)).toHaveLength(2);
    const { routes } = parseRouteText(xml, options());
    expect(routes.map((r) => r.name)).toEqual(['A&B <C> "D"', "</RTE><RTE>"]);
  });
});

describe("a set exported and opened again is the same set", () => {
  it("has the same routes, names, points and order", () => {
    const xml = buildSetGpxXml([ARIEL, BEEMIN]);
    const result = parseRouteText(xml, options("260818_Multi.gpx"));
    expect(result.format).toBe("gpx");
    expect(result.routes).toHaveLength(2);
    expect(result.summary).toBe("2 routes · 9 points");
    // Several routes are named by the file they came in.
    expect(result.name).toBe("260818_MULTI");
    [ARIEL, BEEMIN].forEach((sent, index) => {
      const back = result.routes[index];
      expect(back.name).toBe(sent.name);
      expect(places(back.points)).toEqual(places(sent.points));
      expect(back.points.map((p) => p.name)).toEqual(sent.points.map((p) => p.name));
      expect(back.points.map((p) => p.kind)).toEqual(sent.points.map((p) => p.kind));
      expect(back.points.map((p) => p.ptType)).toEqual(sent.points.map((p) => p.ptType));
    });
    expect(result.skipped).toBe(0);
    expect(result.waypointsLeftOut).toBe(0);
  });

  it("and written again is the same file", () => {
    const xml = buildSetGpxXml([ARIEL, BEEMIN]);
    expect(buildSetGpxXml(parseRouteText(xml, options()).routes)).toBe(xml);
    const reversed = buildSetGpxXml([BEEMIN, ARIEL]);
    expect(buildSetGpxXml(parseRouteText(reversed, options()).routes)).toBe(reversed);
    expect(reversed).not.toBe(xml);
  });

  it("a set of one route opens as that route, named for it", () => {
    const result = parseRouteText(buildSetGpxXml([STUB, ARIEL]), options("whatever.gpx"));
    expect(result.routes).toHaveLength(1);
    expect(result.name).toBe("ARIEL");
  });

  it("keeps the same name in two routes the same in both", () => {
    const { routes } = parseRouteText(buildSetGpxXml([ARIEL, BEEMIN]), options());
    expect(routes[0].points.at(-1).name).toBe("TGT");
    expect(routes[1].points[0].name).toBe("TGT");
  });

  it.each(FIXTURE.cases.map((c) => [c.name, c]))("the fixture's set: %s", (_, c) => {
    const written = c.expected.written.map((index) => c.routes[index]);
    if (written.length === 0) {
      expect(() => parseRouteText(c.expected.gpx, options())).toThrow(NO_ROUTE);
      return;
    }
    const { routes, skipped } = parseRouteText(c.expected.gpx, options());
    expect(skipped).toBe(0);
    expect(routes).toHaveLength(written.length);
    routes.forEach((back, index) => {
      // A GPX keeps 25 characters of a route's name, in capitals.
      expect(back.name).toBe(written[index].name.toUpperCase().slice(0, 25).trim());
      expect(places(back.points)).toEqual(places(written[index].points));
    });
  });

  it("the fixture is not a few cases", () => {
    expect(FIXTURE.cases.length).toBeGreaterThanOrEqual(8);
  });
});

describe("the files a route and a set download as", () => {
  it("a set's is named for the set and holds every route of it", async () => {
    downloadSetGpx({ name: "260818 Multi", routes: [ARIEL, STUB, BEEMIN] });
    expect(downloadBlob).toHaveBeenCalledTimes(1);
    const [blob, fileName] = downloadBlob.mock.calls[0];
    expect(fileName).toBe("260818_Multi.gpx");
    expect(blob.type).toBe("application/gpx+xml");
    expect(await textOf(blob)).toBe(buildSetGpxXml([ARIEL, BEEMIN]));
  });

  it("a name a file cannot have is made one, and no name is 'routes'", () => {
    downloadSetGpx({ name: 'OP "A&B" <1>/2', routes: [ARIEL] });
    downloadSetGpx({ name: "", routes: [ARIEL] });
    downloadSetGpx({ routes: [ARIEL] });
    expect(downloadBlob.mock.calls.map((call) => call[1])).toEqual(["OP_A_B_1_2.gpx", "routes.gpx", "routes.gpx"]);
  });

  it("a route's are named as they always were", () => {
    downloadGpx(ARIEL);
    downloadFpl({ ...ARIEL, name: "NEPTUNE RUN" });
    downloadGpx({ ...ARIEL, name: "" });
    expect(downloadBlob.mock.calls.map((call) => call[1])).toEqual(["ARIEL.gpx", "NEPTUNE_RUN.fpl", "route.gpx"]);
  });
});
