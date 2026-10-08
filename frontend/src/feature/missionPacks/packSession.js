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
 * disagreed with the server), with the edits still pending kept on top. The copy
 * holds every edit the server took up to its head_seq, ours among them. A copy that
 * is read-only is settled as an event that made it so would be (see settle).
 */
export const reloadSession = (session, pack) => {
  const fresh = openSession(pack, session.me);
  return settle(withoutReached({ ...fresh, pending: session.pending, dropped: session.dropped }));
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

// An item.replace is an update from the original, which the server lets only whoever copied the item in
// make, and only of a copy that names its original. Afterwards the source says what the server would then
// say (pack_support.source_body). To them the original is the same again, and while it is the same the
// server counts nothing an update would replace: pack_changes and last_pack_change are null. The event does
// not say when the original last changed (original_updated_at): the update leaves the original as it was,
// but it may have changed between the pack being loaded and the update, so that is not known here (null)
// until the pack is loaded again. Everyone else is never told anything about another person's original:
// all four are null.
const replacedSource = (session, source, event) => {
  if (!source) return source;
  const mine = event.actor?.id === session.me && Boolean(source.uuid);
  return { ...source, revision: event.op.source_revision, original: mine ? "same" : null,
    original_updated_at: null, pack_changes: null, last_pack_change: null };
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
    if (event.seq > next.seq + 1) return { session: settle(withHead(next)), gap: true };
    next = ITEM_EVENTS.has(event.type) ? applyItemEvent(next, event) : applyPackEvent(next, event);
    next = { ...next, seq: event.seq };
    if (event.client_op_id) {
      next = { ...next, pending: next.pending.filter((entry) => entry.op.client_op_id !== event.client_op_id) };
    }
  }
  return { session: settle(withHead(next)), gap: false };
};

// The pack's head_seq follows the newest event applied here, so what is keyed on it (the History) keeps up.
const withHead = (session) =>
  session.seq > (session.pack.head_seq ?? 0) ? { ...session, pack: { ...session.pack, head_seq: session.seq } } : session;

// What may be dropped: the edits the server has not said it took. One it took (acked) is the pack's, whatever
// happens to the pack after, so it is never offered back as the person's own; it stays until it is confirmed.
const untaken = (entry) => entry.state === "queued" || entry.state === "sent";

// Dropped in the order they were made (pending's order), all at once: droppedVersions applies them in that order.
const dropUntaken = (session, reason) => withView({
  ...session,
  pending: session.pending.filter((entry) => !untaken(entry)),
  dropped: [...session.dropped, ...session.pending.filter(untaken).map(({ op }) => ({ op, reason }))],
});

// A pack that turned read-only (finished, or our role lowered) takes nothing more, so what is queued is dropped.
// Not while a batch is out: the server decides only when the batch reaches the pack, so it may take it yet (it
// took it before the finish, or takes it after a reopen). Its answer settles it: a refusal drops it with what is
// queued behind it, a 200 acks it and what is queued is dropped then. Dropping the queued edits first would put
// them before older ones in `dropped`, and my version would end on the older value. A pack that is gone drops the
// batch out too: the client stops, and nothing hears its answer.
const settle = (session) => {
  const waiting = session.pending.some((entry) => entry.state === "sent");
  if (!session.gone && (!session.readOnly || waiting)) return withView(session);
  if (!session.pending.some(untaken)) return withView(session);
  return dropUntaken(session, session.gone ? "gone" : session.pack.status === "finished" ? "pack_finished" : "read_only");
};

// An edit the server took is in `confirmed` once seq has reached the event its result named, whether or
// not that event was applied here: a reload, or events that came before the batch's answer, can move seq
// past it, and receive passes over what it already has. Left pending it would wait for an event that never
// comes and be applied over the view for good, over a later change to the same field too. One whose result
// named no seq waits for its event, as there is nothing else to go by.
const withoutReached = (session) => {
  const reached = (entry) => entry.state === "acked" && entry.seq != null && entry.seq <= session.seq;
  return session.pending.some(reached) ? { ...session, pending: session.pending.filter((entry) => !reached(entry)) } : session;
};

/** The answer to a batch: its events (everything after base_seq) are applied. */
export const batchAnswered = (session, answer) => {
  // What the results say the server took is acked before the page is read, so nothing on it (a finish or a
  // removal, on a page that ends there: has_more) can drop it. Each is held until its event comes or seq reaches
  // the event its result names (a skipped one too: its event changes nothing), and is never sent again.
  const answered = new Map((answer.results || []).map((r) => [r.client_op_id, r]));
  const pending = session.pending.map((entry) => {
    const result = answered.get(entry.op.client_op_id);
    return result ? { ...entry, state: "acked", seq: result.seq ?? null } : entry;
  });
  const { session: next, gap } = receive({ ...session, pending }, answer.events || []);
  const after = withoutReached(next);
  return { session: withView(after), catchUp: gap || Boolean(answer.has_more) || after.pending.some((e) => e.state === "acked") };
};

