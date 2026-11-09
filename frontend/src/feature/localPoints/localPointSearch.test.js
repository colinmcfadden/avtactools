import { findLocalPoints, localPointPlace, searchablePoints } from "./localPointSearch";
import { placeChoice, placeTitle } from "../places/placeWords";

const pt = (name, lat = 34, lon = -84, elevationFt) => ({ name, lat, lon, elevationFt });

describe("searchablePoints", () => {
  it("takes the sets here and the Library's, and counts a Library set already here once", () => {
    const here = [{ savedId: 7, name: "NORTH", points: [pt(".CP1")] }];
    const library = [
      { savedId: 7, name: "NORTH", points: [pt(".OLD")] },
      { savedId: 8, name: "SOUTH", points: [pt("HAWK", 33, -83, 900)] },
    ];
    expect(searchablePoints(here, library)).toEqual([
      { name: ".CP1", lat: 34, lon: -84, elevationFt: undefined, setName: "NORTH" },
      { name: "HAWK", lat: 33, lon: -83, elevationFt: 900, setName: "SOUTH" },
    ]);
  });

  it("leaves out a point with no name or place, and the same point in two sets", () => {
    const sets = [
      { name: "A", points: [pt("HAWK"), pt(""), pt("NOPLACE", null, -84)] },
      { name: "B", points: [pt("hawk")] },
    ];
    expect(searchablePoints(sets, []).map((p) => p.setName + p.name)).toEqual(["AHAWK"]);
  });
});

describe("findLocalPoints", () => {
  const names = (found) => found.map((p) => p.name);

  it("puts the whole name first, then how a name starts, then a word in it, then anywhere", () => {
    const points = [pt("OLD HAWK RD"), pt("SEAHAWK"), pt("HAWK 2"), pt("HAWK")];
    expect(names(findLocalPoints(points, "hawk"))).toEqual(["HAWK", "HAWK 2", "OLD HAWK RD", "SEAHAWK"]);
  });

  it("ignores a leading dot, on either side", () => {
    expect(names(findLocalPoints([pt(".CP1")], "cp1"))).toEqual([".CP1"]);
    expect(names(findLocalPoints([pt("CP1")], ".cp1"))).toEqual(["CP1"]);
  });

  it("reads past accents and waits for two characters", () => {
    expect(names(findLocalPoints([pt("CAFÉ RIDGE")], "cafe"))).toEqual(["CAFÉ RIDGE"]);
    expect(findLocalPoints([pt("HAWK")], "h")).toEqual([]);
    expect(findLocalPoints([pt("HAWK")], ". ")).toEqual([]);
  });

  it("breaks a tie by what is nearest, and stops at the limit", () => {
    const points = [pt("HAWK", 40, -90), pt("HAWK", 34.01, -84)];
    expect(findLocalPoints(points, "hawk", { lat: 34, lon: -84 }).map((p) => p.lat)).toEqual([34.01, 40]);
    expect(findLocalPoints([pt("AA1"), pt("AA2"), pt("AA3")], "aa", null, 2)).toHaveLength(2);
  });
});

describe("localPointPlace", () => {
  it("is chosen as a place is, its set named and the point kept", () => {
    const point = { name: "HAWK", lat: 34.5, lon: -84.1, elevationFt: 1730, setName: "NORTH" };
    const place = localPointPlace(point);
    expect(placeTitle(place)).toBe("HAWK");
    const choice = placeChoice(place);
    expect(choice).toMatchObject({ lat: 34.5, lon: -84.1, how: "Local point · NORTH", label: "HAWK" });
    expect(choice.place.localPoint).toBe(point);
  });
});
