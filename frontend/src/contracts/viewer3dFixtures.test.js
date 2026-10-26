import { act, renderHook } from "@testing-library/react";
import api from "../feature/auth/api";
import { MAX_TILT_DEG, compassPose } from "../feature/viewer3d/compass";
import { framingRange } from "../feature/viewer3d/framing";
import * as sceneSettings from "../feature/viewer3d/sceneSettings";
import { AGL_MISMATCH_FT, FT_TO_M, MAX_SAMPLES, SAMPLE_STEP_M, placeRoutes, prepareRoutes } from "../feature/viewer3d/routeScene";
import { routePositions } from "../feature/viewer3d/routeEntities";
import { PROBE_LEVEL, TERRAIN_MAX_LEVEL, TERRAIN_SAMPLES, terrainTileAt } from "../feature/viewer3d/terrainProvider";
import { useLidarTileset } from "../feature/viewer3d/useLidarTileset";

const fs = require("fs");
// The twister Cesium's PntsLoader samples a tile's points with (a dependency of @cesium/engine).
const MersenneTwister = require("mersenne-twister");
// Cesium itself, from its CommonJS build (never through webpack: AGENTS.md §14), for the sphere the web frames the routes in. It reads
// TextDecoder as it loads, which Jest's jsdom has not got: Node's own stands in.
if (typeof global.TextDecoder === "undefined") global.TextDecoder = require("util").TextDecoder;
const Cesium = require("cesium/Build/Cesium/index.cjs");
const { UPDATE, dumps, fixturePath, writeFixture } = require("./fixtureIO");

jest.mock("../feature/auth/api", () => ({
  __esModule: true,
  default: { get: jest.fn(), post: jest.fn(), delete: jest.fn() },
}));

// The 3D view's pure parts, as the web works them out, for the native apps to be held to (docs/ANDROID_3D_PLAN.md, V0): where each route's line,
// curtain and labels go (routeScene.js), how the compass turns and tilts (compass.js), which terrain tile holds a place (terrainProvider.js), and
// how a point cloud is found or built while someone waits (useLidarTileset.js, driven through every answer the server can give). The 3D picture
// itself is Cesium's and cannot be a fixture; everything that decides what it shows is here.

const GENERATED_BY = "frontend/src/contracts/viewer3dFixtures.test.js (UPDATE_CONTRACTS=1)";
const saved = (value) => JSON.parse(JSON.stringify(value));

const settle = (name, document) => {
  const file = fixturePath(name);
  const text = dumps(document);
  if (UPDATE) {
    writeFixture(name, text);
    return;
  }
  if (!fs.existsSync(file)) {
    throw new Error(`${name} is missing; run with UPDATE_CONTRACTS=1 to create it`);
  }
  expect(JSON.parse(fs.readFileSync(file, "utf8"))).toEqual(JSON.parse(text));
  expect(fs.readFileSync(file, "utf8")).toBe(text);
};

// -- The route scene ----------------------------------------------------------------------------------------------------------------------------

const amps = (id, lat, lon, name, ptType = "turn") => ({ id, lat, lon, name, kind: "amps", ptType });
const shaping = (id, lat, lon) => ({ id, lat, lon, kind: "shaping", name: "" });

const ROUTE = {
  id: "r1",
  name: "ROUTE 1",
  color: "#22c55e",
  visible: true,
  points: [
    amps("a", 34.6, -84.1, ".SP", "ip"),
    shaping("s1", 34.605, -84.095),
    amps("b", 34.61, -84.09, ".CP1"),
    amps("c", 34.62, -84.08, ".RP", "ip"),
  ],
  plan: { altitude: { value: 1500, ref: "msl" } },
  // The planner's ground, in feet, per AMPS point.
  elevations: { a: 1000, b: 1100, c: 1200 },
};

