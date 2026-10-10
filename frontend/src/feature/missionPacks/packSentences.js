/*
 * The fixed sentences a client sends for a mission pack's history, and how a new item and a kept
 * version of one are named. Pure and without imports, so contracts/fixtures/packs/describe.json and
 * shared.json can hold the native apps to them (frontend/src/contracts/packSharedFixtures.test.js).
 * packActions.js re-exports what was there first.
 */

const PREFIX = { lz: "lz", route: "rt", pointset: "ps" };
const KIND_WORDS = { lz: "LZ/PZ", route: "route set", pointset: "point set" };

/** Who a person is in the history: their name, else their sign-in address, else "Someone". */
export const actorName = (user) => user?.name || user?.email || "Someone";

/** A new item's uuid in a pack: its kind's prefix (lz-, rt-, ps-) and an id chosen by the client. */
export const packItemId = (kind, id) => `${PREFIX[kind]}-${id}`;

const DEFAULT_NAMES = {
  lz: (count) => `LZ/PZ ${count + 1}`,
  route: (count) => `ROUTES ${count + 1}`,
  pointset: () => "LOCAL POINTS",
};

/**
 * What a new item is called: the name given, trimmed, or when that is blank its kind's default
 * (`count` is how many items of that kind the pack has: "LZ/PZ 3", "ROUTES 2", "LOCAL POINTS").
 */
export const newItemName = (kind, name, count) => (name ?? "").trim() || DEFAULT_NAMES[kind](count);

/** The history's sentence for an item made in the pack (a point set's says how many points it has). */
export const createSummary = (actor, kind, name, pointCount) => {
  const who = actor || "Someone";
  if (kind === "lz") return `${who} added the LZ/PZ "${name}".`;
  if (kind === "route") return `${who} added the route set "${name}".`;
  return `${who} added the point set "${name}" (${pointCount.toLocaleString("en-US")} point${pointCount === 1 ? "" : "s"}).`;
};

/** The operation that makes a new item `item` (its uuid) of `kind`, named as newItemName says, with its sentence. */
export const newItemOp = ({ kind, item, name, count, data, actor }) => {
  const label = newItemName(kind, name, count);
  return {
    type: "item.create", item, kind, name: label, data,
    summary: createSummary(actor, kind, label, kind === "pointset" ? data.length : undefined),
  };
};

/** The history's sentence for an item renamed from `from` to `to`. */
export const renameSummary = (actor, from, to) => `${actor || "Someone"} renamed "${from}" to "${to}".`;

/** The history's sentence for an item removed. */
export const deleteSummary = (actor, name) => `${actor || "Someone"} removed "${name}".`;

/** The history's sentence for a library record copied in; one with no name is called by its kind. */
export const copySummary = (actor, kind, recordName) =>
  `${actor || "Someone"} added "${recordName || KIND_WORDS[kind].toUpperCase()}" (a copy from their library).`;

/** The history's sentence for an item updated from the original in the library of whoever copied it in. */
export const updateFromOriginalSummary = (actor, name) => `${actor || "Someone"} updated "${name}" from their library.`;

/** The operation that removes an item, with its sentence for the history. Pass it to useMissionPack's edit(). */
export const deleteItemOp = ({ uuid, name, actor }) => ({
  type: "item.delete",
  item: uuid,
  summary: deleteSummary(actor, name),
});

/** The name a kept version of an item is saved under: whose edits they were is clear from it. */
export const myEditsName = (name) => `${name} (my edits)`.slice(0, 100);
