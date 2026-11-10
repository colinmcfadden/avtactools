import { TextDecoder as NodeTextDecoder, TextEncoder as NodeTextEncoder } from "util";
import { act, renderHook } from "@testing-library/react";
import api from "../auth/api";
import { parseRouteText } from "../msnxImport/parseRouteFile";
import { DEFAULT_GROUP, LIMITS, exportFileName, pointFromForm, pointGroups, pointsToGpx } from "./customPoints";
import { useLocalPoints } from "./useLocalPoints";

vi.mock("../auth/api", () => ({ default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() } }));

beforeAll(() => {
  if (typeof global.TextDecoder === "undefined") global.TextDecoder = NodeTextDecoder;
  if (typeof global.TextEncoder === "undefined") global.TextEncoder = NodeTextEncoder;
});

const FORM = { name: "hawk", position: { lat: 34.5, lon: -84.25 }, elevation: "1730", group: "North", description: "Ridge" };

describe("a point typed into the form", () => {
  it("is held as an imported point is", () => {
    expect(pointFromForm(FORM).point).toEqual({ name: "HAWK", description: "Ridge", group: "North", icon: "", elevationFt: 1730, lat: 34.5, lon: -84.25 });
  });

  it("needs a name and a place, and says so of each", () => {
    expect(pointFromForm({ ...FORM, name: "  ", position: null }).problems).toEqual({ name: expect.any(String), position: expect.any(String) });
    expect(pointFromForm({ ...FORM, position: { lat: NaN, lon: 0 } }).problems.position).toBeDefined();
  });

  it("keeps a blank elevation blank and a zero zero", () => {
    expect(pointFromForm({ ...FORM, elevation: " " }).point.elevationFt).toBeNull();
    expect(pointFromForm({ ...FORM, elevation: "0" }).point.elevationFt).toBe(0);
    expect(pointFromForm({ ...FORM, elevation: "-12.5" }).point.elevationFt).toBe(-12.5);
  });

  it("refuses an elevation that is not a number of feet", () => {
    for (const elevation of ["high", "0x10", "1,000", "1e9"]) expect(pointFromForm({ ...FORM, elevation }).problems.elevation).toBeDefined();
  });

  it("puts a point with no group in the default one", () => {
    expect(pointFromForm({ ...FORM, group: " " }).point.group).toBe(DEFAULT_GROUP);
  });

  it("cuts by characters, so an emoji is never split", () => {
    const { point } = pointFromForm({ ...FORM, name: "😀".repeat(LIMITS.name + 5) });
    expect(Array.from(point.name)).toHaveLength(LIMITS.name);
    expect(point.name.endsWith("😀")).toBe(true);
  });
});

describe("a set's groups", () => {
  it("are in the order each first appears, a point with none in the default", () => {
    const points = [{ id: 1, group: "B" }, { id: 2 }, { id: 3, group: "B" }];
    expect(pointGroups(points).map((group) => [group.name, group.points.map((point) => point.id)])).toEqual([["B", [1, 3]], [DEFAULT_GROUP, [2]]]);
  });

  it("are none for nothing", () => {
    expect(pointGroups(undefined)).toEqual([]);
  });
});

describe("a file's name", () => {
  it("has nothing a file system or a chat app trips over", () => {
    expect(exportFileName("OBJ PEACH / NORTH", "LPS")).toBe("OBJ_PEACH_NORTH.LPS");
    expect(exportFileName("../../etc/passwd", "gpx")).toBe("etc_passwd.gpx");
  });

  it("is something even for a name that comes to nothing", () => {
    expect(exportFileName("///", "LPS")).toBe("local_points.LPS");
  });
});

