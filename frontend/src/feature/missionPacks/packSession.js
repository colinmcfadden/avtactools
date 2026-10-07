import { CLIENT_OP_TYPES, applyPackOp, validatePackOp } from "./packOps";

/*
 * One open mission pack as this browser holds it: what the server has confirmed
 * (`confirmed`, as of event `seq`), the edits made here it has not confirmed yet
 * (`pending`), and what the person sees (`view`: the pending edits applied over
 * the confirmed items). Every function is pure and returns a new session;
 * packClient.js drives it from the network. See docs/MISSION_PACKS.md, §5.
 *
 * The server's order is the truth. An edit made here shows at once; when the
 * server's events arrive, they are applied to `confirmed` in their order, our own
 * edits among them, and whatever is still pending is applied again on top. Two
 * people moving the same thing therefore both see the later one in the end.
 */

export const MAX_BATCH = 200;

// Events that change an item. Everything else in the log is about the pack itself.
const ITEM_EVENTS = new Set(["item.create", "item.delete", "item.rename", "item.replace", "set", "patch", "upsert", "insert", "remove"]);

const readOnlyFor = (role, status) => status === "finished" || role === "viewer";

const fold = (confirmed, pending) =>
  pending.reduce((items, entry) => applyPackOp(items, entry.op).items, confirmed);

const withView = (session) => ({ ...session, view: fold(session.confirmed, session.pending) });

const infoFrom = (item) => {
  const { data, ...info } = item; // eslint-disable-line no-unused-vars
  return info;
};

/** A session from GET /api/packs/<uuid>. `me` is the signed-in user's id. */
export const openSession = (pack, me) => {
  const confirmed = {};
  const info = {};
  const order = [];
  pack.items.forEach((item) => {
    confirmed[item.uuid] = { kind: item.kind, name: item.name, data: item.data, deleted: false };
    info[item.uuid] = infoFrom(item);
    order.push(item.uuid);
  });
  const { items, members, ...meta } = pack; // eslint-disable-line no-unused-vars
  return withView({
    me,
    pack: meta,
    members,
    confirmed,
    info,
    order,
    seq: pack.head_seq,
    pending: [],
    dropped: [],
    readOnly: readOnlyFor(pack.role, pack.status),
    gone: null,
    diverged: false,
  });
};

/**
 * The server's copy again (after a gap too long to replay, or if this copy ever
 * disagreed with the server), with the edits still pending kept on top.
 */
export const reloadSession = (session, pack) => {
  const fresh = openSession(pack, session.me);
  const pending = fresh.readOnly ? [] : session.pending;
  const lost = fresh.readOnly ? session.pending.map(({ op }) => ({ op, reason: "read_only" })) : [];
  return withView({ ...fresh, pending, dropped: [...session.dropped, ...lost] });
};

/**
 * Edits made here: shown at once, sent in order. Returns { session, refused }:
 * `refused` is why nothing was taken (a read-only pack, or a malformed operation,
 * which is a bug in the caller).
 */
export const edit = (session, ops, newId) => {
  if (session.gone) return { session, refused: "gone" };
  if (session.readOnly) return { session, refused: "read_only" };
  const list = Array.isArray(ops) ? ops : [ops];
  for (const op of list) {
    const invalid = validatePackOp(op);
    if (invalid) return { session, refused: invalid };
    if (!CLIENT_OP_TYPES.includes(op.type)) return { session, refused: "unknown_type" };
  }
  const added = list.map((op) => ({ op: { ...op, client_op_id: newId() }, state: "queued" }));
  return { session: withView({ ...session, pending: [...session.pending, ...added] }), refused: null };
};

/** The next batch to send, or null while one is in flight or nothing waits. */
export const nextBatch = (session) => {
  if (session.pending.some((entry) => entry.state === "sent")) return null;
  const queued = session.pending.filter((entry) => entry.state === "queued").slice(0, MAX_BATCH);
  if (queued.length === 0) return null;
  const sending = new Set(queued);
  return {
    session: { ...session, pending: session.pending.map((entry) => (sending.has(entry) ? { ...entry, state: "sent" } : entry)) },
    batch: { ops: queued.map((entry) => entry.op), base_seq: session.seq },
  };
};

