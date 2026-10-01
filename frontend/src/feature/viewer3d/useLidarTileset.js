import { useCallback, useEffect, useState } from "react";
import api from "../auth/api";

/**
 * Resolves a map target to a point cloud tileset, building one if none exists.
 *
 * Opening the 3D view somewhere new is the normal case, not an error: the
 * server is asked for a build, and progress is polled until the tileset is on
 * disk. Only a server with no build service falls back to "missing", where the
 * window tells an operator what to run instead.
 *
 * Coordinates only ever travel in POST bodies. Progress is read by the build's
 * opaque key, so an LZ's position never lands in a URL, a request log, or the
 * browser's history.
 *
 * States: idle · resolving · building · available · missing · error
 */

// The service reports a stage change every few seconds at most, and a build
// takes minutes, so polling faster would only add load.
const POLL_MS = 3000;

// Consecutive polls that may fail before giving up. A redeploy of the API or
// the service briefly drops requests, and that should not lose a build.
const MAX_POLL_FAILURES = 5;

// A service restart forgets its jobs and answers 404. Asking again requeues
// the same place; this bounds it so a service that keeps losing work cannot
// loop forever.
const MAX_RESUBMITS = 2;

const IDLE = {
  state: "idle", url: null, contextUrl: null, key: null, target: null,
  error: "", stage: null, position: null, elapsedS: null, buildFailed: false,
};

const isCancel = (err) => err?.name === "CanceledError" || err?.code === "ERR_CANCELED";

const messageOf = (err, fallback) =>
  err?.response?.data?.error || err?.message || fallback;

export const useLidarTileset = ({ lat, lon, radiusM } = {}) => {
  const [result, setResult] = useState(IDLE);
  // Bumped by refresh() to run the whole lookup again.
  const [attempt, setAttempt] = useState(0);

  const hasTarget = Number.isFinite(lat) && Number.isFinite(lon);

  useEffect(() => {
    if (!hasTarget) {
      setResult(IDLE);
      return undefined;
    }

    const controller = new AbortController();
    const { signal } = controller;
    let cancelled = false;
    let timer = null;
    let failures = 0;
    let resubmits = 0;
    let resolvedTarget = null;

    const body = { lat, lon };
    if (Number.isFinite(radiusM)) body.radius_m = radiusM;

    const finish = (data) => setResult({
      ...IDLE,
      state: "available",
      url: data.url,
      // Optional wider, thinned ring. Absent unless one was built.
      contextUrl: data.contextUrl || null,
      key: data.key || null,
    });

    const fail = (message, { buildFailed = false } = {}) => setResult({
      ...IDLE, state: "error", error: message, buildFailed, target: resolvedTarget,
    });

    const showJob = (job) => setResult({
      ...IDLE,
      state: "building",
      key: job.key || null,
      stage: job.stage || "waiting",
      position: Number.isFinite(job.position) ? job.position : null,
      elapsedS: Number.isFinite(job.elapsed_s) ? job.elapsed_s : null,
      target: resolvedTarget,
    });

    const poll = (key) => {
      timer = setTimeout(async () => {
        if (cancelled) return;
        try {
          const res = await api.get(`/lidar/build/${key}`, { signal });
          failures = 0;
          const job = res.data || {};
          if (job.state === "done" && job.url) return finish(job);
          if (job.state === "failed") {
            return fail(job.error || "The build failed.", { buildFailed: true });
          }
          showJob(job);
          poll(key);
        } catch (err) {
          if (cancelled || isCancel(err)) return;
          if (err?.response?.status === 404 && resubmits < MAX_RESUBMITS) {
            resubmits += 1;
            startBuild();
            return;
          }
          failures += 1;
          if (failures >= MAX_POLL_FAILURES) {
            fail(messageOf(err, "Lost contact with the build."), { buildFailed: true });
            return;
          }
          poll(key);
        }
      }, POLL_MS);
    };

    const startBuild = async () => {
      try {
        const res = await api.post("/lidar/build", body, { signal });
        const job = res.data || {};
        if (job.state === "done" && job.url) return finish(job);
        showJob(job);
        poll(job.key);
      } catch (err) {
        if (cancelled || isCancel(err)) return;
        fail(messageOf(err, "Could not start the build."), { buildFailed: true });
      }
    };

    const run = async () => {
      setResult({ ...IDLE, state: "resolving" });
      try {
        const res = await api.post("/lidar/resolve", body, { signal });
        const data = res.data || {};
        if (data.available) return finish(data);
        // The target the server resolved, so a "not built" panel can say
        // which coordinates to build rather than quoting a hash.
        resolvedTarget = data.target || null;
        if (data.canBuild) {
          showJob({ key: data.key, stage: "starting" });
          startBuild();
          return;
        }
        setResult({ ...IDLE, state: "missing", key: data.key || null,
                    target: resolvedTarget });
      } catch (err) {
        if (cancelled || isCancel(err)) return;
        fail(messageOf(err, "Lookup failed."));
      }
    };

    run();
    return () => {
      cancelled = true;
      controller.abort();
      clearTimeout(timer);
    };
  }, [hasTarget, lat, lon, radiusM, attempt]);

  const refresh = useCallback(() => setAttempt((n) => n + 1), []);

  return { ...result, hasTarget, refresh };
};
