import { useEffect, useMemo, useRef, useState } from "react";
import api from "../auth/api";
import { nextFreeName } from "../saveDialog/SaveDialog";
import { makeThreat, newThreatId, threatToPayload } from "./threatModel";
import { parseThsFile } from "./parseThs";
import {
  canon,
  conflictName,
  documentOf,
  FIRST_SET_NAME,
  maskStillFits,
  maskSubject,
  readDocument,
  sameApartFromMasks,
  setNameFromFile,
  wantsMask,
} from "./threatSets";

const downloadBlob = (blob, filename) => {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
};

export const SAVE_PAUSE_MS = 600;
const RETRY_MS = [5000, 10000, 30000];
const retryAfter = (tries) => RETRY_MS[Math.min(tries, RETRY_MS.length - 1)];

const openListKey = (userId) => `ezpz.openThreatSets.${userId}`;
const readOpenList = (userId) => {
  try {
    const ids = JSON.parse(localStorage.getItem(openListKey(userId)));
    return Array.isArray(ids) ? ids.filter((id) => Number.isInteger(id)) : [];
  } catch {
    return [];
  }
};
const writeOpenList = (userId, ids) => {
  try {
    localStorage.setItem(openListKey(userId), JSON.stringify(ids));
  } catch {
    // Not remembered in this browser: the sets are in the Library all the same.
  }
};

const newUuid = () =>
  typeof crypto !== "undefined" && crypto.randomUUID ? crypto.randomUUID() : `${Date.now().toString(16)}-${Math.random().toString(16).slice(2)}`;

let localSeq = 0;
const newLocalId = () => `threat-set-${Date.now()}-${localSeq++}`;

const newSet = (name, threats = []) => ({
  id: newLocalId(),
  savedId: null,
  clientUuid: newUuid(),
  revision: null,
  name,
  threats,
  unreadable: [],
  version: undefined,
  extras: {},
});

const setFromRecord = (record, localId, usedIds) => ({
  id: localId ?? newLocalId(),
  savedId: record.id,
  clientUuid: record.client_uuid,
  revision: record.revision,
  name: record.name,
  ...readDocument(record.data, usedIds),
});

const idsOf = (sets, except) => new Set(sets.filter((set) => set.id !== except).flatMap((set) => set.threats.map((t) => t.id)));

/**
 * The threats on the map, kept in named sets the Library holds (`/api/threatsets`). Nothing is pressed to save:
 * every threat is in a set from the moment it is made, a set is written when it has been still for a moment, and
 * the sets open when the person left come back when they sign in again. An account without cloud save keeps them
 * in this tab alone. Also manages the add/edit dialog and fetching terrain masks from the backend's viewshed.
 */
