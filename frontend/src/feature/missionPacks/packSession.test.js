import {
  MAX_BATCH,
  batchAnswered,
  batchFailed,
  edit,
  nextBatch,
  openSession,
  receive,
  reloadSession,
  visibleItems,
} from "./packSession";

const COLIN = { id: 1, name: "Colin" };
const SAM = { id: 2, name: "Sam" };
const T = "2026-10-05T12:00:00";

const PACK = {
  uuid: "p-1", name: "OP DK", description: "", status: "active", role: "editor",
  owner: COLIN, team: null, head_seq: 3, member_count: 2, item_count: 1,
  finished_at: null, finished_by: null, created_at: T, updated_at: T, live_url: null,
  members: [
    { user_id: 1, name: "Colin", email: "c@x", role: "owner", added_at: T },
    { user_id: 2, name: "Sam", email: "s@x", role: "editor", added_at: T },
  ],
  items: [{
    uuid: "lz-1", kind: "lz", name: "LZ HAWK", revision: 1, seq: 3, created_by: COLIN, updated_by: COLIN,
    created_at: T, updated_at: T, source: null,
    data: { flightData: { landingHeading: 270, callSign: "HAWK 6" }, graphics: { helicopters: [{ id: "h-1", lat: 34.5 }] } },
  }],
};

let ids = 0;
const newId = () => `op-${++ids}`;
beforeEach(() => { ids = 0; });

const open = (pack = PACK) => openSession(pack, SAM.id);
const heading = (session) => session.view["lz-1"].data.flightData.landingHeading;
const setHeading = (value) => ({ type: "set", item: "lz-1", path: ["flightData", "landingHeading"], value });

/** An event as the server logs it. */
const ev = (seq, op, { actor = COLIN, clientOpId = null, status = "applied", reason = null } = {}) => ({
  seq, type: op.type, item: op.item ?? null, actor, summary: "", status, reason, client_op_id: clientOpId, op, created_at: T,
});

/** Make an edit here and return the session and the id it was given. */
const mine = (session, op) => {
  const result = edit(session, op, newId);
  expect(result.refused).toBeNull();
  return { session: result.session, id: `op-${ids}` };
};

describe("opening", () => {
  it("shows the server's items, with what the pack knows about each", () => {
    const session = open();
    expect(session.seq).toBe(3);
    expect(session.readOnly).toBe(false);
    const [item] = visibleItems(session);
    expect(item.uuid).toBe("lz-1");
    expect(item.name).toBe("LZ HAWK");
    expect(item.created_by).toEqual(COLIN);
    expect(item.data.flightData.landingHeading).toBe(270);
  });

  it("is read-only for a viewer and for a finished pack", () => {
    expect(open({ ...PACK, role: "viewer" }).readOnly).toBe(true);
    expect(open({ ...PACK, status: "finished" }).readOnly).toBe(true);
  });
});

describe("edits made here", () => {
  it("show at once and wait to be sent", () => {
    const { session } = mine(open(), setHeading(90));
    expect(heading(session)).toBe(90);
    expect(session.confirmed["lz-1"].data.flightData.landingHeading).toBe(270);
    expect(session.pending).toEqual([{ op: { ...setHeading(90), client_op_id: "op-1" }, state: "queued" }]);
  });

  it("are refused when malformed, server-only, or the pack is read-only", () => {
    expect(edit(open(), { type: "set", item: "lz-1", path: [], value: 1 }, newId).refused).toBe("bad_path");
    expect(edit(open(), { type: "item.replace", item: "lz-1", data: {} }, newId).refused).toBe("unknown_type");
    expect(edit(open({ ...PACK, role: "viewer" }), setHeading(90), newId).refused).toBe("read_only");
    const twoOps = edit(open(), [setHeading(90), { type: "set", item: "lz-1", path: [0], value: 1 }], newId);
    expect(twoOps.refused).toBe("bad_path");
    expect(twoOps.session.pending).toEqual([]); // all or nothing
  });

  it("go in batches, one at a time, in order", () => {
    let { session } = mine(open(), setHeading(90));
    ({ session } = mine(session, { type: "item.rename", item: "lz-1", name: "LZ EAGLE" }));
    const first = nextBatch(session);
    expect(first.batch).toEqual({ base_seq: 3, ops: session.pending.map((e) => e.op) });
    ({ session } = mine(first.session, setHeading(180)));
    expect(nextBatch(session)).toBeNull(); // one in flight
    expect(session.pending.map((e) => e.state)).toEqual(["sent", "sent", "queued"]);
  });

  it(`go at most ${MAX_BATCH} to a batch`, () => {
    let session = open();
    for (let i = 0; i < MAX_BATCH + 5; i += 1) ({ session } = mine(session, setHeading(i)));
    expect(nextBatch(session).batch.ops).toHaveLength(MAX_BATCH);
  });
});

