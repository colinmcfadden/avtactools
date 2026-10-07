import { applyPackOp, validatePackOp } from "./packOps";
import { diffData, diffItem, sameData } from "./packDiff";

const clone = (value) => JSON.parse(JSON.stringify(value));

/** Applies `ops` to `data` as the server would, insisting each one is well formed and applies. */
const applyAll = (data, ops, kind = Array.isArray(data) ? "pointset" : "lz") => {
  let items = { it: { kind, name: "IT", data: clone(data), deleted: false } };
  ops.forEach((op, i) => {
    const full = { ...op, item: "it" };
    expect([i, validatePackOp(full)]).toEqual([i, null]);
    const result = applyPackOp(items, full);
    expect([i, op.type, result.status, result.reason]).toEqual([i, op.type, "applied", null]);
    items = result.items;
  });
  return items.it.data;
};

const roundTrips = (before, after) => {
  const ops = diffData(before, after);
  expect(sameData(applyAll(before, ops), after)).toBe(true);
  return ops;
};

const LZ = {
  schemaVersion: 2,
  status: "analyzed",
  target: { lat: 34.5, lon: -84.1, mgrs: "16S GC 28864 55349" },
  flightData: { landing_hdg: "090°", takeoff_hdg: "270°" },
  analysis: { detectedLZ: [[34.5, -84.1], [34.51, -84.1], [34.51, -84.09]], results: { area: 1200 } },
  graphics: {
    helicopters: [{ id: "h-1", lat: 34.5, lon: -84.1, heading: 90 }, { id: "h-2", lat: 34.501, lon: -84.1, heading: 90 }],
    sectorsOfFire: [],
    doghouses: [{ id: "dh1", role: "landing", label: "SP1" }],
    exportBox: null,
  },
};

describe("diffData on an LZ", () => {
  it("sends a moved helicopter as one patch at that helicopter", () => {
    const after = clone(LZ);
    Object.assign(after.graphics.helicopters[1], { lat: 34.502, lon: -84.101 });
    expect(roundTrips(LZ, after)).toEqual([
      { type: "patch", path: ["graphics", "helicopters", { id: "h-2" }], value: { lat: 34.502, lon: -84.101 } },
    ]);
  });

  it("follows into nested objects, so a heading change touches only the heading", () => {
    const after = clone(LZ);
    after.flightData.landing_hdg = "270°";
    expect(roundTrips(LZ, after)).toEqual([{ type: "patch", path: ["flightData"], value: { landing_hdg: "270°" } }]);
  });

  it("adds after its neighbour and removes by id", () => {
    const after = clone(LZ);
    after.graphics.sectorsOfFire.push({ id: "sec-1", points: [[34.5, -84.1]] });
    after.graphics.helicopters.splice(0, 1);
    expect(roundTrips(LZ, after)).toEqual([
      { type: "remove", path: ["graphics", "helicopters", { id: "h-1" }] },
      { type: "insert", path: ["graphics", "sectorsOfFire"], after: null, value: { id: "sec-1", points: [[34.5, -84.1]] } },
    ]);
  });

  it("replaces a boundary of [lat, lon] pairs whole, inside one patch with what else changed beside it", () => {
    const after = clone(LZ);
    after.analysis.detectedLZ[1] = [34.512, -84.1];
    after.analysis.results.area = 1400;
    expect(roundTrips(LZ, after)).toEqual([
      { type: "patch", path: ["analysis"], value: { detectedLZ: after.analysis.detectedLZ } },
      { type: "patch", path: ["analysis", "results"], value: { area: 1400 } },
    ]);
  });

  it("sends a field that went as null, and counts a missing field and a null one as the same", () => {
    const after = clone(LZ);
    delete after.flightData.takeoff_hdg;
    expect(roundTrips(LZ, after)).toEqual([{ type: "patch", path: ["flightData"], value: { takeoff_hdg: null } }]);
    const withNull = clone(LZ);
    withNull.flightData.extra = null;
    expect(diffData(LZ, withNull)).toEqual([]);
    expect(diffData(LZ, clone(LZ))).toEqual([]);
  });

  it("sets an object that appears or goes whole", () => {
    const after = clone(LZ);
    after.graphics.exportBox = { north: 34.6, south: 34.4, east: -84, west: -84.2 };
    after.target = null;
    roundTrips(LZ, after);
    expect(diffData(LZ, after)).toEqual([
      { type: "patch", path: [], value: { target: null } },
      { type: "patch", path: ["graphics"], value: { exportBox: after.graphics.exportBox } },
    ]);
  });

  it("never puts a key that reaches a prototype in a path", () => {
    const before = { constructor: { a: 1 }, other: { b: 1 } };
    const after = { constructor: { a: 2 }, other: { b: 2 } };
    const ops = roundTrips(before, after);
    expect(ops).toContainEqual({ type: "patch", path: [], value: { constructor: { a: 2 } } });
  });

  it("refuses to replace the item's data whole", () => {
    expect(() => diffData({ a: 1 }, [{ id: "a" }])).toThrow(/never replaced whole/);
    expect(() => diffData([{ name: "no id" }], [{ name: "still no id" }])).toThrow(/never replaced whole/);
  });

  it("names the item", () => {
    const after = clone(LZ);
    after.status = "targeted";
    expect(diffItem("lz-1", LZ, after)).toEqual([{ type: "patch", path: [], value: { status: "targeted" }, item: "lz-1" }]);
  });
});

