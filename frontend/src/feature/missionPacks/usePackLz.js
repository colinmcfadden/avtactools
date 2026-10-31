import { useCallback, useEffect, useMemo } from "react";
import { createLzDiagramFromTarget } from "../lzWorkspace/useLzWorkspace";
import { describeLzChange, lzDiagramFromItem, lzItemData, packDiagramId, packDiagramRef, sharedLzData } from "./packLz";
import { newItemOp, packItemId } from "./packSentences";
import { usePackItemSync } from "./usePackItemSync";

/*
 * The LZ/PZ items of an open mission pack, kept in step with the diagrams in the workspace both ways
 * (usePackItemSync has the rules; useLzWorkspace stays the one place every editor reads and writes):
 *
 *  - openItem puts an item in the workspace as a diagram whose id names the pack and the item, and
 *    createItem makes a new one there (anything started while a pack is open goes into it);
 *  - a change made to such a diagram is sent, someone else's is applied to it keeping this person's
 *    own view of it (APPLY_REMOTE_DIAGRAM), and one the pack removed leaves the workspace.
 */

const randomId = () =>
  typeof crypto !== "undefined" && crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(16).slice(2)}`;

// A diagram's shared data, worked out once per version of it (a drag makes many versions).
const docs = new WeakMap();
const docOf = (diagram) => {
  if (!docs.has(diagram)) docs.set(diagram, lzItemData(diagram));
  return docs.get(diagram);
};

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
  const ours = useCallback(
    (diagramId) => {
      const ref = packDiagramRef(diagramId);
      return ref && ref.pack === packUuid ? ref.item : null;
    },
    [packUuid],
  );

  // The pack's LZ/PZs open in the workspace, as their items would have them.
  const local = useMemo(() => {
    const open = new Map();
    (workspace?.diagramOrder ?? []).forEach((diagramId) => {
      const uuid = ours(diagramId);
      const diagram = workspace.diagramsById[diagramId];
      if (uuid && diagram) open.set(uuid, { doc: docOf(diagram), name: diagram.name ?? "" });
    });
    return open;
  }, [workspace, ours]);

  const currentShape = useCallback((item) => lzItemData(lzDiagramFromItem(packUuid, item)), [packUuid]);
  const applyTheirs = useCallback(
    (uuid, item) => applyRemoteDiagram(lzDiagramFromItem(packUuid, item), packDiagramId(packUuid, uuid)),
    [applyRemoteDiagram, packUuid],
  );
  const removeLocal = useCallback((uuid) => removeDiagram(packDiagramId(packUuid, uuid)), [removeDiagram, packUuid]);
  const goneDiagram = useCallback((uuid, name) => onGone?.(packDiagramId(packUuid, uuid), name), [onGone, packUuid]);
  const refusedDiagram = useCallback(
    (why, uuid) => onRefused?.(why, uuid ? packDiagramId(packUuid, uuid) : null),
    [onRefused, packUuid],
  );

  const sync = usePackItemSync({
    kind: "lz",
    items,
    local,
    edit,
    readOnly,
    actor,
    currentShape,
    shared: sharedLzData,
    applyTheirs,
    removeLocal,
    describe: describeLzChange,
    onGone: goneDiagram,
    onRefused: refusedDiagram,
    delayMs,
  });

  // What this person has open, for everyone else's presence.
  const activeItem = ours(workspace?.activeDiagramId);
  useEffect(() => {
    setFocus?.(activeItem ? { item: activeItem } : null);
  }, [activeItem, setFocus]);

  const openItem = useCallback(
    (uuid) => {
      const item = sync.items.get(uuid);
      if (!item) return null;
      const diagramId = packDiagramId(packUuid, uuid);
      if (workspace?.diagramsById?.[diagramId]) setActiveDiagram?.(diagramId);
      else importDiagram(lzDiagramFromItem(packUuid, item), { activate: true });
      return diagramId;
    },
    [sync.items, packUuid, workspace, importDiagram, setActiveDiagram],
  );

  /**
   * A new LZ/PZ in the pack at `target`, opened in the workspace. `name` defaults to the next
   * "LZ/PZ n". Returns the diagram's id, or null if the pack would not take it (and why, to onRefused).
   */
  const createItem = useCallback(
    (target, { name, mgrs = "" } = {}) => {
      const uuid = packItemId("lz", newId());
      const diagram = createLzDiagramFromTarget({ target, mgrs, id: packDiagramId(packUuid, uuid) });
      if (!diagram) return null;
      const op = newItemOp({ kind: "lz", item: uuid, name, count: sync.items.size, data: lzItemData(diagram), actor });
      const refused = edit([op]);
      if (refused) {
        onRefused?.(refused, null);
        return null;
      }
      importDiagram({ ...diagram, name: op.name }, { activate: true });
      return diagram.id;
    },
    [packUuid, sync.items, edit, actor, importDiagram, onRefused, newId],
  );

  return { openItem, createItem, flush: sync.flush, isPackDiagram: (diagramId) => Boolean(ours(diagramId)) };
};
