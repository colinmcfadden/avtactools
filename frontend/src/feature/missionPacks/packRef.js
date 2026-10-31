/*
 * The id a pack item has in an editor here (a diagram in the workspace, a set of routes in the
 * sketch, a set of points): it names the pack and the item, so what is a pack's can be told from
 * what is this person's own, and from another pack's.
 */

export const packLocalId = (packUuid, itemUuid) => `pack:${packUuid}:${itemUuid}`;

/** { pack, item } for an id made by packLocalId, else null. */
export const packLocalRef = (localId) => {
  const match = /^pack:([^:]+):(.+)$/.exec(String(localId ?? ""));
  return match ? { pack: match[1], item: match[2] } : null;
};
