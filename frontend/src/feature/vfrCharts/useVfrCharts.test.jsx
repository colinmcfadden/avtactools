import { act, renderHook } from "@testing-library/react";
import useVfrCharts, { AFTER_A_FAILED_TILE_MS, LOOK_EVERY_MS, NO_CHARTS, PAST_EXPIRY_MS } from "./useVfrCharts";

const START = Date.parse("2026-11-05T12:00:00Z");
const MINUTE = 60 * 1000;

const configOf = (build, expires = "2026-12-24T09:01Z") => ({
  charts: {
    vfr: {
      build,
      tiles: `/charts/vfr/${build}/{z}/{x}/{y}.webp`,
      coverage: `/charts/vfr/${build}/coverage.json`,
      tileSize: 512,
      minZoom: 5,
      maxZoom: 14,
      effective: "2026-10-29T09:01Z",
      expires,
      bounds: [-125, 24, -66, 50],
      fallback: [],
    },
  },
});

let server;
const asks = (part) => fetch.mock.calls.filter(([url]) => url.includes(part));
const configAsks = () => asks("/config").length;
const tick = (ms) => act(() => vi.advanceTimersByTimeAsync(ms));

beforeEach(() => {
  vi.useFakeTimers({ now: START, toFake: ["setTimeout", "clearTimeout", "Date"] });
  server = { config: configOf("A.1"), coverageFails: false, configFails: false };
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url) => {
      if (url.includes("coverage.json")) {
        if (server.coverageFails) throw new TypeError("no network");
        return { ok: true, json: async () => ({ minLevel: 5, charts: [], from: url }) };
      }
      if (server.configFails) throw new TypeError("no network");
      return { ok: true, json: async () => server.config };
    }),
  );
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

const opened = async (shown = true) => {
  const hook = renderHook(({ on }) => useVfrCharts(on), { initialProps: { on: shown } });
  await tick(0);
  return hook;
};

describe("the first look", () => {
  it("knows nothing until the server answers, so nothing is drawn from ArcGIS meanwhile", async () => {
    const hook = renderHook(() => useVfrCharts(true));
    expect(hook.result.current.known).toBe(false);
    await tick(0);
    expect(hook.result.current.known).toBe(true);
    expect(hook.result.current.live.build).toBe("A.1");
  });

  it("is past the browser's cache, which would hand back the config of a minute ago", async () => {
    await opened();
    expect(asks("/config")[0][1].cache).toBe("no-store");
  });

  it("with no answer leaves ArcGIS to draw the map, and the next look can still bring the charts", async () => {
    server.configFails = true;
    const { result } = await opened();
    expect(result.current).toMatchObject({ known: true, live: null });
    server.configFails = false;
    await tick(LOOK_EVERY_MS);
    expect(result.current.live.build).toBe("A.1");
  });

  it("gives up on a server that says nothing", async () => {
    fetch.mockImplementation((url, init) => new Promise((_, reject) => init.signal.addEventListener("abort", () => reject(new Error("aborted")))));
    const { result } = await opened();
    expect(result.current.known).toBe(false);
    await tick(8000);
    expect(result.current.known).toBe(true);
  });

  it("reads a server with no charts as no build", async () => {
    server.config = { apiVersion: "1.0.0" };
    const { result } = await opened();
    expect(result.current).toMatchObject({ known: true, live: null, coverage: null });
    expect(asks("coverage.json")).toHaveLength(0);
  });
});

