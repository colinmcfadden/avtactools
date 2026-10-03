import { buildSidc, parseSidc, sidcAffiliation, symbolParts, withAffiliation } from "../feature/symbols/milsym";
import { AFFILIATIONS, ECHELONS, THREAT_PRESETS, UNIT_FUNCTIONS } from "../feature/symbols/presets";
import { UNIT_TYPES } from "../feature/unit/UnitIcons";
import { createUnit } from "../feature/unit/useUnit";

const fs = require("fs");
const path = require("path");
const vm = require("vm");
const { UPDATE, dumps, fixturePath, writeFixture } = require("./fixtureIO");

// MIL-STD-2525C symbols. Three things are held here for the native apps:
//
//  1. The pure SIDC logic the unit builder is made of (build, split, change the affiliation) and the preset lists, as JSON.
//  2. The preset symbols pre-rendered by milsymbol, as SVG files (symbols/svg/*.svg): the apps ship these, so the common symbols draw with
//     no JavaScript, and they are the web's own pictures. Each is listed with the size and anchor milsymbol reports for it.
//  3. The script the apps run in the JavaScript sandbox to draw every other symbol (android/core-symbols/.../ezpz-render.js), run here
//     against the vendored milsymbol.js in a context with no DOM, as the sandbox is, and held to the web's own answer for each case.
//
// Regenerate with UPDATE_CONTRACTS=1 after an intended change; upgrading milsymbol means copying its dist file over the vendored one.

const GENERATED_BY = "frontend/src/contracts/symbolFixtures.test.js (UPDATE_CONTRACTS=1)";
const ASSETS = path.resolve(__dirname, "../../../android/core-symbols/src/main/assets");
const SIZE = 32; // what the unit marker draws at (UnitMarker.jsx)

const settleText = (name, text) => {
  const file = fixturePath(name);
  if (UPDATE) {
    writeFixture(name, text);
    return;
  }
  if (!fs.existsSync(file)) throw new Error(`${name} is missing; run with UPDATE_CONTRACTS=1 to create it`);
  expect(fs.readFileSync(file, "utf8")).toBe(text);
};

const settle = (name, document) => settleText(name, dumps(document));

// -- 1. The SIDC logic ------------------------------------------------------------------------------------------------

const BUILD_ARGS = [
  undefined, {}, { affiliation: "H" }, { affiliation: "N", dimension: "A" }, { affiliation: "U", dimension: "S", status: "A" },
  { functionId: "UCI---", echelon: "D" }, { functionId: "UCI" }, { functionId: "UCIL---xx" }, { functionId: "" }, { echelon: "DD" }, { echelon: "" },
  { affiliation: "" }, { affiliation: "HH" }, { dimension: "" }, { status: "" }, { functionId: "UCIS--", echelon: "H", affiliation: "H" },
  { functionId: "EWMAI-", dimension: "G", affiliation: "H" },
];
const SIDCS = ["SFGPUCI--------", "SHGPEWRR------", "SUGPUCIL-D-----", "SFGP", "SFGPUCI---", "SFGPUCI----", "S", "", "SF", "SNAPMFF--------", "sfgpuci--------"];