describe("points written as GPX", () => {
  const POINTS = [
    { id: "a", name: "HAWK", description: "Ridge & road <north>", group: "North", icon: "", elevationFt: 1730, lat: 34.5, lon: -84.25 },
    { id: "b", name: "DOVE", description: "", group: "Default", icon: "", elevationFt: null, lat: 34.6, lon: -84.3 },
  ];

  it("come back as local points", () => {
    const parsed = parseRouteText(pointsToGpx("OBJ", POINTS), { fileName: "o.gpx" });
    expect(parsed.routes).toEqual([]);
    expect(parsed.points).toHaveLength(2);
    expect(parsed.points[0]).toMatchObject({ name: "HAWK", description: "Ridge & road <north>", group: "North", lat: 34.5, lon: -84.25 });
    expect(parsed.points[0].elevationFt).toBeCloseTo(1730, 0);
    expect(parsed.points[1].elevationFt).toBeNull();
  });

  it("escape what would break the file", () => {
    const text = pointsToGpx('A<B&"C', [{ ...POINTS[0], name: "</name><evil>" }]);
    expect(text).not.toContain("<evil>");
    expect(text).toContain("&lt;/name&gt;");
  });
});

describe("the hook", () => {
  const POINT = { name: "HAWK", description: "", group: "North", icon: "", elevationFt: null, lat: 34.5, lon: -84.25 };
  beforeEach(() => {
    vi.clearAllMocks();
    api.get.mockResolvedValue({ data: [] });
    api.post.mockResolvedValue({ data: { id: 7 } });
    api.put.mockResolvedValue({ data: { id: 7 } });
  });
  const first = (result) => result.current.pointSets[0];

  it("makes a set shown on the map, unsaved", () => {
    const { result } = renderHook(() => useLocalPoints());
    act(() => {
      result.current.createPointSet("CUSTOM", [POINT]);
    });
    expect(first(result)).toMatchObject({ name: "CUSTOM", visible: true, savedId: null, dirty: true });
    expect(first(result).points[0].id).toMatch(/^pt-/);
  });

  it("adds, changes and takes away points", () => {
    const { result } = renderHook(() => useLocalPoints());
    act(() => {
      result.current.createPointSet("CUSTOM", [POINT]);
    });
    const setId = first(result).id;
    act(() => result.current.addPoint(setId, { ...POINT, name: "DOVE" }));
    expect(first(result).points.map((point) => point.name)).toEqual(["HAWK", "DOVE"]);
    const pointId = first(result).points[0].id;
    act(() => result.current.updatePoint(setId, pointId, { name: "EAGLE" }));
    expect(first(result).points[0]).toMatchObject({ id: pointId, name: "EAGLE", group: "North" });
    act(() => result.current.removePoint(setId, pointId));
    expect(first(result).points.map((point) => point.name)).toEqual(["DOVE"]);
  });

  it("saves as any point set is saved, and knows when it is behind again", async () => {
    const { result } = renderHook(() => useLocalPoints());
    act(() => {
      result.current.createPointSet("CUSTOM", [POINT]);
    });
    await act(async () => {
      await result.current.savePointSet(first(result).id);
    });
    expect(api.post).toHaveBeenCalledWith("/pointsets", { name: "CUSTOM", points: first(result).points });
    expect(first(result)).toMatchObject({ savedId: 7, dirty: false });
    act(() => result.current.renamePointSet(first(result).id, "RENAMED"));
    expect(first(result).dirty).toBe(true);
    await act(async () => {
      await result.current.savePointSet(first(result).id);
    });
    expect(api.put).toHaveBeenCalledWith("/pointsets/7", expect.objectContaining({ name: "RENAMED" }));
    expect(first(result).dirty).toBe(false);
  });

  it("never sends a set with no points, which the server refuses", async () => {
    const { result } = renderHook(() => useLocalPoints());
    act(() => {
      result.current.createPointSet("EMPTY");
    });
    await expect(result.current.savePointSet(first(result).id)).rejects.toThrow(/no points/);
    expect(api.post).not.toHaveBeenCalled();
  });

  it("takes points the review already read, without reading the file", async () => {
    const { result } = renderHook(() => useLocalPoints());
    await act(async () => {
      await result.current.importLpsFile({ name: "w.gpx" }, { name: "WAYPOINTS", parsed: { name: "w", points: [{ id: "a", ...POINT }] } });
    });
    expect(first(result)).toMatchObject({ name: "WAYPOINTS", points: [expect.objectContaining({ name: "HAWK" })] });
  });
});
