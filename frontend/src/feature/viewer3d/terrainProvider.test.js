import {
  TERRAIN_MAX_LEVEL,
  TERRAIN_SAMPLES,
  createTerrainProvider,
  fetchHeightmap,
} from "./terrainProvider";

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

  it("treats 204 as 'no DEM here' rather than a failure", async () => {
    // The DEMs cover part of one country; Cesium asks across the whole globe.
    const fetchImpl = jest.fn().mockResolvedValue({ ok: false, status: 204 });
    await expect(fetchHeightmap(13, 100, 100, { fetchImpl })).resolves.toBeNull();
  });

  it("returns null on a server error instead of rejecting", async () => {
    // A rejected promise stalls Cesium's tile queue and retries forever.
    const fetchImpl = jest.fn().mockResolvedValue({ ok: false, status: 500 });
    await expect(fetchHeightmap(13, 1, 1, { fetchImpl })).resolves.toBeNull();
  });

  it("rejects a payload of the wrong size rather than rendering garbage", async () => {
    const fetchImpl = jest.fn().mockResolvedValue(ok(new ArrayBuffer(16)));
    await expect(fetchHeightmap(13, 1, 1, { fetchImpl })).resolves.toBeNull();
  });

  it("does not ask past the level where the source runs out of detail", async () => {
    const fetchImpl = jest.fn();
    await expect(
      fetchHeightmap(TERRAIN_MAX_LEVEL + 1, 1, 1, { fetchImpl }),
    ).resolves.toBeNull();
    expect(fetchImpl).not.toHaveBeenCalled();
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
