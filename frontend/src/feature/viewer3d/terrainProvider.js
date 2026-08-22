/**
 * Terrain for the 3D view, served from the DEMs on our own server.
 *
 * Cesium's usual terrain format is quantized-mesh, which needs a generation
 * pass over the whole coverage area and somewhere to keep the output. This
 * uses CustomHeightmapTerrainProvider instead: the backend samples the DEMs
 * per tile on request and returns a fixed grid of Int16 metres. For a landing
 * zone a few hundred metres across the grid is already finer than the
 * 1/3 arc-second source, so the adaptive triangulation buys nothing.
 *
 * Heights are ellipsoidal, matching the frame the LiDAR tiles are built in —
 * the backend adds the geoid separation, the same correction and the same
 * -30.35 m at this latitude that the point cloud gets.
 */

import { absoluteTilesetUrl } from "./tilesetResource";

// Must match terrain_tiles.TILE_SAMPLES. Cesium asks for a fixed grid size up
// front and cannot renegotiate per tile.
export const TERRAIN_SAMPLES = 65;

// Matches terrain_tiles.MAX_LEVEL: past this a tile is finer than the source.
export const TERRAIN_MAX_LEVEL = 14;

const HEIGHTMAP_PATH = "/terrain/heightmap";

/**
 * Fetch one tile's heights, or null where there is no DEM coverage.
 *
 * A null is a normal answer, not a failure: the DEMs cover part of one
 * country and Cesium asks for tiles across the whole globe. Returning null
 * lets it render those as flat rather than stalling on retries.
 */
export const fetchHeightmap = async (level, x, y, { fetchImpl = fetch } = {}) => {
  if (level > TERRAIN_MAX_LEVEL) return null;

  const token = localStorage.getItem("auth_token");
  const response = await fetchImpl(
    absoluteTilesetUrl(`${HEIGHTMAP_PATH}/${level}/${x}/${y}`),
    { headers: token ? { Authorization: `Bearer ${token}` } : {} },
  );

  // 204 is the backend saying "no DEM here", which is not an error.
  if (response.status === 204) return null;
  if (!response.ok) return null;

  const buffer = await response.arrayBuffer();
  if (buffer.byteLength !== TERRAIN_SAMPLES * TERRAIN_SAMPLES * 2) return null;
  return new Int16Array(buffer);
};

/**
 * A terrain provider backed by the heightmap endpoint.
 *
 * Returns null when Cesium is unavailable so the viewer can fall back to a
 * flat ellipsoid rather than failing to open at all.
 */
export const createTerrainProvider = (Cesium, { fetchImpl } = {}) => {
  if (!Cesium?.CustomHeightmapTerrainProvider) return null;

  return new Cesium.CustomHeightmapTerrainProvider({
    width: TERRAIN_SAMPLES,
    height: TERRAIN_SAMPLES,
    tilingScheme: new Cesium.GeographicTilingScheme(),
    callback: (x, y, level) => fetchHeightmap(level, x, y, { fetchImpl }),
  });
};
