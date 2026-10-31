/*
 * What an editor's change to a pack item is sent as: the operations that turn the item's data as
 * the pack has it into the data as the editor has it (packOps.js applies them; docs/MISSION_PACKS.md
 * §3). Small and addressed by id, so two people changing different things never touch each other's
 * work:
 *
 *  - an object's changed fields go as one `patch` at that object, and a field that is itself an
 *    object, or a list of things with ids, is followed into rather than replaced;
 *  - in a list whose elements all have ids (helicopters, routes, a route's points, a set's points),
 *    an element that went is a `remove`, a new one an `insert` after its neighbour, a changed one a
 *    patch at that element, and one that moved a `remove` and an `insert`;
 *  - anything else that changed (a number, a boundary of [lat, lon] pairs) goes whole, as a field
 *    of its object's patch. (So `set` is never needed.)
 *
 * A missing field and a null one are the same here, as they are to every reader of these documents:
 * there is no operation that deletes a key, so a removed field is sent as null.
 */

const FORBIDDEN_KEYS = new Set(["__proto__", "constructor", "prototype"]);

const has = (object, key) => Object.prototype.hasOwnProperty.call(object, key);
const isObject = (value) => value !== null && typeof value === "object" && !Array.isArray(value);
const isId = (value) => typeof value === "string" || (typeof value === "number" && Number.isFinite(value));
const copy = (value) => (value === undefined ? null : JSON.parse(JSON.stringify(value)));

/** Equal as JSON, with a missing field and a null one the same. */
export const sameData = (a, b) => {
  if (a === b) return true;
  if (a == null || b == null) return a == null && b == null;
  if (Array.isArray(a) || Array.isArray(b)) {
    if (!Array.isArray(a) || !Array.isArray(b) || a.length !== b.length) return false;
    return a.every((value, i) => sameData(value, b[i]));
  }
  if (isObject(a) && isObject(b)) {
    const keys = new Set([...Object.keys(a), ...Object.keys(b)]);
    for (const key of keys) if (!sameData(a[key], b[key])) return false;
    return true;
  }
  return false;
};

/** Every element an object with an id, no id twice: a list whose elements can be addressed. */
const isIdList = (value) => {
  if (!Array.isArray(value)) return false;
  const seen = new Set();
  for (const element of value) {
    if (!isObject(element) || !has(element, "id") || !isId(element.id)) return false;
    // "1" and 1 are different ids to packOps, but one Set would hold both: key them apart.
    const key = `${typeof element.id}:${element.id}`;
    if (seen.has(key)) return false;
    seen.add(key);
  }
  return true;
};

const idKey = (element) => `${typeof element.id}:${element.id}`;

/** Indexes of the longest run of `values` that only goes up: the elements of a list that kept their order. */
const longestRising = (values) => {
  const tails = [];
  const tailIndex = [];
  const previous = new Array(values.length).fill(-1);
  values.forEach((value, i) => {
    let lo = 0;
    let hi = tails.length;
    while (lo < hi) {
      const mid = (lo + hi) >> 1;
      if (tails[mid] < value) lo = mid + 1;
      else hi = mid;
    }
    tails[lo] = value;
    tailIndex[lo] = i;
    previous[i] = lo > 0 ? tailIndex[lo - 1] : -1;
  });
  const kept = new Set();
  for (let i = tails.length ? tailIndex[tails.length - 1] : -1; i >= 0; i = previous[i]) kept.add(i);
  return kept;
};

// Only ever reached with two objects or two lists with ids, or at the top: anything else is sent
// whole inside its object's patch (diffObject).
const diffValue = (path, before, after, out) => {
  if (sameData(before, after)) return;
  if (isObject(before) && isObject(after)) diffObject(path, before, after, out);
  else if (isIdList(before) && isIdList(after)) diffList(path, before, after, out);
  else throw new Error("A pack item's data can only be changed inside, never replaced whole.");
};

const diffObject = (path, before, after, out) => {
  const fields = {};
  const deeper = [];
  const keys = [...Object.keys(after), ...Object.keys(before).filter((key) => !has(after, key))];
  keys.forEach((key) => {
    const was = before[key];
    const now = after[key];
    if (sameData(was, now)) return;
    const followable =
      !FORBIDDEN_KEYS.has(key) &&
      ((isObject(was) && isObject(now)) || (isIdList(was) && isIdList(now)));
    if (followable) deeper.push(key);
    else fields[key] = copy(now);
  });
  if (Object.keys(fields).length > 0) out.push({ type: "patch", path, value: fields });
  deeper.forEach((key) => diffValue([...path, key], before[key], after[key], out));
};

const diffList = (path, before, after, out) => {
  const was = new Map(before.map((element, index) => [idKey(element), { element, index }]));
  const now = new Set(after.map(idKey));

  // Those still there whose order did not change stay put; the rest of those still there moved.
  const stayed = after.filter((element) => was.has(idKey(element)));
  const inOrder = longestRising(stayed.map((element) => was.get(idKey(element)).index));
  const moved = new Set(stayed.filter((_, i) => !inOrder.has(i)).map(idKey));

  before.forEach((element) => {
    if (!now.has(idKey(element)) || moved.has(idKey(element))) {
      out.push({ type: "remove", path: [...path, { id: element.id }] });
    }
  });
  after.forEach((element, index) => {
    const key = idKey(element);
    if (!was.has(key) || moved.has(key)) {
      out.push({ type: "insert", path, after: index === 0 ? null : after[index - 1].id, value: copy(element) });
    } else {
      diffValue([...path, { id: element.id }], was.get(key).element, element, out);
    }
  });
};

/**
 * The operations (without `item` or `client_op_id`) that turn `before` into `after`. Applied in
 * order to `before`, they give data equal to `after` (sameData). Throws if the item's data would
 * have to be replaced whole: an object turned into something else, or a list with no ids.
 */
export const diffData = (before, after) => {
  const out = [];
  diffValue([], before, after, out);
  return out;
};

/** The same, as operations on pack item `item`, ready for useMissionPack's edit(). */
export const diffItem = (item, before, after) => diffData(before, after).map((op) => ({ ...op, item }));
