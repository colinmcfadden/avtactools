import fs from "fs";
import path from "path";
import { TextDecoder as NodeTextDecoder, TextEncoder as NodeTextEncoder } from "util";
import { buildFplXml, buildGpxXml } from "./foreflight";
import { LIMITS, NO_ROUTE, TOO_LARGE, parseRouteFile, parseRouteText, pointTypeFromName, thinTrack } from "./parseRouteFile";
import { designatePoint } from "./sketchOps";
import { planPoints } from "./routeCalc";
import { DECLARES_DTD, NOT_XML, declaresDtd } from "../../utils/xmlFiles";

beforeAll(() => {
  if (typeof global.TextDecoder === "undefined") global.TextDecoder = NodeTextDecoder;
  if (typeof global.TextEncoder === "undefined") global.TextEncoder = NodeTextEncoder;
});

const FIXTURE = JSON.parse(fs.readFileSync(path.join(__dirname, "../../../../contracts/fixtures/routes/handoff.json"), "utf8"));

let counter;
beforeEach(() => { counter = 0; });
const options = (fileName = "plan.fpl") => ({ fileName, newPointId: () => `p${counter++}` });

const gpx = (body) => `<?xml version="1.0"?><gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">${body}</gpx>`;
const pt = (lat, lon, name) => `<rtept lat="${lat}" lon="${lon}">${name === undefined ? "" : `<name>${name}</name>`}</rtept>`;

// What the file can say of a point once written: the place to six decimals, in order.
// (+ 0: toFixed writes a tiny negative as "-0.000000", which is the same place as 0.)
const places = (points) => points.map((p) => [Number(p.lat.toFixed(6)) + 0, Number(p.lon.toFixed(6)) + 0]);

describe("files this app exported come back as what they were", () => {
  // Below two points there is no route, and every parser says so.
  const routable = FIXTURE.cases.filter((c) => c.route.points.length >= 2);

  it.each(routable.map((c) => [c.name, c]))("a flight plan: %s", (_, c) => {
    const { routes } = parseRouteText(c.expected.fpl, options());
    expect(routes).toHaveLength(1);
    const [route] = routes;
    expect(places(route.points)).toEqual(places(c.route.points));
    // The flight plan keeps a point's own name in its comment. One with none (or only blanks) has none, bar the two ends.
    route.points.slice(1, -1).forEach((point, index) => {
      const given = (c.route.points[index + 1].name ?? "").trim();
      expect(point.name).toBe(given.slice(0, 100));
      expect(point.kind).toBe(given ? "amps" : "shaping");
    });
    expect(route.points[0].kind).toBe("amps");
    expect(route.points.at(-1).kind).toBe("amps");
  });

  it.each(routable.map((c) => [c.name, c]))("a GPX: %s", (_, c) => {
    const { routes } = parseRouteText(c.expected.gpx, options("plan.gpx"));
    expect(routes).toHaveLength(1);
    expect(places(routes[0].points)).toEqual(places(c.route.points));
    expect(routes[0].points.length).toBe(c.route.points.length);
  });

  it("the same route written again says the same thing", () => {
    routable.forEach((c) => {
      const first = parseRouteText(c.expected.fpl, options()).routes[0];
      const again = parseRouteText(buildFplXml(first), options()).routes[0];
      expect(again.points.map((p) => p.name)).toEqual(first.points.map((p) => p.name));
      expect(again.points.map((p) => p.kind)).toEqual(first.points.map((p) => p.kind));
      expect(places(again.points)).toEqual(places(first.points));
      expect(again.name).toBe(first.name);

      const gpxFirst = parseRouteText(c.expected.gpx, options("x.gpx")).routes[0];
      const gpxAgain = parseRouteText(buildGpxXml(gpxFirst), options("x.gpx")).routes[0];
      expect(gpxAgain.points.map((p) => p.name)).toEqual(gpxFirst.points.map((p) => p.name));
      expect(places(gpxAgain.points)).toEqual(places(gpxFirst.points));
    });
  });

  it("a route's first case reads with the kinds the rule gives", () => {
    const [route] = parseRouteText(FIXTURE.cases[0].expected.fpl, options()).routes;
    expect(route.name).toBe(FIXTURE.cases[0].route.name.toUpperCase());
    expect(route.points.map((p) => [p.name, p.kind, p.ptType, p.role])).toEqual([
      [".LZ HAWK", "amps", "target", "start"],
      ["", "shaping", null, "waypoint"],
      ["IP 1", "amps", "ip", "waypoint"],
      [".TGT", "amps", "target", "waypoint"],
    ]);
    expect(route.id).toMatch(/^sketch-/);
    expect(route.elevations).toEqual({});
    expect(route.points.every((p) => p.ele === null)).toBe(true);
  });
});

