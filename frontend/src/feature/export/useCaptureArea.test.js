import { act, renderHook } from "@testing-library/react";
import useCaptureArea from "./useCaptureArea";

const BOX = [[34, -84], [34.01, -83.99]];
const MOVED = [[35, -85], [35.01, -84.99]];

describe("an LZ card's capture area", () => {
  it("is the LZ/PZ's own where the person may edit it", () => {
    const setShared = jest.fn();
    const { result } = renderHook(() => useCaptureArea({ own: false, diagramId: "a", shared: BOX, setShared }));
    expect(result.current.exportBox).toBe(BOX);
    act(() => result.current.setExportBox(MOVED));
    expect(setShared).toHaveBeenCalledWith(MOVED);
  });

  it("is kept here, per LZ/PZ, in a pack the person cannot change, and the pack's is left alone", () => {
    const setShared = jest.fn();
    const { result, rerender } = renderHook((props) => useCaptureArea({ setShared, ...props }), {
      initialProps: { own: true, diagramId: "a", shared: null },
    });
    expect(result.current.exportBox).toBeNull();

    act(() => result.current.setExportBox(MOVED));
    expect(setShared).not.toHaveBeenCalled();
    expect(result.current.exportBox).toBe(MOVED);

    // A drag hands an updater, which starts from the area shown.
    act(() => result.current.setExportBox((current) => [current[0], [36, -84]]));
    expect(result.current.exportBox).toEqual([MOVED[0], [36, -84]]);

    // Another LZ/PZ shows its own (the pack's) until this person sets one there.
    rerender({ own: true, diagramId: "b", shared: BOX });
    expect(result.current.exportBox).toBe(BOX);
    act(() => result.current.setExportBox(null));
    expect(result.current.exportBox).toBeNull();
    rerender({ own: true, diagramId: "a", shared: null });
    expect(result.current.exportBox).toEqual([MOVED[0], [36, -84]]);
  });

  it("goes back to the LZ/PZ's own once the person may edit again", () => {
    const setShared = jest.fn();
    const { result, rerender } = renderHook((props) => useCaptureArea({ setShared, diagramId: "a", ...props }), {
      initialProps: { own: true, shared: BOX },
    });
    act(() => result.current.setExportBox(MOVED));
    rerender({ own: false, shared: BOX });
    expect(result.current.exportBox).toBe(BOX);
    // And a later read-only spell starts again from the pack's.
    rerender({ own: true, shared: BOX });
    expect(result.current.exportBox).toBe(BOX);
  });
});
