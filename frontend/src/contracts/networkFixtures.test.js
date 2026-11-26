import { isPriorityRequest, PRIORITY_PATHS, UPLOADS } from "../feature/auth/requestPriority";

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

// An upload is heavy by its method too (docs/DOCUMENTS.md §5): the same paths read with a GET are not.
const METHOD_CASES = [
  { url: "/api/documents", method: "POST" },
  { url: "/api/documents", method: "GET" },
  { url: "/api/documents/12/version", method: "POST" },
  { url: "/api/documents/12/pdf", method: "GET" },
  { url: "/api/documents/12", method: "PUT" },
  { url: "/api/packs/a4f7e2c9/documents", method: "POST" },
  { url: "/api/packs/a4f7e2c9/items/doc-1/version", method: "POST" },
  { url: "/api/packs/a4f7e2c9/items/doc-1/pdf", method: "GET" },
  { url: "https://example.com/api/documents?x=1", method: "post" },
  { url: "/api/documents/12/version/extra", method: "POST" },
  { url: "/api/lz", method: "POST" },
];

const expected = () => ({
  description: "Whether each URL is heavy server work (isPriorityRequest in feature/auth/requestPriority.js): "
    + "the end of the path, with any query string left off, is one of the listed paths, or the request is a POST "
    + "whose path matches one of the uploads (a case with no method is a GET). A native client holds "
    + "background requests back while a heavy one is in flight.",
  generatedBy: "frontend/src/contracts/networkFixtures.test.js (UPDATE_CONTRACTS=1)",
  heavyPaths: PRIORITY_PATHS,
  uploads: UPLOADS.map((upload) => upload.source),
  cases: [
    ...URLS.map((url) => ({ url, heavy: isPriorityRequest(url) })),
    ...METHOD_CASES.map(({ url, method }) => ({ url, method, heavy: isPriorityRequest(url, method) })),
  ],
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
    expect(cases.find((c) => c.url === "/api/documents" && c.method === "POST").heavy).toBe(true);
    expect(cases.find((c) => c.url === "/api/documents" && c.method === "GET").heavy).toBe(false); // listing is not an upload
  });
});
