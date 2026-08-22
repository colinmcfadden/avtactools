import { useCallback, useEffect, useState } from "react";
import api from "../auth/api";

/**
 * Resolves a map target to a generated point cloud tileset.
 *
 * Tilesets are built offline, so a target usually has one or usually does not —
 * "not generated yet" is a normal state to report rather than an error. The
 * lookup is a POST because the coordinates are the sensitive part: a GET would
 * put an LZ's position into request logs and browser history.
 */

const IDLE = { state: "idle", url: null, key: null, target: null, error: "" };

export const useLidarTileset = ({ lat, lon, radiusM } = {}) => {
  const [result, setResult] = useState(IDLE);

  const hasTarget = Number.isFinite(lat) && Number.isFinite(lon);

  const resolve = useCallback(async (signal) => {
    if (!hasTarget) {
      setResult(IDLE);
      return;
    }
    setResult({ ...IDLE, state: "resolving" });
    try {
      const body = { lat, lon };
      if (Number.isFinite(radiusM)) body.radius_m = radiusM;
      const res = await api.post("/lidar/resolve", body, { signal });
      const { key, available, url, target } = res.data || {};
      setResult({
        state: available ? "available" : "missing",
        url: available ? url : null,
        key: key || null,
        // The target the server resolved, so a "not built" panel can say
        // which coordinates to build rather than quoting a hash.
        target: target || null,
        error: "",
      });
    } catch (err) {
      if (err?.name === "CanceledError" || err?.code === "ERR_CANCELED") return;
      setResult({
        ...IDLE,
        state: "error",
        error: err?.response?.data?.error || err?.message || "Lookup failed.",
      });
    }
  }, [hasTarget, lat, lon, radiusM]);

  useEffect(() => {
    const controller = new AbortController();
    resolve(controller.signal);
    return () => controller.abort();
  }, [resolve]);

  return { ...result, hasTarget, refresh: () => resolve() };
};
