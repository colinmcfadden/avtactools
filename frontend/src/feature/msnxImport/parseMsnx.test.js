import fs from "fs";
import path from "path";
import JSZip from "jszip";
import { parseMsnxFile } from "./parseMsnx";
import { applyPlanToMsnxDocs, buildMsnxBlob } from "./mutateMsnx";
import { computeRoutePlan, planPoints } from "./routeCalc";

const TEMPLATE_PATH = path.join(__dirname, "../../../public/msnx_template.msnx");

const loadTemplate = () => fs.readFileSync(TEMPLATE_PATH);

test("keeps anonymous GPX points distinct without inventing AMPS IDs", async () => {
  const zip = await JSZip.loadAsync(loadTemplate());
  const gpx = await zip.file("mission.gpx").async("string");
  let remainingToRemove = 2;
  const gpxWithAnonymousPoints = gpx.replace(
    /<msnx:point\b[\s\S]*?<\/msnx:point>/g,
    (pointExtension) => {
      if (remainingToRemove === 0) return pointExtension;
      remainingToRemove -= 1;
      return "";
    },
  );
  expect(remainingToRemove).toBe(0);

  zip.file("mission.gpx", gpxWithAnonymousPoints);
  const mission = await zip.generateAsync({ type: "nodebuffer" });
  const { routes } = await parseMsnxFile(mission);
  const anonymousPoints = routes.flatMap((route) => route.points).filter((point) => !point.id);

  expect(anonymousPoints).toHaveLength(2);
  expect(anonymousPoints.every((point) => Boolean(point.uiId))).toBe(true);
  expect(new Set(anonymousPoints.map((point) => point.uiId))).toHaveProperty("size", 2);
});

test("reads planned performance data out of an imported mission", async () => {
  const { routes } = await parseMsnxFile(loadTemplate());
  expect(routes.length).toBeGreaterThan(0);

  const route = routes[0];
  expect(route.plan).toBeDefined();
  expect(route.elevations).toBeDefined();

  const amps = planPoints(route);
  expect(amps.length).toBeGreaterThanOrEqual(2);
  const computed = computeRoutePlan(route, route.plan, route.elevations);
  expect(computed.points.map((point) => point.uiId)).toEqual(
    amps.map((point) => point.uiId),
  );

  // Every AMPS point has a planned altitude and a ground elevation; every
  // point after the first (an arrival) has a "to" airspeed.
  for (const p of amps) {
    expect(route.plan.perPoint[p.id]?.altitude).toBeDefined();
    expect(typeof route.elevations[p.id]).toBe("number");
  }
  for (const p of amps.slice(1)) {
    expect(route.plan.perPoint[p.id]?.airspeed).toBeDefined();
  }

  // Route-level defaults are seeded from the first leg / first point.
  expect(route.plan.airspeed.value).toBeGreaterThan(0);
  expect(["ground", "indicated", "true"]).toContain(route.plan.airspeed.type);
});

test("plan edits round-trip through export back into the mission file", async () => {
  const { zip, docs, routes } = await parseMsnxFile(loadTemplate());
  const route = routes[0];
  const amps = planPoints(route);
  const target = amps[1]; // an arrival point (has a leg into it)

  // Edit the plan the way the inline editor would — per-point "to" values.
  // (Wind must be a per-point override; the file already stores a per-point
  // wind on each arrival, which takes precedence over the route default.)
  route.plan.perPoint[target.id] = {
    ...route.plan.perPoint[target.id],
    airspeed: { value: 123, type: "ground" },
    altitude: { value: 1500, ref: "msl" },
    wind: { dirTrue: 250, speedKts: 20 },
  };

  // Write the plan back into the docs, re-zip, and re-parse.
  applyPlanToMsnxDocs(docs, route);
  await buildMsnxBlob(zip, docs); // mutates zip in place with the serialized docs
  const bytes = await zip.generateAsync({ type: "nodebuffer" });
  const { routes: reparsed } = await parseMsnxFile(bytes);

  const rRoute = reparsed[0];
  const rTarget = planPoints(rRoute).find((p) => p.id === target.id);
  expect(rTarget).toBeDefined();

  const over = rRoute.plan.perPoint[rTarget.id];
  expect(over.airspeed).toEqual({ value: 123, type: "ground" });
  // 1500 ft MSL survives the meters round-trip to the nearest foot.
  expect(over.altitude.ref).toBe("msl");
  expect(over.altitude.value).toBe(1500);
  // The applied leg wind (250°/20 kt) is read back within rounding.
  expect(Math.abs(over.wind.dirTrue - 250)).toBeLessThanOrEqual(1);
  expect(Math.abs(over.wind.speedKts - 20)).toBeLessThanOrEqual(1);
});
