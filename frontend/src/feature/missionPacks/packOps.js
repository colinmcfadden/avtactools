/*
 * What one edit does to a mission pack's items. Every member's edits travel as
 * small operations addressed by stable ids ("move helicopter h-3 on diagram d-1"),
 * never by array position, so two people editing different things never collide,
 * and two editing the same field leave the later one in the server's order.
 *
 * The server applies them one at a time per pack (backend/pack_ops.py is the same
 * rules in Python); a client applies its own at once and everyone else's as they
 * arrive. This module is the reference: it writes contracts/fixtures/packs/ops.json
 * (frontend/src/contracts/packOpsFixtures.test.js), and the backend and the native
 * apps are held to every case there. See docs/MISSION_PACKS.md.
 *
 * `items` maps an item's uuid to { kind, name, data, deleted }, where `data` is the
 * same JSON the library saves (lz_data, route_data or the list of points). Nothing
 * passed in is changed: the result carries new objects for whatever was touched.
 */

export const ITEM_KINDS = ["lz", "route", "pointset"];
// What a client may send. `item.replace` is the server's own (update from original).
export const CLIENT_OP_TYPES = ["item.create", "item.delete", "item.rename", "set", "patch", "upsert", "insert", "remove"];
export const SERVER_OP_TYPES = ["item.replace"];
export const MAX_NAME_LENGTH = 100;

const ITEM_ID = /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}$/;
// A key that would reach an object's prototype in JavaScript. Refused everywhere, so
// every client agrees on what is malformed.
const FORBIDDEN_KEYS = ["__proto__", "constructor", "prototype"];

const has = (object, key) => Object.prototype.hasOwnProperty.call(object, key);
const isObject = (value) => value !== null && typeof value === "object" && !Array.isArray(value);
// An element's id: text or a number. The number 1 is not the text "1".
const isId = (value) => typeof value === "string" || (typeof value === "number" && Number.isFinite(value));
const isKeySegment = (segment) => typeof segment === "string" && !FORBIDDEN_KEYS.includes(segment);
const isIdSegment = (segment) =>
  isObject(segment) && Object.keys(segment).length === 1 && has(segment, "id") && isId(segment.id);
// Not blank, and at most 100 characters counted as code points (as Python and Kotlin count them).
const isName = (name) =>
  typeof name === "string" && /[^ \t\n\r]/.test(name) && Array.from(name).length <= MAX_NAME_LENGTH;
const copy = (value) => JSON.parse(JSON.stringify(value));

const findIndex = (array, id) => array.findIndex((element) => isObject(element) && has(element, "id") && element.id === id);

/** Why `op` is malformed, or null. A malformed operation is a client's bug: the server refuses its whole batch. */
export const validatePackOp = (op) => {
  if (!isObject(op)) return "bad_op";
  if (!CLIENT_OP_TYPES.includes(op.type) && !SERVER_OP_TYPES.includes(op.type)) return "unknown_type";
  if (typeof op.item !== "string" || !ITEM_ID.test(op.item)) return "bad_item";
  switch (op.type) {
    case "item.create":
      if (!ITEM_KINDS.includes(op.kind)) return "bad_kind";
      if (!isName(op.name)) return "bad_name";
      if (op.kind === "pointset" ? !Array.isArray(op.data) : !isObject(op.data)) return "bad_data";
      return null;
    case "item.rename":
      return isName(op.name) ? null : "bad_name";
    case "item.delete":
      return null;
    case "item.replace":
      return isObject(op.data) || Array.isArray(op.data) ? null : "bad_data";
    default:
      break;
  }
  if (!Array.isArray(op.path) || !op.path.every((s) => isKeySegment(s) || isIdSegment(s))) return "bad_path";
  const last = op.path[op.path.length - 1];
  switch (op.type) {
    case "set":
      if (op.path.length === 0) return "bad_path";
      if (!has(op, "value")) return "bad_value";
      // Setting an element whole keeps its identity.
      if (isIdSegment(last) && !(isObject(op.value) && has(op.value, "id") && op.value.id === last.id)) return "bad_value";
      return null;
    case "patch":
      if (!isObject(op.value)) return "bad_value";
      if (isIdSegment(last) && has(op.value, "id") && op.value.id !== last.id) return "bad_value";
      return null;
    case "upsert":
      return isObject(op.value) && has(op.value, "id") && isId(op.value.id) ? null : "bad_value";
    case "insert":
      if (!has(op, "after") || !(op.after === null || isId(op.after))) return "bad_after";
      return isObject(op.value) && has(op.value, "id") && isId(op.value.id) ? null : "bad_value";
    case "remove":
      return isIdSegment(last) ? null : "bad_path";
    default:
      return "unknown_type";
  }
};

// Follows `segments` from `root` without creating anything: { value } or { reason }.
// A key that is missing or null is "target_missing", as is an id no element has.
const follow = (root, segments) => {
  let current = root;
  for (const segment of segments) {
    if (typeof segment === "string") {
      if (!isObject(current)) return { reason: "not_an_object" };
      if (!has(current, segment) || current[segment] === null) return { reason: "target_missing" };
      current = current[segment];
    } else {
      if (!Array.isArray(current)) return { reason: "not_an_array" };
      const index = findIndex(current, segment.id);
      if (index < 0) return { reason: "target_missing" };
      current = current[index];
    }
  }
  return { value: current };
};

