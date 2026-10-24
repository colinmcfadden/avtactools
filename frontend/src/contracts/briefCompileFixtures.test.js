import { MAX_STRIPS, STRIP_CM, compileBrief, partsText, scriptText } from "../feature/brief/compile";
import { briefFormat } from "../feature/brief/templates";
import { CASES, compileCase } from "./briefCases";
import { UPDATE, compact, writeFixture } from "./fixtureIO";
import { readFixture } from "./readFixture";

// contracts/fixtures/brief/compile.json: what compileBrief makes of a plan, a format and the person's answers. The
// web is the reference (docs/MISSION_BRIEF.md §2.3); Android's core-brief is held to every case. Regenerate with
//   cd frontend && UPDATE_CONTRACTS=1 CI=true npx react-scripts test --watchAll=false src/contracts/briefCompile
// and review the diff. The cases are in briefCases.js.

const FIXTURE = "brief/compile.json";

describe("compileBrief fixtures", () => {
  if (UPDATE) {
    it("writes contracts/fixtures/brief/compile.json", () => {
      const cases = CASES.map((c) => ({ ...c, expected: compileCase(c) }));
      writeFixture(FIXTURE, `${compact({ generatedBy: "frontend/src/contracts/briefCompileFixtures.test.js (UPDATE_CONTRACTS=1)", cases })}\n`);
    });
    return;
  }

  const fixture = readFixture(FIXTURE);

  it("has every case this test builds, with the same inputs", () => {
    expect(fixture.cases.map(({ expected, ...inputs }) => inputs)).toEqual(JSON.parse(JSON.stringify(CASES)));
  });

  it.each(fixture.cases.map((c) => [c.name, c]))("%s", (_name, c) => {
    expect(compileCase(c)).toEqual(c.expected);
  });
});

// What the fixture cannot show at a glance: the rules a brief must keep, asked of the compiled cases directly.
describe("compileBrief", () => {
  const amb = compileCase(CASES[0]);
  const hasty = compileCase(CASES[1]);
  const step = (model, id) => model.steps.find((s) => s.id === id);

  it("briefs the routes and the LZ/PZs in the order the flight meets them", () => {
    expect(amb.steps.map((s) => s.id)).toEqual([
      "1", "2", "3", "4.a", "4.b",
      "4.c@route:set-opdk/r-alderaan", "4.e@lz:lz-bluebird", "4.e@lz:lz-hawk",
      "4.c@route:set-opdk/r-bespin", "4.d@lz:pz-ibis",
      "4.f@lz:farp-bucees", "4.e@lz:lz-decoy",
      "5.a", "5.b", "6", "7", "8", "9",
    ]);
  });

  it("gives every step a page, the first the cover, then the appendices", () => {
    expect(amb.steps.map((s) => s.page)).toEqual(amb.steps.map((_, i) => i + 1));
    expect(amb.pages[0].kind).toBe("cover");
    expect(amb.pages.filter((p) => p.kind === "lzCard")).toHaveLength(5);
    expect(amb.pages.filter((p) => p.kind === "navLog")).toHaveLength(2);
    expect(amb.pages[amb.pages.length - 1].kind).toBe("iimc");
    // The packet by section, never by page: Chromium decides where pages break.
    expect(scriptText(step(amb, "1")).join(" ")).toContain("The packet holds the brief (18 steps), 5 LZ/PZ cards, 2 nav logs, 12 strip charts and the IIMC card");
    expect(scriptText(step(amb, "1")).join(" ")).not.toMatch(/\d+–\d+/);
  });

  it("finds what the plan holds", () => {
    const hawk = step(amb, "4.e@lz:lz-hawk");
    const fact = (key) => hawk.facts.find((f) => f.key === key).value;
    expect(fact("stop.landingHeading")).toBe("203°");
    expect(fact("stop.elevation")).toBe("2,230 ft MSL");
    expect(fact("stop.goAround")).toBe("right");
    expect(hawk.items.find((i) => i.id === "4.e.i").text).toBe("16S GD 59833 44327");
    expect(step(amb, "4.b").items.find((i) => i.id === "4.b.i").text).toBe("11:31");
    expect(amb.constants.find((c) => c.id === "spOutboundAirspeed")).toMatchObject({ value: "60 KIAS", source: "standard" });
  });

  it("prints a blank as a write-in, in the item and in the script", () => {
    const hawk = step(amb, "4.e@lz:lz-hawk");
    expect(hawk.items.find((i) => i.id === "4.e.iii")).toMatchObject({ writeIn: true, text: null });
    expect(hawk.script.some((s) => s.some((p) => p.writeIn === "iii"))).toBe(true);
  });

  it("writes a standard procedure with the person's fields, and their own words for a custom one", () => {
    const contingencies = scriptText(step(amb, "5.a")).join(" ");
    expect(contingencies).toContain("Base altitude 4,000 ft MSL, adjusted 500 feet");
    expect(contingencies).toContain("Lead calls it; old lead takes trail.");
    expect(contingencies).toContain("Rally points are ACP2 (inbound), RP5 (outbound).");
    expect(contingencies).toContain("Bump plan: ________");
  });

  it("keeps an edited script as the person wrote it", () => {
    expect(step(amb, "3")).toMatchObject({ scriptEdited: true, script: [[{ text: "Insert the teams, extract them, everyone home." }]] });
  });

  it("briefs sections that share a step together, once per stop", () => {
    const lz = hasty.steps.filter((s) => s.sections.join() === "12,13");
    expect(lz.map((s) => s.ref)).toEqual(["lz:lz-bluebird"]);
    expect(hasty.steps.find((s) => s.id === "laager@lz:lz-hawk")).toBeTruthy();
    expect(hasty.steps.find((s) => s.id === "false@lz:lz-decoy")).toBeTruthy();
  });

  it("says what it could not brief", () => {
    const gone = compileCase(CASES[2]);
    expect(gone.flow[1]).toEqual({ ref: "lz:gone-lz", gone: true });
    expect(gone.problems.map((p) => p.ref)).toContain("lz:gone-lz");
    const short = compileCase(CASES[4]);
    expect(short.problems.map((p) => p.message).join(" ")).toMatch(/ONE needs at least two route points/);
    expect(short.problems.map((p) => p.message).join(" ")).toMatch(/LZ LONE, so it has no arrival time|reaches LZ LONE/);
  });

  it("takes nothing from the device's clock or zone", () => {
    const empty = compileCase(CASES[3]);
    expect(empty.mission.date).toBe("15 Oct 2026");
    expect(empty.steps.find((s) => s.id === "2").items.find((i) => i.id === "2.c").writeIn).toBe(true);
    expect(partsText([{ text: "a " }, { writeIn: "x" }])).toBe("a ________");
  });
});

