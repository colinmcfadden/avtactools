import { beginPriority } from "../auth/requestPriority";
import {
  TERRAIN_MAX_LEVEL,
  TERRAIN_SAMPLES,
  createTerrainProvider,
  fetchHeightmap,
  hasElevation,
} from "./terrainProvider";

const expectFlat = (heights) => {
  // Never null: CustomHeightmapTerrainProvider reads .constructor off the
  // callback's result, so a null throws inside Cesium and fails the tile.
  // A tile that fails at level 0 stops the globe subdividing at all.
  expect(heights).toBeInstanceOf(Int16Array);
  expect(hasElevation(heights)).toBe(false);
  expect(heights.every((h) => h === 0)).toBe(true);
};

const tileBytes = (fill = 100) => {
  const heights = new Int16Array(TERRAIN_SAMPLES * TERRAIN_SAMPLES).fill(fill);
  return heights.buffer;
};

const ok = (buffer) => ({
  ok: true,
  status: 200,
  arrayBuffer: async () => buffer,
});

beforeEach(() => localStorage.clear());

describe("fetchHeightmap", () => {
  it("holds new tiles while heavy server work runs, then fetches them", async () => {
    // An LZ analysis ran three times slower while terrain tiles were computed.
    const fetchImpl = jest.fn().mockResolvedValue(ok(tileBytes(250)));
    const end = beginPriority();
    const pending = fetchHeightmap(13, 4375, 2519, { fetchImpl });
    await Promise.resolve();
    expect(fetchImpl).not.toHaveBeenCalled();
    end();
    expect((await pending)[0]).toBe(250);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it("returns the tile's heights as an Int16Array", async () => {
    const fetchImpl = jest.fn().mockResolvedValue(ok(tileBytes(250)));
    const heights = await fetchHeightmap(13, 4375, 2519, { fetchImpl });
    expect(heights).toBeInstanceOf(Int16Array);
    expect(heights).toHaveLength(TERRAIN_SAMPLES * TERRAIN_SAMPLES);
    expect(heights[0]).toBe(250);
  });

  it("sends the bearer token, since the endpoint is behind auth", async () => {
    localStorage.setItem("auth_token", "tok-123");
    const fetchImpl = jest.fn().mockResolvedValue(ok(tileBytes()));
    await fetchHeightmap(13, 1, 1, { fetchImpl });
    const [, options] = fetchImpl.mock.calls[0];
    expect(options.headers.Authorization).toBe("Bearer tok-123");
  });

  it("falls back to flat on 204, which means 'no DEM here'", async () => {
    // The DEMs cover part of one country; Cesium asks across the whole globe.
    const fetchImpl = jest.fn().mockResolvedValue({ ok: false, status: 204 });
    expectFlat(await fetchHeightmap(13, 100, 100, { fetchImpl }));
  });

  it("falls back to flat on a server error", async () => {
    const fetchImpl = jest.fn().mockResolvedValue({ ok: false, status: 500 });
    expectFlat(await fetchHeightmap(13, 1, 1, { fetchImpl }));
  });

  it("falls back to flat when the network rejects", async () => {
    // A rejected callback fails the tile exactly the way a null does.
    const fetchImpl = jest.fn().mockRejectedValue(new Error("offline"));
    expectFlat(await fetchHeightmap(13, 1, 1, { fetchImpl }));
  });

  it("falls back to flat on a payload of the wrong size", async () => {
    const fetchImpl = jest.fn().mockResolvedValue(ok(new ArrayBuffer(16)));
    expectFlat(await fetchHeightmap(13, 1, 1, { fetchImpl }));
  });

  it("asks for every level Cesium is likely to want", async () => {
    // The bug this guards: the bound was 14, and returning flat above it put
    // the surface at ellipsoid height — some 600 m below real ground — which
    // tore a hole in the globe as soon as the camera zoomed in.
    const fetchImpl = jest.fn().mockResolvedValue(ok(tileBytes(600)));
    for (const level of [14, 16, 18, 20]) {
      const heights = await fetchHeightmap(level, 1, 1, { fetchImpl });
      expect(hasElevation(heights)).toBe(true);
      expect(heights[0]).toBe(600);
    }
    expect(fetchImpl).toHaveBeenCalledTimes(4);
  });

  it("stops only past the sanity bound, which Cesium never reaches", async () => {
    const fetchImpl = jest.fn();
    expectFlat(await fetchHeightmap(TERRAIN_MAX_LEVEL + 1, 1, 1, { fetchImpl }));
    expect(fetchImpl).not.toHaveBeenCalled();
    expect(TERRAIN_MAX_LEVEL).toBeGreaterThanOrEqual(20);
  });

  it("never resolves to null for any failure mode", async () => {
    // The bug this guards: null crashed Cesium at level 0, so the globe
    // could never subdivide and a world-sized imagery tile stretched across
    // the whole view, reading as a flat wash of colour where ground should be.
    const failures = [
      { ok: false, status: 204 },
      { ok: false, status: 401 },
      { ok: false, status: 500 },
      ok(new ArrayBuffer(0)),
    ];
    for (const outcome of failures) {
      const heights = await fetchHeightmap(0, 0, 0, {
        fetchImpl: jest.fn().mockResolvedValue(outcome),
      });
      expect(heights).not.toBeNull();
      expect(heights).toBeInstanceOf(Int16Array);
      expect(heights).toHaveLength(TERRAIN_SAMPLES * TERRAIN_SAMPLES);
    }
  });

  it("reports real elevation as distinct from the flat fallback", async () => {
    const real = await fetchHeightmap(13, 1, 1, {
      fetchImpl: jest.fn().mockResolvedValue(ok(tileBytes(430))),
    });
    expect(hasElevation(real)).toBe(true);
  });

  it("requests the level/x/y path the backend serves", async () => {
    const fetchImpl = jest.fn().mockResolvedValue(ok(tileBytes()));
    await fetchHeightmap(13, 4375, 2519, { fetchImpl });
    expect(fetchImpl.mock.calls[0][0]).toContain("/terrain/heightmap/13/4375/2519");
  });
});

describe("createTerrainProvider", () => {
  it("matches the grid size the backend encodes", () => {
    const CustomHeightmapTerrainProvider = jest.fn(function (options) {
      Object.assign(this, options);
    });
    class GeographicTilingScheme {}
    const provider = createTerrainProvider({
      CustomHeightmapTerrainProvider,
      GeographicTilingScheme,
    });
    expect(provider.width).toBe(TERRAIN_SAMPLES);
    expect(provider.height).toBe(TERRAIN_SAMPLES);
  });

  it("uses the geographic scheme the tile arithmetic assumes", () => {
    // The backend computes bounds as 2x1 root tiles over the full globe; a
    // Web Mercator scheme would shift every tile.
    const CustomHeightmapTerrainProvider = jest.fn(function (options) {
      Object.assign(this, options);
    });
    class GeographicTilingScheme {}
    const provider = createTerrainProvider({
      CustomHeightmapTerrainProvider,
      GeographicTilingScheme,
    });
    expect(provider.tilingScheme).toBeInstanceOf(GeographicTilingScheme);
  });

  it("returns null when Cesium cannot supply the provider", () => {
    // The viewer then falls back to a flat ellipsoid rather than failing open.
    expect(createTerrainProvider({})).toBeNull();
  });
});