describe("a point's type comes from its name", () => {
  it.each([
    [".TGT", "target"], ["TGT", "target"], ["tgt 2", "target"], [".LZ HAWK", "target"], ["PZ3", "target"],
    [".SP", "ip"], ["RP", "ip"], ["IP 1", "ip"], ["IP1", "ip"],
    [".CP", "turn"], ["SPRINGER", "turn"], ["LZHAWK", "turn"], ["RPM", "turn"], ["", "turn"],
  ])("%s is %s", (name, type) => expect(pointTypeFromName(name)).toBe(type));

  it("every route has two named points, so it can be planned and designated", () => {
    const { routes } = parseRouteText(gpx(`<rte>${pt(34, -84)}${pt(34.1, -84.1)}${pt(34.2, -84.2)}</rte>`), options("x.gpx"));
    expect(routes[0].points.map((p) => p.kind)).toEqual(["amps", "shaping", "amps"]);
    expect(routes[0].points.map((p) => p.name)).toEqual(["WP1", "", "WP3"]);
    expect(planPoints(routes[0])).toHaveLength(2);
    // Demoting one of the last two is refused as for any route.
    expect(designatePoint(routes[0], routes[0].points[0].id, { kind: "shaping" }).points[0].kind).toBe("amps");
  });
});

describe("files from other apps", () => {
  it("reads a flight plan by its identifiers, not the description in its comments", () => {
    const fpl = `<flight-plan xmlns="http://www8.garmin.com/xmlschemas/FlightPlan/v1"><waypoint-table>
      <waypoint><identifier>KATL</identifier><type>AIRPORT</type><country-code>K7</country-code><lat>33.6367</lat><lon>-84.4281</lon><comment>ATLANTA HARTSFIELD</comment></waypoint>
      <waypoint><identifier>ATL</identifier><type>VOR</type><country-code>K7</country-code><lat>33.6291</lat><lon>-84.4350</lon><comment>ATLANTA</comment></waypoint>
      <waypoint><identifier>ATL</identifier><type>NDB</type><country-code>K7</country-code><lat>1</lat><lon>2</lon></waypoint>
    </waypoint-table><route><route-name>home</route-name><flight-plan-index>1</flight-plan-index>
      <route-point><waypoint-identifier>KATL</waypoint-identifier><waypoint-type>AIRPORT</waypoint-type><waypoint-country-code>K7</waypoint-country-code></route-point>
      <route-point><waypoint-identifier>ATL</waypoint-identifier><waypoint-type>VOR</waypoint-type><waypoint-country-code>K7</waypoint-country-code></route-point>
    </route></flight-plan>`;
    const { routes, name } = parseRouteText(fpl, options());
    expect(name).toBe("HOME");
    expect(routes[0].points.map((p) => p.name)).toEqual(["KATL", "ATL"]);
    expect(routes[0].points[1].lat).toBe(33.6291);
  });

  it("takes a flight plan that is only its table, in the table's order", () => {
    const fpl = `<flight-plan><waypoint-table>
      <waypoint><identifier>B</identifier><lat>34</lat><lon>-84</lon></waypoint>
      <waypoint><identifier>A</identifier><lat>35</lat><lon>-85</lon></waypoint>
    </waypoint-table></flight-plan>`;
    expect(parseRouteText(fpl, options("my plan.fpl")).routes[0].points.map((p) => p.name)).toEqual(["B", "A"]);
    expect(parseRouteText(fpl, options("my plan.fpl")).name).toBe("MY PLAN");
  });

  it("counts what it leaves out of a flight plan, and never makes a place up", () => {
    const fpl = `<flight-plan><waypoint-table>
      <waypoint><identifier>A</identifier><lat>34</lat><lon>-84</lon></waypoint>
      <waypoint><identifier>B</identifier><lat></lat><lon>-84</lon></waypoint>
      <waypoint><identifier>C</identifier><lat>95</lat><lon>-84</lon></waypoint>
      <waypoint><identifier>D</identifier><lat>0x10</lat><lon>-84</lon></waypoint>
      <waypoint><identifier>E</identifier><lat>35</lat><lon>-85</lon></waypoint>
    </waypoint-table><route>
      <route-point><waypoint-identifier>A</waypoint-identifier></route-point>
      <route-point><waypoint-identifier>GHOST</waypoint-identifier></route-point>
      <route-point><waypoint-identifier>E</waypoint-identifier></route-point>
    </route></flight-plan>`;
    const result = parseRouteText(fpl, options());
    expect(result.routes[0].points.map((p) => p.name)).toEqual(["A", "E"]);
    expect(result.skipped).toBe(4);
    expect(result.summary).toBe("1 route · 2 points · 4 unreadable left out");
  });

  it("reads every route of a GPX, unnamespaced or not", () => {
    const body = `<rte><name>one</name>${pt(34, -84, "A")}${pt(34.1, -84.1, "B")}</rte><rte>${pt(35, -85, "C")}${pt(35.1, -85.1, "D")}</rte>`;
    const namespaced = parseRouteText(gpx(body), options("two.gpx"));
    expect(namespaced.routes.map((r) => r.name)).toEqual(["ONE", "TWO 2"]);
    expect(namespaced.name).toBe("TWO");
    const bare = parseRouteText(`<gpx>${body}</gpx>`, options("two.gpx"));
    expect(bare.routes).toHaveLength(2);
    const prefixed = parseRouteText(`<g:gpx xmlns:g="urn:x"><g:rte><g:rtept lat="1" lon="2"/><g:rtept lat="3" lon="4"/></g:rte></g:gpx>`, options("p.gpx"));
    expect(prefixed.routes[0].points).toHaveLength(2);
  });

  it("makes a route of each track segment, with only its ends named", () => {
    const seg = (n, lat) => `<trkseg>${Array.from({ length: n }, (_, i) => `<trkpt lat="${lat + i / 1000}" lon="-84"><name>fix</name><ele>900</ele></trkpt>`).join("")}</trkseg>`;
    const { routes } = parseRouteText(gpx(`<trk><name>flown</name>${seg(3, 34)}${seg(1, 35)}${seg(2, 36)}</trk>`), options("t.gpx"));
    expect(routes.map((r) => r.name)).toEqual(["FLOWN 1", "FLOWN 3"]);
    expect(routes[0].points.map((p) => p.kind)).toEqual(["amps", "shaping", "amps"]);
    expect(routes[0].points.every((p) => p.ele === null)).toBe(true);
  });

  it("thins a long track to what a route can hold, keeping its ends and its shape", () => {
    const zig = Array.from({ length: 20000 }, (_, i) => ({ lat: 34 + i / 10000, lon: -84 + Math.sin(i / 300) / 50 }));
    const thin = thinTrack(zig, 500);
    expect(thin.length).toBeLessThanOrEqual(500);
    expect(thin.length).toBeGreaterThan(20);
    expect(thin[0]).toBe(zig[0]);
    expect(thin.at(-1)).toBe(zig.at(-1));
    expect(thinTrack(zig.slice(0, 100), 500)).toHaveLength(100);
  });

  it("thins a straight track to its two ends", () => {
    const line = Array.from({ length: 1000 }, (_, i) => ({ lat: 34 + i / 10000, lon: -84 }));
    expect(thinTrack(line, 500)).toHaveLength(2);
  });

  it("says so when a track was simplified", () => {
    const trkpts = Array.from({ length: 600 }, (_, i) => `<trkpt lat="${34 + i / 1000}" lon="${-84 + Math.sin(i / 20) / 50}"/>`).join("");
    const result = parseRouteText(gpx(`<trk><trkseg>${trkpts}</trkseg></trk>`), options("t.gpx"));
    expect(result.routes[0].points.length).toBeLessThanOrEqual(LIMITS.trackPoints);
    expect(result.thinned).toBe(1);
    expect(result.summary).toMatch(/1 track simplified/);
  });

  it("says when waypoints beside routes were left out", () => {
    const result = parseRouteText(gpx(`<wpt lat="1" lon="2"><name>X</name></wpt><rte>${pt(34, -84, "A")}${pt(35, -85, "B")}</rte>`), options("x.gpx"));
    expect(result.points).toEqual([]);
    expect(result.waypointsLeftOut).toBe(1);
    expect(result.summary).toMatch(/1 loose waypoint left out/);
  });

  it("reads a GPX of only waypoints as local points, its elevations from metres to feet", () => {
    const result = parseRouteText(gpx(`<wpt lat="34.5" lon="-84.1"><ele>300</ele><name>PAD</name><desc>north pad</desc><type>LZS</type></wpt><wpt lat="35" lon="-85"/><wpt lat="99" lon="0"/>`), options("pts.gpx"));
    expect(result.routes).toEqual([]);
    expect(result.points).toEqual([
      { id: "p0", name: "PAD", description: "north pad", group: "LZS", icon: "", elevationFt: 984, lat: 34.5, lon: -84.1 },
      { id: "p1", name: "", description: "", group: "Default", icon: "", elevationFt: null, lat: 35, lon: -85 },
    ]);
    expect(result.skipped).toBe(1);
    expect(result.summary).toBe("2 points · 1 unreadable left out");
  });
});

