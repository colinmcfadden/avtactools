/**
 * Loading Cesium once, on demand.
 *
 * Cesium is loaded from its own prebuilt browser bundle (public/cesium,
 * copied there by scripts/copy-cesium.js along with its workers and assets),
 * not through webpack. Bundling it broke production: Cesium's source reads
 * `import.meta`, which CRA's webpack leaves in place, and in a non-module
 * script that is a SyntaxError — the chunk never registered and every 3D view
 * failed with "Loading chunk ... failed". The development server happened not
 * to hit it, so nothing caught it before release.
 *
 * Loading on demand also keeps several MB out of the main bundle for everyone
 * who never opens a 3D view. CESIUM_BASE_URL has to be on `window` before the
 * script runs, so Cesium can find its workers, shaders and widget assets.
 */

// Default import: webpack refuses named imports from JSON in production.
import cesiumPackage from "cesium/package.json";

export const CESIUM_BASE_URL = "/cesium";

// Versioned so a Cesium upgrade is never served from a stale browser cache.
export const CESIUM_SCRIPT = `${CESIUM_BASE_URL}/Cesium.js?v=${cesiumPackage.version}`;

let pending = null;

const injectScript = (src) => new Promise((resolve, reject) => {
  const script = document.createElement("script");
  script.src = src;
  script.async = true;
  script.onload = () => resolve();
  script.onerror = () => reject(new Error(`Could not load ${src}`));
  document.head.appendChild(script);
});

/** Resolves to the Cesium namespace, loading it on first use. */
export const loadCesium = () => {
  if (!pending) {
    window.CESIUM_BASE_URL = CESIUM_BASE_URL;
    pending = (window.Cesium ? Promise.resolve() : injectScript(CESIUM_SCRIPT))
      .then(() => {
        const cesium = window.Cesium;
        if (!cesium) throw new Error("Cesium loaded but did not define window.Cesium");
        // Cesium ion is Cesium's hosted asset service. Every tileset here is
        // self-hosted, so no token is set — leaving the default in place makes
        // Cesium emit credential warnings for assets we never request.
        cesium.Ion.defaultAccessToken = undefined;
        return cesium;
      })
      .catch((error) => {
        // A failed load (a network blip, a deploy mid-switch) must not stick:
        // the next 3D view tries again.
        pending = null;
        throw error;
      });
  }
  return pending;
};

/** For tests: forget a previous load. */
export const resetCesiumForTests = () => {
  pending = null;
};
