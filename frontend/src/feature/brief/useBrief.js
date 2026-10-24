import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { diffItem } from "../missionPacks/packDiff";
import { actorName } from "../missionPacks/packSentences";
import { compileBrief } from "./compile";
import { briefFormat } from "./templates";
import { createBrief, getBrief, updateBrief, weatherAt } from "./briefApi";
import { localClock, newBriefDocument, planFromPack, planFromSession, planSummary } from "./briefPlan";

/*
 * The open brief (docs/MISSION_BRIEF.md §3): the person's answers, where they are kept, and the brief compiled from them
 * and the plan as it is now.
 *
 *  - In a pack it is the pack's brief item (the first live one; made at the first change), open to every member in
 *    every state of the pack. A change goes as the pack's own operations, at once: the fields commit when the person
 *    stops typing, so a sentence is one change, and the pack's history reads a run of them as one line.
 *  - Outside a pack it is a record in the person's Library (/api/briefs), the one this browser opened last for this
 *    account, saved once the person has been still for a moment. A new one is made at the first change, under an
 *    identity chosen here, so a create whose answer was lost is the same record when sent again.
 *
 * The weather is asked for while the brief is open (`active`): the departure, the arrival and each stop, at the times
 * the routes give them, and compiled in with the time it was fetched.
 */

const SAVE_DELAY_MS = 800;
const DEFAULT_FORMAT = "amb";
const randomId = () => (window.crypto?.randomUUID ? window.crypto.randomUUID() : `${Date.now()}-${Math.random().toString(16).slice(2)}`);
const storageKey = (user) => `ezpz.brief.${user?.id ?? "anon"}`;
const remembered = (user) => {
  try {
    const value = JSON.parse(localStorage.getItem(storageKey(user)) || "null");
    return value && typeof value === "object" ? value : null;
  } catch {
    return null;
  }
};
const remember = (user, value) => {
  try {
    if (value) localStorage.setItem(storageKey(user), JSON.stringify(value));
    else localStorage.removeItem(storageKey(user));
  } catch {
    // Private windows refuse storage: the brief is then made again next time, which loses nothing on the server.
  }
};

const briefItemOf = (items) => (items ?? []).find((item) => item.kind === "brief" && !item.deleted && item.data) ?? null;