const ZONE_TO_ZONE = {
  ...ROUTE,
  id: "z1",
  name: "ZONES",
  points: [
    amps("pz", 34.59, -84.11, ".TGT", "target"),
    amps("a", 34.6, -84.1, ".SP", "ip"),
    amps("b", 34.61, -84.09, ".RP", "ip"),
    amps("lz", 34.62, -84.08, ".TGT", "target"),
  ],
  elevations: { pz: 900, a: 1000, b: 1100, lz: 1200 },
};

const GEOID_M = -30.35;
const feetMsl = (ft) => ft * FT_TO_M + GEOID_M;

/** A server answer for every sample: the same ground and geoid everywhere, then any sample's ground changed. */
const heights = (prepared, groundM, { geoidM = GEOID_M, at = {} } = {}) => ({
  groundM: prepared.samples.map((_s, i) => (i in at ? at[i] : groundM)),
  geoidM: prepared.samples.map(() => geoidM),
});

/** A long route's samples, by a few of them: its count, first and last five, and every 500th. */
const digest = (samples) => ({
  count: samples.length,
  first: samples.slice(0, 5),
  last: samples.slice(-5),
  every500: samples.filter((_s, i) => i % 500 === 0),
});

/**
 * What *Routes* frames (Viewer3D.jsx's `frameRoutes`): Cesium's own `BoundingSphere.fromPoints` of every place the lines pass, its centre in
 * geocentric metres; null with none to frame.
 */
const framing = (placedRoutes) => {
  const positions = routePositions(Cesium, placedRoutes);
  if (positions.length === 0) return null;
  const sphere = Cesium.BoundingSphere.fromPoints(positions);
  return { center: [sphere.center.x, sphere.center.y, sphere.center.z], radius: sphere.radius };
};

const sceneCase = (name, routes, answers = [], { long = false } = {}) => {
  const prepared = prepareRoutes(routes);
  const placed = answers.map(([label, makeAnswer]) => {
    const answer = makeAnswer(prepared);
    const placedRoutes = placeRoutes(prepared, answer);
    return { answer: label, ...(long ? {} : { heights: saved(answer) }), routes: saved(placedRoutes), framing: framing(placedRoutes) };
  });
  return {
    name,
    routes: saved(routes ?? null),
    prepared: long ? { routes: saved(prepared.routes.map(({ distances, ...rest }) => ({ ...rest, distances: distances.length }))), samples: digest(prepared.samples) }
      : saved(prepared),
    placed: long ? placed.map(({ answer, routes: placedRoutes, framing: sphere }) => ({ answer, routes: placedRoutes.map((r) => ({ id: r.id, markers: r.markers, lines: r.lines.length, walls: r.walls.length })), framing: sphere })) : placed,
  };
};

const COLORS = [
  "#22c55e", "#38bdf8", "#F97316", "#0af", "#0AF8", "#00aaff80", "  teal\u00a0", "TEAL", "DarkGrey", "darkgray", "transparent",
  "rgb(255, 0, 0)", "rgba(0,128,255,0.5)", "RGB(50%, 25%, 100%)", "rgb(12 34 56 / 0.25)", "rgb(1.5.2, 3, 4)", "hsl(120, 100%, 50%)",
  "hsla(240,50%,25%,0.3)", "hsl(400, 30%, 70%)", "#12345", "#ggg", "rgb(1, 2)", "notacolor", "",
];

const middle = (prepared) => Math.floor(prepared.samples.length / 2);
const anchorSample = (prepared, routeIndex, anchorIndex) => prepared.routes[routeIndex].anchors[anchorIndex].sample;

