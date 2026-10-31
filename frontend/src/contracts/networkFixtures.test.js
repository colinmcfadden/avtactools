import { isPriorityRequest, PRIORITY_PATHS } from "../feature/auth/requestPriority";

const fs = require("fs");
const { UPDATE, compact, fixturePath, writeFixture } = require("./fixtureIO");

// Which requests are heavy enough that the server should be left to them: the paths the web holds
// background work (terrain tiles) back for, and the native apps hold sync pulls and prefetching back
// for. Same rule on every client, so the same cases.
//
// (The recorded server responses in network/responses.json are written by the backend's own tests,
// backend/tests/test_network_fixtures.py, not here.)

const URLS = [
  "/analyze-field",
  "/api/analyze-field",
  "https://example.com/api/analyze-field",
  "/api/analyze-field?debug=1",
  "/api/terrain-analysis",
  "/api/threat-mask",
  "/api/export-package",
  "/api/generate-excel",
  "/api/lz",
  "/api/lz/12",
  "/api/sync/changes?since=0",
  "/api/terrain/heightmap/3/4/5",
  "/api/terrain/heights",
  "/api/analyze-field/extra",
  "/api/analyze-fields",
  "/api/not-analyze-field",
  "/api/convert-grid?next=/api/analyze-field",
  "",
];

const expected = () => ({
  description: "Whether each URL is heavy server work (isPriorityRequest in feature/auth/requestPriority.js): "
    + "the end of the path, with any query string left off, is one of the listed paths. A native client holds "
    + "background requests back while a heavy one is in flight.",
  generatedBy: "frontend/src/contracts/networkFixtures.test.js (UPDATE_CONTRACTS=1)",
  heavyPaths: PRIORITY_PATHS,
  cases: URLS.map((url) => ({ url, heavy: isPriorityRequest(url) })),
});

describe("network fixtures (the web's request priority rule)", () => {
  if (UPDATE) {
    it("writes the cases", () => {
      writeFixture("network/priority.json", `${compact(expected())}\n`);
    });
    return;
  }

  it("the committed cases still say what the web decides", () => {
    const recorded = JSON.parse(fs.readFileSync(fixturePath("network/priority.json"), "utf8"));
    expect(recorded).toEqual(JSON.parse(JSON.stringify(expected())));
  });

  it("covers both answers, a query string, a whole URL and near misses", () => {
    const { cases } = JSON.parse(fs.readFileSync(fixturePath("network/priority.json"), "utf8"));
    expect(cases.filter((c) => c.heavy).length).toBeGreaterThan(5);
    expect(cases.filter((c) => !c.heavy).length).toBeGreaterThan(8);
    expect(cases.find((c) => c.url === "/api/analyze-field?debug=1").heavy).toBe(true);
    expect(cases.find((c) => c.url === "/api/analyze-fields").heavy).toBe(false);          // the path must end with it
    expect(cases.find((c) => c.url === "/api/not-analyze-field").heavy).toBe(false);       // ... as a whole segment
  });
});
