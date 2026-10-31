const fs = require("fs");
const { UPDATE, dumps, fixturePath, writeFixture } = require("./fixtureIO");

// How JavaScript writes a number as text (`String(n)`, which is what a template literal does). The AMPS writers build values like
// `raw15.24 m Foot AGL` by putting a computed number into a string, so the native writers have to produce the same digits: the shortest
// that read back as the same number, in plain form between 1e-6 and 1e21 and as `1e+21` / `1e-7` outside it. Java's own Double.toString
// is not that (it writes `1.0E21`, `100.0`, and on older runtimes more digits than needed).

const GENERATED_BY = "frontend/src/contracts/numberTextFixtures.test.js (UPDATE_CONTRACTS=1)";
const FT_TO_M = 0.3048;
const KTS_TO_MPS = 0.514444;

// Numbers as JSON can hold them. Anything else (NaN, the infinities) is a word.
const NUMBERS = [
  0, 1, -1, 10, 100, 1000, 123456789, 0.5, 0.1, 0.25, 1.5, -1.5, 3.14159, 100.25, 0.1 + 0.2, 1 / 3, 2 / 3, 1e-6, 1.5e-6, 1e-7, 1.5e-7, 9.9e-7, 123e-9,
  1e20, 1e21, 1.5e21, 123456789012345680000, 12345678901234567890, 1e22, 1e100, 1.7976931348623157e308, 5e-324, 2.2250738585072014e-308,
  4503599627370496, 9007199254740991, 9007199254740992, 0.000001234, 1234.5678, 33.3333333333333, 15.24, 50 * FT_TO_M, 3000 * FT_TO_M,
  1200 * FT_TO_M, 1500 * FT_TO_M, 1350 * FT_TO_M, 15 * KTS_TO_MPS, 20 * KTS_TO_MPS, 100 * KTS_TO_MPS, 0 * KTS_TO_MPS, 940.0032000000001, 924.7632000000001,
  34.5, -84.2, 34.783817, -84.08219, 84.08219, 0.30479999999999996, 4.35, 8.2, 1.005, 2.675, 1e15, 1e16, 123456789.12345679, -0.000001, -1e-7, -1e21,
];

const numberText = () => ({
  generatedBy: GENERATED_BY,
  numbers: NUMBERS.map((n) => ({ value: n, text: String(n) })),
  words: [NaN, Infinity, -Infinity].map((n) => ({ value: String(n), text: String(n) })),
  // JSON cannot hold -0, so it is a word here: String(-0) is "0".
  negativeZero: String(-0),
});

describe("how JavaScript writes a number", () => {
  it("is what the fixture says (and the committed fixture is current)", () => {
    const text = dumps(numberText());
    const name = "formats/number_text.json";
    const file = fixturePath(name);
    if (UPDATE) {
      writeFixture(name, text);
      return;
    }
    if (!fs.existsSync(file)) throw new Error(`${name} is missing; run with UPDATE_CONTRACTS=1 to create it`);
    expect(JSON.parse(fs.readFileSync(file, "utf8"))).toEqual(JSON.parse(text));
    expect(fs.readFileSync(file, "utf8")).toBe(text);
  });
});