const sidcFixture = () => ({
  generatedBy: GENERATED_BY,
  build: BUILD_ARGS.map((args) => ({ args: args ?? null, expected: buildSidc(args) })),
  withAffiliation: [...SIDCS.map((sidc) => [sidc, "H"]), ["SFGPUCI--------", "N"], ["SFGPUCI--------", ""], ["SFGPUCI--------", "HX"], [null, "H"]]
    .map(([sidc, affiliation]) => ({ sidc, affiliation, expected: withAffiliation(sidc, affiliation) })),
  affiliation: [...SIDCS, null].map((sidc) => ({ sidc, expected: sidcAffiliation(sidc) })),
  parse: [...SIDCS, null].map((sidc) => ({ sidc, expected: parseSidc(sidc) })),
  presets: {
    affiliations: AFFILIATIONS,
    unitFunctions: UNIT_FUNCTIONS,
    echelons: ECHELONS,
    threats: THREAT_PRESETS,
    unitTypes: UNIT_TYPES,
  },
  createUnit: [
    { name: "a preset", config: UNIT_TYPES[0], center: [34.5, -84.1], offset: 0.0004, id: "unit-1" },
    { name: "from the unit builder", config: { sidc: "SFGPUCIL-D-----", uniqueDesignation: "A/1-171", higherFormation: "2-101" }, center: [34.5, -84.1], offset: 0, id: "unit-2" },
    { name: "an older image-based unit", config: { id: "tank", path: "/units/tank.svg" }, center: [-33.8688, 151.2093], offset: 0.001, id: "unit-3" },
    { name: "a config with nothing in it", config: {}, center: [0, 0], offset: 0.00025, id: "unit-4" },
  ].map((c) => ({ ...c, expected: JSON.parse(JSON.stringify(createUnit(c.config, c.center, c.offset, c.id))) })),
});

// -- 2. The pre-rendered presets --------------------------------------------------------------------------------------

const AFFILIATION_IDS = AFFILIATIONS.map((a) => a.id);
/** Every unit preset in every affiliation, then the threat presets, which are hostile. */
const PRESET_SIDCS = [...UNIT_TYPES.flatMap((u) => AFFILIATION_IDS.map((a) => withAffiliation(u.sidc, a))), ...THREAT_PRESETS.map((t) => t.sidc)];
const svgName = (sidc) => `symbols/svg/${sidc}.svg`;

const webSymbol = (sidc, options = {}) => {
  const parts = symbolParts(sidc, { size: SIZE, ...options });
  return parts && { svg: parts.svg, width: parts.size.width, height: parts.size.height, anchorX: parts.anchor.x, anchorY: parts.anchor.y };
};

const presetIndex = () => ({
  generatedBy: GENERATED_BY,
  size: SIZE,
  symbols: PRESET_SIDCS.map((sidc) => {
    const { width, height, anchorX, anchorY } = webSymbol(sidc);
    return { sidc, file: `svg/${sidc}.svg`, width, height, anchorX, anchorY };
  }),
});

// -- 3. The script the apps run ----------------------------------------------------------------------------------------

const sandboxContext = () => {
  const context = vm.createContext({}); // no window, no document, no require: what the sandbox has
  vm.runInContext(fs.readFileSync(path.join(ASSETS, "milsymbol.js"), "utf8"), context);
  vm.runInContext(fs.readFileSync(path.join(ASSETS, "ezpz-render.js"), "utf8"), context);
  return context;
};

/** What the sandbox hands back for one symbol: the script's JSON string, exactly as the app receives it. */
const sandboxAnswer = (context, sidc, options) =>
  vm.runInContext(`ezpzRenderSymbol(${JSON.stringify(sidc)}, ${JSON.stringify(JSON.stringify(options))})`, context);

const sandboxDraw = () => {
  const context = sandboxContext();
  return (sidc, options) => JSON.parse(sandboxAnswer(context, sidc, options));
};

const RENDER_CASES = [
  ["SFGPUCI--------", { size: 32 }],
  ["SHGPUCI--------", { size: 32 }],
  ["SNGPUCI--------", { size: 32 }],
  ["SUGPUCI--------", { size: 32 }],
  ["SFGPUCI---D----", { size: 64 }],
  ["SFGPUCIL-H-----", { size: 32 }],
  ["SFGPUCI--------", { size: 32, uniqueDesignation: "A/1-171", higherFormation: "2-101" }],
  ["SFGPUCI--------", { size: 64, uniqueDesignation: "A & B <1>", higherFormation: "\"quoted\"" }],
  ["SFGPUCI--------", { size: 32, uniqueDesignation: "ÅÄÖ é 東京" }],
  ["SFGPUCV-------", { size: 32 }],
  ["SHGPEWRR------", { size: 32 }],
  ["SHGPEWMAI-----", { size: 48 }],
  ["SFAPMFF--------", { size: 32 }],
  ["SFSPCLFF-------", { size: 32 }],
  ["SFGPUH----E----", { size: 32, uniqueDesignation: "TOC" }],
  ["SFGPUCI--------", {}],
  ["XXXXXXXXXXXXXXX", { size: 32 }],
  ["", { size: 32 }],
  ["SFG", { size: 32 }],
];

