import api from "../auth/api";
import { applyPackOp } from "./packOps";
import * as packApi from "./packApi";

/*
 * What a person does to a pack's items besides editing them, and keeping what a pack would not take.
 */

const randomId = () =>
  typeof crypto !== "undefined" && crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(16).slice(2)}`;

const PREFIX = { lz: "lz", route: "rt", pointset: "ps" };
const KIND_WORDS = { lz: "LZ/PZ", route: "route set", pointset: "point set" };

/** The operation that removes an item, with its sentence for the history. Pass it to useMissionPack's edit(). */
export const deleteItemOp = ({ uuid, name, actor }) => ({
  type: "item.delete",
  item: uuid,
  summary: `${actor || "Someone"} removed "${name}".`,
});

/**
 * Copies one of the person's library records into the pack (`kind`: lz, route or pointset; `record`:
 * the library entry, with its `id`). The new item's uuid is chosen here, so asking again after a lost
 * answer returns the same copy. Resolves to the server's answer ({ item, event }).
 */
export const copyFromLibrary = (packUuid, { kind, record, actor, newId = randomId }) => {
  const name = record.name || KIND_WORDS[kind].toUpperCase();
  return packApi.copyIntoPack(packUuid, {
    source: { kind, id: record.id },
    item: `${PREFIX[kind]}-${newId()}`,
    summary: `${actor || "Someone"} added "${name}" (a copy from their library).`,
  });
};

/**
 * The edits this person made that the pack will never take (it was finished, or they were made a
 * viewer, or removed, while the edits were on their way: packSession's `dropped`), as their own
 * version of each item they touched: the pack's confirmed item with those edits applied in order.
 * What cannot apply any more (its target gone) is left out, as the pack would have left it.
 * [{ uuid, kind, name, data }], in the order the items were first touched.
 */
export const droppedVersions = (session) => {
  const versions = new Map();
  (session?.dropped ?? []).forEach(({ op }) => {
    if (!op?.item) return;
    if (!versions.has(op.item)) {
      const confirmed = session.confirmed?.[op.item];
      versions.set(op.item, { [op.item]: confirmed && !confirmed.deleted ? confirmed : null });
    }
    const items = versions.get(op.item);
    // An item made here and never taken starts from its own item.create.
    const result = applyPackOp(items[op.item] ? items : {}, op);
    if (result.status === "applied") versions.set(op.item, result.items);
  });
  return [...versions.entries()]
    .map(([uuid, items]) => ({ uuid, item: items[uuid] }))
    .filter(({ item }) => item && !item.deleted)
    .map(({ uuid, item }) => ({ uuid, kind: item.kind, name: item.name, data: item.data }));
};

/**
 * Saves a version of an item to the person's own library, as a new record (`name`), in the form the
 * library keeps each kind: an LZ/PZ's document, a route set's sketched routes, a set's points.
 * Resolves to the new record.
 */
export const saveVersionToLibrary = async ({ kind, name, data }) => {
  if (kind === "lz") return (await api.post("/lz", { name, lz_data: data })).data;
  if (kind === "pointset") return (await api.post("/pointsets", { name, points: data })).data;
  const form = new FormData();
  form.append("name", name);
  form.append("kind", "sketch");
  form.append("route_data", JSON.stringify({ version: 1, routes: Array.isArray(data?.routes) ? data.routes : [] }));
  return (await api.post("/routes", form)).data;
};

/** The name a kept version of an item is saved under: whose edits they were is clear from it. */
export const myEditsName = (name) => `${name} (my edits)`.slice(0, 100);
