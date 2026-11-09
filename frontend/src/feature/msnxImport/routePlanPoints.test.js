import { insertNamedPoint, nextCheckpointName, removePoint, whyPointStays } from "./sketchOps";

const amps = (id, lat, lon, name = id) => ({ id, lat, lon, kind: "amps", ptType: "turn", name, role: "waypoint" });
const shaping = (id, lat, lon) => ({ id, lat, lon, kind: "shaping", ptType: null, name: "", role: "waypoint" });
const route = (...points) => ({ id: "r", points });
const ids = (r) => r.points.map((p) => p.id);
const newId = () => "new";

describe("nextCheckpointName", () => {
  it("takes the first number not used, whatever the case", () => {
    expect(nextCheckpointName(route(amps("a", 0, 0, ".SP")))).toBe(".CP1");
    expect(nextCheckpointName(route(amps("a", 0, 0, ".cp1"), amps("b", 0, 0, ".CP3")))).toBe(".CP2");
  });
});

describe("insertNamedPoint", () => {
  it("adds at the end with no point to go after", () => {
    const out = insertNamedPoint(route(amps("a", 0, 0), amps("b", 0, 1)), null, 0, 2, { name: ".X" }, newId);
    expect(ids(out)).toEqual(["a", "b", "new"]);
    expect(out.points[2]).toMatchObject({ kind: "amps", ptType: "turn", name: ".X", lat: 0, lon: 2 });
  });

  it("goes straight after a named point when the next is named", () => {
    const out = insertNamedPoint(route(amps("a", 0, 0), amps("b", 0, 1), amps("c", 0, 2)), "a", 5, 5, {}, newId);
    expect(ids(out)).toEqual(["a", "new", "b", "c"]);
  });

  it("takes the stretch nearest it where shaping points bend the leg", () => {
    const bent = route(amps("a", 0, 0), shaping("s1", 1, 1), shaping("s2", 1, 2), amps("b", 0, 3));
    expect(ids(insertNamedPoint(bent, "a", 1.1, 1.5, {}, newId))).toEqual(["a", "s1", "new", "s2", "b"]);
    expect(ids(insertNamedPoint(bent, "a", 0.4, 2.8, {}, newId))).toEqual(["a", "s1", "s2", "new", "b"]);
  });

  it("does not reach past the next named point for a nearer stretch", () => {
    const r = route(amps("a", 0, 0), amps("b", 0, 1), amps("c", 0, 2));
    expect(ids(insertNamedPoint(r, "a", 0, 1.9, {}, newId))).toEqual(["a", "new", "b", "c"]);
  });

  it("keeps a local point's charted elevation, and adds at the end if the point has gone", () => {
    const out = insertNamedPoint(route(amps("a", 0, 0), amps("b", 0, 1)), "gone", 0, 2, { chartElevationFt: 900 }, newId);
    expect(ids(out)).toEqual(["a", "b", "new"]);
    expect(out.points[2].chartElevationFt).toBe(900);
  });

  it("comes off again with removePoint", () => {
    const r = route(amps("a", 0, 0), amps("b", 0, 1));
    const out = insertNamedPoint(r, "a", 0, 0.5, {}, newId);
    expect(whyPointStays(out, "new")).toBeNull();
    expect(ids(removePoint(out, "new"))).toEqual(["a", "b"]);
  });
});
