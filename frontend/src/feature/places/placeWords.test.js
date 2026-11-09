import { countyWords, placeNote, placeSources, placeTitle, placeZoom } from "./placeWords";

describe("placeWords", () => {
  it("says a county as a crew would", () => {
    expect(countyWords("Towns", "GA")).toBe("Towns County");
    expect(countyWords("East Baton Rouge", "LA")).toBe("East Baton Rouge Parish");
    expect(countyWords("Nome (CA)", "AK")).toBe("Nome (CA)");
    expect(countyWords("Kenai Peninsula", "AK")).toBe("Kenai Peninsula");
    expect(countyWords("Suffolk (city)", "VA")).toBe("Suffolk (city)");
    expect(countyWords("Guam", "GU")).toBe("Guam");
    expect(countyWords(null, "GA")).toBe("");
  });

  it("notes a GNIS name by its county, an FAA place by its city", () => {
    expect(placeNote({ kindWords: "Populated place, historical", county: "Union", state: "GA", elevationFt: null }))
      .toBe("Populated place, historical · Union County, GA");
    expect(placeNote({ kindWords: "Airport", city: "BLAIRSVILLE", county: null, state: "GA", elevationFt: 1907 }))
      .toBe("Airport · BLAIRSVILLE, GA · 1,907 ft");
    expect(placeTitle({ name: "Brasstown Bald", ident: null })).toBe("Brasstown Bald");
  });

  it("goes as close to a town as to an airfield, closer to a point", () => {
    expect(placeZoom({ kind: "populated_place" })).toBe(14);
    expect(placeZoom({ kind: "airport" })).toBe(14);
    expect(placeZoom({ kind: "summit" })).toBe(15);
  });

  it("credits the sources the answer names", () => {
    expect(placeSources({ nasrCycle: "2026-10-29", gnisDate: "2026-11-02" })).toBe("FAA NASR 2026-10-29 · USGS GNIS 2026-11-02");
    expect(placeSources({ nasrCycle: "2026-10-29", gnisDate: null })).toBe("FAA NASR 2026-10-29");
    expect(placeSources(null)).toBe("");
  });
});
