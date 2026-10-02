import { renderHook } from "@testing-library/react";
import api from "../auth/api";
import { useBuildOnSave } from "./useBuildOnSave";

jest.mock("../auth/api", () => ({
  __esModule: true,
  default: { post: jest.fn() },
}));

const TARGET = { lat: 34.783817, lon: -84.08219 };
const lz = (overrides) => ({ id: "a", name: "LZ/PZ 1", target: TARGET, savedId: null, ...overrides });

// CRA resets mock implementations before every test.
beforeEach(() => api.post.mockResolvedValue({}));

it("asks for a kept build when an LZ is saved", () => {
  const { rerender } = renderHook(({ diagrams }) => useBuildOnSave(diagrams),
                                  { initialProps: { diagrams: [lz()] } });
  expect(api.post).not.toHaveBeenCalled();

  rerender({ diagrams: [lz({ savedId: 42 })] });
  expect(api.post).toHaveBeenCalledWith("/lidar/build", { ...TARGET, keep: true });
});

it("asks only once, not on every later update", () => {
  const { rerender } = renderHook(({ diagrams }) => useBuildOnSave(diagrams),
                                  { initialProps: { diagrams: [lz()] } });
  rerender({ diagrams: [lz({ savedId: 42 })] });
  rerender({ diagrams: [lz({ savedId: 42, name: "renamed" })] });
  expect(api.post).toHaveBeenCalledTimes(1);
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
