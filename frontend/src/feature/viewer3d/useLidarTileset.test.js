import { act, renderHook, waitFor } from "@testing-library/react";
import api from "../auth/api";
import { useLidarTileset } from "./useLidarTileset";

jest.mock("../auth/api", () => ({
  __esModule: true,
  default: { get: jest.fn(), post: jest.fn() },
}));

const TARGET = { lat: 34.783817, lon: -84.08219 };
const KEY = "0123456789abcdef";
const DONE = {
  state: "done", key: KEY, url: `/lidar/tilesets/${KEY}/tileset.json`, contextUrl: null,
};

const missing = (canBuild) => ({
  data: { key: KEY, available: false, canBuild,
          target: { ...TARGET, radius_m: 250 } },
});

// Let pending promise callbacks run, then move the poll clock on.
const advance = async (ms) => {
  await act(async () => {
    jest.advanceTimersByTime(ms);
  });
};

beforeEach(() => {
  jest.useFakeTimers();
  api.get.mockReset();
  api.post.mockReset();
});

afterEach(() => {
  jest.useRealTimers();
});

const route = ({ resolve, build }) => {
  api.post.mockImplementation((path) => {
    if (path === "/lidar/resolve") return Promise.resolve(resolve);
    if (path === "/lidar/build") {
      return typeof build === "function" ? build() : Promise.resolve(build);
    }
    return Promise.reject(new Error(`unexpected ${path}`));
  });
};

it("opens straight onto a tileset that already exists", async () => {
  route({ resolve: { data: { ...DONE, available: true } } });
  const { result } = renderHook(() => useLidarTileset(TARGET));
  await waitFor(() => expect(result.current.state).toBe("available"));
  expect(result.current.url).toBe(DONE.url);
  expect(api.post).not.toHaveBeenCalledWith("/lidar/build", expect.anything(),
                                            expect.anything());
});

it("builds a missing tileset and opens it when the build finishes", async () => {
  route({ resolve: missing(true),
          build: { data: { key: KEY, state: "queued", stage: "waiting", position: 0 } } });
  api.get
    .mockResolvedValueOnce({ data: { key: KEY, state: "running",
                                     stage: "processing points", elapsed_s: 20 } })
    .mockResolvedValueOnce({ data: DONE });

  const { result } = renderHook(() => useLidarTileset(TARGET));
  await waitFor(() => expect(result.current.state).toBe("building"));

  await advance(3000);
  await waitFor(() => expect(result.current.stage).toBe("processing points"));
  expect(result.current.elapsedS).toBe(20);

  await advance(3000);
  await waitFor(() => expect(result.current.state).toBe("available"));
  expect(result.current.url).toBe(DONE.url);
});

it("never puts the target's coordinates in a URL", async () => {
  // A GET would leave an LZ's position in request logs and browser history.
  route({ resolve: missing(true), build: { data: { key: KEY, state: "queued" } } });
  api.get.mockResolvedValue({ data: DONE });

  const { result } = renderHook(() => useLidarTileset(TARGET));
  await waitFor(() => expect(result.current.state).toBe("building"));
  await advance(3000);
  await waitFor(() => expect(result.current.state).toBe("available"));

  const urls = [...api.get.mock.calls, ...api.post.mock.calls].map(([url]) => url);
  for (const url of urls) {
    expect(url).not.toContain(String(TARGET.lat));
    expect(url).not.toContain(String(TARGET.lon));
  }
});

it("falls back to the operator panel when the server cannot build", async () => {
  route({ resolve: missing(false) });
  const { result } = renderHook(() => useLidarTileset(TARGET));
  await waitFor(() => expect(result.current.state).toBe("missing"));
  expect(result.current.target).toEqual({ ...TARGET, radius_m: 250 });
  expect(api.post).toHaveBeenCalledTimes(1);   // resolve only, no build
});

it("reports why a build failed", async () => {
  route({ resolve: missing(true), build: { data: { key: KEY, state: "queued" } } });
  api.get.mockResolvedValue({
    data: { key: KEY, state: "failed", error: "no USGS lidar covers this point" },
  });

  const { result } = renderHook(() => useLidarTileset(TARGET));
  await waitFor(() => expect(result.current.state).toBe("building"));
  await advance(3000);
  await waitFor(() => expect(result.current.state).toBe("error"));
  expect(result.current.buildFailed).toBe(true);
  expect(result.current.error).toMatch(/no USGS lidar/);
});

it("asks again when a restarted service has forgotten the build", async () => {
  let builds = 0;
  route({
    resolve: missing(true),
    build: () => {
      builds += 1;
      return Promise.resolve({ data: { key: KEY, state: "queued" } });
    },
  });
  api.get
    .mockRejectedValueOnce({ response: { status: 404 } })
    .mockResolvedValueOnce({ data: DONE });

  const { result } = renderHook(() => useLidarTileset(TARGET));
  await waitFor(() => expect(result.current.state).toBe("building"));
  await advance(3000);
  await waitFor(() => expect(builds).toBe(2));
  await advance(3000);
  await waitFor(() => expect(result.current.state).toBe("available"));
});

it("rides out a brief outage instead of abandoning the build", async () => {
  // A redeploy of the API or the service drops a few polls; the build goes on.
  route({ resolve: missing(true), build: { data: { key: KEY, state: "queued" } } });
  api.get
    .mockRejectedValueOnce({ response: { status: 503 } })
    .mockRejectedValueOnce({ response: { status: 503 } })
    .mockResolvedValueOnce({ data: DONE });

  const { result } = renderHook(() => useLidarTileset(TARGET));
  await waitFor(() => expect(result.current.state).toBe("building"));
  for (let i = 0; i < 3; i += 1) await advance(3000);
  await waitFor(() => expect(result.current.state).toBe("available"));
});

it("stops polling when the window closes", async () => {
  route({ resolve: missing(true), build: { data: { key: KEY, state: "queued" } } });
  api.get.mockResolvedValue({ data: { key: KEY, state: "running" } });

  const { result, unmount } = renderHook(() => useLidarTileset(TARGET));
  await waitFor(() => expect(result.current.state).toBe("building"));
  await advance(3000);
  const polls = api.get.mock.calls.length;
  unmount();
  await advance(30000);
  expect(api.get.mock.calls.length).toBe(polls);
});

it("does nothing until there is a target", () => {
  const { result } = renderHook(() => useLidarTileset({}));
  expect(result.current.state).toBe("idle");
  expect(api.post).not.toHaveBeenCalled();
});