// Strip charts (docs/MISSION_BRIEF.md §2.6): where a route is cut into strips, and what each says.
describe("compileBrief's strip charts", () => {
  const R = 3440.065 * 1852;
  const metres = ([lat1, lon1], [lat2, lon2]) => {
    const r = Math.PI / 180;
    const h = Math.sin(((lat2 - lat1) * r) / 2) ** 2 + Math.cos(lat1 * r) * Math.cos(lat2 * r) * Math.sin(((lon2 - lon1) * r) / 2) ** 2;
    return 2 * R * Math.asin(Math.sqrt(h));
  };
  const amb = compileCase(CASES[0]);
  const dog = compileCase(CASES.find((c) => /dogleg/.test(c.name)));
  const ofRoute = (model, ref) => model.strips.filter((s) => s.ref === ref);

  it("follows the nav logs, three strips to a page, a route's strips together, before the IIMC card", () => {
    const kinds = amb.pages.map((p) => p.kind);
    expect(kinds.lastIndexOf("navLog")).toBeLessThan(kinds.indexOf("strips"));
    expect(kinds.lastIndexOf("strips")).toBeLessThan(kinds.indexOf("iimc"));
    const stripPages = amb.pages.filter((p) => p.kind === "strips");
    expect(stripPages.every((p) => p.strips.length >= 1 && p.strips.length <= 3)).toBe(true);
    expect(stripPages.flatMap((p) => p.strips)).toEqual(amb.strips.map((s) => s.id));
    expect(stripPages.map((p) => p.title)).toEqual([
      "Strip charts · ALDERAAN (1–3 of 6)", "Strip charts · ALDERAAN (4–6 of 6)",
      "Strip charts · BESPIN (1–3 of 6)", "Strip charts · BESPIN (4–6 of 6)",
    ]);
    expect(dog.pages.filter((p) => p.kind === "strips").map((p) => p.title)).toEqual(["Strip charts · DOGLEG (1–3 of 4)", "Strip charts · DOGLEG (4 of 4)"]);
  });

  it("runs each route on unbroken, every strip starting where the last ended, none longer than the strip less its margins", () => {
    [amb, dog].forEach((model) => model.flow.filter((f) => f.kind === "route").forEach((r) => {
      const strips = ofRoute(model, r.ref);
      strips.forEach((s, i) => {
        expect(s).toMatchObject({ part: i + 1, parts: strips.length });
        if (i > 0) expect(s.path[0]).toEqual(strips[i - 1].path[strips[i - 1].path.length - 1]);
        expect(metres(s.path[0], s.path[s.path.length - 1])).toBeLessThanOrEqual(s.length * 0.9 + 1);
        expect(s.length / s.width).toBeCloseTo(STRIP_CM.length / STRIP_CM.width, 6);
      });
    }));
  });

  it("ends a strip at a real turn, holds a gentle bend, and runs a long leg on over several strips", () => {
    const strips = ofRoute(dog, "route:set-dog/dog");
    expect(strips.map((s) => s.points.map((p) => p.name))).toEqual([["SP", "BEND"], ["BEND", "DOG"], ["DOG"], ["FAR", "END"]]);
    expect(strips[0].path).toHaveLength(3); // the shaping point's bend stays on the first strip
    expect(strips.map((s) => s.courseText)).toEqual(["023°", "083°", "038°", "038°"]);
    expect(strips[2].legs.map((l) => l.from + "-" + l.to)).toEqual(["DOG-FAR"]);
    expect(strips[3].legs.map((l) => l.from + "-" + l.to)).toEqual(["DOG-FAR", "FAR-END"]);
    // A reversal ends one too: ALDERAAN's last strip is the leg back from BLUEBIRD to HAWK alone.
    expect(ofRoute(amb, "route:set-opdk/r-alderaan").pop()).toMatchObject({ courseText: "223°", points: [{ name: "BLUEBIRD" }, { name: "HAWK" }] });
  });

  it("says each leg as the nav log does, beside the stretch of it on the strip", () => {
    const first = amb.strips[0];
    expect(first.legs).toEqual([expect.objectContaining({ from: "3MILE", to: "TRACK", course: "030°", distance: "16.3 nm", time: "0+07+19", altitude: "2,500 ft MSL" })]);
    const log = amb.geometry.routes["route:set-opdk/r-alderaan"].log;
    amb.strips.filter((s) => s.ref === "route:set-opdk/r-alderaan").flatMap((s) => s.legs).forEach((leg) => {
      expect(log).toContainEqual(expect.objectContaining({ from: leg.from, to: leg.to, course: leg.course, distance: leg.distance, time: leg.time, altitude: leg.altitude }));
    });
  });

  it("puts the LZ/PZs and threats that lie on a strip on it, and leaves out threats the brief leaves out", () => {
    expect(dog.strips[3].stops.map((s) => s.name)).toEqual(["LZ END"]);
    expect(dog.strips[2].threats.map((t) => t.name)).toEqual(["SA-8"]);
    const quiet = compileCase({ ...CASES.find((c) => /dogleg/.test(c.name)), document: { options: { stripScale: 250000 }, include: { threats: false } } });
    expect(quiet.strips.every((s) => s.threats.length === 0)).toBe(true);
  });

  it("draws at the scale the brief asks, 1:100,000 unless it says 1:250,000, and none when strips are off or a route has no legs", () => {
    expect(amb.strips[0]).toMatchObject({ scale: 100000, length: 21500, width: 5500 });
    expect(dog.strips[0]).toMatchObject({ scale: 250000, length: 53750, width: 13750 });
    const odd = compileCase({ ...CASES[0], document: { ...CASES[0].document, options: { stripScale: 50000 } } });
    expect(odd.strips[0].scale).toBe(100000);
    expect(compileCase(CASES[2]).strips).toEqual([]);
    expect(compileCase(CASES[4]).strips).toEqual([]);
    expect(compileCase({ ...CASES[0], document: { ...CASES[0].document, options: { strips: false } } }).pages.some((p) => p.kind === "strips")).toBe(false);
  });

  it("never makes a strip of a stub, nor turns a piece with no length yet to the north", () => {
    const long = compileCase(CASES.find((c) => /doubled start/.test(c.name)));
    // The 119° leg (about 48.6 km) in three equal parts, the 030° leg after the turn in two; the doubled start goes with the first.
    expect(long.strips.map((s) => s.courseText)).toEqual(["119°", "119°", "119°", "030°", "029°"]);
    // A long leg leaves no stub: no part of it is under a quarter of what a strip holds.
    const usable = (s) => s.length * 0.9;
    long.strips.forEach((s) => expect(metres(s.path[0], s.path[s.path.length - 1])).toBeGreaterThan(usable(s) * 0.25));
    // As the review found them: a leg a little longer than a strip, then a turn onto another long leg, at both scales.
    const pt = (id, lat, lon) => ({ id, name: id.toUpperCase(), ptType: "turn", kind: "amps", lat, lon });
    const ahead = (lat, lon, course, m) => {
      const r = Math.PI / 180;
      return [lat + (m * Math.cos(course * r)) / 111320, lon + (m * Math.sin(course * r)) / (111320 * Math.cos(lat * r))];
    };
    [[100000, 19650], [250000, 48575]].forEach(([scale, length]) => {
      const b = ahead(34, -84, 120, length);
      const c = ahead(b[0], b[1], 30, 60000);
      const route = { ...CASES[0].plan.routeSets[0].data.routes[0], id: "r", name: "R",
        points: [pt("a", 34, -84), pt("b", ...b), pt("c", ...c)],
        plan: { ...CASES[0].plan.routeSets[0].data.routes[0].plan, perPoint: { a: { clock: "08:00:00" } } } };
      const model = compileBrief(briefFormat("amb"), { options: { stripScale: scale } }, { name: "X", routeSets: [{ id: "s", name: "S", data: { version: 1, routes: [route] } }] }, CASES[0].context);
      model.strips.forEach((st) => expect(metres(st.path[0], st.path[st.path.length - 1])).toBeGreaterThan(usable(st) * 0.25));
      expect(model.strips.every((st) => st.courseText !== "000°")).toBe(true);
    });
    // Threats the brief leaves out are on no strip, and a scale it does not know is 1:100,000.
    expect(long.strips.every((s) => s.threats.length === 0 && s.scale === 100000)).toBe(true);
  });

  it("cuts a route across the antimeridian the short way", () => {
    const date = compileCase(CASES.find((c) => /antimeridian/.test(c.name)));
    expect(date.strips.map((s) => s.courseText)).toEqual(["051°", "127°"]);
    expect(date.strips[0].path[1][1]).toBeCloseTo(180.05, 6);
  });

  it("gives no strips to a route past 84° of latitude, and a far-flung point costs no more than the most strips", () => {
    const route = (points) => ({ ...CASES[0].plan.routeSets[0].data.routes[0], id: "r", name: "R", points,
      plan: { ...CASES[0].plan.routeSets[0].data.routes[0].plan, perPoint: { a: { clock: "08:00:00" } } } });
    const pt = (id, lat, lon) => ({ id, name: id.toUpperCase(), ptType: "turn", kind: "amps", lat, lon });
    const compiled = (points) => compileBrief(briefFormat("amb"), {}, { name: "X", routeSets: [{ id: "s", name: "S", data: { version: 1, routes: [route(points)] } }] }, CASES[0].context);
    const polar = compiled([pt("a", 80, 10), pt("b", 85, 10)]);
    expect(polar.strips).toEqual([]);
    expect(polar.problems.map((p) => p.message)).toContain("R runs beyond 84° of latitude, past what the map and the MGRS grid show: it has no strip charts.");
    expect(compiled([pt("a", 34, -84), pt("b", 1e6, -84)]).strips).toEqual([]);
    const started = Date.now();
    const far = compiled([pt("a", -80, -84), pt("b", 83.9, -84)]);
    expect(far.strips).toHaveLength(MAX_STRIPS);
    expect(Date.now() - started).toBeLessThan(2000);
    // A route that goes nowhere has none.
    expect(compiled([pt("a", 34, -84), pt("b", 34, -84)]).strips).toEqual([]);
  });

  it("stops at the most strips a packet holds, and says so", () => {
    const far = { ...CASES[0].plan.routeSets[0].data.routes[0], id: "far", name: "FAR",
      points: [{ id: "f1", name: "A", ptType: "ip", kind: "amps", lat: 30, lon: -90 }, { id: "f2", name: "B", ptType: "target", kind: "amps", lat: 42, lon: -90 }],
      plan: { ...CASES[0].plan.routeSets[0].data.routes[0].plan, perPoint: { f1: { clock: "08:00:00" } } } };
    const model = compileBrief(briefFormat("amb"), {}, { name: "FAR", routeSets: [{ id: "s", name: "S", data: { version: 1, routes: [far] } }] }, CASES[0].context);
    expect(model.strips).toHaveLength(MAX_STRIPS);
    expect(model.problems.map((p) => p.message).join(" ")).toContain("Strip charts stop at 60 strips: the rest of FAR has none.");
  });
});
