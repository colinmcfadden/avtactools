import { useEffect, useState } from "react";
import * as packApi from "./packApi";

/*
 * How far this person has looked in a pack, on every device they use (PUT /api/packs/<uuid>/seen).
 *
 * While they are looking (`looking`: the pack's panel is open and the tab is visible), the marker
 * moves to the newest event after a moment. `newSince` is where they had got to when they started
 * looking, and stays put while they look, so "New since you looked" and the dots on what changed
 * do not vanish under them as the marker moves.
 *
 * `seenSeq` is the server's marker, which counts only once the pack has `loaded`: until then
 * nothing is known (`newSince` is null), and the first one known is where "new" starts, even if
 * they were already looking. Nothing of one pack's carries over to the next.
 */
export const usePackSeen = ({ packUuid, headSeq = 0, seenSeq = 0, loaded = true, looking, markSeen = packApi.markSeen, delayMs = 1500 }) => {
  const server = loaded ? seenSeq ?? 0 : null;
  const [mark, setMark] = useState(() => ({ pack: packUuid, seen: server, since: server, looking }));

  // Worked out while rendering, so no frame after a switch or a load is measured from the wrong place.
  let next = mark;
  if (next.pack !== packUuid) next = { pack: packUuid, seen: server, since: server, looking };
  // The server knowing more (another device) moves the marker; the first it says is where "new" starts.
  if (server !== null && (next.seen === null || server > next.seen)) next = { ...next, seen: server, since: next.since ?? server };
  // "New" is measured from where they were when they started looking.
  if (looking !== next.looking) next = { ...next, looking, since: looking && next.seen !== null ? next.seen : next.since };
  if (next !== mark) setMark(next);
  const { seen, since } = next;

  useEffect(() => {
    if (!looking || !packUuid || seen === null || headSeq <= seen) return undefined;
    let current = true;
    const timer = setTimeout(() => {
      markSeen(packUuid, headSeq).then(
        (answer) => {
          if (current) setMark((m) => (m.pack === packUuid ? { ...m, seen: Math.max(m.seen ?? 0, answer?.seen_seq ?? headSeq) } : m));
        },
        () => {}, // tried again the next time something changes
      );
    }, delayMs);
    return () => {
      current = false;
      clearTimeout(timer);
    };
  }, [looking, packUuid, headSeq, seen, markSeen, delayMs]);

  return { seenSeq: seen ?? 0, newSince: since };
};

/** Whether an item changed since `sinceSeq` by someone other than `me` (an item from visibleItems). */
export const changedSince = (item, sinceSeq, me) =>
  sinceSeq != null && Number.isFinite(item?.seq) && item.seq > sinceSeq && item.updated_by?.id !== me;
