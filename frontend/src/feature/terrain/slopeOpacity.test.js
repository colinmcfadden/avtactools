import { act, renderHook } from "@testing-library/react";
import useSlopeOpacity, { SLOPE_OPACITY, SLOPE_OPACITY_STORAGE_KEY, slopeOpacityOf } from "./slopeOpacity";

describe("the slope map's opacity", () => {
  afterEach(() => {
    window.localStorage.clear();
    vi.restoreAllMocks();
  });

  it("runs from 10 to 100 percent in steps of 5, from 55 (as Android's SlopeOpacity does)", () => {
    expect(SLOPE_OPACITY).toEqual({ DEFAULT: 55, MIN: 10, MAX: 100, STEP: 5 });
    const kept = new Set(Array.from({ length: 121 }, (_, i) => slopeOpacityOf(i)));
    expect([...kept]).toEqual(Array.from({ length: 19 }, (_, i) => 10 + 5 * i));
  });

  it("brings a value onto the nearest step, within the ends", () => {
    expect([12, 13, 57, 58, 0, -40, 98, 1e9].map(slopeOpacityOf)).toEqual([10, 15, 55, 60, 10, 10, 100, 100]);
    expect(slopeOpacityOf(12.5)).toBe(15);                                          // halves up
    expect(slopeOpacityOf("35")).toBe(35);
  });

  it("reads anything that is not a number as the default", () => {
    [undefined, null, "", "  ", "half", NaN, Infinity, true, {}].forEach((value) => expect(slopeOpacityOf(value)).toBe(55));
  });

  it("starts at 55 with nothing remembered, and remembers what is set, on its step, for the next load", () => {
    const { result } = renderHook(() => useSlopeOpacity());
    expect(result.current.slopeOpacity).toBe(55);
    act(() => result.current.setSlopeOpacity(72));
    expect(result.current.slopeOpacity).toBe(70);
    expect(window.localStorage.getItem(SLOPE_OPACITY_STORAGE_KEY)).toBe("70");
    expect(renderHook(() => useSlopeOpacity()).result.current.slopeOpacity).toBe(70);
  });

  it("reads what another version kept off the steps or out of range onto them, and nonsense as the default", () => {
    window.localStorage.setItem(SLOPE_OPACITY_STORAGE_KEY, "33");
    expect(renderHook(() => useSlopeOpacity()).result.current.slopeOpacity).toBe(35);
    window.localStorage.setItem(SLOPE_OPACITY_STORAGE_KEY, "250");
    expect(renderHook(() => useSlopeOpacity()).result.current.slopeOpacity).toBe(100);
    window.localStorage.setItem(SLOPE_OPACITY_STORAGE_KEY, "on");
    expect(renderHook(() => useSlopeOpacity()).result.current.slopeOpacity).toBe(55);
  });

  it("still works where the browser keeps nothing, starting at 55", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => { throw new Error("blocked"); });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new Error("blocked"); });
    const { result } = renderHook(() => useSlopeOpacity());
    expect(result.current.slopeOpacity).toBe(55);
    act(() => result.current.setSlopeOpacity(20));
    expect(result.current.slopeOpacity).toBe(20);
  });
});
