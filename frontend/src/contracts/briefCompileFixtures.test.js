import { partsText, scriptText } from "../feature/brief/compile";
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
    expect(scriptText(step(amb, "1")).join(" ")).toContain("The packet holds the brief (18 steps), 5 LZ/PZ cards, 2 nav logs and the IIMC card");
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
