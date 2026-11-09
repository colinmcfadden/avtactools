import { applyPackOp } from "./packOps";

const ITEM = "rebase";

/**
 * `data` with `ops` (someone else's change, as packDiff states it) laid over it. An operation on
 * something this version does not have is passed over: a step that added or took away the thing it
 * names stands as it was.
 */
export const applyOpsTo = (data, ops) => {
  let items = { [ITEM]: { kind: "lz", name: "", data, deleted: false } };
  ops.forEach((op) => {
    const result = applyPackOp(items, { ...op, item: ITEM });
    if (result.status === "applied") items = result.items;
  });
  return items[ITEM].data;
};
