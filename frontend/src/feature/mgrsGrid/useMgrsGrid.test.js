import { act, renderHook } from "@testing-library/react";
import useMgrsGrid, { MGRS_GRID_STORAGE_KEY } from "./useMgrsGrid";

const storage = () => window.localStorage;

afterEach(() => {
  jest.restoreAllMocks();
  storage().clear();
});

describe("useMgrsGrid", () => {
  it("is off until someone turns it on", () => {
    const { result } = renderHook(() => useMgrsGrid());
    expect(result.current.showMgrsGrid).toBe(false);
  });

  it("is remembered in this browser, under an ezpz. key", () => {
    expect(MGRS_GRID_STORAGE_KEY).toMatch(/^ezpz\./);
    const first = renderHook(() => useMgrsGrid());
    act(() => first.result.current.setShowMgrsGrid(true));
    expect(first.result.current.showMgrsGrid).toBe(true);
    first.unmount();

    const again = renderHook(() => useMgrsGrid());
    expect(again.result.current.showMgrsGrid).toBe(true);
    act(() => again.result.current.setShowMgrsGrid(false));
    expect(again.result.current.showMgrsGrid).toBe(false);
    expect(storage().getItem(MGRS_GRID_STORAGE_KEY)).toBeNull();
    again.unmount();

    expect(renderHook(() => useMgrsGrid()).result.current.showMgrsGrid).toBe(false);
  });

  it("reads anything stored but its own 'on' as off", () => {
    ["true", "1", "ON", ""].forEach((value) => {
      storage().setItem(MGRS_GRID_STORAGE_KEY, value);
      expect(renderHook(() => useMgrsGrid()).result.current.showMgrsGrid).toBe(false);
    });
  });

  it("still turns on and off when storage throws, and starts off", () => {
    const refuse = () => {
      throw new Error("SecurityError: storage is disabled");
    };
    jest.spyOn(Storage.prototype, "getItem").mockImplementation(refuse);
    jest.spyOn(Storage.prototype, "setItem").mockImplementation(refuse);
    jest.spyOn(Storage.prototype, "removeItem").mockImplementation(refuse);

    const { result } = renderHook(() => useMgrsGrid());
    expect(result.current.showMgrsGrid).toBe(false);
    act(() => result.current.setShowMgrsGrid(true));
    expect(result.current.showMgrsGrid).toBe(true);
    act(() => result.current.setShowMgrsGrid(false));
    expect(result.current.showMgrsGrid).toBe(false);
  });

  it("still works when even reaching storage throws (site data blocked)", () => {
    const reach = jest.spyOn(window, "localStorage", "get").mockImplementation(() => {
      throw new Error("SecurityError: access denied");
    });
    const { result } = renderHook(() => useMgrsGrid());
    expect(result.current.showMgrsGrid).toBe(false);
    act(() => result.current.setShowMgrsGrid(true));
    expect(result.current.showMgrsGrid).toBe(true);
    expect(reach).toHaveBeenCalled();
  });
});
