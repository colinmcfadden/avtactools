import { useCallback, useEffect, useMemo, useRef } from "react";
import { packLocalId, packLocalRef } from "./packRef";
import { describeThreatChange, newThreatSetOp, sharedThreatData, threatSetDoc, threatSetFromItem } from "./packThreats";
import { usePackItemSync } from "./usePackItemSync";

/*
 * The sets of threats of an open mission pack, in place of this person's own while the pack is open (useThreats
 * holds them, as `packSets`), kept in step with the pack; usePackItemSync has the rules. Whether a threat is hidden
 * and its terrain mask stay here: nothing of them is sent (packThreats.js).
 */

const randomId = () =>
  typeof crypto !== "undefined" && crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(16).slice(2)}`;

const ownSet = (packUuid, item, previous) => ({
  id: packLocalId(packUuid, item.uuid),
  savedId: null,
  clientUuid: null,
  revision: null,
  name: item.name,
  ...threatSetFromItem(item.data, previous),
});

const currentShape = (item) => threatSetDoc(threatSetFromItem(item.data));

export const usePackThreats = ({
  packUuid,
  items,
  edit,
  readOnly = false,
  actor,
  packSets,
  setPackSets,
  fetchMasks,
  onGone,
  onRefused,
  newId = randomId,
  delayMs = 400,
}) => {
  // No pack open is null, and useThreats shows the person's own sets; another pack starts with nothing open.
  useEffect(() => {
    setPackSets(packUuid ? [] : null);
  }, [packUuid, setPackSets]);

  const setsNow = useRef(packSets);
  setsNow.current = packSets;
  const masks = useRef(fetchMasks);
  masks.current = fetchMasks;

  const ours = useCallback(
    (localId) => {
      const ref = packLocalRef(localId);
      return ref && ref.pack === packUuid ? ref.item : null;
    },
    [packUuid],
  );

  const local = useMemo(() => {
    const map = new Map();
    (packSets ?? []).forEach((set) => {
      const uuid = ours(set.id);
      if (uuid) map.set(uuid, { doc: threatSetDoc(set), name: set.name ?? "" });
    });
    return map;
  }, [packSets, ours]);

  const applyTheirs = useCallback(
    (uuid, item) => {
      const localId = packLocalId(packUuid, uuid);
      const next = ownSet(packUuid, item, setsNow.current?.find((set) => set.id === localId));
      setPackSets((sets) => sets && sets.map((set) => (set.id === localId ? ownSet(packUuid, item, set) : set)));
      masks.current?.(next.threats);
    },
    [packUuid, setPackSets],
  );

  const removeLocal = useCallback(
    (uuid) => {
      const localId = packLocalId(packUuid, uuid);
      setPackSets((sets) => sets && sets.filter((set) => set.id !== localId));
    },
    [packUuid, setPackSets],
  );

  const sync = usePackItemSync({
    kind: "threatset",
    items,
    local,
    edit,
    readOnly,
    actor,
    currentShape,
    shared: sharedThreatData,
    applyTheirs,
    removeLocal,
    describe: describeThreatChange,
    onGone,
    onRefused,
    delayMs,
  });

  const add = useCallback(
    (set) => {
      setPackSets((sets) => (sets && !sets.some((other) => other.id === set.id) ? [...sets, set] : sets));
      masks.current?.(set.threats);
    },
    [setPackSets],
  );

  /** Puts a pack's set of threats on the map. */
  const openItem = useCallback(
    (uuid) => {
      const item = sync.items.get(uuid);
      if (!item) return false;
      if (!local.has(uuid)) add(ownSet(packUuid, item));
      return true;
    },
    [sync.items, local, add, packUuid],
  );

  /** Takes it off the map; it stays in the pack. */
  const closeItem = useCallback((uuid) => removeLocal(uuid), [removeLocal]);

  /** Puts threats in the pack as a new set, on the map. A blank name takes the kind's. Returns its uuid, or null if refused. */
  const createItem = useCallback(
    (name, threats) => {
      const op = newThreatSetOp({ name, threats, actor, newId, count: sync.items.size });
      const refused = edit([op]);
      if (refused) {
        onRefused?.(refused, null);
        return null;
      }
      add(ownSet(packUuid, { uuid: op.item, name: op.name, data: op.data }, { threats }));
      return op.item;
    },
    [edit, actor, add, packUuid, onRefused, newId, sync.items],
  );

  const renameSet = useCallback(
    (uuid, name) => {
      const clean = Array.from(name.trim().toUpperCase()).slice(0, 100).join("");
      if (!clean) return;
      const localId = packLocalId(packUuid, uuid);
      setPackSets((sets) => sets && sets.map((set) => (set.id === localId ? { ...set, name: clean } : set)));
    },
    [packUuid, setPackSets],
  );

  return { openItem, closeItem, createItem, renameSet, flush: sync.flush, isPackSet: (localId) => Boolean(ours(localId)) };
};
