import { graphicScale, referenceZoom } from "../feature/graphicScale/scaleRule";

const fs = require("fs");
const { UPDATE, dumps, fixturePath, writeFixture } = require("./fixtureIO");

// How large the LZ/PZ's planning graphics are drawn as the map zooms out (scaleRule.js): the zoom at which an LZ's longer side is
// 100 px, and the scale and whether they are put away at a zoom. Both are rounded by the web (a hundredth, a thousandth), so the file
// is the same on any machine. Zooms are Leaflet's (MapLibre's plus one) and pixels CSS pixels.

const NAME = "planning/graphicScale.json";

// An LZ about 200 m across in north Georgia, and others round it: each a boundary as the web may hold one.
const LZ = [[34.7838, -84.0822], [34.7838, -84.0800], [34.7822, -84.0800], [34.7822, -84.0822]];
const BOUNDARIES = {
  anLz: LZ,
  asLatLon: LZ.map(([lat, lon]) => ({ lat, lon })),
  asLatLng: LZ.map(([lat, lng]) => ({ lat, lng })),
  tallAndNarrow: [[34.79, -84.08], [34.78, -84.08], [34.78, -84.0799]],
  aFieldTwoKmAcross: [[34.8, -84.1], [34.8, -84.078], [34.782, -84.078]],
  aYardAcross: [[34.7838, -84.0822], [34.78381, -84.08219]],
  nearThePole: [[84.9, 10], [84.9, 10.01], [84.89, 10.01]],
  pastTheMercatorLimit: [[89.5, 10], [89.6, 10.002]],
  oneCorner: [[34.7838, -84.0822]],
  noLength: [[34.7838, -84.0822], [34.7838, -84.0822]],
  someNotPlaces: [[34.7838, -84.0822], ["34.78", -84.08], { lat: 34.7822 }, null, [34.7822, -84.0800]],
  none: [],
  notAList: { lat: 34.7838, lon: -84.0822 },
};

const ZOOMS = [21, 18, 17.5, 17, 16.99, 16, 15.5, 15.000000001, 15, 14.9999999999, 14, 10, 0, -3];
const REFERENCES = [17, 15, 18, 16.37];

const fixture = () => ({
  generatedBy: "frontend/src/contracts/graphicScaleFixtures.test.js (UPDATE_CONTRACTS=1)",
  description:
    "referenceZoom(boundary): corners as [lat, lon], {lat, lon} or {lat, lng} (anything else left out), projected with Leaflet's zoom-0 Web Mercator (256 px world, "
    + "latitude clamped to 85.0511287798); the zoom at which the larger of their x and y extents is 100 px, kept within 15 to 18 and rounded to a hundredth; 17 with fewer than two "
    + "corners or no extent. graphicScale(zoom, reference = 17): {scale: min(1, 2^((zoom - reference) / 2)) rounded to a thousandth, hidden: zoom < reference - 2 - 1e-9}; a zoom that "
    + "is not a finite number (null here) is {scale: 1, hidden: false}.",
  references: Object.entries(BOUNDARIES).map(([name, boundary]) => ({ name, boundary, zoom: referenceZoom(boundary) })),
  scales: [
    ...REFERENCES.flatMap((reference) => ZOOMS.map((zoom) => ({ zoom, reference, ...graphicScale(zoom, reference) }))),
    ...ZOOMS.slice(0, 4).map((zoom) => ({ zoom, ...graphicScale(zoom) })),
    { zoom: null, reference: 17, ...graphicScale(Number.NaN, 17) },
  ],
});

describe("the graphic scale fixture", () => {
  it(NAME, () => {
    const text = dumps(fixture());
    if (UPDATE) {
      writeFixture(NAME, text);
      return;
    }
    expect(fs.readFileSync(fixturePath(NAME), "utf8")).toBe(text);
  });
});
