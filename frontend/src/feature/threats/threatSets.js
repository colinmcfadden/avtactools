import { newThreatId, threatToPayload } from "./threatModel";

/*
 * A set of threats as the Library keeps it: `{version: 1, threats: [...]}` under /api/threatsets. Plain
 * functions, so the document's rules are tested without a browser or a server.
 *
 * A terrain mask is stored with its threat, but only with what it was worked out for (`for`): a mask that no
 * longer matches its threat's place or radars is never drawn or kept, and is worked out again.
 */

export const DOCUMENT_VERSION = 1;
export const FIRST_SET_NAME = "THREATS";
export const MAX_NAME = 100;
// What a set's masks may come to. The server takes 5 MB a set (the limit of a pack's item, which a set will
// become); past this a mask is left out and worked out again when the set is opened, so a large set still saves.
export const MASK_BUDGET = 4 * 1024 * 1024;

/** One text for equal values, whatever order their keys are in: the API answers with its keys sorted. */
export const canon = (value) => {
  if (Array.isArray(value)) return `[${value.map(canon).join(",")}]`;
  if (value && typeof value === "object") {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canon(value[key])}`).join(",")}}`;
  }
  return JSON.stringify(value) ?? "null";
};

/** What a terrain mask depends on. */
export const maskSubject = (threat) => ({ lat: threat.lat, lon: threat.lon, radars: threatToPayload(threat).radars });

export const maskStillFits = (threat, mask) => Boolean(mask?.for) && canon(mask.for) === canon(maskSubject(threat));

export const wantsMask = (threat) => (threat.radars ?? []).some((radar) => radar.showMask);

export const readableThreat = (threat) =>
  Boolean(threat) && typeof threat === "object" && typeof threat.lat === "number" && typeof threat.lon === "number" && Array.isArray(threat.radars)
  && threat.radars.every((radar) => radar && Array.isArray(radar.bands));

/**
 * A saved document read for the map. `usedIds` are the threat ids other open sets hold: a duplicate opened beside
 * its original has the same ones, and every edit finds its threat by id. A threat this version cannot read is
 * kept to be written back rather than lost by the next save.
 */
export const readDocument = (data, usedIds = new Set()) => {
  const { threats: list, version, ...extras } = data && typeof data === "object" ? data : {};
  const seen = new Set(usedIds);
  const threats = [];
  const unreadable = [];
  (Array.isArray(list) ? list : []).forEach((threat) => {
    if (!readableThreat(threat)) {
      unreadable.push(threat);
      return;
    }
    const id = threat.id && !seen.has(threat.id) ? threat.id : newThreatId();
    seen.add(id);
    const read = { ...threat, id, visible: threat.visible !== false, maskLoading: false, maskError: null };
    threats.push({ ...read, mask: maskStillFits(read, threat.mask) ? threat.mask : null });
  });
  return { threats, unreadable, version: version ?? DOCUMENT_VERSION, extras };
};

const stored = (threat, withMask) => {
  const { mask, maskLoading, maskError, ...kept } = threat;
  return withMask ? { ...kept, mask } : kept;
};

/** The document saved for a set: every threat, and each mask while the document stays within the budget. */
export const documentOf = (set) => {
  let room = MASK_BUDGET;
  const threats = set.threats.map((threat) => {
    if (!maskStillFits(threat, threat.mask)) return stored(threat, false);
    const size = JSON.stringify(threat.mask).length;
    if (size > room) return stored(threat, false);
    room -= size;
    return stored(threat, true);
  });
  return { ...set.extras, version: set.version ?? DOCUMENT_VERSION, threats: [...threats, ...(set.unreadable ?? [])] };
};

const withoutMasks = (data) => ({
  ...data,
  threats: (data?.threats ?? []).map((threat) => {
    if (!threat || typeof threat !== "object") return threat;
    const { mask, ...kept } = threat;
    return kept;
  }),
});

/** Two versions that differ only in their masks: not a conflict, since a mask is worked out, not planned. */
export const sameApartFromMasks = (a, b) => canon(withoutMasks(a)) === canon(withoutMasks(b));

const pad = (n) => String(n).padStart(2, "0");

/** The name this browser's version is kept under when another device changed the set meanwhile. */
export const conflictName = (name, now = new Date()) => {
  const tail = ` (from this browser, ${pad(now.getHours())}:${pad(now.getMinutes())})`;
  return `${Array.from(name).slice(0, Math.max(0, MAX_NAME - tail.length)).join("")}${tail}`;
};

/** A file's name as the set it becomes is called. */
export const setNameFromFile = (fileName) =>
  Array.from(fileName.replace(/\.ths$/i, "").trim().toUpperCase()).slice(0, MAX_NAME).join("") || FIRST_SET_NAME;
