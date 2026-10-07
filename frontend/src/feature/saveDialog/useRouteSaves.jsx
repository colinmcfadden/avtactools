import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { computeRoutePlan, defaultRoutePlan, planPoints } from "../msnxImport/routeCalc";
import { useToast } from "../ui/Toast";
import SaveDialog, { nextFreeName } from "./SaveDialog";

/*
 * Saving route sets to the Library (docs/MENU_REDESIGN.md §4, screen SaveRoutes). A set is this
 * session's sketched routes (key "sketches"), a saved set opened beside them (key: its setId, from
 * useRouteSketch's openSavedRoutes), or one imported mission file (key: its fileId). Each set may be
 * linked to a saved record; the first save asks for a name, later saves update that record in place
 * without asking, and "Save as…" makes a copy.
 *
 * Whether a set has unsaved changes is worked out by comparing it with what was last saved (or
 * opened): routes keep no dirty flag of their own. Hiding or showing a route is not a change.
 */

export const SKETCHES = "sketches";
const PENDING = Symbol("pending");

export const routesFingerprint = (routes) =>
  JSON.stringify((routes ?? []).map(({ visible, ...rest }) => rest));

const plural = (n, one, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;

export const routeTotals = (route) => {
  try {
    const result = computeRoutePlan(route, { ...defaultRoutePlan(), ...route.plan }, route.elevations || {});
    return result.totals ?? null;
  } catch {
    return null;
  }
};

export const setSummary = (routes) => {
  const points = routes.reduce((sum, route) => sum + (route.points?.length ?? 0), 0);
  return `${plural(routes.length, "route")} · ${plural(points, "point")}`;
};

const contentsFor = (set) => {
  const routes = set.routes;
  const points = routes.reduce((sum, route) => sum + planPoints(route).length, 0);
  const nm = routes.reduce((sum, route) => sum + (routeTotals(route)?.distNm ?? 0), 0);
  if (set.kind === "mission") {
    return [
      { text: `${plural(routes.length, "route")}, ${plural(points, "route point")}${nm ? `, ${nm.toFixed(1)} nm` : ""}` },
      { text: "Your edits to points and plans, written into the .msnx file" },
      { icon: "info", text: "Threats are never saved. Export them as a .ths if you need them next to the mission." },
    ];
  }
  return [
    { text: `${plural(routes.length, "route")}, ${plural(points, "point")}${nm ? `, ${nm.toFixed(1)} nm` : ""}` },
    { text: "Speeds, altitudes, winds, fuel flow and the start time" },
    { icon: "info", text: "Threats are never saved. Export them as a .ths if you need them next to the mission." },
  ];
};

export const defaultSetName = (set) =>
  set.kind === "mission" ? (set.fileName || "MISSION").replace(/\.msnx$/i, "") : "SKETCHED ROUTES";

/**
 * `sets`: [{ key, kind: "sketch" | "mission", fileName, routes }]. `library`: the useSavedRoutes calls
 * plus `serializeFile(fileId)` from useMsnxImport.
 */
export const useRouteSaves = ({ sets, library, signedIn, onOpenLibrary }) => {
  const { show } = useToast();
  const [links, setLinks] = useState({});
  const [snapshots, setSnapshots] = useState({});
  const [names, setNames] = useState({});
  const [savedAt, setSavedAt] = useState({});
  const [dialog, setDialog] = useState(null);
  const [savingKey, setSavingKey] = useState(null);
  const lib = useRef(library);
  lib.current = library;
  const setsRef = useRef(sets);
  setsRef.current = sets;

  const prints = useMemo(() => Object.fromEntries(sets.map((set) => [set.key, routesFingerprint(set.routes)])), [sets]);

  // A set just opened from the Library is clean once its routes have arrived; a set that has gone
  // (every sketch removed, a mission closed) is no longer linked, so the next save is a new record.
  useEffect(() => {
    const present = new Set(sets.filter((set) => set.routes.length > 0).map((set) => set.key));
    setSnapshots((prev) => {
      let changed = false;
      const next = { ...prev };
      Object.entries(prev).forEach(([key, value]) => {
        if (value === PENDING && present.has(key)) {
          next[key] = prints[key];
          changed = true;
        }
      });
      return changed ? next : prev;
    });
    setLinks((prev) => {
      const gone = Object.keys(prev).filter((key) => !present.has(key) && snapshots[key] !== PENDING);
      if (gone.length === 0) return prev;
      const next = { ...prev };
      gone.forEach((key) => delete next[key]);
      return next;
    });
  }, [sets, prints, snapshots]);

  const nameOf = useCallback(
    (set) => names[set.key] ?? links[set.key]?.name ?? defaultSetName(set),
    [links, names],
  );

  const stateOf = useCallback(
    (key) => {
      const set = sets.find((s) => s.key === key);
      const link = links[key] ?? null;
      const renamed = names[key] != null && names[key] !== link?.name;
      const dirty = !link || renamed || (snapshots[key] !== PENDING && snapshots[key] !== prints[key]);
      return { link, name: set ? nameOf(set) : link?.name ?? "", dirty, savedAt: savedAt[key] ?? null, saving: savingKey === key };
    },
    [links, names, nameOf, prints, savedAt, savingKey, sets, snapshots],
  );

  /** A record opened from the Library now backs the set `key`. */
  const adopt = useCallback((key, entry) => {
    setLinks((prev) => ({ ...prev, [key]: { id: entry.id, name: entry.name } }));
    setNames((prev) => {
      const { [key]: _drop, ...rest } = prev;
      return rest;
    });
    setSnapshots((prev) => ({ ...prev, [key]: PENDING }));
    setSavedAt((prev) => ({ ...prev, [key]: entry.updated_at ?? null }));
  }, []);

  const rename = useCallback((key, name) => setNames((prev) => ({ ...prev, [key]: name })), []);

  /** The record `recordId` was deleted (or renamed) in the Library: the sets follow it. */
  const recordChanged = useCallback((recordId, { deleted = false, name } = {}) => {
    setLinks((prev) => {
      const keys = Object.keys(prev).filter((key) => prev[key].id === recordId);
      if (keys.length === 0) return prev;
      const next = { ...prev };
      keys.forEach((key) => {
        if (deleted) delete next[key];
        else next[key] = { ...next[key], name };
      });
      return next;
    });
  }, []);

  /** Writes the set as `name` to `recordId` (or a new record) and marks it saved. */
  const write = useCallback(async (key, name, recordId) => {
    const set = setsRef.current.find((s) => s.key === key);
    if (!set) throw new Error("Those routes are no longer open.");
    const print = routesFingerprint(set.routes);
    let saved;
    if (set.kind === "mission") {
      const file = await lib.current.serializeFile(key);
      if (!file) throw new Error("The mission file is no longer loaded.");
      saved =
        recordId != null
          ? await lib.current.updateMission(recordId, set.routes, file.blob, file.fileName, name)
          : await lib.current.saveMission(name, set.routes, file.blob, file.fileName);
    } else {
      // Which set a route is filed under here (useRouteSketch) is not part of what is saved.
      const routes = set.routes.map(({ setId: _filed, ...route }) => route);
      saved = recordId != null ? await lib.current.updateSketch(recordId, routes, name) : await lib.current.saveSketch(name, routes);
    }
    const id = recordId ?? saved?.id;
    setLinks((prev) => ({ ...prev, [key]: { id, name } }));
    setNames((prev) => {
      const { [key]: _drop, ...rest } = prev;
      return rest;
    });
    // What was sent, not what is there now: an edit made meanwhile stays unsaved.
    setSnapshots((prev) => ({ ...prev, [key]: print }));
    setSavedAt((prev) => ({ ...prev, [key]: saved?.updated_at ?? new Date().toISOString() }));
    lib.current.fetchSavedRoutes?.();
    return id;
  }, []);

  const toastSaved = useCallback(
    (name) => show({ message: `Saved “${name}” to your Library`, action: onOpenLibrary ? { label: "Open Library", onClick: onOpenLibrary } : undefined }),
    [onOpenLibrary, show],
  );

  /** Save: the dialog the first time, silently after that. `then` runs once it is saved. */
  const save = useCallback(
    async (key, { then } = {}) => {
      if (!signedIn) {
        show({ tone: "warn", message: "Sign in to save to your Library." });
        return;
      }
      const state = stateOf(key);
      if (!state.link) {
        setDialog({ key, copy: false, then });
        return;
      }
      if (!state.dirty) {
        then?.();
        return;
      }
      setSavingKey(key);
      try {
        await write(key, state.name, state.link.id);
        toastSaved(state.name);
        then?.();
      } catch (err) {
        show({ tone: "error", message: `“${state.name}” could not be saved. Check the connection and try again.`, action: { label: "Try again", onClick: () => save(key) } });
      } finally {
        setSavingKey(null);
      }
    },
    [show, signedIn, stateOf, toastSaved, write],
  );

  const saveAs = useCallback(
    (key) => {
      if (!signedIn) {
        show({ tone: "warn", message: "Sign in to save to your Library." });
        return;
      }
      setDialog({ key, copy: true });
    },
    [show, signedIn],
  );

  // Saves asked for before their set had arrived (a mission imported straight to the Library): run
  // each once its routes are here.
  const [queued, setQueued] = useState([]);
  const queueSave = useCallback((key, name) => setQueued((prev) => [...prev, { key, name }]), []);
  useEffect(() => {
    const ready = queued.filter((item) => sets.some((s) => s.key === item.key && s.routes.length > 0));
    if (ready.length === 0) return;
    setQueued((prev) => prev.filter((item) => !ready.includes(item)));
    ready.forEach(async (item) => {
      const existing = (lib.current.savedRoutes ?? []).filter((entry) => entry.kind === "mission");
      const name = nextFreeName(item.name, existing);
      try {
        await write(item.key, name, null);
        toastSaved(name);
      } catch {
        show({ tone: "error", message: `“${name}” is open but could not be saved to your Library. Use Save on its card to try again.` });
      }
    });
  }, [queued, sets, show, toastSaved, write]);

  let dialogs = null;
  const set = dialog ? sets.find((s) => s.key === dialog.key) : null;
  if (dialog && set) {
    const kind = set.kind === "mission" ? "mission" : "sketch";
    const existing = (library.savedRoutes ?? [])
      .filter((entry) => (entry.kind ?? "sketch") === kind)
      .map((entry) => ({ ...entry, updatedAt: entry.updated_at }));
    const name = nameOf(set);
    dialogs = (
      <SaveDialog
        key={`${dialog.key}-${dialog.copy}`}
        title={dialog.copy ? "Save as new routes" : kind === "mission" ? "Save mission" : "Save routes"}
        subtitle={
          dialog.copy
            ? "Saves a copy under a new name. What is open becomes the copy."
            : kind === "mission"
              ? "Saves the mission file with your edits to its points and plans."
              : "Saves the sketched routes with their plans."
        }
        icon="route"
        initialName={dialog.copy ? nextFreeName(name, existing) : name}
        contents={contentsFor(set)}
        existing={existing}
        kindPhrase={kind === "mission" ? "a mission" : "a route set"}
        onCancel={() => setDialog(null)}
        onSave={async (chosen, { replace }) => {
          await write(set.key, chosen, replace ? replace.id : null);
          setDialog(null);
          toastSaved(chosen);
          dialog.then?.();
        }}
      />
    );
  }

  return { stateOf, save, saveAs, rename, adopt, recordChanged, queueSave, dialogs, savingKey, nameOf };
};

export default useRouteSaves;
