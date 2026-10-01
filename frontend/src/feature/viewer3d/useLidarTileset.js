import { useCallback, useEffect, useRef, useState } from "react";
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
 * A build runs only while someone is waiting for it. Polling is what tells the
 * build service this tab still wants it; closing the window says it no longer
 * does, and a build nobody asks after is dropped. An LZ that is saved passes
 * `keep`, and its build finishes regardless — a refresh should not cost it.
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

// Who is waiting, as far as the build service is concerned: one opaque id per
// browser tab. Session storage, so a refresh keeps it — the reloaded page can
// then tell the service the previous page's builds are no longer wanted.
const WATCHER_KEY = "avtac.lidar.watcher";
// Builds this tab started and has not yet seen finish or released.
const IN_FLIGHT_KEY = "avtac.lidar.building";

const randomId = () => {
  if (typeof crypto !== "undefined" && crypto.randomUUID) return crypto.randomUUID();
  return `tab-${Math.random().toString(36).slice(2)}${Date.now().toString(36)}`;
};

// Storage can be unavailable (private windows, blocked site data); the lease
// on the server still drops abandoned builds, just a little later.
const readSession = (key) => {
  try { return sessionStorage.getItem(key); } catch { return null; }
};
const writeSession = (key, value) => {
  try { sessionStorage.setItem(key, value); } catch { /* lease covers it */ }
};

let memoryWatcher = null;
export const watcherId = () => {
  const stored = readSession(WATCHER_KEY);
  if (stored) return stored;
  memoryWatcher = memoryWatcher || randomId();
  writeSession(WATCHER_KEY, memoryWatcher);
  return memoryWatcher;
};

const inFlight = () => {
  try { return JSON.parse(readSession(IN_FLIGHT_KEY) || "[]"); } catch { return []; }
};
const markInFlight = (key) => {
  if (key && !inFlight().includes(key)) writeSession(IN_FLIGHT_KEY, JSON.stringify([...inFlight(), key]));
};
const clearInFlight = (key) => {
  writeSession(IN_FLIGHT_KEY, JSON.stringify(inFlight().filter((k) => k !== key)));
};

/** Stop waiting for a build. Fire and forget: the lease is the backstop. */
const release = (key) => {
  if (!key) return;
  clearInFlight(key);
  api.delete(`/lidar/build/${key}`, { params: { watcher: watcherId() } }).catch(() => {});
};

/**
 * Release builds a previous load of this tab was waiting on.
 *
 * A refresh gives the page no chance to say it is leaving — the request would
 * be cut off mid-flight — so the reloaded page says it instead. Without this,
 * the old build ran on and every LZ opened afterwards queued behind it until
 * the server's lease ran out.
 */
export const releaseAbandonedBuilds = () => {
  inFlight().forEach(release);
};

/**
 * Ask for a point cloud to be built in the background and kept — for an LZ
 * that has just been saved, so it is ready by the time anyone opens it in 3D.
 * Nobody waits on it, so the build service runs it behind anything a person
 * in the 3D window is waiting for. Already built, or a server that cannot
 * build: both are fine, and nothing is reported.
 */
export const requestKeptBuild = ({ lat, lon } = {}) => {
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) return;
  api.post("/lidar/build", { lat, lon, keep: true }).catch(() => {});
};

const isCancel = (err) => err?.name === "CanceledError" || err?.code === "ERR_CANCELED";

const messageOf = (err, fallback) =>
  err?.response?.data?.error || err?.message || fallback;

export const useLidarTileset = ({ lat, lon, radiusM, keep = false } = {}) => {
  const [result, setResult] = useState(IDLE);
  // Bumped by refresh() to run the whole lookup again.
  const [attempt, setAttempt] = useState(0);
  // Read on every request rather than restarting the lookup: saving an LZ
  // mid-build should mark its build kept, not begin it again.
  const keepRef = useRef(keep);
  keepRef.current = keep;

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
    // The build this effect is waiting on, if any — released on cleanup.
    let waitingOn = null;

    const body = { lat, lon };
    if (Number.isFinite(radiusM)) body.radius_m = radiusM;

    const settled = () => {
      clearInFlight(waitingOn);
      waitingOn = null;
    };

    const finish = (data) => {
      settled();
      setResult({
        ...IDLE,
        state: "available",
        url: data.url,
        // Optional wider, thinned ring. Absent unless one was built.
        contextUrl: data.contextUrl || null,
        key: data.key || null,
      });
    };

    const fail = (message, { buildFailed = false } = {}) => {
      settled();
      setResult({
        ...IDLE, state: "error", error: message, buildFailed, target: resolvedTarget,
      });
    };

    const showJob = (job) => {
      if (job.key && job.key !== waitingOn) {
        waitingOn = job.key;
        markInFlight(job.key);
      }
      setResult({
        ...IDLE,
        state: "building",
        key: job.key || null,
        stage: job.stage || "waiting",
        position: Number.isFinite(job.position) ? job.position : null,
        elapsedS: Number.isFinite(job.elapsed_s) ? job.elapsed_s : null,
        target: resolvedTarget,
      });
    };

    // Every poll says who is still waiting, and whether the LZ is now saved.
    const watching = () => ({ watcher: watcherId(), ...(keepRef.current ? { keep: "1" } : {}) });

    const poll = (key) => {
      timer = setTimeout(async () => {
        if (cancelled) return;
        try {
          const res = await api.get(`/lidar/build/${key}`, { signal, params: watching() });
          failures = 0;
          const job = res.data || {};
          if (job.state === "done" && job.url) return finish(job);
          if (job.state === "failed") {
            return fail(job.error || "The build failed.", { buildFailed: true });
          }
          // Dropped as unwatched while this tab was still here — a background
          // tab the browser stopped running timers in. Ask again.
          if (job.state === "cancelled" && resubmits < MAX_RESUBMITS) {
            resubmits += 1;
            startBuild();
            return;
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
        const res = await api.post("/lidar/build",
                                   { ...body, keep: keepRef.current, watcher: watcherId() },
                                   { signal });
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
      // The window closed or moved to another LZ: this tab no longer wants
      // the build. The service drops it unless it is kept or someone else
      // is waiting.
      release(waitingOn);
    };
  }, [hasTarget, lat, lon, radiusM, attempt]);

  const refresh = useCallback(() => setAttempt((n) => n + 1), []);

  return { ...result, hasTarget, refresh };
};