describe("diffData on routes and points", () => {
  const ROUTES = {
    version: 1,
    routes: [{ id: "r-1", name: "RED 1", points: [{ id: "p-1", lat: 1, lon: 1 }, { id: "p-2", lat: 2, lon: 2 }, { id: "p-3", lat: 3, lon: 3 }] }],
  };

  it("puts a new point between its neighbours", () => {
    const after = clone(ROUTES);
    after.routes[0].points.splice(1, 0, { id: "p-9", lat: 1.5, lon: 1.5 });
    expect(roundTrips(ROUTES, after)).toEqual([
      { type: "insert", path: ["routes", { id: "r-1" }, "points"], after: "p-1", value: { id: "p-9", lat: 1.5, lon: 1.5 } },
    ]);
  });

  it("moves as few points as it can when the order changes", () => {
    const after = clone(ROUTES);
    after.routes[0].points = [after.routes[0].points[2], after.routes[0].points[0], after.routes[0].points[1]];
    const ops = roundTrips(ROUTES, after);
    expect(ops.filter((op) => op.type === "remove")).toHaveLength(1);
    expect(ops.filter((op) => op.type === "insert")).toHaveLength(1);
  });

  it("works on a set of points, whose data is the list itself", () => {
    const before = [{ id: "a", name: "A", lat: 1, lon: 1 }, { id: 2, name: "TWO", lat: 2, lon: 2 }];
    const after = [{ id: 2, name: "TWO", lat: 2.5, lon: 2 }, { id: "2", name: "TEXT TWO", lat: 3, lon: 3 }];
    const ops = roundTrips(before, after);
    expect(ops).toContainEqual({ type: "remove", path: [{ id: "a" }] });
    expect(ops).toContainEqual({ type: "patch", path: [{ id: 2 }], value: { lat: 2.5 } });
    expect(ops).toContainEqual({ type: "insert", path: [], after: 2, value: { id: "2", name: "TEXT TWO", lat: 3, lon: 3 } });
  });

  it("replaces a list whole when its ids repeat, since an id must name one element", () => {
    const before = { list: [{ id: "a", v: 1 }, { id: "a", v: 2 }] };
    const after = { list: [{ id: "a", v: 3 }] };
    expect(roundTrips(before, after)).toEqual([{ type: "patch", path: [], value: { list: [{ id: "a", v: 3 }] } }]);
  });
});

