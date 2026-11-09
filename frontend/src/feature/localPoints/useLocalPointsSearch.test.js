import { act, renderHook, waitFor } from "@testing-library/react";
import api from "../auth/api";
import { useLocalPoints } from "./useLocalPoints";

vi.mock("../auth/api", () => ({ default: { get: vi.fn() } }));

const listed = (over = {}) => ({ id: 3, name: "NORTH", point_count: 1, updated_at: "2026-11-01T00:00:00", ...over });

const signedInWith = async (entries) => {
  api.get.mockImplementation(async (url) => (url === "/pointsets"
    ? { data: entries() }
    : { data: { points: [{ name: `FROM ${url}`, lat: 34, lon: -84 }] } }));
  const hook = renderHook(() => useLocalPoints());
  await act(async () => { await hook.result.current.fetchSavedPointSets(); });
  return hook;
};

const setFetches = () => api.get.mock.calls.filter(([url]) => url !== "/pointsets").length;

describe("useLocalPoints: the Library's points for the search fields", () => {
  beforeEach(() => api.get.mockReset());

  it("fetches nothing until someone goes to search, then each set once", async () => {
    const { result } = await signedInWith(() => [listed()]);
    expect(setFetches()).toBe(0);
    act(() => result.current.askForLibraryPoints());
    await waitFor(() => expect(result.current.libraryPointSets).toHaveLength(1));
    expect(result.current.libraryPointSets[0]).toMatchObject({ savedId: 3, name: "NORTH" });
    act(() => result.current.askForLibraryPoints());
    await act(async () => {});
    expect(setFetches()).toBe(1);
  });

  it("fetches a set again once it has changed, and lets go of one no longer listed", async () => {
    let entries = [listed()];
    const { result } = await signedInWith(() => entries);
    act(() => result.current.askForLibraryPoints());
    await waitFor(() => expect(result.current.libraryPointSets).toHaveLength(1));
    entries = [listed({ updated_at: "2026-11-02T00:00:00" })];
    await act(async () => { await result.current.fetchSavedPointSets(); });
    await waitFor(() => expect(setFetches()).toBe(2));
    entries = [];
    await act(async () => { await result.current.fetchSavedPointSets(); });
    expect(result.current.libraryPointSets).toEqual([]);
  });

  it("asks again at the next search for a set that could not be fetched", async () => {
    const { result } = await signedInWith(() => [listed()]);
    api.get.mockRejectedValueOnce(new Error("offline"));
    act(() => result.current.askForLibraryPoints());
    await act(async () => {});
    expect(result.current.libraryPointSets).toEqual([]);
    act(() => result.current.askForLibraryPoints());
    await waitFor(() => expect(result.current.libraryPointSets).toHaveLength(1));
  });
});