const routeSceneFixture = () => {
  const climbing = { ...ROUTE, plan: { altitude: { value: 1500, ref: "msl" }, perPoint: { b: { altitude: { value: 2500, ref: "msl" } } } } };
  const agl = { ...ROUTE, plan: { altitude: { value: 300, ref: "agl" } }, elevations: {} };
  const midTarget = { ...ZONE_TO_ZONE, points: [ZONE_TO_ZONE.points[0], amps("a", 34.6, -84.1, ".TGT2", "target"), ...ZONE_TO_ZONE.points.slice(2)] };
  const names = {
    ...ROUTE,
    id: "n1",
    points: [amps("a", 34.6, -84.1, "..DOUBLE", "ip"), amps("b", 34.61, -84.09, ""), amps("c", 34.62, -84.08, "PLAIN", "ip")],
  };
  const vee = {
    ...ROUTE,
    id: "vee",
    points: [amps("a", 34.65, -84.1, ".SP", "ip"), amps("b", 34.6, -84.075, ".CP1"), amps("c", 34.65, -84.05, ".RP", "ip")],
    elevations: {},
  };
  const long = {
    ...ROUTE,
    id: "long",
    points: [amps("a", 34.0, -84.5, ".SP", "ip"), amps("b", 35.5, -83.0, ".RP", "ip")],
    elevations: {},
  };
  return {
    generatedBy: GENERATED_BY,
    constants: { FT_TO_M, SAMPLE_STEP_M, MAX_SAMPLES, AGL_MISMATCH_FT },
    // A route's colour as Cesium reads it (routeEntities.js hands it `route.color || "#38bdf8"`): red, green, blue and alpha, or null for one
    // it cannot read, which stops the web's routes being drawn.
    colors: COLORS.map((css) => {
      const color = Cesium.Color.fromCssColorString(css);
      return { css, rgba: color ? [color.red, color.green, color.blue, color.alpha] : null };
    }),
    cases: [
      sceneCase("a route at MSL with a shaping point", [ROUTE], [
        ["flat ground", (p) => heights(p, 300)],
        ["the ground where the planner thought", (p) => heights(p, feetMsl(1000))],
        ["the ground 100 ft higher than the planner thought", (p) => heights(p, feetMsl(1100))],
        ["no ground in the middle", (p) => heights(p, 300, { at: { [middle(p)]: null } })],
        ["no geoid", (p) => heights(p, 300, { geoidM: null })],
        ["the route under the ground", (p) => heights(p, feetMsl(1600))],
        ["the route just under the ground", (p) => heights(p, feetMsl(1500) + 0.1)],
        ["heights for another set of routes", () => ({ groundM: [300, 300], geoidM: [-30, -30] })],
      ]),
      sceneCase("climbing between named points", [climbing], [["flat ground", (p) => heights(p, 300)]]),
      sceneCase("AGL with no planned MSL", [agl], [
        ["ground everywhere", (p) => heights(p, 400)],
        ["no ground under the RP", (p) => heights(p, 400, { at: { [anchorSample(p, 0, 2)]: null } })],
      ]),
      sceneCase("from a pickup zone to a landing zone", [ZONE_TO_ZONE], [
        ["the zones' ground known", (p) => heights(p, feetMsl(1200))],
        ["no ground under the landing zone", (p) => heights(p, feetMsl(1200), { at: { [anchorSample(p, 0, 3)]: null } })],
      ]),
      sceneCase("a target in the middle of a route", [midTarget], [["ground known", (p) => heights(p, feetMsl(1200))]]),
      sceneCase("what cannot be drawn", [
        { ...ROUTE, id: "hidden", visible: false },
        { ...ROUTE, id: "short", points: [amps("a", 34.6, -84.1, ".SP")] },
        { ...ROUTE, id: "broken", points: [amps("a", 34.6, -84.1, ".SP"), amps("b", Number.NaN, -84.09, ".CP1")] },
        { ...ROUTE, id: "one named point", points: [amps("a", 34.6, -84.1, ".SP"), shaping("s", 34.61, -84.09), shaping("t", 34.62, -84.08)] },
        { ...ROUTE, id: "kept" },
      ], [["flat ground", (p) => heights(p, 300)]]),
      sceneCase("two routes", [ROUTE, { ...ZONE_TO_ZONE, color: "#f97316" }], [["flat ground", (p) => heights(p, 300)]]),
      sceneCase("names as AMPS writes them", [names], [["flat ground", (p) => heights(p, 300)]]),
      // Framed as Cesium frames it, in geocentric coordinates, Ritter's sphere round a V starts from another pair of places, and comes out
      // another size, than it would in the LZ's own frame (a straight route's ends are the farthest apart either way).
      sceneCase("a route in a V", [vee], [["flat ground", (p) => heights(p, 300)]]),
      sceneCase("no routes", [], [["nothing", () => ({ groundM: [], geoidM: [] })]]),
      sceneCase("long routes, sampled more coarsely", [long, { ...long, id: "long2" }], [["flat ground", (p) => heights(p, 300)]], { long: true }),
    ],
  };
};