describe("the server's events", () => {
  it("confirm an edit made here, which then stops being pending", () => {
    const { session, id } = mine(open(), setHeading(90));
    const after = receive(session, [ev(4, setHeading(90), { actor: SAM, clientOpId: id })]).session;
    expect(after.pending).toEqual([]);
    expect(after.seq).toBe(4);
    expect(heading(after)).toBe(90);
    expect(after.confirmed["lz-1"].data.flightData.landingHeading).toBe(90);
    expect(after.info["lz-1"]).toMatchObject({ seq: 4, revision: 2, updated_by: SAM });
  });

  it("from someone else merge with what is pending here when they touch other fields", () => {
    const { session } = mine(open(), setHeading(90));
    const after = receive(session, [ev(4, { type: "set", item: "lz-1", path: ["flightData", "callSign"], value: "HAWK 7" })]).session;
    expect(after.view["lz-1"].data.flightData).toEqual({ landingHeading: 90, callSign: "HAWK 7" });
    expect(after.pending).toHaveLength(1);
  });

  it("leave the later edit in the server's order when two people set one field", () => {
    const { session, id } = mine(open(), setHeading(90));
    // Colin's 180 reached the server first; ours (90) is still pending, so we still see ours on top...
    let after = receive(session, [ev(4, setHeading(180))]).session;
    expect(heading(after)).toBe(90);
    // ...and the server applied ours after it, so 90 is where everyone ends.
    after = receive(after, [ev(5, setHeading(90), { actor: SAM, clientOpId: id })]).session;
    expect(heading(after)).toBe(90);
    expect(after.pending).toEqual([]);
  });

  it("show someone else's later edit once ours is confirmed", () => {
    const { session, id } = mine(open(), setHeading(90));
    const after = receive(session, [ev(4, setHeading(90), { actor: SAM, clientOpId: id }), ev(5, setHeading(180))]).session;
    expect(heading(after)).toBe(180);
  });

  it("are applied once: anything already seen is passed over", () => {
    const session = receive(open(), [ev(4, setHeading(90))]).session;
    const again = receive(session, [ev(3, setHeading(1)), ev(4, setHeading(2))]).session;
    expect(heading(again)).toBe(90);
    expect(again.seq).toBe(4);
  });

  it("stop at a gap, which the caller fills from the server", () => {
    const { session, gap } = receive(open(), [ev(5, setHeading(90))]);
    expect(gap).toBe(true);
    expect(session.seq).toBe(3);
    expect(heading(session)).toBe(270);
  });

  it("that were skipped change nothing but answer the edit", () => {
    const { session, id } = mine(open(), { type: "remove", item: "lz-1", path: ["graphics", "helicopters", { id: "h-9" }] });
    const skipped = ev(4, session.pending[0].op, { actor: SAM, clientOpId: id, status: "skipped", reason: "target_missing" });
    const after = receive(session, [skipped]).session;
    expect(after.pending).toEqual([]);
    expect(after.seq).toBe(4);
    expect(after.confirmed).toBe(session.confirmed);
  });

  it("add and remove items, keeping the order they were added in", () => {
    let session = receive(open(), [ev(4, { type: "item.create", item: "ps-1", kind: "pointset", name: "LOCAL", data: [] })]).session;
    expect(visibleItems(session).map((i) => i.uuid)).toEqual(["lz-1", "ps-1"]);
    expect(session.info["ps-1"]).toMatchObject({ created_by: COLIN, seq: 4, source: null });
    session = receive(session, [ev(5, { type: "item.delete", item: "lz-1" })]).session;
    expect(visibleItems(session).map((i) => i.uuid)).toEqual(["ps-1"]);
    expect(session.info["lz-1"]).toBeUndefined();
  });

  it("show an item made here before the server has it, at the end", () => {
    const { session } = mine(open(), { type: "item.create", item: "lz-2", kind: "lz", name: "LZ CROW", data: {} });
    expect(visibleItems(session).map((i) => [i.uuid, Boolean(i.pendingCreate)])).toEqual([["lz-1", false], ["lz-2", true]]);
  });

  it("notice a copy that has drifted from the server's, so the caller reloads it", () => {
    const drifted = receive(open(), [ev(4, { type: "patch", item: "lz-1", path: ["graphics", "helicopters", { id: "h-9" }], value: { lat: 1 } })]).session;
    expect(drifted.diverged).toBe(true);
    const { session } = mine(drifted, setHeading(90));
    const reloaded = reloadSession(session, { ...PACK, head_seq: 9 });
    expect(reloaded.diverged).toBe(false);
    expect(reloaded.seq).toBe(9);
    expect(heading(reloaded)).toBe(90); // what is pending survives a reload
  });
});

