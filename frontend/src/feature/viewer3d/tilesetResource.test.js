// The module reads VITE_API_URL at import time, and vitest does not load the
// project's .env, so each test loads it fresh with the base it wants to prove.
// An empty base stands in for an unset one: the app code folds both to "".
const load = async (apiUrl) => {
  vi.stubEnv("VITE_API_URL", apiUrl ?? "");
  vi.resetModules();
  return import("./tilesetResource");
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

afterEach(() => {
  vi.unstubAllEnvs();
  localStorage.clear();
});

describe("absoluteTilesetUrl", () => {
  it("resolves an API path against the API base", async () => {
    const { absoluteTilesetUrl } = await load(API_BASE);
    expect(absoluteTilesetUrl("/lidar/tilesets/abc/tileset.json"))
      .toBe(`${API_BASE}/lidar/tilesets/abc/tileset.json`);
  });

  it("does not double the /api prefix the API base already carries", async () => {
    // The whole reason the backend returns an API-relative path.
    const { absoluteTilesetUrl } = await load(API_BASE);
    expect(absoluteTilesetUrl("/lidar/tilesets/abc/tileset.json"))
      .not.toMatch(/\/api\/api\//);
  });

  it("leaves a static path alone", async () => {
    // The demo tileset is served by the frontend; sending it to the API host
    // points it at a server that has never heard of it.
    const { absoluteTilesetUrl } = await load(API_BASE);
    expect(absoluteTilesetUrl("/tiles/lz_demo/tileset.json", { fromApi: false }))
      .toBe("/tiles/lz_demo/tileset.json");
  });

  it("passes an absolute URL through untouched", async () => {
    const { absoluteTilesetUrl } = await load(API_BASE);
    const url = "https://tiles.example.mil/abc/tileset.json";
    expect(absoluteTilesetUrl(url)).toBe(url);
  });

  it("treats a same-origin deployment as a bare path", async () => {
    const { absoluteTilesetUrl } = await load(undefined);
    expect(absoluteTilesetUrl("/lidar/tilesets/abc/tileset.json"))
      .toBe("/lidar/tilesets/abc/tileset.json");
  });
});

describe("tilesetResource", () => {
  it("carries the bearer token so Cesium's own child requests are authorized", async () => {
    localStorage.setItem("auth_token", "tok-123");
    const { tilesetResource } = await load(API_BASE);
    const resource = tilesetResource(Cesium, "/lidar/tilesets/abc/tileset.json");
    expect(resource).toBeInstanceOf(FakeResource);
    expect(resource.headers.Authorization).toBe("Bearer tok-123");
    expect(resource.url).toBe(`${API_BASE}/lidar/tilesets/abc/tileset.json`);
  });

  it("returns a plain static URL for the unauthenticated demo", async () => {
    localStorage.setItem("auth_token", "tok-123");
    const { tilesetResource } = await load(API_BASE);
    expect(tilesetResource(Cesium, "/tiles/lz_demo/tileset.json", { requiresAuth: false }))
      .toBe("/tiles/lz_demo/tileset.json");
  });

  it("falls back to a plain URL when signed out rather than sending 'Bearer null'", async () => {
    const { tilesetResource } = await load(API_BASE);
    expect(tilesetResource(Cesium, "/lidar/tilesets/abc/tileset.json"))
      .toBe(`${API_BASE}/lidar/tilesets/abc/tileset.json`);
  });
});
