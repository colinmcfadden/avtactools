import L from "leaflet";
import {
  GEOLOCATION_OPTIONS,
  LOCATED_ZOOM,
  START_LOCATION_STORAGE_KEY,
  attachStartAtLocation,
  resetStartAtLocation,
} from "./startLocation";

// The map's default view (MapView.jsx), which every case that does not use a position must leave exactly as it was.
const DEFAULT_CENTER = [34.0522, -118.2437];
const DEFAULT_ZOOM = 11;
const HERE = { latitude: 34.78, longitude: -84.08 };

// A browser's geolocation the test answers by hand, when it likes.
const fakeGeolocation = () => {
  const asks = [];
  return {
    asks,
    getCurrentPosition: vi.fn((success, failure, options) => asks.push({ success, failure, options })),
    allow: (coords = HERE) => asks.at(-1).success({ coords: { accuracy: 30, ...coords } }),
    fail: (code) => asks.at(-1).failure({ code, message: "" }),
  };
};
const permissionsSaying = (state) => ({ query: vi.fn(async () => ({ state })) });
const flush = async () => {
  for (let i = 0; i < 5; i += 1) await Promise.resolve();
};

let container;
let map;
let geolocation;
let storage;
const env = (more = {}) => ({ geolocation, permissions: undefined, storage, ...more });
const view = () => ({ center: [map.getCenter().lat, map.getCenter().lng], zoom: map.getZoom() });
const expectDefaultView = () => {
  expect(view().center[0]).toBeCloseTo(DEFAULT_CENTER[0], 9);
  expect(view().center[1]).toBeCloseTo(DEFAULT_CENTER[1], 9);
  expect(view().zoom).toBe(DEFAULT_ZOOM);
};

beforeEach(() => {
  resetStartAtLocation();
  container = document.createElement("div");
  Object.defineProperty(container, "clientWidth", { configurable: true, value: 1200 });
  Object.defineProperty(container, "clientHeight", { configurable: true, value: 800 });
  document.body.appendChild(container);
  map = L.map(container, { zoomAnimation: false, fadeAnimation: false }).setView(DEFAULT_CENTER, DEFAULT_ZOOM);
  geolocation = fakeGeolocation();
  window.localStorage.clear();
  storage = window.localStorage;
});
afterEach(() => {
  map.remove();
  container.remove();
  window.localStorage.clear();
  vi.restoreAllMocks();
});

