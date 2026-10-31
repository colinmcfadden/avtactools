import { useCallback, useMemo, useState } from "react";
import { describeRouteChange, routeSetData, routesFromItem, sharedRouteData } from "./packRoutes";
import { packLocalId } from "./packRef";
import { newItemOp, packItemId } from "./packSentences";
import { usePackItemSync } from "./usePackItemSync";

/*
 * The route sets of an open mission pack, kept in step with the sketch (useRouteSketch) both ways;
 * usePackItemSync has the rules. A set's routes sit in the sketch filed under the set (`setIdOf`),
 * keep their ids, and are changed with every route tool as any route is. A route drawn while a set
 * is chosen goes into it (finishSketch(name, setIdOf(uuid))).
 *
 * A set's name is not in the sketch: it is kept here, and changes when it is renamed here
 * (renameSet) or by someone else.
 */

const randomId = () =>
  typeof crypto !== "undefined" && crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(16).slice(2)}`;

export const usePackRoutes = ({
  packUuid,
  items,
  edit,
  readOnly = false,
  actor,
  sketchedRoutes,
  loadSketchRoutes,
  replaceRouteSet,
  removeRouteSet,
  onGone,
  onRefused,
  newId = randomId,
  delayMs = 400,
}) => {
  const [open, setOpen] = useState([]);
  const [names, setNames] = useState({});
  const setIdOf = useCallback((uuid) => packLocalId(packUuid, uuid), [packUuid]);

  // The pack's route sets open here, as their items would have them.
  const local = useMemo(() => {
    const map = new Map();
    open.forEach((uuid) => {
      const setId = setIdOf(uuid);
      map.set(uuid, { doc: routeSetData((sketchedRoutes ?? []).filter((route) => route.setId === setId)), name: names[uuid] ?? "" });
    });
    return map;
  }, [open, names, sketchedRoutes, setIdOf]);

  const forget = (uuid) => {
    setOpen((list) => list.filter((u) => u !== uuid));
    setNames(({ [uuid]: _gone, ...rest }) => rest); // eslint-disable-line no-unused-vars
  };

  const currentShape = useCallback((item) => routeSetData(routesFromItem(item, setIdOf(item.uuid))), [setIdOf]);
  const applyTheirs = useCallback(
    (uuid, item) => {
      replaceRouteSet(setIdOf(uuid), item.data?.routes ?? []);
      setNames((n) => ({ ...n, [uuid]: item.name }));
    },
    [replaceRouteSet, setIdOf],
  );
  const removeLocal = useCallback(
    (uuid) => {
      removeRouteSet(setIdOf(uuid));
      forget(uuid);
    },
    [removeRouteSet, setIdOf],
  );

  const sync = usePackItemSync({
    kind: "route",
    items,
    local,
    edit,
    readOnly,
    actor,
    currentShape,
    shared: sharedRouteData,
    applyTheirs,
    removeLocal,
    describe: describeRouteChange,
    onGone,
    onRefused,
    delayMs,
  });

  /** Puts a route set's routes on the map and in the route tools. */
  const openItem = useCallback(
    (uuid) => {
      const item = sync.items.get(uuid);
      if (!item || open.includes(uuid)) return Boolean(item);
      loadSketchRoutes(item.data?.routes ?? [], { setId: setIdOf(uuid) });
      setNames((n) => ({ ...n, [uuid]: item.name }));
      setOpen((list) => [...list, uuid]);
      return true;
    },
    [sync.items, open, loadSketchRoutes, setIdOf],
  );

  /** Takes a route set off the map (it stays in the pack). A change still waiting is sent first. */
  const closeItem = useCallback(
    (uuid) => {
      if (!open.includes(uuid)) return;
      removeRouteSet(setIdOf(uuid));
      forget(uuid);
    },
    [open, removeRouteSet, setIdOf],
  );

  /** A new, empty route set in the pack, opened here. Returns its uuid, or null if refused. */
  const createItem = useCallback(
    (name) => {
      const uuid = packItemId("route", newId());
      const op = newItemOp({ kind: "route", item: uuid, name, count: sync.items.size, data: { version: 1, routes: [] }, actor });
      const refused = edit([op]);
      if (refused) {
        onRefused?.(refused, null);
        return null;
      }
      setNames((n) => ({ ...n, [uuid]: op.name }));
      setOpen((list) => [...list, uuid]);
      return uuid;
    },
    [sync.items, edit, actor, onRefused, newId],
  );

  const renameSet = useCallback((uuid, name) => setNames((n) => ({ ...n, [uuid]: name })), []);

  return { openItem, closeItem, createItem, renameSet, setIdOf, open, flush: sync.flush };
};
