import { useCallback, useEffect, useMemo, useReducer, useRef } from "react";
import { sameData } from "./packDiff";
import { composeEdit } from "./packEdit";

/*
 * Keeps the items of one kind (LZ/PZs, route sets or point sets) that are open in an editor in step
 * with an open mission pack, both ways. The editor stays where its documents live; the caller says
 * what is open there (`local`: item uuid -> { doc, name }, `doc` being the item's shared data as the
 * editor has it) and how to put the pack's version back into it.
 *
 *  - A change made here goes once the item has been still for `delayMs`, so a drag is one change,
 *    not one per frame, with a sentence for the pack's history (`describe`).
 *  - Someone else's change, arriving in `items` (useMissionPack), is put into the editor (`applyTheirs`).
 *  - An item someone removed is taken out of the editor (`removeLocal`), and a change the pack will
 *    not take (the pack is finished, or this person may only look) is put back to the pack's version.
 *
 * Each open item remembers what it was last in step with: the pack's data for it (by reference:
 * packSession makes a new object for whatever it changes) and the editor's `doc` then. What is sent
 * is the change from that `doc`, never from the pack's latest data, so a change made here lands on
 * top of whatever others did meanwhile and both stand (on one field, the later one does). A change
 * waiting here is sent before anyone else's is put into the editor, so nothing made here is lost
 * under it. An item still in an older shape is brought to today's in the same send (`shared` is the
 * raw data's shared part, `currentShape` the same in today's shape), so every change's path exists.
 */

// `data` is null right after a send: the next pass compares the editor with the pack again.
const JUST_SENT = null;

export const usePackItemSync = ({
  kind,
  items,
  local,
  edit,
  readOnly = false,
  actor,
  currentShape,
  shared,
  applyTheirs,
  removeLocal,
  describe,
  onGone,
  onRefused,
  delayMs = 400,
}) => {
  const ofKind = useMemo(() => {
    const map = new Map();
    (items ?? []).forEach((item) => {
      if (item.kind === kind) map.set(item.uuid, item);
    });
    return map;
  }, [items, kind]);

  const baselines = useRef(new Map());
  const timers = useRef(new Map());
  // The editor's last version of each item, so a change still waiting is sent even once it is closed.
  const lastSeen = useRef(new Map());
  const [tick, wake] = useReducer((n) => n + 1, 0);
  const latest = useRef({});
  latest.current = { ofKind, local, edit, readOnly, actor, currentShape, shared, applyTheirs, describe, onRefused };

  const settle = (uuid, item) => {
    baselines.current.set(uuid, { data: item.data, name: item.name, doc: latest.current.currentShape(item) });
  };

  const takeTheirs = (uuid, item) => {
    latest.current.applyTheirs(uuid, item);
    settle(uuid, item);
  };

  const flush = useCallback((uuid) => {
    clearTimeout(timers.current.get(uuid));
    timers.current.delete(uuid);
    const now = latest.current;
    const mine = now.local.get(uuid) ?? lastSeen.current.get(uuid);
    const item = now.ofKind.get(uuid);
    const base = baselines.current.get(uuid);
    if (!mine || !item || !base) return;
    if (now.readOnly) {
      takeTheirs(uuid, item);
      return;
    }
    const sent = composeEdit({
      uuid, base, mine, item, shared: now.shared, currentShape: now.currentShape, describe: now.describe, actor: now.actor,
    });
    if (!sent) return;
    const refused = now.edit(sent.ops);
    if (refused) {
      takeTheirs(uuid, item);
      now.onRefused?.(refused, uuid);
      return;
    }
    baselines.current.set(uuid, { data: JUST_SENT, name: sent.name, doc: mine.doc });
    wake();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const flushAll = useCallback(() => {
    [...timers.current.keys()].forEach(flush);
  }, [flush]);

  const schedule = (uuid) => {
    clearTimeout(timers.current.get(uuid));
    timers.current.set(uuid, setTimeout(() => flush(uuid), delayMs));
  };

  useEffect(() => {
    local.forEach((mine, uuid) => {
      lastSeen.current.set(uuid, mine);
      const item = ofKind.get(uuid);
      const base = baselines.current.get(uuid);
      if (!item) {
        if (base) {
          // Removed from the pack (by anyone, here included): it goes from the editor too.
          clearTimeout(timers.current.get(uuid));
          timers.current.delete(uuid);
          baselines.current.delete(uuid);
          removeLocal(uuid);
          onGone?.(uuid, base.name);
        }
        return;
      }
      if (!base) {
        settle(uuid, item);
        return;
      }
      const changedHere = !sameData(mine.doc, base.doc) || (mine.name ?? "").trim() !== base.name;
      if (base.data === JUST_SENT) {
        // Still being changed: send that when it is still, and compare with the pack after.
        if (changedHere) schedule(uuid);
        else if (!sameData(currentShape(item), base.doc) || item.name !== base.name) takeTheirs(uuid, item);
        else settle(uuid, item);
        return;
      }
      if (item.data !== base.data || item.name !== base.name) {
        if (changedHere) {
          flush(uuid); // ours first; the pass after the send takes theirs
          return;
        }
        if (!sameData(currentShape(item), mine.doc) || item.name !== (mine.name ?? "")) takeTheirs(uuid, item);
        else settle(uuid, item);
        return;
      }
      if (changedHere) {
        if (readOnly) takeTheirs(uuid, item);
        else schedule(uuid);
      }
    });
    // Items no longer open here are forgotten (and a change of theirs still waiting is sent).
    [...baselines.current.keys()].forEach((uuid) => {
      if (local.has(uuid)) return;
      if (timers.current.has(uuid)) flush(uuid);
      baselines.current.delete(uuid);
      lastSeen.current.delete(uuid);
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [local, ofKind, readOnly, tick]);

  // A change still waiting goes when this closes. Switching to another pack: call flush() first;
  // by the time this runs the items are the new pack's, which do not have the old pack's items
  // (their ids are random), so nothing of one pack is ever sent to another.
  useEffect(() => () => flushAll(), [flushAll]);

  return { items: ofKind, flush: flushAll };
};