describe("hostile and damaged files", () => {
  it("refuses a document type or an entity, by its text, before parsing", () => {
    const bomb = `<?xml version="1.0"?><!DOCTYPE gpx [<!ENTITY a "aaaa"><!ENTITY b "&a;&a;&a;">]><gpx><rte><name>&b;</name>${pt(34, -84, "A")}${pt(35, -85, "B")}</rte></gpx>`;
    expect(() => parseRouteText(bomb, options("x.gpx"))).toThrow(DECLARES_DTD);
    expect(() => parseRouteText(`<!doctype gpx SYSTEM "file:///etc/passwd"><gpx/>`, options("x.gpx"))).toThrow(DECLARES_DTD);
  });

  it("is not fooled by a declaration hidden between a comment and a CDATA section", () => {
    expect(declaresDtd(`<!-- <![CDATA[ --><!DOCTYPE x [<!ENTITY a "b">]><!-- ]]> --><x/>`)).toBe(true);
    expect(declaresDtd(`<?pi <!-- ?><!DOCTYPE x><!-- --><x/>`)).toBe(true);
    expect(declaresDtd(`<x><![CDATA[<!DOCTYPE html><p>a page</p>]]></x>`)).toBe(false);
    expect(declaresDtd(`<!-- <!DOCTYPE x> --><x/>`)).toBe(false);
  });

  it.each([
    ["not XML", "hello, this is a memo", NOT_XML],
    ["cut off", `<gpx><rte><rtept lat="1"`, NOT_XML],
    ["another document", "<html><body/></html>", "This is not a Garmin flight plan or a GPX file."],
    ["empty gpx", "<gpx/>", NO_ROUTE],
    ["a route of one point", `<gpx><rte>${pt(1, 2, "A")}</rte></gpx>`, NO_ROUTE],
    ["an empty flight plan", "<flight-plan/>", NO_ROUTE],
  ])("%s", (_, text, message) => {
    expect(() => parseRouteText(text, options("x.gpx"))).toThrow(message);
  });

  it("refuses a file past its size before reading it", async () => {
    const read = vi.fn();
    await expect(parseRouteFile({ name: "big.gpx", size: LIMITS.fileBytes + 1, arrayBuffer: read })).rejects.toThrow(TOO_LARGE);
    expect(read).not.toHaveBeenCalled();
  });

  it("does not trust the size a file claims", async () => {
    const bytes = new Uint8Array(LIMITS.fileBytes + 1);
    await expect(parseRouteFile({ name: "big.gpx", size: 10, arrayBuffer: async () => bytes.buffer })).rejects.toThrow(TOO_LARGE);
  });

  it("refuses a route past the limit in words, and too many routes", () => {
    const many = Array.from({ length: LIMITS.routePoints + 1 }, (_, i) => pt(34, -84 + i / 1e4, `P${i}`)).join("");
    expect(() => parseRouteText(`<gpx><rte>${many}</rte></gpx>`, options("x.gpx"))).toThrow(/more than 2,000 points/);
    const routes = Array.from({ length: LIMITS.routes + 1 }, () => `<rte>${pt(1, 2, "A")}${pt(3, 4, "B")}</rte>`).join("");
    expect(() => parseRouteText(`<gpx>${routes}</gpx>`, options("x.gpx"))).toThrow(/more than 100 routes/);
  });

  it("reads UTF-16 by its byte order mark", async () => {
    const text = `﻿<gpx><rte>${pt(34, -84, "Ä")}${pt(35, -85, "B")}</rte></gpx>`;
    const bytes = new Uint8Array(new Uint16Array(Array.from(text, (c) => c.charCodeAt(0))).buffer);
    const result = await parseRouteFile({ name: "x.gpx", size: bytes.byteLength, arrayBuffer: async () => bytes.buffer }, { newPointId: () => "p" });
    expect(result.routes[0].points[0].name).toBe("Ä");
  });
});
