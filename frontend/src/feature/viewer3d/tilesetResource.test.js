// The module reads REACT_APP_API_URL at import time, and jest does not load the
// project's .env, so each test loads it fresh with the base it wants to prove.
const load = (apiUrl) => {
  let mod;
  jest.isolateModules(() => {
    // Deleted rather than assigned: Node stringifies an assigned undefined,
    // whereas CRA's build-time substitution yields a real undefined.
    if (apiUrl === undefined) delete process.env.REACT_APP_API_URL;
    else process.env.REACT_APP_API_URL = apiUrl;
    mod = require("./tilesetResource");
  });
  return mod;
};

// A stand-in for Cesium.Resource — the real one lives in a 1.4 MB deferred
// chunk, and this only needs to observe what would be constructed.
class FakeResource {
  constructor(options) {
    Object.assign(this, options);
  }
}
const Cesium = { Resource: FakeResource };

// Matches the real deployment: the API base already carries the /api prefix.
const API_BASE = "http://127.0.0.1:5000/api";

const originalApiUrl = process.env.REACT_APP_API_URL;
afterEach(() => {
  process.env.REACT_APP_API_URL = originalApiUrl;
  localStorage.clear();
});

describe("absoluteTilesetUrl", () => {
  it("resolves an API path against the API base", () => {
    const { absoluteTilesetUrl } = load(API_BASE);
    expect(absoluteTilesetUrl("/lidar/tilesets/abc/tileset.json"))
      .toBe(`${API_BASE}/lidar/tilesets/abc/tileset.json`);
  });

  it("does not double the /api prefix the API base already carries", () => {
    // The whole reason the backend returns an API-relative path.
    const { absoluteTilesetUrl } = load(API_BASE);
    expect(absoluteTilesetUrl("/lidar/tilesets/abc/tileset.json"))
      .not.toMatch(/\/api\/api\//);
  });

  it("leaves a static path alone", () => {
    // The demo tileset is served by the frontend; sending it to the API host
    // points it at a server that has never heard of it.
    const { absoluteTilesetUrl } = load(API_BASE);
    expect(absoluteTilesetUrl("/tiles/lz_demo/tileset.json", { fromApi: false }))
      .toBe("/tiles/lz_demo/tileset.json");
  });

  it("passes an absolute URL through untouched", () => {
    const { absoluteTilesetUrl } = load(API_BASE);
    const url = "https://tiles.example.mil/abc/tileset.json";
    expect(absoluteTilesetUrl(url)).toBe(url);
  });

  it("treats a same-origin deployment as a bare path", () => {
    const { absoluteTilesetUrl } = load(undefined);
    expect(absoluteTilesetUrl("/lidar/tilesets/abc/tileset.json"))
      .toBe("/lidar/tilesets/abc/tileset.json");
  });
});

describe("tilesetResource", () => {
  it("carries the bearer token so Cesium's own child requests are authorized", () => {
    localStorage.setItem("auth_token", "tok-123");
    const { tilesetResource } = load(API_BASE);
    const resource = tilesetResource(Cesium, "/lidar/tilesets/abc/tileset.json");
    expect(resource).toBeInstanceOf(FakeResource);
    expect(resource.headers.Authorization).toBe("Bearer tok-123");
    expect(resource.url).toBe(`${API_BASE}/lidar/tilesets/abc/tileset.json`);
  });

  it("returns a plain static URL for the unauthenticated demo", () => {
    localStorage.setItem("auth_token", "tok-123");
    const { tilesetResource } = load(API_BASE);
    expect(tilesetResource(Cesium, "/tiles/lz_demo/tileset.json", { requiresAuth: false }))
      .toBe("/tiles/lz_demo/tileset.json");
  });

  it("falls back to a plain URL when signed out rather than sending 'Bearer null'", () => {
    const { tilesetResource } = load(API_BASE);
    expect(tilesetResource(Cesium, "/lidar/tilesets/abc/tileset.json"))
      .toBe(`${API_BASE}/lidar/tilesets/abc/tileset.json`);
  });
});