// -- The compass ---------------------------------------------------------------------------------------------------------------------------------

const DEG = Math.PI / 180;

const compassFixture = () => {
  const headings = [0, 30, 90, 179.5, 180, 270, 330, 359.4, 359.5, 359.6, 360, 390, -0.06, -30, -90, -400, 720.4];
  const pitches = [-90, -89.9, -60, -35, -28, -27.9, -10, 0, 10, 90];
  const cases = [];
  for (const heading of headings) {
    for (const pitch of pitches) {
      const headingRad = heading * DEG;
      const pitchRad = pitch * DEG;
      cases.push({ headingRad, pitchRad, expected: compassPose(headingRad, pitchRad) });
    }
  }
  return { generatedBy: GENERATED_BY, constants: { MAX_TILT_DEG }, cases };
};

// -- Terrain tiles -------------------------------------------------------------------------------------------------------------------------------

const terrainFixture = () => {
  const places = [
    { lat: 34.591552, lon: -84.128225 }, { lat: 0, lon: 0 }, { lat: -33.8688, lon: 151.2093 }, { lat: 64.8378, lon: -147.7164 },
    { lat: 90, lon: -180 }, { lat: -89.999999, lon: 179.999999 }, { lat: 45, lon: -90 }, { lat: 0.0000001, lon: -0.0000001 },
  ];
  const levels = [0, 1, 2, 9, PROBE_LEVEL, 14, 18, TERRAIN_MAX_LEVEL];
  return {
    generatedBy: GENERATED_BY,
    constants: { TERRAIN_SAMPLES, TERRAIN_MAX_LEVEL, PROBE_LEVEL },
    cases: places.flatMap((place) => levels.map((level) => ({ ...place, level, expected: terrainTileAt(place.lat, place.lon, level) }))),
  };
};

// -- Finding or building a point cloud -----------------------------------------------------------------------------------------------------------

const KEY = "0123456789abcdef";
const TARGET = { lat: 34.591552, lon: -84.128225 };
const built = (key = KEY, ring = false) => ({
  key, url: `/lidar/tilesets/${key}/tileset.json`, contextUrl: ring ? `/lidar/tilesets/${key}/context/tileset.json` : null,
});

const ok = (data, status = 200) => ({ status, data });
const refused = (status, data) => ({ status, data });
const UNREACHABLE = { network: true };

const NOT_BUILT = (canBuild) => ok({ key: KEY, available: false, url: null, contextUrl: null, canBuild, target: { ...TARGET, radius_m: 500 } });
const AVAILABLE = (ring = false) => ok({ ...built(KEY, ring), available: true });
const job = (state, extra = {}) => ({ key: KEY, state, stage: null, error: null, position: null, elapsed_s: null, ...extra });
const QUEUED = (position) => ok(job("queued", { stage: "waiting", position }), 202);
const RUNNING = (stage, elapsed) => ok(job("running", { stage, elapsed_s: elapsed }));
const DONE = (ring = false) => ok({ state: "done", ...built(KEY, ring), available: true });
const FORGOTTEN = refused(404, { error: "No build is running for this location.", code: "no_build" });
const SERVICE_DOWN = refused(503, { error: "The build service is not reachable.", code: "builder_unavailable" });