const applyPackEvent = (session, event) => {
  const op = event.op || {};
  const pack = { ...session.pack };
  let { members, gone, readOnly } = session;
  switch (event.type) {
    case "pack.update":
      if (op.name !== undefined) pack.name = op.name;
      if (op.description !== undefined) pack.description = op.description;
      break;
    case "pack.share":
      pack.team = op.team_id == null ? null : { ...(pack.team || {}), id: op.team_id, role: op.team_role };
      break;
    case "pack.finish":
      Object.assign(pack, { status: "finished", finished_at: event.created_at, finished_by: event.actor });
      break;
    case "pack.reopen":
      Object.assign(pack, { status: "active", finished_at: null, finished_by: null });
      break;
    case "pack.transfer":
      pack.owner = { id: op.user_id, name: op.name };
      members = members.map((m) => {
        if (m.user_id === op.user_id) return { ...m, role: "owner" };
        return m.role === "owner" ? { ...m, role: "editor" } : m;
      });
      if (op.user_id === session.me) pack.role = "owner";
      else if (pack.role === "owner") pack.role = "editor";
      break;
    case "member.add":
    case "member.join":
      if (!members.some((m) => m.user_id === op.user_id)) {
        members = [...members, { user_id: op.user_id, name: op.name, email: "", role: op.role, added_at: event.created_at }];
        pack.member_count = (pack.member_count || 0) + 1;
      }
      break;
    case "member.role":
      members = members.map((m) => (m.user_id === op.user_id ? { ...m, role: op.role } : m));
      if (op.user_id === session.me) pack.role = op.role;
      break;
    case "member.remove":
      members = members.filter((m) => m.user_id !== op.user_id);
      pack.member_count = Math.max(0, (pack.member_count || 1) - 1);
      // Still in the pack through a shared team, perhaps; the next request says.
      if (op.user_id === session.me && !pack.team) gone = "removed";
      break;
    default:
      break; // pack.create, invites, and types this version does not know
  }
  if (event.type === "pack.finish" || event.type === "pack.reopen" || event.type === "member.role" || event.type === "pack.transfer") {
    readOnly = readOnlyFor(pack.role, pack.status);
  }
  return { ...session, pack, members, gone, readOnly };
};

// An item.replace is an update from the original, which only whoever copied the item in may make. It
// takes the original as it is now, so for them it is the same again (only they are told either way).
const replacedSource = (session, source, event) => {
  if (!source) return source;
  const original = event.actor?.id === session.me && source.original ? "same" : source.original;
  return { ...source, revision: event.op.source_revision, original };
};

const applyItemEvent = (session, event) => {
  if (event.status !== "applied") return session;
  const result = applyPackOp(session.confirmed, event.op);
  if (result.status !== "applied") return { ...session, diverged: true };
  const uuid = event.op.item;
  const info = { ...session.info };
  let { order } = session;
  const item = result.items[uuid];
  if (event.type === "item.create") {
    const source = event.op.source ? { ...event.op.source, original: null } : null;
    info[uuid] = { uuid, kind: item.kind, name: item.name, revision: 1, seq: event.seq, created_by: event.actor,
      updated_by: event.actor, created_at: event.created_at, updated_at: event.created_at, source };
    order = [...order, uuid];
  } else if (event.type === "item.delete") {
    delete info[uuid];
    order = order.filter((u) => u !== uuid);
  } else if (info[uuid]) {
    const before = info[uuid];
    info[uuid] = { ...before, name: item.name, revision: before.revision + 1, seq: event.seq, updated_by: event.actor,
      updated_at: event.created_at, source: event.type === "item.replace" ? replacedSource(session, before.source, event) : before.source };
  }
  return { ...session, confirmed: result.items, info, order };
};

/**
 * Events from the server (the live stream, a catch-up, a batch's answer), oldest
 * first. Returns { session, gap }: `gap` means an event is missing before the
 * first one given, and the caller fetches from `session.seq`.
 */
