/*
 * A pack's history as people read it: one line for each change. A change goes to the pack as one operation or several (a doghouse
 * turned is its heading and the landing or takeoff heading it gives; an analysis is what it found, the doghouses it made and their
 * headings), each with the change's one sentence (packEdit.js), and the pack logs every operation as an event of its own, so a line
 * per event said the same thing two or five times. Events that follow one another in the log, by one person, on one item, with one
 * sentence and one outcome, are one line: the server counts a copy's changes the same way (pack_support.pack_changes). Pure, so
 * contracts/fixtures/packs/history.json can hold the native apps to it.
 */

const same = (a, b) => (a ?? null) === (b ?? null);

/** Whether [event], straight after [previous] in the log, belongs to the same line. */
const sameLine = (previous, event) =>
  event.seq === previous.seq + 1 &&
  same(previous.actor?.id, event.actor?.id) &&
  same(previous.actor?.name, event.actor?.name) &&
  same(previous.item, event.item) &&
  same(previous.summary, event.summary) &&
  same(previous.status, event.status) &&
  same(previous.reason, event.reason);

/**
 * [events], the log oldest first, as the history's lines, oldest first: each line the events it stands for, oldest first. A line
 * never holds events on both sides of [since] (where "new since you looked" starts), so nothing new is folded into what was seen.
 */
export const historyLines = (events, since = null) => {
  const lines = [];
  (events ?? []).forEach((event) => {
    const line = lines[lines.length - 1];
    const previous = line?.[line.length - 1];
    const acrossSince = since != null && previous != null && previous.seq <= since && event.seq > since;
    if (previous && !acrossSince && sameLine(previous, event)) line.push(event);
    else lines.push([event]);
  });
  return lines;
};
