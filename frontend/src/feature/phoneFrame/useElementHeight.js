import { useEffect, useState } from "react";

/**
 * The height of the element `ref` holds, kept as it changes (the map's, which shortens as the bottom sheet rises);
 * undefined where it cannot be watched (no ResizeObserver: an old browser, a test), which the rail reads as "room
 * for everything". Not watched at all while `watch` is false: a wide window has no rail to fit.
 */
const useElementHeight = (ref, watch = true) => {
  const [height, setHeight] = useState(undefined);
  useEffect(() => {
    const element = ref.current;
    if (!watch || !element || typeof ResizeObserver === "undefined") {
      setHeight(undefined);
      return undefined;
    }
    const observer = new ResizeObserver(([entry]) => setHeight(Math.round(entry.contentRect.height)));
    observer.observe(element);
    return () => observer.disconnect();
  }, [ref, watch]);
  return height;
};

export default useElementHeight;