export const receive = (session, events) => {
  let next = session;
  for (const event of events) {
    if (event.seq <= next.seq) continue; // already have it
    if (event.seq > next.seq + 1) return { session: settle(next), gap: true };
    next = ITEM_EVENTS.has(event.type) ? applyItemEvent(next, event) : applyPackEvent(next, event);
    next = { ...next, seq: event.seq };
    if (event.client_op_id) {
      next = { ...next, pending: next.pending.filter((entry) => entry.op.client_op_id !== event.client_op_id) };
    }
  }
  return { session: settle(next), gap: false };
};

// A pack that turned read-only (finished, or our role lowered) will never take what is still pending.
const settle = (session) => {
  if ((!session.readOnly && !session.gone) || session.pending.length === 0) return withView(session);
  const reason = session.gone ? "gone" : session.pack.status === "finished" ? "pack_finished" : "read_only";
  return withView({
    ...session,
    pending: [],
    dropped: [...session.dropped, ...session.pending.map(({ op }) => ({ op, reason }))],
  });
};

/** The answer to a batch: its events (everything after base_seq) are applied. */
export const batchAnswered = (session, answer) => {
  const { session: next, gap } = receive(session, answer.events || []);
  // Our operations whose events were not in this page (has_more) are confirmed but not yet seen: never resent.
  const answered = new Set((answer.results || []).map((r) => r.client_op_id));
  const pending = next.pending.map((entry) => (answered.has(entry.op.client_op_id) ? { ...entry, state: "acked" } : entry));
  return { session: withView({ ...next, pending }), catchUp: gap || Boolean(answer.has_more) || pending.some((e) => e.state === "acked") };
};

/**
 * A batch the server did not take. `failure` is { status, code, reason } from the
 * response, or { status: 0 } when nothing came back (sent again later, unchanged,
 * which the server recognises by each operation's client_op_id).
 */
export const batchFailed = (session, failure) => {
  const { status = 0, code } = failure;
  const sent = session.pending.filter((entry) => entry.state === "sent");
  const others = session.pending.filter((entry) => entry.state !== "sent");
  const drop = (entries, reason) => entries.map(({ op }) => ({ op, reason }));

  // Nothing changed on the server: sent again as it was. A 401 waits for the person to sign in again.
  if (status === 0 || status === 401 || status === 429 || status >= 500) {
    return withView({ ...session, pending: session.pending.map((e) => (e.state === "sent" ? { ...e, state: "queued" } : e)) });
  }
  if (status === 423) {
    const pack = { ...session.pack, status: "finished" };
    if (failure.finished_by) Object.assign(pack, { finished_by: failure.finished_by, finished_at: failure.finished_at });
    return withView({ ...session, pack, readOnly: true, pending: [], dropped: [...session.dropped, ...drop(session.pending, "pack_finished")] });
  }
  if (status === 403 && code === "pack_read_only") {
    return withView({ ...session, pack: { ...session.pack, role: "viewer" }, readOnly: true, pending: [],
      dropped: [...session.dropped, ...drop(session.pending, "read_only")] });
  }
  if (status === 404 || status === 403) {
    return withView({ ...session, gone: status === 404 ? "not_found" : "forbidden", pending: [],
      dropped: [...session.dropped, ...drop(session.pending, "gone")] });
  }
  // 400 (a malformed operation: our bug) or 413 (too large): this batch is not taken; the rest still goes.
  return withView({ ...session, pending: others, dropped: [...session.dropped, ...drop(sent, failure.reason || code || `http_${status}`)] });
};

/** Every edit not yet taken given up for `reason` (the person who made them signed out). Taken ones stay. */
export const abandon = (session, reason) => {
  const lost = session.pending.filter((entry) => entry.state === "queued" || entry.state === "sent");
  if (lost.length === 0) return session;
  return withView({
    ...session,
    pending: session.pending.filter((entry) => !lost.includes(entry)),
    dropped: [...session.dropped, ...lost.map(({ op }) => ({ op, reason }))],
  });
};

/** What the person sees: live items in the order they were added, with what the pack knows about each. */
export const visibleItems = (session) =>
  [...session.order, ...Object.keys(session.view).filter((uuid) => !session.order.includes(uuid))]
    .filter((uuid) => session.view[uuid] && !session.view[uuid].deleted)
    .map((uuid) => ({ ...(session.info[uuid] || { uuid, pendingCreate: true }), ...session.view[uuid], uuid }));
