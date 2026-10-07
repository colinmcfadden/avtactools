/* eslint-env jest */
import { useCallback, useRef, useState } from "react";
import { applyPackOp, validatePackOp } from "./packOps";

/*
 * For tests only: a mission pack's items as useMissionPack shows them, where an edit made here
 * appears at once, as in packSession. Every operation must be well formed and must apply, as it
 * would on the server, or the test fails. Untouched items keep their data object.
 */

export const applyOps = (items, ops) => {
  let map = Object.fromEntries(items.map((i) => [i.uuid, { kind: i.kind, name: i.name, data: i.data, deleted: false }]));
  const order = items.map((i) => i.uuid);
  ops.forEach((op) => {
    const { summary, ...bare } = op; // eslint-disable-line no-unused-vars
    expect(validatePackOp(bare)).toBeNull();
    const result = applyPackOp(map, bare);
    expect([op.type, result.status, result.reason]).toEqual([op.type, "applied", null]);
    map = result.items;
    if (!order.includes(op.item)) order.push(op.item);
  });
  return order
    .filter((uuid) => map[uuid] && !map[uuid].deleted)
    .map((uuid) => {
      const before = items.find((i) => i.uuid === uuid);
      return { uuid, kind: map[uuid].kind, name: map[uuid].name, data: before?.data === map[uuid].data ? before.data : map[uuid].data };
    });
};

/** { items, edit, remote, sent }: `edit` is this person's (recorded in `sent`), `remote` someone else's. */
export const useFakePack = (initial, { refuse = null } = {}) => {
  const [items, setItems] = useState(initial);
  const sent = useRef([]);
  const edit = useCallback((ops) => {
    if (refuse) return refuse;
    sent.current.push(ops);
    setItems((before) => applyOps(before, ops));
    return null;
  }, [refuse]);
  const remote = useCallback((ops) => setItems((before) => applyOps(before, ops)), []);
  return { items, edit, remote, sent };
};