const STATE_FIELDS = ["state", "key", "url", "contextUrl", "target", "error", "stage", "position", "elapsedS", "buildFailed"];
const STREAM_FIELDS = [...STATE_FIELDS, "streamed", "frame"];
const snapshot = (result, fields = STATE_FIELDS) => Object.fromEntries(fields.map((field) => [field, result[field] ?? null]));

// Who waits is one random id per tab; the fixture names it.
const WATCHER = "<watcher>";
const named = (value) => (value && typeof value === "object" ? { ...value, ...(value.watcher ? { watcher: WATCHER } : {}) } : value);

const flush = async () => {
  for (let i = 0; i < 6; i += 1) {
    // eslint-disable-next-line no-await-in-loop
    await act(async () => { await Promise.resolve(); });
  }
};

/**
 * One scenario: the hook mounted on the target, then each step in turn. An answer step waits for the next request (moving the clock on half a
 * second at a time, which is how long after the last step it came), answers it, and records what the hook shows then. `keep` saves the LZ/PZ,
 * `retry` presses Retry, `close` closes the window. At the end, the request that would come next, if one comes within ten seconds.
 */
const flowCase = async (name, steps, { keep = false, target = TARGET, stream = false } = {}) => {
  const fields = stream ? STREAM_FIELDS : STATE_FIELDS;
  const waiting = [];
  const taking = (method) => (path, second) => {
    const request = { method, path };
    if (method === "POST") request.body = named(saved(second));
    else if (second?.params) request.params = named(saved(second.params));
    if (method === "DELETE") return Promise.resolve({});                    // let go and not waited for
    return new Promise((resolve, reject) => waiting.push({ request, resolve, reject }));
  };
  const deletes = [];
  api.post.mockImplementation(taking("POST"));
  api.get.mockImplementation(taking("GET"));
  api.delete.mockImplementation((path, config) => {
    deletes.push({ method: "DELETE", path, params: named(saved(config?.params)) });
    return Promise.resolve({});
  });

  // renderHook and rerender are in act already; what the hook starts is settled by flush.
  const { result, rerender, unmount } = renderHook((props) => useLidarTileset(props),
                                                  { initialProps: { ...target, keep, ...(stream ? { stream } : {}) } });
  await flush();
  const log = [{ mounted: { keep, ...(stream ? { stream } : {}) }, then: snapshot(result.current, fields) }];

  const nextRequest = async (limitMs) => {
    let afterMs = 0;
    while (waiting.length === 0 && afterMs < limitMs) {
      // eslint-disable-next-line no-await-in-loop
      await act(async () => { jest.advanceTimersByTime(500); });
      // eslint-disable-next-line no-await-in-loop
      await flush();
      afterMs += 500;
    }
    return waiting.length ? { pending: waiting.shift(), afterMs } : null;
  };

  for (const step of steps) {
    if (step.keep !== undefined) {
      rerender({ ...target, keep: step.keep, ...(stream ? { stream } : {}) });
      // eslint-disable-next-line no-await-in-loop
      await flush();
      log.push({ saved: step.keep, then: snapshot(result.current, fields) });
    } else if (step.retry) {
      // eslint-disable-next-line no-await-in-loop
      await act(async () => { result.current.refresh(); });
      // eslint-disable-next-line no-await-in-loop
      await flush();
      log.push({ retried: true, then: snapshot(result.current, fields) });
    } else if (step.close) {
      const before = deletes.length;
      unmount();
      // eslint-disable-next-line no-await-in-loop
      await flush();
      log.push({ closed: true, released: deletes.slice(before) });
    } else {
      // eslint-disable-next-line no-await-in-loop
      const next = await nextRequest(30000);
      if (!next) throw new Error(`${name}: no request came for ${JSON.stringify(step)}`);
      const { pending, afterMs } = next;
      const answer = step.answer;
      if (answer.network) pending.reject({ message: "Network Error" });
      else if (answer.status >= 400) pending.reject({ message: `Request failed with status code ${answer.status}`, response: { status: answer.status, data: answer.data } });
      else pending.resolve({ status: answer.status, data: answer.data });
      // eslint-disable-next-line no-await-in-loop
      await flush();
      const then = snapshot(result.current, fields);
      log.push({ request: pending.request, afterMs, answer, then, ...(then.error ? { errorFromServer: then.error === answer.data?.error } : {}) });
    }
  }
  const closed = steps.some((step) => step.close);
  const coming = closed ? null : await nextRequest(10000);
  if (!closed) unmount();
  return { name, keep, ...(stream ? { stream } : {}), target, steps: log, next: coming ? { request: coming.pending.request, afterMs: coming.afterMs } : null };
};

