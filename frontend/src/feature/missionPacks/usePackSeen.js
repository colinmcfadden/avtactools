import { useEffect, useRef, useState } from "react";
import * as packApi from "./packApi";

/*
 * How far this person has looked in a pack, on every device they use (PUT /api/packs/<uuid>/seen).
 *
 * While they are looking (`looking`: the pack's panel is open and the tab is visible), the marker
 * moves to the newest event after a moment. `newSince` is where they had got to when they started
 * looking, and stays put while they look, so "New since you looked" and the dots on what changed
 * do not vanish under them as the marker moves.
 */
export const usePackSeen = ({ packUuid, headSeq = 0, seenSeq = 0, looking, markSeen = packApi.markSeen, delayMs = 1500 }) => {
  const [seen, setSeen] = useState(seenSeq ?? 0);
  const [since, setSince] = useState(seenSeq ?? 0);
  const pack = useRef(packUuid);

  // Another pack, or the server knowing more (another device): start from what the server says.
  useEffect(() => {
    if (pack.current !== packUuid) {
      pack.current = packUuid;
      setSeen(seenSeq ?? 0);
      setSince(seenSeq ?? 0);
    } else {
      setSeen((s) => Math.max(s, seenSeq ?? 0));
    }
  }, [packUuid, seenSeq]);

  // "New" is measured from where they were when they started looking.
  const seenRef = useRef(seen);
  seenRef.current = seen;
  useEffect(() => {
    if (looking) setSince(seenRef.current);
  }, [looking, packUuid]);

  useEffect(() => {
    if (!looking || !packUuid || headSeq <= seen) return undefined;
    let current = true;
    const timer = setTimeout(() => {
      markSeen(packUuid, headSeq).then(
        (answer) => {
          if (current) setSeen((s) => Math.max(s, answer?.seen_seq ?? headSeq));
        },
        () => {}, // tried again the next time something changes
      );
    }, delayMs);
    return () => {
      current = false;
      clearTimeout(timer);
    };
  }, [looking, packUuid, headSeq, seen, markSeen, delayMs]);

  return { seenSeq: seen, newSince: since };
};

/** Whether an item changed since `sinceSeq` by someone other than `me` (an item from visibleItems). */
export const changedSince = (item, sinceSeq, me) =>
  Number.isFinite(item?.seq) && item.seq > sinceSeq && item.updated_by?.id !== me;