/**
 * A batch the server did not take. `failure` is { status, code, reason, finished_by,
 * finished_at } from the response (packApi.failureOf), or { status: 0 } when nothing
 * came back (sent again later, unchanged, which the server recognises by each
 * operation's client_op_id). Only what the server has not said it took is dropped.
 *
 * Known gap (docs/MISSION_PACKS.md §5, rule 7): a batch whose answer was lost may
 * have been taken. Sent again, it is refused (423, 403 pack_read_only, or a 413 with
 * another operation in it) before the server looks for what it already has, so the
 * refusal cannot say, and it is dropped with the rest. Only the server can close that.
 */
export const batchFailed = (session, failure) => {
  const { status = 0, code } = failure;
  const sent = session.pending.filter((entry) => entry.state === "sent");
  const others = session.pending.filter((entry) => entry.state !== "sent");
  const drop = (entries, reason) => entries.map(({ op }) => ({ op, reason }));

  // Sent again as it was, and the next answer decides: none came (the server may have taken it), or it was
  // refused before it was looked at (a 401 waits for the person to sign in again). Not settled here, in a pack
  // seen read-only either: the batch may have been taken, and only the server can say.
  if (status === 0 || status === 401 || status === 429 || status >= 500) {
    return withView({ ...session, pending: session.pending.map((e) => (e.state === "sent" ? { ...e, state: "queued" } : e)) });
  }
  // From here the pack takes nothing more of ours, but what it took already (acked) stays.
  if (status === 423) {
    const pack = { ...session.pack, status: "finished" };
    // Each the body has, null too: whoever finished it may have deleted their account since, and when still holds.
    if (failure.finished_by !== undefined) pack.finished_by = failure.finished_by;
    if (failure.finished_at !== undefined) pack.finished_at = failure.finished_at;
    return dropUntaken({ ...session, pack, readOnly: true }, "pack_finished");
  }
  if (status === 403 && code === "pack_read_only") {
    return dropUntaken({ ...session, pack: { ...session.pack, role: "viewer" }, readOnly: true }, "read_only");
  }
  if (status === 404 || status === 403) {
    return dropUntaken({ ...session, gone: status === 404 ? "not_found" : "forbidden" }, "gone");
  }
  // 400 (a malformed operation: our bug) or 413 (too large): this batch is not taken; the rest still goes, unless
  // the pack turned read-only while it was out, when the rest is dropped behind it, in order.
  return settle({ ...session, pending: others, dropped: [...session.dropped, ...drop(sent, failure.reason || code || `http_${status}`)] });
};

/** Every edit not yet taken given up for `reason` (the person who made them signed out). Taken ones stay. */
export const abandon = (session, reason) => (session.pending.some(untaken) ? dropUntaken(session, reason) : session);

/** What the person sees: live items in the order they were added, with what the pack knows about each. */
export const visibleItems = (session) =>
  [...session.order, ...Object.keys(session.view).filter((uuid) => !session.order.includes(uuid))]
    .filter((uuid) => session.view[uuid] && !session.view[uuid].deleted)
    .map((uuid) => ({ ...(session.info[uuid] || { uuid, pendingCreate: true }), ...session.view[uuid], uuid }));

/**
 * The edits this person made that the pack did not take (it was finished, or they were made a
 * viewer, or removed, while the edits were on their way: the session's `dropped`, in the order
 * they were made), as their own version of each item they touched: the pack's confirmed item
 * with those edits applied in order.
 * What cannot apply any more (its target gone) is left out, as the pack would have left it.
 * [{ uuid, kind, name, data }], in the order the items were first touched. (packActions.js
 * re-exports it, where the screens find it beside saving to the library.)
 */
export const droppedVersions = (session) => {
  const versions = new Map();
  (session?.dropped ?? []).forEach(({ op }) => {
    if (!op?.item) return;
    if (!versions.has(op.item)) {
      const confirmed = session.confirmed?.[op.item];
      versions.set(op.item, { [op.item]: confirmed && !confirmed.deleted ? confirmed : null });
    }
    const items = versions.get(op.item);
    // An item made here and never taken starts from its own item.create.
    const result = applyPackOp(items[op.item] ? items : {}, op);
    if (result.status === "applied") versions.set(op.item, result.items);
  });
  return [...versions.entries()]
    .map(([uuid, items]) => ({ uuid, item: items[uuid] }))
    .filter(({ item }) => item && !item.deleted)
    .map(({ uuid, item }) => ({ uuid, kind: item.kind, name: item.name, data: item.data }));
};