describe("the map opens where the person is", () => {
  it("goes to the position when the browser allows it", async () => {
    attachStartAtLocation(map, { env: env() });
    await flush();
    expect(geolocation.getCurrentPosition).toHaveBeenCalledTimes(1);
    expect(geolocation.asks[0].options).toEqual(GEOLOCATION_OPTIONS);
    geolocation.allow();
    await flush();
    expect(view().center[0]).toBeCloseTo(HERE.latitude, 9);
    expect(view().center[1]).toBeCloseTo(HERE.longitude, 9);
    expect(view().zoom).toBe(LOCATED_ZOOM);
  });

  it("keeps the default view when the person refuses, and does not ask again in this browser", async () => {
    attachStartAtLocation(map, { env: env() });
    await flush();
    geolocation.fail(1); // PERMISSION_DENIED
    await flush();
    expectDefaultView();
    expect(storage.getItem(START_LOCATION_STORAGE_KEY)).toBe("refused");

    resetStartAtLocation(); // the next page load
    attachStartAtLocation(map, { env: env() });
    await flush();
    expect(geolocation.getCurrentPosition).toHaveBeenCalledTimes(1);
    expectDefaultView();
  });

  it("asks again after a refusal once the browser says it is granted (changed in site settings)", async () => {
    storage.setItem(START_LOCATION_STORAGE_KEY, "refused");
    attachStartAtLocation(map, { env: env({ permissions: permissionsSaying("granted") }) });
    await flush();
    geolocation.allow();
    await flush();
    expect(view().zoom).toBe(LOCATED_ZOOM);
    expect(storage.getItem(START_LOCATION_STORAGE_KEY)).toBeNull();
  });

  it("does not ask where the browser already says it is denied", async () => {
    attachStartAtLocation(map, { env: env({ permissions: permissionsSaying("denied") }) });
    await flush();
    expect(geolocation.getCurrentPosition).not.toHaveBeenCalled();
    expectDefaultView();
  });

  it("asks where the Permissions API is missing or throws", async () => {
    const broken = { query: vi.fn(async () => { throw new TypeError("geolocation is not a permission name"); }) };
    attachStartAtLocation(map, { env: env({ permissions: broken }) });
    await flush();
    expect(geolocation.getCurrentPosition).toHaveBeenCalledTimes(1);
  });

  it("keeps the default view when the request times out, and remembers nothing", async () => {
    attachStartAtLocation(map, { env: env() });
    await flush();
    geolocation.fail(3); // TIMEOUT
    await flush();
    expectDefaultView();
    expect(storage.getItem(START_LOCATION_STORAGE_KEY)).toBeNull();
  });

  it("keeps the default view when the position is unavailable", async () => {
    attachStartAtLocation(map, { env: env() });
    await flush();
    geolocation.fail(2); // POSITION_UNAVAILABLE
    await flush();
    expectDefaultView();
  });

  it("keeps the default view where the browser has no geolocation", async () => {
    attachStartAtLocation(map, { env: env({ geolocation: undefined }) });
    await flush();
    expectDefaultView();
  });

  it("keeps the default view when getCurrentPosition throws", async () => {
    geolocation.getCurrentPosition.mockImplementation(() => {
      throw new Error("blocked by policy");
    });
    attachStartAtLocation(map, { env: env() });
    await flush();
    expectDefaultView();
  });

  it("ignores a position that is not a place", async () => {
    attachStartAtLocation(map, { env: env() });
    await flush();
    geolocation.allow({ latitude: NaN, longitude: 10 });
    await flush();
    expectDefaultView();
  });

  it("never moves a view the person set while the answer was on its way (a pan)", async () => {
    attachStartAtLocation(map, { env: env() });
    await flush();
    map.panBy([200, 0], { animate: false });
    const panned = view();
    geolocation.allow();
    await flush();
    expect(view()).toEqual(panned);
  });

  it("never moves a view the app set while the answer was on its way (an LZ/PZ, a grid link, a search)", async () => {
    attachStartAtLocation(map, { env: env() });
    await flush();
    map.setView([35.1, -83.9], 17); // what MapView's MapUpdater does for a target or a #grid= link
    geolocation.allow();
    await flush();
    expect(view().center[0]).toBeCloseTo(35.1, 9);
    expect(view().zoom).toBe(17);
  });

  it("never moves the view once the app claims it, even if the map did not move", async () => {
    let claimed = false;
    attachStartAtLocation(map, { env: env(), claimed: () => claimed });
    await flush();
    claimed = true; // a pack opened with its LZ/PZ on its way
    geolocation.allow();
    await flush();
    expectDefaultView();
  });

  it("does not ask at all when the app has a view of its own at load", async () => {
    attachStartAtLocation(map, { env: env(), claimed: () => true });
    await flush();
    expect(geolocation.getCurrentPosition).not.toHaveBeenCalled();
  });

  it("takes a resize of the map's box for no view chosen", async () => {
    attachStartAtLocation(map, { env: env() });
    await flush();
    Object.defineProperty(container, "clientWidth", { configurable: true, value: 900 });
    map.invalidateSize({ pan: false }); // the sidebar folding (SizeWatcher)
    geolocation.allow();
    await flush();
    expect(view().zoom).toBe(LOCATED_ZOOM);
  });

  it("asks once a page, and a map made again (React's double mount) takes the answer", async () => {
    const detach = attachStartAtLocation(map, { env: env() });
    detach();
    attachStartAtLocation(map, { env: env() });
    await flush();
    expect(geolocation.getCurrentPosition).toHaveBeenCalledTimes(1);
    geolocation.allow();
    await flush();
    expect(view().zoom).toBe(LOCATED_ZOOM);

    attachStartAtLocation(map, { env: env() });
    await flush();
    expect(geolocation.getCurrentPosition).toHaveBeenCalledTimes(1);
  });

  it("keeps an answer that came while no map was there for the map made next", async () => {
    const detach = attachStartAtLocation(map, { env: env() });
    await flush();
    detach();
    geolocation.allow();
    await flush();
    expectDefaultView();
    attachStartAtLocation(map, { env: env() });
    await flush();
    expect(view().zoom).toBe(LOCATED_ZOOM);
    expect(geolocation.getCurrentPosition).toHaveBeenCalledTimes(1);
  });

  it("does nothing to a map it has let go of", async () => {
    const detach = attachStartAtLocation(map, { env: env() });
    await flush();
    detach();
    geolocation.allow();
    await flush();
    expectDefaultView();
  });

  it("works when storage cannot be reached", async () => {
    const throwing = {
      getItem: () => { throw new Error("SecurityError"); },
      setItem: () => { throw new Error("SecurityError"); },
      removeItem: () => { throw new Error("SecurityError"); },
    };
    attachStartAtLocation(map, { env: env({ storage: throwing }) });
    await flush();
    geolocation.allow();
    await flush();
    expect(view().zoom).toBe(LOCATED_ZOOM);
  });

  it("keeps the position to the map alone: nothing about it is stored", async () => {
    attachStartAtLocation(map, { env: env() });
    await flush();
    geolocation.allow();
    await flush();
    const everything = Object.keys(storage).map((key) => storage.getItem(key)).join(" ");
    expect(everything).not.toContain(String(HERE.latitude));
    expect(everything).not.toContain(String(HERE.longitude));
    expect(window.location.href).not.toContain(String(HERE.latitude));
  });
});
