import { useEffect, useMemo, useState } from "react";
import api from "../auth/api";
import { placeRoutes, prepareRoutes } from "./routeScene";

// Dragging a route point in 2D changes the route on every mouse move; one
// request once the drag settles is enough.
const DEBOUNCE_MS = 400;

/**
 * The visible routes as 3D shapes, once the server has said where the ground
 * is under them.
 *
 * Keeps showing the last placed routes while a newer set of heights is on its
 * way, so editing in 2D moves the 3D routes rather than blinking them out.
 * Coordinates go in the request body only, never a URL.
 */
export const useRouteScene = (routes) => {
  const prepared = useMemo(() => prepareRoutes(routes), [routes]);
  const [scene, setScene] = useState([]);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (prepared.samples.length === 0) {
      setScene([]);
      return undefined;
    }
    let cancelled = false;
    const timer = setTimeout(async () => {
      try {
        const { data } = await api.post("/terrain/heights", { points: prepared.samples });
        if (cancelled) return;
        setScene(placeRoutes(prepared, data));
        setError(null);
      } catch (requestError) {
        if (!cancelled) setError(requestError);
      }
    }, DEBOUNCE_MS);

    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [prepared]);

  return { scene, error };
};

export default useRouteScene;