describe("while the VFR map is shown", () => {
  it("a switch reaches the page within five minutes, and its coverage with it", async () => {
    const { result } = await opened();
    server.config = configOf("B.1");
    await tick(LOOK_EVERY_MS - 1);
    expect(result.current.live.build).toBe("A.1");
    await tick(1);
    expect(result.current.live.build).toBe("B.1");
    expect(result.current.live.tiles).toContain("/charts/vfr/B.1/{z}/{x}/{y}.webp");
    expect(result.current.coverage.from).toContain("/charts/vfr/B.1/coverage.json");
  });

  it("does not hand out the old build's coverage with the new build", async () => {
    const { result } = await opened();
    expect(result.current.coverage.from).toContain("A.1");
    server.coverageFails = true;
    server.config = configOf("B.1");
    await tick(LOOK_EVERY_MS);
    expect(result.current.live.build).toBe("B.1");
    expect(result.current.coverage).toBeNull();
    server.coverageFails = false;
    await tick(LOOK_EVERY_MS);
    expect(result.current.coverage.from).toContain("B.1");
  });

  it("the same build changes nothing, and its coverage is not asked for again", async () => {
    const { result } = await opened();
    const { current } = result;
    await tick(LOOK_EVERY_MS * 3);
    expect(configAsks()).toBe(4);
    expect(result.current).toBe(current);
    expect(asks("coverage.json")).toHaveLength(1);
  });

  it("a build taken away puts ArcGIS back", async () => {
    const { result } = await opened();
    server.config = {};
    await tick(LOOK_EVERY_MS);
    expect(result.current).toMatchObject({ known: true, live: null, coverage: null });
  });

  it("a look with no answer leaves the charts as they are", async () => {
    const { result } = await opened();
    const { current } = result;
    server.configFails = true;
    await tick(LOOK_EVERY_MS);
    expect(configAsks()).toBe(2);
    expect(result.current).toBe(current);
  });

  it("looks again just after the build runs out, not five minutes after", async () => {
    server.config = configOf("A.1", new Date(START + 2 * MINUTE).toISOString());
    const { result } = await opened();
    server.config = configOf("B.1");
    await tick(2 * MINUTE + PAST_EXPIRY_MS - 1);
    expect(result.current.live.build).toBe("A.1");
    await tick(1);
    expect(result.current.live.build).toBe("B.1");
  });

  it("a build past its expiry is looked for every five minutes, not without end", async () => {
    server.config = configOf("A.1", new Date(START - 10 * MINUTE).toISOString());
    await opened();
    await tick(LOOK_EVERY_MS - 1);
    expect(configAsks()).toBe(1);
    await tick(1);
    expect(configAsks()).toBe(2);
  });

  it("a build out past the longest wait a timer can hold still gets its five-minute looks", async () => {
    server.config = configOf("A.1", "2030-01-01T09:01Z");
    await opened();
    await tick(LOOK_EVERY_MS);
    expect(configAsks()).toBe(2);
  });

  it("does not ask while the tab is hidden, and asks when it comes back after a while", async () => {
    let state = "visible";
    vi.spyOn(document, "visibilityState", "get").mockImplementation(() => state);
    await opened();
    state = "hidden";
    await tick(LOOK_EVERY_MS * 3);
    expect(configAsks()).toBe(1);
    state = "visible";
    document.dispatchEvent(new Event("visibilitychange"));
    await tick(0);
    expect(configAsks()).toBe(2);
    vi.restoreAllMocks();
  });

  it("does not ask again on coming back within the five minutes", async () => {
    let state = "visible";
    vi.spyOn(document, "visibilityState", "get").mockImplementation(() => state);
    await opened();
    await tick(MINUTE);
    document.dispatchEvent(new Event("visibilitychange"));
    await tick(0);
    expect(configAsks()).toBe(1);
    state = "hidden";
    vi.restoreAllMocks();
  });

  it("stops with the page", async () => {
    const { unmount } = await opened();
    unmount();
    await tick(LOOK_EVERY_MS * 2);
    expect(configAsks()).toBe(1);
  });
});

describe("after a chart tile fails", () => {
  it("asks at once, once it has been a minute, and no more than once a minute", async () => {
    const { result } = await opened();
    await tick(AFTER_A_FAILED_TILE_MS - 1);
    act(() => result.current.tileFailed());
    expect(configAsks()).toBe(1);
    await tick(1);
    act(() => result.current.tileFailed());
    await tick(0);
    expect(configAsks()).toBe(2);
    act(() => result.current.tileFailed());
    await tick(AFTER_A_FAILED_TILE_MS - 1);
    act(() => result.current.tileFailed());
    await tick(0);
    expect(configAsks()).toBe(2);
  });

  it("moves a page that was refused a deleted build on to the new one", async () => {
    const { result } = await opened();
    server.config = configOf("B.1");
    await tick(AFTER_A_FAILED_TILE_MS);
    act(() => result.current.tileFailed());
    await tick(0);
    expect(result.current.live.build).toBe("B.1");
  });

  it("asks once however many tiles fail together", async () => {
    const { result } = await opened();
    await tick(AFTER_A_FAILED_TILE_MS);
    act(() => {
      for (let i = 0; i < 30; i += 1) result.current.tileFailed();
    });
    await tick(0);
    expect(configAsks()).toBe(2);
  });
});

describe("while another map is shown", () => {
  it("asks once, for the switcher's thumbnail, and fetches no coverage", async () => {
    const { result } = await opened(false);
    await tick(LOOK_EVERY_MS * 3);
    expect(configAsks()).toBe(1);
    expect(asks("coverage.json")).toHaveLength(0);
    expect(result.current.live.build).toBe("A.1");
    expect(result.current.coverage).toBeNull();
  });

  it("on coming to the VFR map fetches the coverage, and asks again if the last look is old", async () => {
    const hook = await opened(false);
    await tick(MINUTE);
    hook.rerender({ on: true });
    await tick(0);
    expect(configAsks()).toBe(2);        // the coverage comes with a look
    expect(hook.result.current.coverage.from).toContain("A.1");
    hook.rerender({ on: false });
    await tick(LOOK_EVERY_MS * 2);
    expect(configAsks()).toBe(2);
    server.config = configOf("B.1");
    hook.rerender({ on: true });
    await tick(0);
    expect(hook.result.current.live.build).toBe("B.1");
  });
});

describe("a screen given no charts", () => {
  it("has ArcGIS draw the map and asks nobody", () => {
    expect(NO_CHARTS).toMatchObject({ known: true, live: null, coverage: null });
    expect(() => NO_CHARTS.tileFailed()).not.toThrow();
  });
});