export const useBrief = ({
  enabled,
  active = false,
  user,
  pack = null,
  session = {},
  threats = [],
  aircraftProfiles = [],
  missionAircraft = null,
  toast,
}) => {
  const inPack = Boolean(pack);
  const actor = actorName(user);

  // -- The plan -----------------------------------------------------------------------------------------------------
  const plan = useMemo(
    () => (inPack ? planFromPack(pack.name, pack.items) : planFromSession(session.name, session.diagrams, session.routeSets)),
    [inPack, pack?.name, pack?.items, session.name, session.diagrams, session.routeSets],
  );

  // -- The document -------------------------------------------------------------------------------------------------
  const briefItem = inPack ? briefItemOf(pack.items) : null;
  const [libraryDoc, setLibraryDoc] = useState(null);
  const [packDraft, setPackDraft] = useState(null);
  const [saveState, setSaveState] = useState(null);
  const [opening, setOpening] = useState(false);
  const record = useRef(null);
  const saveTimer = useRef(null);
  const latestDoc = useRef(null);

  const doc = inPack ? briefItem?.data ?? packDraft : libraryDoc;
  latestDoc.current = doc;

  const loaded = useRef(false);
  const reads = useRef(0);
  // Another account signing in starts from nothing. Before the read below, so that read is the new account's (and on
  // mount clears nothing it has just begun); a read still out for the account before is not taken.
  useEffect(() => {
    reads.current += 1;
    loaded.current = false;
    record.current = null;
    setOpening(false);
    setLibraryDoc(null);
    setPackDraft(null);
  }, [user?.id]);

  // Outside a pack: the brief this browser opened last, read once the brief is first opened.
  useEffect(() => {
    if (!enabled || inPack || !active || loaded.current) return;
    loaded.current = true;
    const kept = remembered(user);
    record.current = kept;
    if (!kept?.id) return;
    const read = ++reads.current;
    setOpening(true);
    getBrief(kept.id)
      .then((body) => {
        if (read !== reads.current) return;
        if (body?.data && typeof body.data === "object") setLibraryDoc((current) => current ?? body.data);
      })
      .catch(() => {
        if (read !== reads.current) return;
        // Deleted elsewhere, or another account's: start again, under a new identity.
        record.current = null;
        remember(user, null);
      })
      .finally(() => {
        if (read === reads.current) setOpening(false);
      });
  }, [enabled, inPack, active, user]);
  // Whether the brief opened last is still being read: from the moment the brief is opened, not only once the request is
  // out, so nothing shows the blank brief in the meantime (the live sandtable waits for it).
  const loading = Boolean(enabled && !inPack && active && (opening || (!loaded.current && remembered(user)?.id)));

  const saveToLibrary = useCallback(async () => {
    saveTimer.current = null;
    const data = latestDoc.current;
    if (!data) return;
    setSaveState("saving");
    try {
      const name = (data.name || "MISSION BRIEF").slice(0, 100);
      if (record.current?.id) {
        await updateBrief(record.current.id, { name, data });
      } else {
        const clientUuid = record.current?.clientUuid ?? randomId();
        record.current = { clientUuid };
        const saved = await createBrief({ name, data, clientUuid });
        record.current = { id: saved.id, clientUuid };
        remember(user, record.current);
      }
      setSaveState("saved");
    } catch {
      setSaveState("error");
    }
  }, [user]);

  useEffect(() => () => clearTimeout(saveTimer.current), []);

  /** Changes the brief: `next` is the whole document, or a function of the one there is. */
  const setDoc = useCallback(
    (next) => {
      const current = latestDoc.current ?? newBriefDocument(DEFAULT_FORMAT, briefFormat(DEFAULT_FORMAT).template.version);
      const value = typeof next === "function" ? next(current) : next;
      if (!value || value === current) return;
      latestDoc.current = value;
      if (!inPack) {
        setLibraryDoc(value);
        setSaveState("unsaved");
        clearTimeout(saveTimer.current);
        saveTimer.current = setTimeout(saveToLibrary, SAVE_DELAY_MS);
        return;
      }
      const item = briefItemOf(pack.items);
      const name = item?.name ?? `${pack.name || "PACK"} BRIEF`.slice(0, 100);
      const summary = `${actor} changed the brief "${name}".`;
      const ops = item
        ? diffItem(item.uuid, item.data, value).map((op) => ({ ...op, summary }))
        : [{ type: "item.create", item: `br-${randomId()}`, kind: "brief", name, data: value, summary: `${actor} added the brief "${name}".` }];
      if (ops.length === 0) return;
      if (!item) setPackDraft(value);
      const refused = pack.edit(ops);
      if (refused) toast?.({ tone: "error", message: refused === "loading" ? "The pack is still opening: try again in a moment." : "The pack would not take that change to the brief." });
    },
    [inPack, pack, actor, saveToLibrary, toast],
  );

  /** Writes a change still waiting now (the setup closing). */
  const flush = useCallback(() => {
    if (saveTimer.current) {
      clearTimeout(saveTimer.current);
      saveToLibrary();
    }
  }, [saveToLibrary]);

  const formatId = doc?.template?.id && briefFormat(doc.template.id) ? doc.template.id : DEFAULT_FORMAT;
  const format = useMemo(() => briefFormat(formatId), [formatId]);

  // -- The weather, and the brief compiled ------------------------------------------------------------------------
  const [openedAt, setOpenedAt] = useState(() => Date.now());
  useEffect(() => {
    if (active) setOpenedAt(Date.now());
  }, [active]);
  // `key`: the places asked about last; `fetchedFor`: the places what is here was fetched for.
  const [weather, setWeather] = useState({ key: null, fetchedFor: null, loading: false, fetchedAt: null, departure: null, arrival: null, stops: {} });

  const extras = useMemo(
    () => ({ threats, aircraftProfiles, missionAircraft }),
    [threats, aircraftProfiles, missionAircraft],
  );
  const working = useMemo(() => doc ?? newBriefDocument(formatId, format.template.version), [doc, formatId, format]);

  // Compiled once without the weather to learn where and when to ask for it, then with it.
  const bare = useMemo(() => {
    const clock = localClock(openedAt);
    return compileBrief(format, working, { ...plan, ...extras }, { generatedAt: new Date(openedAt).toISOString(), utcOffsetMinutes: clock.utcOffsetMinutes });
  }, [format, working, plan, extras, openedAt]);
  const departureAt = bare.places.departure?.at ? Date.parse(bare.places.departure.at) : openedAt;
  const utcOffsetMinutes = localClock(departureAt).utcOffsetMinutes;
  const placesKey = JSON.stringify(bare.places);

  const refreshWeather = useCallback(async (places = bare.places, key = placesKey) => {
    setWeather((w) => ({ ...w, key, loading: true }));
    const ask = (place) => (place?.lat != null ? weatherAt(place) : Promise.resolve(null));
    const [departure, arrival, ...stops] = await Promise.all([ask(places.departure), ask(places.arrival), ...places.stops.map(ask)]);
    setWeather((w) => {
      if (w.key !== key) return w;
      // No answer at all (no connection): what was fetched for these places stays, with its age, as the weather tiles keep theirs.
      if (departure == null && arrival == null && stops.every((report) => report == null) && w.fetchedFor === key && w.fetchedAt != null) {
        return { ...w, loading: false };
      }
      return {
        key,
        fetchedFor: key,
        loading: false,
        fetchedAt: Date.now(),
        departure,
        arrival,
        stops: Object.fromEntries(places.stops.map((stop, i) => [stop.id, stops[i]]).filter(([, report]) => report)),
      };
    });
  }, [bare.places, placesKey]);

  useEffect(() => {
    if (!enabled || !active || weather.key === placesKey) return;
    refreshWeather(JSON.parse(placesKey), placesKey);
  }, [enabled, active, placesKey, weather.key, refreshWeather]);

  const model = useMemo(() => {
    const context = {
      generatedAt: new Date(openedAt).toISOString(),
      utcOffsetMinutes,
      weather: { departure: weather.departure, arrival: weather.arrival, stops: weather.stops },
      notams: weather.departure?.notams,
    };
    return compileBrief(format, working, { ...plan, ...extras }, context);
  }, [format, working, plan, extras, openedAt, utcOffsetMinutes, weather]);

  return {
    enabled: Boolean(enabled),
    inPack,
    readOnlyPack: Boolean(pack?.readOnly),
    plan,
    summary: planSummary(plan, threats.length),
    doc: working,
    hasDoc: Boolean(doc),
    setDoc,
    flush,
    format,
    model,
    saveState: inPack ? null : saveState,
    loading,
    weather: { loading: weather.loading, fetchedAt: weather.fetchedAt, refresh: () => refreshWeather() },
  };
};