// Random documents and random changes to them: whatever the change, the operations rebuild it.
describe("diffData, at random", () => {
  let seed = 20261007;
  const random = () => {
    seed = (seed * 1103515245 + 12345) % 2147483648;
    return seed / 2147483648;
  };
  const pick = (list) => list[Math.floor(random() * list.length)];
  let nextId = 0;
  const newId = () => (random() < 0.2 ? (nextId += 1) : `e-${(nextId += 1)}`);

  const scalar = () => pick([0, 1.5, -84.123456789, "x", "", "LZ HAWK", true, false, null]);
  const element = (depth) => ({ id: newId(), ...object(depth + 1, 3) });
  const value = (depth) => {
    const r = random();
    if (depth > 3 || r < 0.45) return scalar();
    if (r < 0.65) return object(depth + 1, 4);
    if (r < 0.85) return Array.from({ length: Math.floor(random() * 4) }, () => element(depth + 1));
    return Array.from({ length: Math.floor(random() * 3) }, () => [scalar(), scalar()]);
  };
  const object = (depth, width) => {
    const result = {};
    Array.from({ length: 1 + Math.floor(random() * width) }).forEach(() => {
      result[pick(["a", "b", "c", "graphics", "points", "plan", "name"]) + Math.floor(random() * 3)] = value(depth);
    });
    return result;
  };

  // A random change somewhere inside `doc`, in place.
  const mutate = (doc) => {
    const containers = [];
    const walk = (node) => {
      if (Array.isArray(node)) {
        containers.push(node);
        node.forEach(walk);
      } else if (node && typeof node === "object") {
        containers.push(node);
        Object.values(node).forEach(walk);
      }
    };
    walk(doc);
    const target = pick(containers);
    if (Array.isArray(target)) {
      const isList = target.every((e) => e && typeof e === "object" && !Array.isArray(e) && "id" in e);
      const r = random();
      if (r < 0.3) target.splice(Math.floor(random() * (target.length + 1)), 0, isList ? element(2) : [scalar(), scalar()]);
      else if (r < 0.55 && target.length) target.splice(Math.floor(random() * target.length), 1);
      else if (r < 0.8 && target.length > 1) {
        const [moved] = target.splice(Math.floor(random() * target.length), 1);
        target.splice(Math.floor(random() * (target.length + 1)), 0, moved);
      } else if (target.length && isList) Object.assign(pick(target), { changed: scalar() });
    } else {
      const keys = Object.keys(target).filter((key) => key !== "id");
      const r = random();
      if (r < 0.4 && keys.length) target[pick(keys)] = value(2);
      else if (r < 0.6 && keys.length) delete target[pick(keys)];
      else target[`new${Math.floor(random() * 5)}`] = value(2);
    }
  };

  it("rebuilds every changed document from its operations", () => {
    const kinds = { set: 0, patch: 0, insert: 0, remove: 0 };
    for (let round = 0; round < 400; round += 1) {
      const before = object(0, 6);
      const after = clone(before);
      const changes = 1 + Math.floor(random() * 4);
      for (let i = 0; i < changes; i += 1) mutate(after);
      roundTrips(before, after).forEach((op) => { kinds[op.type] += 1; });
    }
    // The changes really reached every kind of operation, not only the easy ones; and none needed a `set`.
    expect(kinds.set).toBe(0);
    ["patch", "insert", "remove"].forEach((type) => expect([type, kinds[type] > 20]).toEqual([type, true]));
  });

  it("rebuilds every changed set of points", () => {
    for (let round = 0; round < 200; round += 1) {
      const before = Array.from({ length: Math.floor(random() * 6) }, () => element(1));
      const after = clone(before);
      // Changes inside the list (a set's data is always a list of points with ids).
      for (let i = 0; i < 3; i += 1) mutate(after);
      roundTrips(before, after);
    }
  });
});