const flowFixture = async () => {
  const scenarios = [
    ["built already", [{ answer: AVAILABLE() }]],
    ["built already, with its ring", [{ answer: AVAILABLE(true) }]],
    ["this server cannot build", [{ answer: NOT_BUILT(false) }, { retry: true }, { answer: NOT_BUILT(false) }]],
    ["built while someone waits", [
      { answer: NOT_BUILT(true) }, { answer: QUEUED(2) }, { answer: ok(job("queued", { stage: "waiting", position: 1 })) },
      { answer: RUNNING("locating survey", 3) }, { answer: RUNNING("processing points", 41) }, { answer: RUNNING("building tiles", 96) },
      { answer: DONE(true) },
    ]],
    ["a stage this version does not know", [{ answer: NOT_BUILT(true) }, { answer: QUEUED(0) }, { answer: RUNNING("checking the sky", 5) }, { close: true }]],
    ["the build says it is done", [{ answer: NOT_BUILT(true) }, { answer: DONE() }]],
    ["saved while it builds", [{ answer: NOT_BUILT(true) }, { answer: QUEUED(0) }, { keep: true }, { answer: RUNNING("starting", 0) }, { answer: DONE() }]],
    ["saved from the start", [{ answer: NOT_BUILT(true) }, { answer: QUEUED(0) }, { answer: DONE() }], { keep: true }],
    ["closed while it builds", [{ answer: NOT_BUILT(true) }, { answer: QUEUED(0) }, { answer: RUNNING("processing points", 10) }, { close: true }]],
    ["closed while it looks up", [{ close: true }]],
    ["closed once it is open", [{ answer: AVAILABLE() }, { close: true }]],
    ["the build fails", [
      { answer: NOT_BUILT(true) }, { answer: QUEUED(0) },
      { answer: ok(job("failed", { stage: "locating survey", error: "No LiDAR survey covers this place.", elapsed_s: 2 })) },
      { retry: true }, { answer: NOT_BUILT(true) }, { answer: QUEUED(0) }, { close: true },
    ]],
    ["the service forgot the build", [{ answer: NOT_BUILT(true) }, { answer: QUEUED(0) }, { answer: FORGOTTEN }, { answer: QUEUED(0) }, { answer: DONE() }]],
    ["the service forgot it three times", [
      { answer: NOT_BUILT(true) }, { answer: QUEUED(0) },
      { answer: FORGOTTEN }, { answer: QUEUED(0) }, { answer: FORGOTTEN }, { answer: QUEUED(0) },
      { answer: FORGOTTEN }, { answer: FORGOTTEN }, { answer: FORGOTTEN }, { answer: FORGOTTEN }, { answer: FORGOTTEN },
    ]],
    ["dropped while someone waited", [
      { answer: NOT_BUILT(true) }, { answer: QUEUED(0) }, { answer: ok(job("cancelled", { stage: "cancelled" })) }, { answer: QUEUED(0) }, { answer: DONE() },
    ]],
    ["dropped three times", [
      { answer: NOT_BUILT(true) }, { answer: QUEUED(0) },
      { answer: ok(job("cancelled", { stage: "cancelled" })) }, { answer: QUEUED(0) },
      { answer: ok(job("cancelled", { stage: "cancelled" })) }, { answer: QUEUED(0) },
      { answer: ok(job("cancelled", { stage: "cancelled" })) }, { answer: ok(job("cancelled", { stage: "cancelled" })) }, { close: true },
    ]],
    ["an outage while it builds", [
      { answer: NOT_BUILT(true) }, { answer: QUEUED(0) }, { answer: SERVICE_DOWN }, { answer: UNREACHABLE }, { answer: RUNNING("building tiles", 70) },
      { answer: SERVICE_DOWN }, { answer: DONE() },
    ]],
    ["an outage, a moment's answer, and another outage", [
      { answer: NOT_BUILT(true) }, { answer: QUEUED(0) },
      { answer: SERVICE_DOWN }, { answer: UNREACHABLE }, { answer: SERVICE_DOWN }, { answer: UNREACHABLE }, { answer: RUNNING("building tiles", 70) },
      { answer: SERVICE_DOWN }, { answer: UNREACHABLE }, { answer: SERVICE_DOWN }, { answer: UNREACHABLE }, { answer: DONE() },
    ]],
    ["a job that names no stage", [{ answer: NOT_BUILT(true) }, { answer: ok(job("queued", { position: 3 }), 202) }, { answer: ok(job("running")) }, { close: true }]],
    ["a job whose stage is empty", [{ answer: NOT_BUILT(true) }, { answer: ok(job("queued", { stage: "", position: 0 }), 202) }, { close: true }]],
    ["done, with no tileset to open", [{ answer: NOT_BUILT(true) }, { answer: QUEUED(0) }, { answer: ok(job("done", { stage: "done", elapsed_s: 90 })) }, { close: true }]],
    ["built already, with an empty ring", [{ answer: ok({ ...built(KEY), contextUrl: "", available: true }) }]],
    ["contact lost for good", [
      { answer: NOT_BUILT(true) }, { answer: QUEUED(0) },
      { answer: UNREACHABLE }, { answer: UNREACHABLE }, { answer: SERVICE_DOWN }, { answer: UNREACHABLE }, { answer: UNREACHABLE },
    ]],
    ["the build cannot start", [{ answer: NOT_BUILT(true) }, { answer: SERVICE_DOWN }]],
    ["the queue is full", [{ answer: NOT_BUILT(true) }, { answer: refused(429, { error: "The build queue is full. Try again in a few minutes.", code: "builder_busy" }) }]],
    ["the build cannot start, no connection", [{ answer: NOT_BUILT(true) }, { answer: UNREACHABLE }]],
    ["the lookup fails", [{ answer: refused(500, "<!doctype html><title>500 Internal Server Error</title>") }, { retry: true }, { answer: AVAILABLE() }]],
    ["the lookup has no connection", [{ answer: UNREACHABLE }]],
    ["the lookup refused", [{ answer: refused(400, { error: "lat and lon are out of range." }) }]],
  ];
  const cases = [];
  for (const [name, steps, options] of scenarios) {
    // eslint-disable-next-line no-await-in-loop
    cases.push(await flowCase(name, steps, options));
  }
  // No target, nothing asked.
  cases.push(await flowCase("no target", [], { target: { lat: Number.NaN, lon: -84.1 } }));
  return { generatedBy: GENERATED_BY, constants: { pollMs: 3000 }, cases };
};

