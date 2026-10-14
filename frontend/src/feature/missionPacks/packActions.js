import api from "../auth/api";
import * as packApi from "./packApi";
import { copySummary, packItemId } from "./packSentences";

/*
 * What a person does to a pack's items besides editing them, and keeping what a pack would not take.
 */

const randomId = () =>
  typeof crypto !== "undefined" && crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(16).slice(2)}`;

// The fixed sentences a client sends for the pack's history, and how a new item is named, live in
// packSentences.js (pure, with no imports); droppedVersions lives with the session it reads.
export {
  copySummary, deleteItemOp, deleteSummary, myEditsName, packItemId, renameSummary,
} from "./packSentences";
export { droppedVersions } from "./packSession";

/**
 * Copies one of the person's library records into the pack (`kind`: lz, route or pointset; `record`:
 * the library entry, with its `id`). The new item's uuid is chosen here, so asking again after a lost
 * answer returns the same copy. Resolves to the server's answer ({ item, event }).
 */
export const copyFromLibrary = (packUuid, { kind, record, actor, newId = randomId }) => {
  const summary = copySummary(actor, kind, record.name);
  return packApi.copyIntoPack(packUuid, {
    source: { kind, id: record.id },
    item: packItemId(kind, newId()),
    summary,
  });
};

/**
 * A version of an item in the form the library keeps each kind: an LZ/PZ's document and a set's
 * points as they are, a route set's sketched routes as { version: 1, routes } (any other field of
 * the set, and any other version, is not kept; routes that are not a list are none).
 */
export const libraryData = (kind, data) => {
  if (kind === "lz" || kind === "pointset") return data;
  return { version: 1, routes: Array.isArray(data?.routes) ? data.routes : [] };
};

/**
 * Saves a version of an item to the person's own library, as a new record (`name`), in the form the
 * library keeps each kind (libraryData). Resolves to the new record.
 */
export const saveVersionToLibrary = async ({ kind, name, data }) => {
  if (kind === "lz") return (await api.post("/lz", { name, lz_data: libraryData(kind, data) })).data;
  if (kind === "pointset") return (await api.post("/pointsets", { name, points: libraryData(kind, data) })).data;
  const form = new FormData();
  form.append("name", name);
  form.append("kind", "sketch");
  form.append("route_data", JSON.stringify(libraryData(kind, data)));
  return (await api.post("/routes", form)).data;
};
