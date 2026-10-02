/**
 * Loading Cesium once, on demand.
 *
 * Two reasons this is not a plain top-level import. Cesium resolves its
 * workers, shaders and widget assets at runtime from `CESIUM_BASE_URL`, which
 * has to be set on `window` *before* the module initialises — so the import is
 * deferred until after that assignment. And the bundle is large enough
 * (several MB) that pulling it into the main chunk would slow the 2D map for
 * everyone who never opens a 3D view.
 *
 * The assets themselves are copied into public/cesium by scripts/copy-cesium.js.
 */

export const CESIUM_BASE_URL = "/cesium";

let pending = null;

/** Resolves to the Cesium module, loading it on first use. */
export const loadCesium = () => {
  if (!pending) {
    window.CESIUM_BASE_URL = CESIUM_BASE_URL;
    pending = import("cesium").then((cesium) => {
      // Cesium ion is Cesium's hosted asset service. Every tileset here is
      // self-hosted, so no token is set — leaving the default in place makes
      // Cesium emit credential warnings for assets we never request.
      cesium.Ion.defaultAccessToken = undefined;
      return cesium;
    });
  }
  return pending;
};
