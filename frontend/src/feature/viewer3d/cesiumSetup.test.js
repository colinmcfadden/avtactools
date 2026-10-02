import { CESIUM_BASE_URL, CESIUM_SCRIPT, loadCesium, resetCesiumForTests } from "./cesiumSetup";

// Cesium is loaded from its prebuilt bundle, not through webpack: bundling it
// left `import.meta` in a non-module script, a SyntaxError that broke every 3D
// view in production while the development server worked.

const lastScript = () => [...document.head.querySelectorAll("script")].pop();

beforeEach(() => {
  resetCesiumForTests();
  delete window.Cesium;
  delete window.CESIUM_BASE_URL;
  document.head.innerHTML = "";
});

it("loads Cesium's prebuilt bundle, not a webpack chunk", async () => {
  const loading = loadCesium();
  expect(lastScript().getAttribute("src")).toBe(CESIUM_SCRIPT);
  expect(CESIUM_SCRIPT).toMatch(/^\/cesium\/Cesium\.js\?v=\d+\.\d+/);
  window.Cesium = { Ion: { defaultAccessToken: "default" } };
  lastScript().onload();
  const cesium = await loading;
  expect(cesium).toBe(window.Cesium);
  expect(cesium.Ion.defaultAccessToken).toBeUndefined();
});

it("tells Cesium where its workers and assets are before it runs", () => {
  loadCesium();
  expect(window.CESIUM_BASE_URL).toBe(CESIUM_BASE_URL);
});

it("loads it only once", async () => {
  const first = loadCesium();
  const second = loadCesium();
  expect(document.head.querySelectorAll("script")).toHaveLength(1);
  window.Cesium = { Ion: {} };
  lastScript().onload();
  expect(await first).toBe(await second);
});

it("tries again after a failed load rather than failing for good", async () => {
  const failed = loadCesium();
  lastScript().onerror();
  await expect(failed).rejects.toThrow(/Could not load/);

  const retry = loadCesium();
  expect(document.head.querySelectorAll("script")).toHaveLength(2);
  window.Cesium = { Ion: {} };
  lastScript().onload();
  await expect(retry).resolves.toBe(window.Cesium);
});

it("says so if the script runs but defines nothing", async () => {
  const loading = loadCesium();
  lastScript().onload();
  await expect(loading).rejects.toThrow(/did not define window.Cesium/);
});
