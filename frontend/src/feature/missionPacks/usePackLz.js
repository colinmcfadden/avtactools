import { useCallback, useEffect, useMemo, useReducer, useRef } from "react";
import { createLzDiagramFromTarget } from "../lzWorkspace/useLzWorkspace";
import { diffItem, sameData } from "./packDiff";
import { describeLzChange, lzDiagramFromItem, lzItemData, packDiagramId, packDiagramRef, sharedLzData } from "./packLz";

/*
 * The LZ/PZ items of an open mission pack, kept in step with the diagrams in the workspace both ways
 * (useLzWorkspace stays the one place every editor reads and writes):
 *
 *  - openItem puts an item in the workspace as a diagram whose id names the pack and the item, and
 *    createItem makes a new one (anything started while a pack is open goes into it);
 *  - this person's changes to such a diagram are sent once it has been still for `delayMs`, so a
 *    drag goes as one change, not one per frame, with a sentence for the pack's history;
 *  - everyone else's changes arrive in `items` (useMissionPack) and are applied to the diagram,
 *    keeping this person's own view of it;
 *  - an item someone removed leaves the workspace, and a diagram the pack will not take (it is
 *    finished, or this person may only look) goes back to what the pack has.
 *
 * Each diagram remembers what it was last in step with (`baselines`): the item's data as the pack
 * had it (by reference: packSession makes a new object for whatever it changes) and the diagram's
 * shared data then (`doc`). What is sent is the change from that `doc`, never from the pack's
 * latest data: a change made here is applied on top of whatever others did meanwhile, so both
 * stand (or, on one field, the later one). A change waiting here is sent before anyone else's is
 * applied, so nothing made here is lost under it.
 */

