import { overviewBounds, sandtableSteps, stepView } from "../feature/brief/sandtableSteps";
import { CASES as COMPILE_CASES, compileCase } from "./briefCases";
import { UPDATE, compact, writeFixture } from "./fixtureIO";
import { readFixture } from "./readFixture";

// contracts/fixtures/brief/sandtable.json: what the live sandtable makes of each brief in brief/compile.json: each step as it
// is listed (its place, number, section, title, who and time) and what the map shows for it (the area, the figure's kind
// and focus, and how each route and LZ/PZ is drawn). The web is the reference (feature/brief/sandtableSteps.js); Android's
// sandtable (docs/MISSION_BRIEF.md, B9) reads each case's model from compile.json and is held to every case. Regenerate
// with
//   cd frontend && UPDATE_CONTRACTS=1 CI=true npx react-scripts test --watchAll=false src/contracts/brief
// and review the diff. The briefs are compiled here from compile.json's own cases (briefCases.js), so both files come out
// of one run whatever order Jest runs the two tests in.

const FIXTURE = "brief/sandtable.json";
const COMPILED = COMPILE_CASES.map((c) => ({ name: c.name, expected: compileCase(c) }));

const sandtableCase = (c) => ({
  name: c.name,
  overview: overviewBounds(c.expected),
  steps: sandtableSteps(c.expected).map((listed, i) => ({ ...listed, view: stepView(c.expected, i) })),
});

const CASES = COMPILED.map(sandtableCase);

describe("brief/sandtable.json", () => {
  if (UPDATE) {
    it("writes the fixture", () => {
      writeFixture(FIXTURE, `${compact({ generatedBy: "frontend/src/contracts/briefSandtableFixtures.test.js (UPDATE_CONTRACTS=1)", from: "brief/compile.json", cases: CASES })}\n`);
    });
    return;
  }

  const fixture = readFixture(FIXTURE);

  it("holds a case for every compiled brief", () => {
    expect(fixture.cases.map((c) => c.name)).toEqual(COMPILED.map((c) => c.name));
  });

  it.each(CASES.map((c, i) => [c.name, i]))("%s", (name, i) => {
    expect(fixture.cases[i]).toEqual(JSON.parse(JSON.stringify(CASES[i])));
  });
});

describe("the sandtable's steps", () => {
  const opdk = CASES[0];
  const step = (id) => opdk.steps.find((s) => s.id === id);

  it("calls a step by its place in the brief, never by a page", () => {
    expect(opdk.steps[0].position).toBe(`Step 1 of ${opdk.steps.length}`);
    expect(JSON.stringify(CASES)).not.toMatch(/page/i);
  });

  it("names what a step is about and when the plan has it there", () => {
    const hawk = step("4.e@lz:lz-hawk");
    expect(hawk).toMatchObject({ number: "4.e", section: "Landing sequence", title: "LZ HAWK", who: "Flight lead" });
    expect(hawk.time).toMatch(/^\d{2}:\d{2}:\d{2}$/);
    expect(step("1")).toMatchObject({ section: "Roll call", title: "Roll call", time: null });
  });

  it("lights the route in focus, dims the others on a route step, and lights the departure's route", () => {
    const enroute = opdk.steps.find((s) => s.view.kind === "route");
    const lit = enroute.view.routes.filter((r) => r.lit);
    expect(lit.map((r) => r.ref)).toEqual([enroute.view.focus]);
    expect(enroute.view.routes.filter((r) => !r.lit).every((r) => r.dimmed && !r.labelled)).toBe(true);
    const departure = opdk.steps.find((s) => s.view.kind === "departure");
    expect(departure.view.routes.find((r) => r.lit)?.ref).toBe(departure.view.focus);
    expect(departure.view.focus).toBeTruthy();
  });

  it("shows the LZ/PZ in focus close, and the rally points only at the contingencies", () => {
    const hawk = step("4.e@lz:lz-hawk").view;
    expect(hawk.stops.filter((s) => s.close).map((s) => s.ref)).toEqual(["lz:lz-hawk"]);
    expect(opdk.steps.filter((s) => s.view.rally).map((s) => s.view.kind)).toEqual(["rally"]);
  });

  it("shows the whole plan, unlit, over the overview where a step has no figure", () => {
    const none = opdk.steps.find((s) => s.view.kind === null);
    expect(none.view.bounds).toEqual(opdk.overview);
    expect(none.view.routes.some((r) => r.lit || r.dimmed || r.labelled)).toBe(false);
    expect(none.view.stops.some((s) => s.close)).toBe(false);
  });

  it("has nowhere to fly for an empty plan", () => {
    const empty = CASES.find((c) => /empty plan/.test(c.name));
    expect(empty.overview).toBeNull();
    expect(empty.steps.every((s) => s.view.bounds === null && s.view.routes.length === 0)).toBe(true);
  });
});