// The same lookup by a viewer that frames on the server's `frame`, so it asks for the place's survey streamed from USGS's octree
// (docs/DATA_ADMIN_AND_PREBUILT_3D_PLAN.md, Part C): what it sends, and what it shows of a streamed answer and of each other.
const STREAM_KEY = "fedcba9876543210";
const STREAMED = (heightM = 371.2) => ok({
  key: STREAM_KEY, available: true, streamed: true, url: `/lidar/tilesets/${STREAM_KEY}/tileset.json`, contextUrl: null,
  frame: { ...TARGET, radius_m: 500, height_m: heightM },
});
const streamFixture = async () => {
  const scenarios = [
    ["streamed", [{ answer: STREAMED() }]],
    ["streamed, the ground there unknown", [{ answer: STREAMED(null) }]],
    ["streamed, then closed", [{ answer: STREAMED() }, { close: true }]],
    ["built already, which wins", [{ answer: AVAILABLE(true) }]],
    ["nothing to stream there, so it is built", [{ answer: NOT_BUILT(true) }, { answer: QUEUED(0) }, { answer: DONE() }]],
    ["nothing to stream and nothing to build with", [{ answer: NOT_BUILT(false) }]],
    ["the lookup fails, then streams", [{ answer: UNREACHABLE }, { retry: true }, { answer: STREAMED() }]],
  ];
  const cases = [];
  for (const [name, steps, options] of scenarios) {
    // eslint-disable-next-line no-await-in-loop
    cases.push(await flowCase(name, steps, { ...options, stream: true }));
  }
  return { generatedBy: GENERATED_BY, constants: { pollMs: 3000 }, cases };
};