describe("events about the pack", () => {
  it("rename it and record who finished it, and a finished pack drops what is pending", () => {
    const { session } = mine(open(), setHeading(90));
    let after = receive(session, [ev(4, { type: "pack.update", name: "OP EAGLE" })]).session;
    expect(after.pack.name).toBe("OP EAGLE");
    after = receive(after, [ev(5, { type: "pack.finish" })]).session;
    expect(after.pack).toMatchObject({ status: "finished", finished_by: COLIN, finished_at: T });
    expect(after.readOnly).toBe(true);
    expect(after.pending).toEqual([]);
    expect(after.dropped).toEqual([{ op: expect.objectContaining(setHeading(90)), reason: "pack_finished" }]);
    expect(heading(after)).toBe(270);
    after = receive(after, [ev(6, { type: "pack.reopen" })]).session;
    expect(after.readOnly).toBe(false);
  });

  it("follow who is in it and what this person may do", () => {
    let session = receive(open(), [ev(4, { type: "member.join", user_id: 3, name: "Alex", role: "viewer" })]).session;
    expect(session.members.map((m) => m.name)).toEqual(["Colin", "Sam", "Alex"]);
    expect(session.pack.member_count).toBe(3);
    session = receive(session, [ev(5, { type: "pack.transfer", user_id: SAM.id, name: "Sam" })]).session;
    expect(session.pack.role).toBe("owner");
    expect(session.members.find((m) => m.user_id === 1).role).toBe("editor");
    session = receive(session, [ev(6, { type: "member.role", user_id: SAM.id, role: "viewer" })]).session;
    expect(session.readOnly).toBe(true);
    session = receive(session, [ev(7, { type: "member.remove", user_id: SAM.id, name: "Sam" })]).session;
    expect(session.gone).toBe("removed");
  });

  it("of a kind this version does not know are passed over", () => {
    const session = receive(open(), [ev(4, { type: "pack.archive" }), ev(5, setHeading(90))]).session;
    expect(session.seq).toBe(5);
    expect(heading(session)).toBe(90);
  });
});

describe("answers to a batch", () => {
  it("apply everything after base_seq, ours and others'", () => {
    const { session, id } = mine(open(), setHeading(90));
    const sent = nextBatch(session).session;
    const { session: after, catchUp } = batchAnswered(sent, {
      head_seq: 5, has_more: false, results: [{ client_op_id: id, seq: 5, status: "applied", reason: null }],
      events: [ev(4, { type: "item.rename", item: "lz-1", name: "LZ EAGLE" }), ev(5, setHeading(90), { actor: SAM, clientOpId: id })],
    });
    expect(catchUp).toBe(false);
    expect(after.pending).toEqual([]);
    expect(after.view["lz-1"].name).toBe("LZ EAGLE");
  });

  it("whose events did not all fit leave ours confirmed but unseen, never sent again", () => {
    const { session, id } = mine(open(), setHeading(90));
    const sent = nextBatch(session).session;
    const { session: after, catchUp } = batchAnswered(sent, {
      head_seq: 900, has_more: true, results: [{ client_op_id: id, seq: 900, status: "applied", reason: null }], events: [],
    });
    expect(catchUp).toBe(true);
    expect(after.pending.map((e) => e.state)).toEqual(["acked"]);
    expect(nextBatch(after)).toBeNull();
  });

  it("that never came are sent again as they were", () => {
    const { session } = mine(open(), setHeading(90));
    const sent = nextBatch(session);
    const after = batchFailed(sent.session, { status: 0 });
    expect(after.pending.map((e) => e.state)).toEqual(["queued"]);
    expect(nextBatch(after).batch.ops).toEqual(sent.batch.ops); // the same client_op_ids
    expect(batchFailed(sent.session, { status: 401 }).pending).toHaveLength(1); // waits for sign-in
    expect(batchFailed(sent.session, { status: 502 }).pending).toHaveLength(1);
  });

  it("refused because the pack was finished drop everything pending, and say who finished it", () => {
    let { session } = mine(open(), setHeading(90));
    session = nextBatch(session).session;
    ({ session } = mine(session, setHeading(91)));
    const after = batchFailed(session, { status: 423, code: "pack_finished", finished_by: COLIN, finished_at: T });
    expect(after.readOnly).toBe(true);
    expect(after.pack.finished_by).toEqual(COLIN);
    expect(after.pending).toEqual([]);
    expect(after.dropped.map((d) => d.reason)).toEqual(["pack_finished", "pack_finished"]);
    expect(heading(after)).toBe(270);
  });

  it("refused as malformed or too large drop that batch only", () => {
    let { session } = mine(open(), setHeading(90));
    session = nextBatch(session).session;
    ({ session } = mine(session, setHeading(91)));
    const after = batchFailed(session, { status: 413, code: "item_too_large" });
    expect(after.dropped).toEqual([{ op: expect.objectContaining({ value: 90 }), reason: "item_too_large" }]);
    expect(after.pending.map((e) => e.op.value)).toEqual([91]);
    const malformed = batchFailed(session, { status: 400, code: "invalid_op", reason: "bad_path" });
    expect(malformed.dropped[0].reason).toBe("bad_path");
  });

  it("refused because this person may only view, or the pack is gone, drop everything", () => {
    const { session } = mine(open(), setHeading(90));
    const sent = nextBatch(session).session;
    const viewer = batchFailed(sent, { status: 403, code: "pack_read_only" });
    expect(viewer.readOnly).toBe(true);
    expect(viewer.pack.role).toBe("viewer");
    expect(batchFailed(sent, { status: 404, code: "pack_not_found" }).gone).toBe("not_found");
  });
});