const randomId = () =>
  typeof crypto !== "undefined" && crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(16).slice(2)}`;

// A diagram's shared data, worked out once per version of it (a drag makes many versions).
const docs = new WeakMap();
const docOf = (diagram) => {
  if (!docs.has(diagram)) docs.set(diagram, lzItemData(diagram));
  return docs.get(diagram);
};

// The item's data as the editor would have it: today's shape, with defaults filled in.
const currentShape = (packUuid, item) => lzItemData(lzDiagramFromItem(packUuid, item));

// `data` is null right after a send: the next pass compares the diagram with the pack again.
const JUST_SENT = null;

export const usePackLz = ({
  packUuid,
  items,
  edit,
  readOnly = false,
  workspace,
  importDiagram,
  applyRemoteDiagram,
  removeDiagram,
  setActiveDiagram,
  setFocus,
  actor,
  onGone,
  onRefused,
  newId = randomId,
  delayMs = 400,
}) => {
  const lzItems = useMemo(() => {
    const map = new Map();
    (items ?? []).forEach((item) => {
      if (item.kind === "lz") map.set(item.uuid, item);
    });
    return map;
  }, [items]);

  const baselines = useRef(new Map());
  const timers = useRef(new Map());
  const [, wake] = useReducer((n) => n + 1, 0);
  const latest = useRef({});
  latest.current = { lzItems, workspace, edit, readOnly, actor, applyRemoteDiagram, onRefused };

  const ours = useCallback(
    (diagramId) => {
      const ref = packDiagramRef(diagramId);
      return ref && ref.pack === packUuid ? ref.item : null;
    },
    [packUuid],
  );

  const settle = (diagramId, item) => {
    baselines.current.set(diagramId, { data: item.data, name: item.name, doc: currentShape(packUuid, item) });
  };

  // Shows what the pack has, in place of this diagram's own version.
  const takeTheirs = (diagramId, item) => {
    latest.current.applyRemoteDiagram(lzDiagramFromItem(packUuid, item), diagramId);
    settle(diagramId, item);
  };

  const schedule = (diagramId) => {
    clearTimeout(timers.current.get(diagramId));
    // eslint-disable-next-line no-use-before-define
    timers.current.set(diagramId, setTimeout(() => flush(diagramId), delayMs));
  };

  const flush = useCallback(
    (diagramId) => {
      clearTimeout(timers.current.get(diagramId));
      timers.current.delete(diagramId);
      const { lzItems: current, workspace: ws, edit: send, readOnly: locked, actor: who, onRefused: refusedBy } = latest.current;
      const uuid = ours(diagramId);
      const diagram = ws?.diagramsById?.[diagramId];
      const item = uuid ? current.get(uuid) : null;
      const base = baselines.current.get(diagramId);
      if (!diagram || !item || !base) return;
      if (locked) {
        takeTheirs(diagramId, item);
        return;
      }
      const doc = docOf(diagram);
      const name = (diagram.name ?? "").trim();
      const renamed = Boolean(name) && name !== base.name;
      const changes = diffItem(uuid, base.doc, doc);
      if (!renamed && changes.length === 0) return;
      // An item still in an older shape is brought to today's first, so every change's path exists.
      const reshape = changes.length > 0 ? diffItem(uuid, sharedLzData(item.data), currentShape(packUuid, item)) : [];
      const ops = [...(renamed ? [{ type: "item.rename", item: uuid, name }] : []), ...reshape, ...changes];
      const summary = changes.length > 0
        ? describeLzChange(base.doc, doc, { name: name || item.name, actor: who })
        : `${who || "Someone"} renamed "${base.name}" to "${name}".`;
      const refused = send(ops.map((op) => ({ ...op, summary })));
      if (refused) {
        takeTheirs(diagramId, item);
        refusedBy?.(refused, diagramId);
        return;
      }
      baselines.current.set(diagramId, { data: JUST_SENT, name: renamed ? name : base.name, doc });
      wake();
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [ours, packUuid],
  );

  const flushAll = useCallback(() => {
    [...timers.current.keys()].forEach(flush);
  }, [flush]);

  // Both directions, whenever the workspace or the pack's items change.
  useEffect(() => {
    if (!workspace) return;
    workspace.diagramOrder.forEach((diagramId) => {
      const uuid = ours(diagramId);
      if (!uuid) return;
      const diagram = workspace.diagramsById[diagramId];
      const item = lzItems.get(uuid);
      const base = baselines.current.get(diagramId);
      if (!item) {
        if (base) {
          // Removed from the pack (by anyone, here included): it goes from the workspace too.
          clearTimeout(timers.current.get(diagramId));
          timers.current.delete(diagramId);
          baselines.current.delete(diagramId);
          removeDiagram(diagramId);
          onGone?.(diagramId, base.name);
        }
        return;
      }
      if (!base) {
        settle(diagramId, item);
        return;
      }
      const changedHere = !sameData(docOf(diagram), base.doc) || (diagram.name ?? "").trim() !== base.name;
      if (base.data === JUST_SENT) {
        // Still being changed: send that when it is still, and compare with the pack after.
        if (changedHere) schedule(diagramId);
        else if (!sameData(currentShape(packUuid, item), base.doc) || item.name !== base.name) takeTheirs(diagramId, item);
        else settle(diagramId, item);
        return;
      }
      if (item.data !== base.data || item.name !== base.name) {
        if (changedHere) {
          flush(diagramId); // ours first; the pass after the send takes theirs
          return;
        }
        if (!sameData(currentShape(packUuid, item), docOf(diagram)) || item.name !== (diagram.name ?? "")) takeTheirs(diagramId, item);
        else settle(diagramId, item);
        return;
      }
      if (changedHere) {
        if (readOnly) takeTheirs(diagramId, item);
        else schedule(diagramId);
      }
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workspace, lzItems, readOnly, ours]);

  // What this person has open, for everyone else's presence.
  const activeItem = ours(workspace?.activeDiagramId);
  useEffect(() => {
    setFocus?.(activeItem ? { item: activeItem } : null);
  }, [activeItem, setFocus]);

  // A change still waiting goes when this closes. Switching to another pack: call flush() first,
  // since by the time this runs the pack's items are the new pack's, which do not have the old
  // items (their ids are random), so nothing of the old pack is sent to the new one.
  useEffect(() => () => flushAll(), [flushAll]);

  const openItem = useCallback(
    (uuid) => {
      const item = lzItems.get(uuid);
      if (!item) return null;
      const diagramId = packDiagramId(packUuid, uuid);
      if (workspace?.diagramsById?.[diagramId]) setActiveDiagram?.(diagramId);
      else importDiagram(lzDiagramFromItem(packUuid, item), { activate: true });
      return diagramId;
    },
    [lzItems, packUuid, workspace, importDiagram, setActiveDiagram],
  );

  /**
   * A new LZ/PZ in the pack at `target`, opened in the workspace. `name` defaults to the next
   * "LZ/PZ n". Returns the diagram's id, or null if the pack would not take it (and why, to onRefused).
   */
  const createItem = useCallback(
    (target, { name, mgrs = "" } = {}) => {
      const uuid = `lz-${newId()}`;
      const diagram = createLzDiagramFromTarget({ target, mgrs, id: packDiagramId(packUuid, uuid) });
      if (!diagram) return null;
      const label = (name ?? "").trim() || `LZ/PZ ${lzItems.size + 1}`;
      const refused = edit([{
        type: "item.create", item: uuid, kind: "lz", name: label, data: lzItemData(diagram),
        summary: `${actor || "Someone"} added the LZ/PZ "${label}".`,
      }]);
      if (refused) {
        onRefused?.(refused, null);
        return null;
      }
      importDiagram({ ...diagram, name: label }, { activate: true });
      return diagram.id;
    },
    [packUuid, lzItems, edit, actor, importDiagram, onRefused, newId],
  );

  return { openItem, createItem, flush: flushAll, isPackDiagram: (diagramId) => Boolean(ours(diagramId)) };
};
