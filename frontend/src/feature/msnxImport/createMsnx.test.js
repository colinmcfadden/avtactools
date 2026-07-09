import fs from "fs";
import path from "path";
import crypto from "crypto";
import JSZip from "jszip";
import { buildSketchMsnxZip } from "./createMsnx";
import { defaultRoutePlan } from "./routeCalc";

// jsdom lacks crypto.randomUUID in some versions.
beforeAll(() => {
  if (typeof global.crypto === "undefined") global.crypto = {};
  if (typeof global.crypto.randomUUID !== "function") {
    global.crypto.randomUUID = () => crypto.randomUUID();
  }
});

const TEMPLATE_PATH = path.join(__dirname, "../../../public/msnx_template.msnx");

const mkPoint = (id, lat, lon, kind, ptType, name) => ({
  id,
  lat,
  lon,
  ele: null,
  kind,
  ptType,
  name,
  role: "waypoint",
});

const sketchRoute = () => ({
  id: "sketch-test",
  name: "TEST ROUTE",
  color: "#FF453A",
  visible: true,
  plan: {
    ...defaultRoutePlan(),
    airspeed: { value: 120, type: "ground" },
    altitude: { value: 300, ref: "agl" },
    wind: { dirTrue: 270, speedKts: 20 },
    tempC: 20,
    fuelFlowLbHr: 1000,
    date: "2026-07-07",
    perPoint: { lz: { clock: "10:00:00" } },
  },
  elevations: { sp: 1000, cp1: 1200, lz: 900 },
  points: [
    mkPoint("sp", 34.0, -84.0, "amps", "turn", ".SP"),
    mkPoint("shape1", 34.25, -84.05, "shaping", null, ""),
    mkPoint("cp1", 34.5, -84.0, "amps", "ip", ".RP"),
    mkPoint("lz", 35.0, -84.0, "amps", "target", ".LZ"),
  ],
});

test("exports planned speeds, winds, altitudes, and clock times", async () => {
  const templateBytes = fs.readFileSync(TEMPLATE_PATH);
  const zip = await buildSketchMsnxZip(templateBytes, [sketchRoute()], "TEST MISSION");

  const legsXml = await zip.file("mission/legs.xml").async("string");
  const pointsXml = await zip.file("mission/points.xml").async("string");
  const routesXml = await zip.file("mission/routes.xml").async("string");

  // Route + legs carry the planned inputs.
  expect(routesXml).toContain("TEST ROUTE");
  expect(legsXml).toContain("raw 120 Ground Knot");
  expect(legsXml).toContain(`270 T/${20 * 0.514444} m/s`);

  // Points carry planned altitude (300 ft AGL = 91.44 m), elevations, TOT.
  expect(pointsXml).toContain("raw91.44 m Foot AGL");
  // .SP: 1000 ft ground + 300 AGL = 1300 ft MSL = 396.24 m.
  expect(pointsXml).toContain("396.24 MM");
  // .SP elevation 1000 ft = 304.8 m, user-sourced.
  expect(pointsXml).toContain("304.8 m User");
  // The LZ hits TOT exactly.
  expect(pointsXml).toContain("7/7/2026 10:00:00.0000 AM");

  // Both legs exist and reference the three AMPS points.
  const doc = new DOMParser().parseFromString(
    legsXml.replace(/^﻿?(?:\s*<\?xml[^>]*\?>)+\s*/, ""),
    "application/xml",
  );
  expect(doc.querySelector("parsererror")).toBeNull();
  const legs = Array.from(doc.getElementsByTagName("leg"));
  expect(legs).toHaveLength(2);

  // Clock times are consistent: SP time + total time = LZ 10:00:00.
  // ~60nm total at 120 GS ≈ 30 min, so SP departs ~09:29-09:31.
  const spTime = pointsXml.match(/7\/7\/2026 9:(\d{2}):(\d{2})\.0000 AM/);
  expect(spTime).not.toBeNull();
  expect(Number(spTime[1])).toBeGreaterThanOrEqual(28);
  expect(Number(spTime[1])).toBeLessThanOrEqual(32);
});