/** The script's own answers, for the apps to read: what a person's JSON parser must make of the strings the sandbox gives back. */
const scriptFixture = () => {
  const context = sandboxContext();
  return {
    generatedBy: GENERATED_BY,
    answers: RENDER_CASES.map(([sidc, options]) => ({ sidc, options, answer: sandboxAnswer(context, sidc, options) })),
    // The expression the app evaluates in the sandbox for each case that names a size, as the web's test builds it.
    calls: RENDER_CASES.filter(([, options]) => options.size !== undefined)
      .map(([sidc, options]) => ({ sidc, options, expression: `ezpzRenderSymbol(${JSON.stringify(sidc)}, ${JSON.stringify(JSON.stringify(options))})` })),
    // Answers that are not ones the script gives: the apps must read them as "no answer", never as a symbol.
    malformed: ["", "not json", "[]", "{}", "{\"valid\":true}", "{\"valid\":\"yes\"}", "{\"valid\":true,\"svg\":\"<svg/>\",\"width\":0,\"height\":1,\"anchorX\":0,\"anchorY\":0}", "{\"valid\":true,\"svg\":\"\",\"width\":1,\"height\":1,\"anchorX\":0,\"anchorY\":0}"],
  };
};

describe("symbol fixtures", () => {
  it("symbols/sidc.json", () => settle("symbols/sidc.json", sidcFixture()));

  it("symbols/script.json", () => settle("symbols/script.json", scriptFixture()));

  it("symbols/presets.json and the pre-rendered SVGs", () => {
    settle("symbols/presets.json", presetIndex());
    PRESET_SIDCS.forEach((sidc) => settleText(svgName(sidc), `${webSymbol(sidc).svg}\n`));
  });

  // Once regenerated, nothing stale is left beside them (a preset that was dropped has its file deleted by hand).
  (UPDATE ? it.skip : it)("no SVG is left that is not a preset", () => {
    const dir = path.dirname(fixturePath("symbols/svg/x"));
    expect(fs.readdirSync(dir).sort()).toEqual(PRESET_SIDCS.map((s) => `${s}.svg`).sort());
  });

  it("the preset symbols are distinct pictures that all draw", () => {
    const svgs = PRESET_SIDCS.map((sidc) => webSymbol(sidc).svg);
    expect(new Set(svgs).size).toBe(svgs.length);
    expect(PRESET_SIDCS.length).toBe(UNIT_TYPES.length * 4 + THREAT_PRESETS.length);
  });

  it("the vendored milsymbol is the one the web runs", () => {
    const vendored = fs.readFileSync(path.join(ASSETS, "milsymbol.js"));
    const installed = fs.readFileSync(require.resolve("milsymbol/dist/milsymbol.js"));
    expect(vendored.equals(installed)).toBe(true);
  });

  it("the sandbox script draws what the web draws, with no DOM", () => {
    const draw = sandboxDraw();
    expect(typeof window).not.toBe("undefined"); // Jest's own environment has a DOM; the sandbox context built above does not
    RENDER_CASES.forEach(([sidc, options]) => {
      const web = symbolParts(sidc, options);
      const expected = web
        ? { valid: true, svg: web.svg, width: web.size.width, height: web.size.height, anchorX: web.anchor.x, anchorY: web.anchor.y }
        : { valid: false };
      expect(draw(sidc, options)).toEqual(expected);
    });
  });

  it("the script says invalid, not an error, for what cannot be drawn", () => {
    const draw = sandboxDraw();
    expect(draw("XXXXXXXXXXXXXXX", { size: 32 })).toEqual({ valid: false });
    expect(draw("SFGPUCI--------", { size: 32 }).valid).toBe(true);
  });
});
