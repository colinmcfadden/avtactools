import { diffItem } from "./packDiff";
import { renameSummary } from "./packSentences";

/*
 * What one change an editor made to a pack item is sent as: usePackItemSync's flush, without the
 * timing. Pure, so contracts/fixtures/packs/edit.json can hold the native apps to it.
 */

/**
 * The operations for item `uuid` that carry the editor's change (`mine`: { doc, name }) since the
 * version it was last in step with (`base`: { doc, name }), or null when there is none.
 *
 *  - An `item.rename` first, when the editor's name, trimmed, is not blank and is not `base.name`.
 *  - Then, only when the content changed: the operations that bring the pack's data into today's
 *    shape (from `shared(item.data)` to `currentShape(item)`; none for an item already in it), so
 *    every path of the change exists; then the change itself, from `base.doc` to `mine.doc`.
 *  - Every operation carries the same summary: `describe(base.doc, mine.doc, { name, actor })` when
 *    the content changed (`name` the new name, or the item's when there is none), else the rename's.
 *
 * Returns { ops, name }: `name` is what the item is called once they are taken. Throws as diffData
 * does when the content would have to be replaced whole.
 */
export const composeEdit = ({ uuid, base, mine, item, shared, currentShape, describe, actor }) => {
  const name = (mine.name ?? "").trim();
  const renamed = Boolean(name) && name !== base.name;
  const changes = diffItem(uuid, base.doc, mine.doc);
  if (!renamed && changes.length === 0) return null;
  const reshape = changes.length > 0 ? diffItem(uuid, shared(item.data), currentShape(item)) : [];
  const ops = [...(renamed ? [{ type: "item.rename", item: uuid, name }] : []), ...reshape, ...changes];
  const summary = changes.length > 0
    ? describe(base.doc, mine.doc, { name: name || item.name, actor })
    : renameSummary(actor, base.name, name);
  return { ops: ops.map((op) => ({ ...op, summary })), name: renamed ? name : base.name };
};