// Sets a field. Objects missing (or null) on the way are made, unless an id has to be
// found inside one, which can never succeed.
const set = (root, path, value) => {
  const parentPath = path.slice(0, -1);
  const last = path[path.length - 1];
  let current = root;
  for (let i = 0; i < parentPath.length; i += 1) {
    const segment = parentPath[i];
    if (typeof segment === "string") {
      if (!isObject(current)) return "not_an_object";
      if (!has(current, segment) || current[segment] === null) {
        if (!path.slice(i + 1).every((s) => typeof s === "string")) return "target_missing";
        for (const key of parentPath.slice(i)) {
          current[key] = {};
          current = current[key];
        }
        current[last] = copy(value);
        return null;
      }
      current = current[segment];
    } else {
      if (!Array.isArray(current)) return "not_an_array";
      const index = findIndex(current, segment.id);
      if (index < 0) return "target_missing";
      current = current[index];
    }
  }
  if (typeof last === "string") {
    if (!isObject(current)) return "not_an_object";
    current[last] = copy(value);
    return null;
  }
  if (!Array.isArray(current)) return "not_an_array";
  const index = findIndex(current, last.id);
  if (index < 0) return "target_missing";
  current[index] = copy(value);
  return null;
};

const patch = (root, path, value) => {
  const found = follow(root, path);
  if (found.reason) return found.reason;
  if (!isObject(found.value)) return "not_an_object";
  // Defined, not assigned: Object.assign would run the __proto__ setter for a key of that name.
  Object.entries(copy(value)).forEach(([key, field]) => {
    Object.defineProperty(found.value, key, { value: field, writable: true, enumerable: true, configurable: true });
  });
  return null;
};

// The array a path names, made empty if its last key is missing or null: { array, make } or { reason }.
const arrayAt = (root, path) => {
  if (path.length === 0) return Array.isArray(root) ? { array: root } : { reason: "not_an_array" };
  const last = path[path.length - 1];
  const parent = follow(root, path.slice(0, -1));
  if (parent.reason) return parent;
  if (typeof last === "string") {
    if (!isObject(parent.value)) return { reason: "not_an_object" };
    if (!has(parent.value, last) || parent.value[last] === null) return { array: [], make: () => { parent.value[last] = []; return parent.value[last]; } };
    return Array.isArray(parent.value[last]) ? { array: parent.value[last] } : { reason: "not_an_array" };
  }
  const found = follow(parent.value, [last]);
  if (found.reason) return found;
  return { reason: "not_an_array" };
};

const upsert = (root, path, value) => {
  const target = arrayAt(root, path);
  if (target.reason) return target.reason;
  const array = target.make ? target.make() : target.array;
  const index = findIndex(array, value.id);
  if (index < 0) array.push(copy(value));
  else array[index] = { ...array[index], ...copy(value) };
  return null;
};

// After the element with id `after`; first when `after` is null; last when that element is gone.
const insert = (root, path, after, value) => {
  const target = arrayAt(root, path);
  if (target.reason) return target.reason;
  if (findIndex(target.array, value.id) >= 0) return "element_exists";
  const array = target.make ? target.make() : target.array;
  if (after === null) {
    array.unshift(copy(value));
    return null;
  }
  const index = findIndex(array, after);
  if (index < 0) array.push(copy(value));
  else array.splice(index + 1, 0, copy(value));
  return null;
};

const remove = (root, path) => {
  const found = follow(root, path.slice(0, -1));
  if (found.reason) return found.reason;
  if (!Array.isArray(found.value)) return "not_an_array";
  const index = findIndex(found.value, path[path.length - 1].id);
  if (index < 0) return "target_missing";
  found.value.splice(index, 1);
  return null;
};

const EDITS = {
  set: (data, op) => set(data, op.path, op.value),
  patch: (data, op) => patch(data, op.path, op.value),
  upsert: (data, op) => upsert(data, op.path, op.value),
  insert: (data, op) => insert(data, op.path, op.after, op.value),
  remove: (data, op) => remove(data, op.path),
};

/**
 * Applies one operation: { items, status, reason }. `status` is "applied", "skipped"
 * (well formed, but what it edits is gone or in the way; `items` is unchanged) or
 * "invalid" (malformed; see validatePackOp).
 */
export const applyPackOp = (items, op) => {
  const invalid = validatePackOp(op);
  if (invalid) return { items, status: "invalid", reason: invalid };
  const skipped = (reason) => ({ items, status: "skipped", reason });
  const applied = (item) => ({ items: { ...items, [op.item]: item }, status: "applied", reason: null });

  const current = has(items, op.item) ? items[op.item] : null;
  if (op.type === "item.create") {
    // A deleted item's uuid is never reused.
    if (current) return skipped("item_exists");
    return applied({ kind: op.kind, name: op.name, data: copy(op.data), deleted: false });
  }
  if (!current || current.deleted) return skipped("item_missing");

  switch (op.type) {
    case "item.rename":
      return applied({ ...current, name: op.name });
    case "item.delete":
      // Deleted means gone: the content is not kept.
      return applied({ ...current, name: "", data: null, deleted: true });
    case "item.replace":
      if (current.kind === "pointset" && !Array.isArray(op.data)) return skipped("not_an_array");
      if (current.kind !== "pointset" && !isObject(op.data)) return skipped("not_an_object");
      return applied({ ...current, data: copy(op.data) });
    default: {
      const data = copy(current.data);
      const reason = EDITS[op.type](data, op);
      return reason ? skipped(reason) : applied({ ...current, data });
    }
  }
};