export const useThreats = ({ userId = null, canSave = false, notify = () => {} } = {}) => {
  const [sets, setSets] = useState([]);
  const [activeId, setActiveId] = useState(null);
  // How each set's last save went, apart from the set so that a save changes no document.
  const [saveStates, setSaveStates] = useState({});
  const [savedSets, setSavedSets] = useState([]);
  const [isLoadingSaved, setIsLoadingSaved] = useState(false);
  const [savedError, setSavedError] = useState(null);
  // The threat currently open in the dialog: { threat, isNew }.
  const [editingThreat, setEditingThreat] = useState(null);

  // While a Mission Pack is open these are its sets (usePackThreats keeps them in step), in place of the person's own,
  // which stay as they are and go on saving. null: no pack is open.
  const [packSets, setPackSets] = useState(null);
  const shown = packSets ?? sets;
  const threats = useMemo(() => shown.flatMap((set) => set.threats), [shown]);
  const packSetsRef = useRef(packSets);
  packSetsRef.current = packSets;
  const inPackNow = () => packSetsRef.current !== null;
  const changeSets = (pack, change) => (pack ? setPackSets((prev) => (prev === null ? prev : change(prev))) : setSets(change));

  const setsRef = useRef(sets);
  setsRef.current = sets;
  const canSaveRef = useRef(canSave);
  canSaveRef.current = canSave;
  const notifyRef = useRef(notify);
  notifyRef.current = notify;
  const savedRef = useRef(savedSets);
  savedRef.current = savedSets;

  // One account's saving, thrown away whole when the account changes, so an answer that comes later lands nowhere.
  const session = useRef({ userId: undefined, runtime: new Map(), seen: new Map(), restored: false, pending: [], restoreTimer: null });
  const runtimeOf = (id) => {
    const { runtime } = session.current;
    if (!runtime.has(id)) runtime.set(id, { timer: null, inFlight: null, again: false, baseline: null, name: null, tries: 0, stopped: false });
    return runtime.get(id);
  };
  const stateIs = (id, state) => setSaveStates((prev) => (prev[id] === state ? prev : { ...prev, [id]: state }));
  const forget = (id) => {
    const r = session.current.runtime.get(id);
    if (r) clearTimeout(r.timer);
    session.current.runtime.delete(id);
    session.current.seen.delete(id);
    setSaveStates((prev) => {
      if (!(id in prev)) return prev;
      const { [id]: gone, ...rest } = prev;
      return rest;
    });
  };
  const noteClean = (set) => {
    const r = runtimeOf(set.id);
    r.baseline = JSON.stringify(documentOf(set));
    r.name = set.name;
  };
  const isClean = (set) => {
    const r = runtimeOf(set.id);
    return !r.inFlight && r.baseline === JSON.stringify(documentOf(set)) && r.name === set.name;
  };

  const patchThreat = (id, patch, pack = inPackNow()) =>
    changeSets(pack, (prev) => prev.map((set) => (set.threats.some((t) => t.id === id) ? { ...set, threats: set.threats.map((t) => (t.id === id ? { ...t, ...patch } : t)) } : set)));

  /** Recomputes a threat's terrain mask from the backend. The answer lands where it was asked for: the pack's sets, or the person's own. */
  const fetchMask = async (threat, pack = inPackNow()) => {
    patchThreat(threat.id, { maskLoading: true, maskError: null }, pack);
    const subject = maskSubject(threat);
    const stillAsked = (current) => canon(maskSubject(current)) === canon(subject);
    const land = (patch) =>
      changeSets(pack, (prev) =>
        prev.map((set) =>
          set.threats.some((t) => t.id === threat.id && stillAsked(t))
            ? { ...set, threats: set.threats.map((t) => (t.id === threat.id && stillAsked(t) ? { ...t, ...patch } : t)) }
            : set,
        ),
      );
    try {
      const res = await api.post("/threat-mask", { lat: threat.lat, lon: threat.lon, radars: subject.radars });
      land({ mask: { ...res.data, for: subject }, maskLoading: false });
    } catch (err) {
      land({ maskLoading: false, maskError: err.response?.data?.error || err.message });
    }
  };

  const maskWhatLacksOne = (list) => list.filter((t) => wantsMask(t) && !maskStillFits(t, t.mask)).forEach((t) => fetchMask(t, false));

  /** A pack's threats have no mask from the pack; each person's are worked out here, once for each place and radars. */
  const fetchMissingPackMasks = (list) => list.filter((t) => wantsMask(t) && !maskStillFits(t, t.mask) && !t.maskLoading && !t.maskError).forEach((t) => fetchMask(t, true));

  // --- Saving --------------------------------------------------------------------------------------

  const adoptSaved = (id, answer) =>
    setSets((prev) => prev.map((set) => (set.id === id ? { ...set, savedId: answer.id, revision: answer.revision, clientUuid: answer.client_uuid ?? set.clientUuid } : set)));

  const keepBothAfterConflict = (set, server) => {
    const used = idsOf(setsRef.current, set.id);
    const theirs = setFromRecord(server, set.id, used);
    const mine = newSet(conflictName(set.name), set.threats.map((t) => ({ ...t, id: newThreatId() })));
    noteClean(theirs);
    setSets((prev) => prev.flatMap((s) => (s.id === set.id ? [theirs, mine] : [s])));
    notifyRef.current({ tone: "warn", message: `“${set.name}” was changed elsewhere. Yours is kept beside it as “${mine.name}”.` });
    maskWhatLacksOne(theirs.threats);
  };

  const saveSet = async (id) => {
    const here = session.current;
    if (here.userId == null || !canSaveRef.current) return;
    const r = runtimeOf(id);
    if (r.inFlight) {
      r.again = true;
      await r.inFlight;
      return;
    }
    clearTimeout(r.timer);
    r.timer = null;
    const set = setsRef.current.find((s) => s.id === id);
    if (!set || r.stopped) return;
    // An empty set nobody has saved is no set yet: the panel would otherwise leave an empty item in the Library.
    if (!set.savedId && set.threats.length === 0) return;
    const data = documentOf(set);
    const body = JSON.stringify(data);
    if (set.savedId && body === r.baseline && set.name === r.name) {
      stateIs(id, "saved");
      return;
    }
    const send = async () => {
      stateIs(id, "saving");
      try {
        const res = set.savedId
          ? await api.put(`/threatsets/${set.savedId}`, { name: set.name, data }, { headers: { "If-Match": `"${set.revision}"` } })
          : await api.post("/threatsets", { name: set.name, data, client_uuid: set.clientUuid });
        if (session.current !== here) return;
        r.baseline = body;
        r.name = set.name;
        r.tries = 0;
        adoptSaved(id, res.data);
        setsRef.current = setsRef.current.map((s) => (s.id === id ? { ...s, savedId: res.data.id, revision: res.data.revision } : s));
        stateIs(id, "saved");
      } catch (err) {
        if (session.current !== here) return;
        const status = err.response?.status;
        const answer = err.response?.data;
        if (status === 409 && answer?.server) {
          r.again = false;
          stateIs(id, "saved");
          if (sameApartFromMasks(answer.server.data, data) && answer.server.name === set.name) {
            setsRef.current = setsRef.current.map((s) => (s.id === id ? { ...s, revision: answer.server.revision } : s));
            setSets((prev) => prev.map((s) => (s.id === id ? { ...s, revision: answer.server.revision } : s)));
            r.again = true;
          } else {
            keepBothAfterConflict(set, answer.server);
          }
        } else if (status === 404) {
          // Deleted elsewhere while it was open here: the work is kept under a new identity.
          const fresh = { savedId: null, revision: null, clientUuid: newUuid() };
          setsRef.current = setsRef.current.map((s) => (s.id === id ? { ...s, ...fresh } : s));
          setSets((prev) => prev.map((s) => (s.id === id ? { ...s, ...fresh } : s)));
          r.again = true;
        } else if (status === 403 && answer?.code === "feature_disabled") {
          r.stopped = true;
          stateIs(id, "off");
        } else if (status === 413) {
          stateIs(id, "too_large");
        } else if (status === undefined || status >= 500 || status === 429) {
          stateIs(id, "failed");
          clearTimeout(r.timer);
          r.timer = setTimeout(() => saveSet(id), retryAfter(r.tries));
          r.tries += 1;
        } else {
          stateIs(id, "failed");
        }
      }
    };
    r.inFlight = send();
    await r.inFlight;
    r.inFlight = null;
    if (r.again && session.current === here) {
      r.again = false;
      await saveSet(id);
    }
  };

  // A set saves once it has been still for a moment. A new instance is only a reason to look: saveSet asks whether
  // the document changed, so a mask being worked out, or the answer to a save, sends nothing.
  useEffect(() => {
    if (session.current.userId == null || !canSave) return;
    const { seen } = session.current;
    sets.forEach((set) => {
      if (seen.get(set.id) === set) return;
      seen.set(set.id, set);
      const r = runtimeOf(set.id);
      if (r.inFlight) {
        r.again = true;
        return;
      }
      clearTimeout(r.timer);
      r.timer = setTimeout(() => saveSet(set.id), SAVE_PAUSE_MS);
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sets, canSave]);

  /** Writes every open set now: as the tab goes to the back, and before signing out. */
  const flush = () => Promise.allSettled(setsRef.current.map((set) => saveSet(set.id)));

  // --- The Library ---------------------------------------------------------------------------------

  const fetchSavedSets = async () => {
    if (!canSaveRef.current) return [];
    const here = session.current;
    setIsLoadingSaved(true);
    try {
      const { data } = await api.get("/threatsets");
      if (session.current !== here) return [];
      setSavedSets(data);
      setSavedError(null);
      return data;
    } catch (err) {
      if (session.current === here) setSavedError(err);
      return null;
    } finally {
      if (session.current === here) setIsLoadingSaved(false);
    }
  };

  const addOpened = (records) => {
    if (records.length === 0) return [];
    const used = idsOf(setsRef.current);
    const opened = records
      .filter((record) => !setsRef.current.some((set) => set.savedId === record.id))
      .map((record) => {
        const set = setFromRecord(record, undefined, used);
        set.threats.forEach((t) => used.add(t.id));
        noteClean(set);
        return set;
      });
    setsRef.current = [...setsRef.current, ...opened];
    setSets((prev) => [...prev.filter((set) => !opened.some((o) => o.savedId === set.savedId && set.savedId)), ...opened]);
    opened.forEach((set) => maskWhatLacksOne(set.threats));
    return opened;
  };

  const openSavedSet = async (entry) => {
    const { data } = await api.get(`/threatsets/${entry.id}`);
    addOpened([data]);
  };

  const closeSet = async (id) => {
    await saveSet(id);
    const set = setsRef.current.find((s) => s.id === id);
    if (!set) return true;
    if (canSaveRef.current && set.threats.length > 0 && !isClean(set)) {
      notifyRef.current({ tone: "error", message: `“${set.name}” is not saved yet, so it stays open. Check the connection and try again.` });
      return false;
    }
    forget(id);
    setsRef.current = setsRef.current.filter((s) => s.id !== id);
    setSets((prev) => prev.filter((s) => s.id !== id));
    setActiveId((current) => (current === id ? null : current));
    return true;
  };

  const renameSet = (id, name) => {
    const clean = Array.from(name.trim().toUpperCase()).slice(0, 100).join("");
    if (!clean) return;
    setSets((prev) => prev.map((set) => (set.id === id ? { ...set, name: clean } : set)));
  };

  const deleteSavedSet = async (entry) => {
    const open = setsRef.current.find((set) => set.savedId === entry.id);
    if (open) await runtimeOf(open.id).inFlight;
    await api.delete(`/threatsets/${entry.id}`);
    if (open) {
      forget(open.id);
      setsRef.current = setsRef.current.filter((s) => s.id !== open.id);
      setSets((prev) => prev.filter((s) => s.id !== open.id));
    }
    setSavedSets((prev) => prev.filter((saved) => saved.id !== entry.id));
  };

  /** Brings the open sets up to what the Library holds: a rename there, or a change made on another device. */
  const pull = async () => {
    if (!canSaveRef.current || session.current.userId == null) return;
    const here = session.current;
    if (here.restored && here.pending.length > 0) {
      await restore(here, here.pending);
      if (session.current !== here) return;
    }
    // Only what was saved before the list was asked for can be missing from it because it went.
    const savedBefore = new Set(setsRef.current.map((set) => set.savedId).filter(Boolean));
    const entries = await fetchSavedSets();
    if (!entries || session.current !== here) return;
    const byId = new Map(entries.map((entry) => [entry.id, entry]));
    for (const set of setsRef.current) {
      if (!set.savedId || !isClean(set)) continue;
      const entry = byId.get(set.savedId);
      if (!entry && !savedBefore.has(set.savedId)) continue;
      if (!entry) {
        forget(set.id);
        setsRef.current = setsRef.current.filter((s) => s.id !== set.id);
        setSets((prev) => prev.filter((s) => s.id !== set.id));
      } else if (entry.revision > set.revision) {
        try {
          const { data } = await api.get(`/threatsets/${set.savedId}`);
          if (session.current !== here) return;
          const current = setsRef.current.find((s) => s.id === set.id);
          if (!current || !isClean(current)) continue;
          const replaced = setFromRecord(data, set.id, idsOf(setsRef.current, set.id));
          noteClean(replaced);
          setsRef.current = setsRef.current.map((s) => (s.id === set.id ? replaced : s));
          setSets((prev) => prev.map((s) => (s.id === set.id ? replaced : s)));
          maskWhatLacksOne(replaced.threats);
        } catch {
          // Asked again the next time the tab comes to the front.
        }
      }
    }
  };

  const restore = async (here, ids) => {
    const answers = await Promise.all(
      ids.map(async (id) => {
        try {
          return { id, record: (await api.get(`/threatsets/${id}`)).data };
        } catch (err) {
          return { id, gone: err.response?.status === 404 };
        }
      }),
    );
    if (session.current !== here) return;
    addOpened(answers.filter((a) => a.record).map((a) => a.record));
    here.pending = answers.filter((a) => !a.record && !a.gone).map((a) => a.id);
    here.restored = true;
    writeOpenList(here.userId, [...setsRef.current.map((set) => set.savedId).filter(Boolean), ...here.pending]);
  };

  // The account decides what is open. Another account must never find this one's threats, on the map or in a timer.
  useEffect(() => {
    if (session.current.userId !== userId) {
      session.current.runtime.forEach((r) => clearTimeout(r.timer));
      clearTimeout(session.current.restoreTimer);
      session.current = { userId, runtime: new Map(), seen: new Map(), restored: false, pending: [], restoreTimer: null };
      setsRef.current = [];
      setSets([]);
      setActiveId(null);
      setSaveStates({});
      setSavedSets([]);
      setSavedError(null);
      setEditingThreat(null);
    }
    const here = session.current;
    if (userId == null || !canSave || here.restored) return undefined;
    fetchSavedSets();
    let tries = 0;
    const attempt = async (ids) => {
      await restore(here, ids);
      if (session.current === here && here.pending.length > 0 && tries < RETRY_MS.length) {
        here.restoreTimer = setTimeout(() => attempt(here.pending), RETRY_MS[tries]);
        tries += 1;
      }
    };
    attempt(readOpenList(userId));
    return undefined;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [userId, canSave]);

  // Which sets are open is remembered once the last ones are back; before that it would forget them.
  useEffect(() => {
    const here = session.current;
    if (here.userId == null || !here.restored || !canSave) return;
    writeOpenList(here.userId, [...sets.map((set) => set.savedId).filter(Boolean), ...here.pending]);
  }, [sets, canSave]);

  const latest = useRef({});
  latest.current = { flush, pull };
  useEffect(() => {
    const onVisibility = () => (document.visibilityState === "hidden" ? latest.current.flush() : latest.current.pull());
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      document.removeEventListener("visibilitychange", onVisibility);
      session.current.runtime.forEach((r) => clearTimeout(r.timer));
      clearTimeout(session.current.restoreTimer);
    };
  }, []);

  // --- Threats -------------------------------------------------------------------------------------

  /** Opens the dialog for a brand-new threat at a location. */
  const beginAddThreat = (lat, lon) => {
    const n = threats.length + 1;
    setEditingThreat({ threat: makeThreat(lat, lon, { name: `Threat ${n}` }), isNew: true });
  };

  const beginEditThreat = (id) => {
    const threat = threats.find((t) => t.id === id);
    if (threat) setEditingThreat({ threat, isNew: false });
  };

  const cancelEdit = () => setEditingThreat(null);

  const freeName = (name) => nextFreeName(name, [...savedRef.current, ...setsRef.current]);

  /**
   * Commits the dialog: adds or updates the threat, then (re)computes its mask. In a pack with no set of threats
   * open, the first one makes an item of the pack (`createSet`), which everyone sees.
   */
  const saveThreat = (threat, { createSet } = {}) => {
    const pack = inPackNow();
    if (pack && packSetsRef.current.length === 0) {
      setEditingThreat(null);
      createSet?.(threat);
      return;
    }
    const fresh = newSet(freeName(FIRST_SET_NAME), [threat]);
    changeSets(pack, (prev) => {
      if (prev.some((set) => set.threats.some((t) => t.id === threat.id))) {
        return prev.map((set) => (set.threats.some((t) => t.id === threat.id) ? { ...set, threats: set.threats.map((t) => (t.id === threat.id ? threat : t)) } : set));
      }
      if (prev.length === 0) return [fresh];
      const into = prev.some((set) => set.id === activeId) ? activeId : prev[prev.length - 1].id;
      return prev.map((set) => (set.id === into ? { ...set, threats: [...set.threats, threat] } : set));
    });
    setEditingThreat(null);
    // Mask fetch reads the just-saved threat directly (state update is async).
    fetchMask(threat, pack);
  };

  const removeThreat = (id) => changeSets(inPackNow(), (prev) => prev.map((set) => (set.threats.some((t) => t.id === id) ? { ...set, threats: set.threats.filter((t) => t.id !== id) } : set)));

  /** Takes every threat off the map and out of its set; the sets stay, empty. */
  const removeAllThreats = () => changeSets(inPackNow(), (prev) => prev.map((set) => (set.threats.length ? { ...set, threats: [] } : set)));

  const toggleVisibility = (id) =>
    changeSets(inPackNow(), (prev) => prev.map((set) => (set.threats.some((t) => t.id === id) ? { ...set, threats: set.threats.map((t) => (t.id === id ? { ...t, visible: !t.visible } : t)) } : set)));

  /** Drag-move a threat marker: reposition and recompute its mask. */
  const moveThreat = (id, lat, lon) => {
    const current = threats.find((t) => t.id === id);
    if (!current) return;
    const moved = { ...current, lat, lon };
    patchThreat(id, { lat, lon });
    fetchMask(moved);
  };

  /**
   * Builds a .ths from the current threats and downloads it. Called alongside a
   * mission (.msnx) export so the two files travel together into AMPS.
   */
  const exportThsFile = async (baseName) => {
    if (threats.length === 0) return;
    const name = `${(baseName || "threats").replace(/\.(msnx|ths)$/i, "")}.ths`;
    const res = await api.post(
      "/threats-ths",
      { fileName: name, threats: threats.map(threatToPayload) },
      { responseType: "blob" },
    );
    downloadBlob(res.data, name);
  };

  /**
   * Builds a KMZ overlay (vector marker + range rings + terrain-mask polygons)
   * and downloads it — the format ForeFlight, ATAK, and Aero App all import.
   */
  const exportKmzFile = async (baseName) => {
    if (threats.length === 0) return;
    const name = `${(baseName || "threats").replace(/\.(msnx|kmz)$/i, "")}.kmz`;
    const res = await api.post(
      "/threats-kmz",
      { fileName: name, threats: threats.map(threatToPayload) },
      { responseType: "blob" },
    );
    downloadBlob(res.data, name);
  };

  /** Imports a .ths as a new set named for it, and computes each threat's mask. */
  const importThsFile = async (file, { name } = {}) => {
    const parsed = await parseThsFile(file);
    const set = newSet(freeName(name || setNameFromFile(file.name)), parsed);
    setsRef.current = [...setsRef.current, set];
    setSets((prev) => [...prev, set]);
    for (const t of parsed) fetchMask(t, false);
    return parsed.length;
  };

  const openSavedIds = useMemo(() => new Set(sets.map((set) => set.savedId).filter(Boolean)), [sets]);

  return {
    threats,
    sets: shown,
    activeId: shown.some((set) => set.id === activeId) ? activeId : (shown[shown.length - 1]?.id ?? null),
    packSets,
    setPackSets,
    fetchMissingPackMasks,
    saveStates,
    canSave,
    savedSets,
    openSavedIds,
    isLoadingSaved,
    savedError,
    editingThreat,
    beginAddThreat,
    beginEditThreat,
    cancelEdit,
    saveThreat,
    removeThreat,
    removeAllThreats,
    toggleVisibility,
    moveThreat,
    fetchMask,
    importThsFile,
    exportThsFile,
    exportKmzFile,
    setActiveSet: setActiveId,
    renameSet,
    closeSet,
    fetchSavedSets,
    openSavedSet,
    deleteSavedSet,
    pull,
    flush,
  };
};
