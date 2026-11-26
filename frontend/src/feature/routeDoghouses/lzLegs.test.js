import { createDefaultDoghouses } from "../doghouses/useDoghouses";
import { doghousesBesideLegs, flightDataWithLegs, legWords, lzLegs } from "./lzLegs";

// The rule itself is held to contracts/fixtures/routes/doghouses.json (routeDoghouseFixtures.test.js); these are what the
// screens rely on besides: what is handed back when nothing stands in, so React sees nothing new.

const point = (id, lat, lon, name) => ({ id, lat, lon, kind: "amps", ptType: "turn", name });
const IN = { id: "in", name: "ROUTE 1", points: [point("rp", 34.58, -84.45, ".RP"), point("tgt", 34.6, -84.4, ".TGT")] };
const LZ = { lat: 34.6, lon: -84.4 };

describe("the legs into and out of an LZ/PZ", () => {
  it("hands back the same flight data and doghouses when no leg stands in", () => {
    const flightData = { landing_hdg: "065°" };
    const doghouses = createDefaultDoghouses(LZ, "lz");
    const none = lzLegs({ lat: 35, lon: -84 }, [IN]);
    expect(flightDataWithLegs(flightData, none)).toBe(flightData);
    expect(doghousesBesideLegs(doghouses, none)).toBe(doghouses);
  });

  it("takes the landing heading from the leg in, and leaves the takeoff one", () => {
    const legs = lzLegs(LZ, [IN]);
    expect(legs.takeoff).toBeNull();
    const judged = flightDataWithLegs({ landing_hdg: "065°", takeoff_hdg: "050°", lz_name: "HAWK" }, legs);
    expect(judged).toEqual({ landing_hdg: legs.landing.heading, takeoff_hdg: "050°", lz_name: "HAWK" });
    expect(doghousesBesideLegs(createDefaultDoghouses(LZ, "lz"), legs).map((dh) => dh.role)).toEqual(["takeoff"]);
    expect(legWords(legs.landing)).toBe("From ROUTE 1, RP to TGT");
  });

  it("follows the route as it is hidden, shown or moved off the LZ/PZ", () => {
    expect(lzLegs(LZ, [{ ...IN, visible: false }]).landing).toBeNull();
    expect(lzLegs(LZ, [IN]).landing).not.toBeNull();
    const movedOff = { ...IN, points: [IN.points[0], { ...IN.points[1], lat: 34.61 }] };
    expect(lzLegs(LZ, [movedOff]).landing).toBeNull();
  });

  it("says a leg with no route name as the route", () => {
    expect(legWords({ routeName: "", from: "RP", to: "TGT" })).toBe("From the route, RP to TGT");
    expect(legWords(null)).toBeNull();
  });
});