beforeEach(() => {
  jest.useFakeTimers();
  api.get.mockReset();
  api.post.mockReset();
  api.delete.mockReset();
  sessionStorage.clear();
});

afterEach(() => {
  jest.useRealTimers();
});

// -- How the view looks and moves ----------------------------------------------------------------------------------------------------------------

// How far back a sphere is framed (framing.js) for views of each shape: wider than tall, square, and narrower than tall (an upright phone),
// at Cesium's default field of view.
const FRAMING_FOV = Math.PI / 3;
const framingCases = () => [0, 150, 30750, 107881.2].flatMap((radius) => [16 / 9, 2.2, 1, 0.75, 412 / 915, 360 / 740, 0.3]
  .map((aspectRatio) => ({ radius, aspectRatio, range: framingRange(radius, aspectRatio, FRAMING_FOV) })));

// The settings the web's view is drawn with (sceneSettings.js), and the draws Cesium sizes a leaf's points from: a tile with no geometric error of
// its own is given one from the box round twenty of its points, picked by these twenty draws of its twister from seed 0, made once for every tile
// (PntsLoader.computeApproximateExtrema and getRandomValues; PointCloudStylingPipelineStage.getGeometricError); and the framings.
const sceneFixture = () => {
  const twister = new MersenneTwister(0);
  return {
    generatedBy: GENERATED_BY,
    settings: saved({ ...sceneSettings }),
    pointSample: { seed: 0, draws: Array.from({ length: 20 }, () => twister.random()) },
    framing: { fov: FRAMING_FOV, cases: framingCases() },
  };
};

test("viewer3d/routeScene.json is what the web places", () => settle("viewer3d/routeScene.json", routeSceneFixture()));
test("viewer3d/scene.json is how the web's view looks and moves, and how Cesium samples a tile to size its points and how far back a sphere is framed", () => settle("viewer3d/scene.json", sceneFixture()));
test("viewer3d/compass.json is how the web's compass turns and tilts", () => settle("viewer3d/compass.json", compassFixture()));
test("viewer3d/terrain.json is which terrain tile holds a place", () => settle("viewer3d/terrain.json", terrainFixture()));
test("viewer3d/lidarFlow.json is how the web finds or builds a point cloud", async () => settle("viewer3d/lidarFlow.json", await flowFixture()));
test("viewer3d/lidarFlowStream.json is how the web finds a streamed point cloud", async () => settle("viewer3d/lidarFlowStream.json", await streamFixture()));
