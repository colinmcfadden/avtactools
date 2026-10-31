import { useCallback, useMemo } from "react";
import { nextRouteColor } from "../msnxImport/colorPalette";
import { sameData } from "./packDiff";
import { packLocalId, packLocalRef } from "./packRef";
import { newItemOp, packItemId } from "./packSentences";
import { usePackItemSync } from "./usePackItemSync";

/*
 * The point sets of an open mission pack, on the map beside this person's own (useLocalPoints),
 * kept in step with the pack; usePackItemSync has the rules. A set's data is the list of points the
 * library saves; its colour and whether it is shown are each person's own, as they are for their own
 * sets. Points are not edited on the web, so what changes here is a set's name; what arrives is
 * whatever anyone else did, a set someone removed leaving the map.
 */

const copy = (value) => JSON.parse(JSON.stringify(value ?? []));
const randomId = () =>
  typeof crypto !== "undefined" && crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
const ownSet = (packUuid, item, previous) => ({
  id: packLocalId(packUuid, item.uuid),
  name: item.name,
  color: previous?.color ?? nextRouteColor(),
  visible: previous?.visible ?? true,
  savedId: null,
  points: copy(item.data),
});

/** One sentence for the pack's history about a change to a point set. */
export const describePointsChange = (before, after, { name, actor }) => {
  const set = name || "the points";
  const was = new Map((before ?? []).map((p) => [p.id, p]));
  const now = new Map((after ?? []).map((p) => [p.id, p]));
  const added = [...now.keys()].filter((id) => !was.has(id)).length;
  const removed = [...was.keys()].filter((id) => !now.has(id)).length;
  const changed = [...now.keys()].filter((id) => was.has(id) && !sameData(was.get(id), now.get(id)));
  let what = `edited ${set}`;
  if (added) what = `added ${added} point${added === 1 ? "" : "s"} to ${set}`;
  else if (removed) what = `removed ${removed} point${removed === 1 ? "" : "s"} from ${set}`;
  else if (changed.length === 1) what = `changed ${now.get(changed[0]).name || "a point"} in ${set}`;
  else if (changed.length) what = `changed ${changed.length} points in ${set}`;
  return `${actor || "Someone"} ${what}.`.slice(0, 300);
};

/** Points ready to go in a pack: a list, each with an id no other has (made from its place if not). */
export const pointsForPack = (points) => {
  const seen = new Set();
  return copy(points).map((point, index) => {
    let id = point?.id;
    if (typeof id !== "string" && !(typeof id === "number" && Number.isFinite(id))) id = `pt-${index}`;
    while (seen.has(`${typeof id}:${id}`)) id = `${id}-${index}`;
    seen.add(`${typeof id}:${id}`);
    return { ...point, id };
  });
};

/**
 * The operation that puts `points` in the pack as a new set (pointsForPack's list, named as
 * newItemName says), its uuid "ps-" and newId(). A set that comes to no points is refused, as
 * `empty_point_set`, before an id is taken: the pack's server would take it, but a library copy of
 * it could never be saved. { refused: null, op } or { refused: "empty_point_set" }.
 */
export const newPointSetOp = ({ name, points, actor, newId }) => {
  const list = pointsForPack(points);
  if (list.length === 0) return { refused: "empty_point_set" };
  return { refused: null, op: newItemOp({ kind: "pointset", item: packItemId("pointset", newId()), name, count: 0, data: list, actor }) };
};

export const usePackPoints = ({
  packUuid,
  items,
  edit,
  readOnly = false,
  actor,
  pointSets,
  setPointSets,
  onGone,
  onRefused,
  newId = randomId,
  delayMs = 400,
}) => {
  const ours = useCallback(
    (localId) => {
      const ref = packLocalRef(localId);
      return ref && ref.pack === packUuid ? ref.item : null;
    },
    [packUuid],
  );

  const local = useMemo(() => {
    const map = new Map();
    (pointSets ?? []).forEach((set) => {
      const uuid = ours(set.id);
      if (uuid) map.set(uuid, { doc: copy(set.points), name: set.name ?? "" });
    });
    return map;
  }, [pointSets, ours]);

  const currentShape = useCallback((item) => copy(item.data), []);
  const applyTheirs = useCallback(
    (uuid, item) => {
      const localId = packLocalId(packUuid, uuid);
      setPointSets((sets) => sets.map((set) => (set.id === localId ? ownSet(packUuid, item, set) : set)));
    },
    [packUuid, setPointSets],
  );
  const removeLocal = useCallback(
    (uuid) => {
      const localId = packLocalId(packUuid, uuid);
      setPointSets((sets) => sets.filter((set) => set.id !== localId));
    },
    [packUuid, setPointSets],
  );

  const sync = usePackItemSync({
    kind: "pointset",
    items,
    local,
    edit,
    readOnly,
    actor,
    currentShape,
    shared: copy,
    applyTheirs,
    removeLocal,
    describe: describePointsChange,
    onGone,
    onRefused,
    delayMs,
  });

  /** Shows a pack's point set on the map. */
  const openItem = useCallback(
    (uuid) => {
      const item = sync.items.get(uuid);
      if (!item) return false;
      if (!local.has(uuid)) setPointSets((sets) => [...sets, ownSet(packUuid, item)]);
      return true;
    },
    [sync.items, local, setPointSets, packUuid],
  );

  /** Takes it off the map; it stays in the pack. */
  const closeItem = useCallback((uuid) => removeLocal(uuid), [removeLocal]);

  /**
   * Puts points in the pack as a new set (an .LPS import going to the pack), shown on the map.
   * Returns its uuid, or null if refused. A set with no points is refused here: the pack's server
   * would take it, but a library copy of it could never be saved.
   */
  const createItem = useCallback(
    (name, points) => {
      const made = newPointSetOp({ name, points, actor, newId });
      if (made.refused) {
        onRefused?.(made.refused, null);
        return null;
      }
      const { op } = made;
      const refused = edit([op]);
      if (refused) {
        onRefused?.(refused, null);
        return null;
      }
      setPointSets((sets) => [...sets, ownSet(packUuid, { uuid: op.item, name: op.name, data: op.data })]);
      return op.item;
    },
    [edit, actor, setPointSets, packUuid, onRefused, newId],
  );

  const renameSet = useCallback(
    (uuid, name) => {
      const localId = packLocalId(packUuid, uuid);
      setPointSets((sets) => sets.map((set) => (set.id === localId ? { ...set, name } : set)));
    },
    [packUuid, setPointSets],
  );

  return { openItem, closeItem, createItem, renameSet, flush: sync.flush, isPackSet: (localId) => Boolean(ours(localId)) };
};
