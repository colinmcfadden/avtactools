import { canon, DOCUMENT_VERSION, maskStillFits, maskSubject, readableThreat } from "../threats/threatSets";
import { sameData } from "./packDiff";
import { newItemOp, packItemId } from "./packSentences";

/*
 * A set of threats as a mission pack holds it: `{version: 1, threats: [...]}`, the Library's document, each threat
 * addressed by its id. Pure, so contracts/fixtures/packs/threats.json can hold the native apps to it.
 *
 * Whether a threat is hidden, and its terrain mask, are each person's own and never sent. A mask is a picture
 * worked out from the threat, and every operation stays in the pack's log for good: a pack would keep each one for
 * ever, for a thing any member can work out again. One that came with a copy from a Library is drawn while it fits.
 */

const OWN = ["visible", "mask", "maskLoading", "maskError"];

/** A threat as everyone in the pack has it. */
export const sharedThreat = (threat) => {
  if (!threat || typeof threat !== "object" || Array.isArray(threat)) return threat;
  const kept = { ...threat };
  OWN.forEach((key) => delete kept[key]);
  return kept;
};

/** The shared part of an item's data. A document with no list of threats is left without one. */
export const sharedThreatData = (data) => {
  if (!data || typeof data !== "object" || Array.isArray(data)) return data;
  return Array.isArray(data.threats) ? { ...data, threats: data.threats.map(sharedThreat) } : { ...data };
};

const usableId = (id) => typeof id === "string" || (typeof id === "number" && Number.isFinite(id));

/**
 * An item's data read for the map. `previous` is the set as this person has it: what is their own (hidden, a mask
 * still being worked out for the same place and radars) comes from there. A threat this version cannot read, with no
 * id, or with an id the set has already is kept to be written back, not drawn: an edit finds its threat by id.
 */
export const threatSetFromItem = (data, previous) => {
  const { threats: list, version, ...extras } = data && typeof data === "object" && !Array.isArray(data) ? data : {};
  const before = new Map((previous?.threats ?? []).map((threat) => [threat.id, threat]));
  const seen = new Set();
  const threats = [];
  const unreadable = [];
  (Array.isArray(list) ? list : []).forEach((threat) => {
    if (!readableThreat(threat) || !usableId(threat.id) || seen.has(threat.id)) {
      unreadable.push(sharedThreat(threat));
      return;
    }
    seen.add(threat.id);
    const had = before.get(threat.id);
    const read = { ...sharedThreat(threat), visible: had?.visible ?? true };
    const sameSubject = Boolean(had) && canon(maskSubject(had)) === canon(maskSubject(read));
    let mask = null;
    if (maskStillFits(read, threat.mask)) ({ mask } = threat);
    else if (had && maskStillFits(read, had.mask)) ({ mask } = had);
    threats.push({ ...read, mask, maskLoading: sameSubject ? Boolean(had.maskLoading) : false, maskError: sameSubject ? had.maskError ?? null : null });
  });
  return { threats, unreadable, version: version ?? DOCUMENT_VERSION, extras };
};

/** What is compared and sent for a set open here: its shared part, in today's shape. */
export const threatSetDoc = (set) => ({
  ...set.extras,
  version: set.version ?? DOCUMENT_VERSION,
  threats: [...set.threats.map(sharedThreat), ...(set.unreadable ?? [])],
});

const byId = (list) => new Map((Array.isArray(list) ? list : []).filter((t) => t && typeof t === "object" && usableId(t.id)).map((t) => [t.id, t]));
const called = (threat) => (threat.name ? `the threat ${threat.name}` : "a threat");

/** One sentence for the pack's history about a change to a set of threats. */
export const describeThreatChange = (before, after, { name, actor }) => {
  const set = name || "the threats";
  const phrases = [];
  const was = byId(before?.threats);
  const now = byId(after?.threats);
  now.forEach((threat, id) => {
    const old = was.get(id);
    if (!old) phrases.push(`added ${called(threat)} to ${set}`);
    else if (old.lat !== threat.lat || old.lon !== threat.lon) phrases.push(`moved ${called(threat)}`);
    else if (!sameData(old, threat)) phrases.push(`changed ${called(threat)}`);
  });
  was.forEach((threat, id) => {
    if (!now.has(id)) phrases.push(`removed ${called(threat)} from ${set}`);
  });
  return `${actor || "Someone"} ${phrases[0] ?? `edited ${set}`}.`.slice(0, 300);
};

/** The operation that puts `threats` in the pack as a new set, named as newItemName says, its uuid "ts-" and newId(). */
export const newThreatSetOp = ({ name, threats, actor, newId, count = 0 }) =>
  newItemOp({
    kind: "threatset",
    item: packItemId("threatset", newId()),
    name,
    count,
    data: { version: DOCUMENT_VERSION, threats: threats.map(sharedThreat) },
    actor,
  });
