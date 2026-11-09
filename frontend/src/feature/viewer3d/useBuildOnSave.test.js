import { act, renderHook } from "@testing-library/react";
import api from "../auth/api";
import { useBuildOnSave } from "./useBuildOnSave";

vi.mock("../auth/api", () => ({
  __esModule: true,
  default: { post: vi.fn() },
}));

const TARGET = { lat: 34.783817, lon: -84.08219 };
const lz = (overrides) => ({ id: "a", name: "LZ/PZ 1", target: TARGET, savedId: null, ...overrides });

// Nothing built or streamed there, unless a test says otherwise.
const NOT_THERE = { data: { key: "0123456789abcdef", available: false, canBuild: true } };

// CRA resets mock implementations before every test.
beforeEach(() => api.post.mockImplementation((path) =>
  Promise.resolve(path === "/lidar/resolve" ? NOT_THERE : {})));

const settle = () => act(async () => { await Promise.resolve(); await Promise.resolve(); });

it("asks for a kept build when an LZ is saved", async () => {
  const { rerender } = renderHook(({ diagrams }) => useBuildOnSave(diagrams),
                                  { initialProps: { diagrams: [lz()] } });
  expect(api.post).not.toHaveBeenCalled();

  rerender({ diagrams: [lz({ savedId: 42 })] });
  await settle();
  expect(api.post).toHaveBeenCalledWith("/lidar/resolve", { ...TARGET, stream: true });
  expect(api.post).toHaveBeenCalledWith("/lidar/build", { ...TARGET, keep: true });
});

it("builds nothing where the server streams the place", async () => {
  // A streamed first view takes seconds; a build would only spend minutes.
  api.post.mockImplementation((path) => Promise.resolve(path === "/lidar/resolve"
    ? { data: { key: "0123456789abcdef", available: true, streamed: true } } : {}));
  const { rerender } = renderHook(({ diagrams }) => useBuildOnSave(diagrams),
                                  { initialProps: { diagrams: [lz()] } });
  rerender({ diagrams: [lz({ savedId: 42 })] });
  await settle();
  expect(api.post).not.toHaveBeenCalledWith("/lidar/build", expect.anything());
});

it("asks only once, not on every later update", async () => {
  const { rerender } = renderHook(({ diagrams }) => useBuildOnSave(diagrams),
                                  { initialProps: { diagrams: [lz()] } });
  rerender({ diagrams: [lz({ savedId: 42 })] });
  rerender({ diagrams: [lz({ savedId: 42, name: "renamed" })] });
  await settle();
  expect(api.post.mock.calls.filter(([path]) => path === "/lidar/build")).toHaveLength(1);
});

it("does not build LZs that arrive already saved", () => {
  // Loading old LZs from the cloud is not a new reason to spend builds.
  const { rerender } = renderHook(({ diagrams }) => useBuildOnSave(diagrams),
                                  { initialProps: { diagrams: [] } });
  rerender({ diagrams: [lz({ id: "b", savedId: 7 }), lz({ id: "c", savedId: 8 })] });
  expect(api.post).not.toHaveBeenCalled();
});

it("needs a target to build anything", () => {
  const { rerender } = renderHook(({ diagrams }) => useBuildOnSave(diagrams),
                                  { initialProps: { diagrams: [lz({ target: null })] } });
  rerender({ diagrams: [lz({ target: null, savedId: 42 })] });
  expect(api.post).not.toHaveBeenCalled();
});
