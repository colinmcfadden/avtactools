import {
  MAX_BATCH,
  batchAnswered,
  batchFailed,
  droppedVersions,
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

  it("say an item updated from its original is the same as it again, to whoever copied it in", () => {
    const source = { kind: "lz", uuid: "lib-1", revision: 1, original: "changed" };
    const copied = { ...PACK, items: [{ ...PACK.items[0], created_by: SAM, source }] };
    const replace = ev(4, { type: "item.replace", item: "lz-1", data: { flightData: { landingHeading: 90 } }, source_revision: 3 }, { actor: SAM });
    const session = receive(open(copied), [replace]).session;
    expect(visibleItems(session)[0].source).toEqual({
      ...source, revision: 3, original: "same", original_updated_at: null, pack_changes: null, last_pack_change: null,
    });
    // Everyone else is never told about another person's original.
    const theirs = receive(openSession({ ...copied, items: [{ ...copied.items[0], source: { ...source, original: null } }] }, COLIN.id), [replace]).session;
    expect(visibleItems(theirs)[0].source.original).toBeNull();
  });

  it("keep the pack's head at the newest event, ours included", () => {
    let session = receive(open(), [ev(4, setHeading(90)), ev(5, setHeading(95))]).session;
    expect(session.pack.head_seq).toBe(5);
    const sent = nextBatch(mine(session, setHeading(100)).session);
    session = batchAnswered(sent.session, { head_seq: 6, has_more: false, results: [{ client_op_id: "op-1", seq: 6, status: "applied", reason: null }],
      events: [ev(6, setHeading(100), { actor: SAM, clientOpId: "op-1" })] }).session;
    expect(session.pack.head_seq).toBe(6);
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

  it("refused with a 423 that names nobody (whoever finished it has since deleted their account) still say when it was finished", () => {
    const sent = nextBatch(mine(open(), setHeading(90)).session).session;
    const after = batchFailed(sent, { status: 423, code: "pack_finished", finished_by: null, finished_at: T });
    expect(after.pack).toMatchObject({ status: "finished", finished_by: null, finished_at: T });
    // A body with neither (not the server's) leaves them as they were.
    const bare = batchFailed(sent, { status: 423 });
    expect(bare.pack).toMatchObject({ status: "finished", finished_by: null, finished_at: null });
  });
});

const setCallSign = (value) => ({ type: "set", item: "lz-1", path: ["flightData", "callSign"], value });

/** An edit the server took at `seq` whose event was not on the answer's page (has_more): left "acked". */
const takenUnseen = (session, op, seq) => {
  const made = mine(session, op);
  const sent = nextBatch(made.session).session;
  const answer = { head_seq: seq, has_more: true, results: [{ client_op_id: made.id, seq, status: "applied", reason: null }], events: [] };
  return { session: batchAnswered(sent, answer).session, id: made.id };
};

/** The pack as loaded again at `headSeq`, its LZ holding `flightData`. */
const reloaded = (headSeq, flightData, extra = {}) => ({
  ...PACK, head_seq: headSeq, ...extra, items: [{ ...PACK.items[0], seq: headSeq, data: { ...PACK.items[0].data, flightData } }],
});

describe("edits the server took but whose event is not seen yet", () => {
  it("keep the seq their result gave, and are shown until that event comes", () => {
    const { session, id } = takenUnseen(open(), setHeading(90), 7);
    expect(session.pending).toEqual([{ op: { ...setHeading(90), client_op_id: id }, state: "acked", seq: 7 }]);
    expect(heading(session)).toBe(90);
    const after = receive(session, [ev(4, setCallSign("HAWK 7")), ev(5, setCallSign("HAWK 8")), ev(6, setCallSign("HAWK 9")),
      ev(7, setHeading(90), { actor: SAM, clientOpId: id })]).session;
    expect(after.pending).toEqual([]);
  });

  it("are never dropped by a 423: the pack has them, so they are not offered back as my own version", () => {
    let { session } = takenUnseen(open(), setCallSign("HAWK 9"), 7);
    session = nextBatch(mine(session, setHeading(91)).session).session;
    const after = batchFailed(session, { status: 423, code: "pack_finished", finished_by: COLIN, finished_at: T });
    expect(after.pending.map((e) => [e.op.value, e.state])).toEqual([["HAWK 9", "acked"]]);
    expect(after.dropped).toEqual([{ op: expect.objectContaining(setHeading(91)), reason: "pack_finished" }]);
    expect(after.view["lz-1"].data.flightData).toEqual({ landingHeading: 270, callSign: "HAWK 9" });
    expect(droppedVersions(after).map((v) => v.data.flightData)).toEqual([{ landingHeading: 91, callSign: "HAWK 6" }]);
  });

  it("are never dropped by a 403 or a 404 either", () => {
    let { session } = takenUnseen(open(), setCallSign("HAWK 9"), 7);
    session = nextBatch(mine(session, setHeading(91)).session).session;
    [{ status: 403, code: "pack_read_only" }, { status: 403, code: "feature_disabled" }, { status: 404, code: "pack_not_found" }].forEach((failure) => {
      const after = batchFailed(session, failure);
      expect(after.pending.map((e) => [e.op.value, e.state])).toEqual([["HAWK 9", "acked"]]);
      expect(after.dropped.map((d) => d.op.value)).toEqual([91]);
    });
  });

  it("are never dropped when an event makes the pack read-only, or takes this person out of it", () => {
    let { session } = takenUnseen(open(), setCallSign("HAWK 9"), 9);
    ({ session } = mine(session, setHeading(91)));
    [{ type: "pack.finish" }, { type: "member.role", user_id: SAM.id, role: "viewer" }, { type: "member.remove", user_id: SAM.id, name: "Sam" }]
      .forEach((op) => {
        const after = receive(session, [ev(4, op)]).session;
        expect(after.pending.map((e) => [e.op.value, e.state])).toEqual([["HAWK 9", "acked"]]);
        expect(after.dropped.map((d) => d.op.value)).toEqual([91]);
      });
  });

  it("are confirmed by a reload that has passed their event, which will not come again, so a later change to the field shows", () => {
    const { session } = takenUnseen(open(), setHeading(90), 7);
    const after = reloadSession(session, reloaded(7, { landingHeading: 90, callSign: "HAWK 6" }));
    expect(after.pending).toEqual([]);
    expect(heading(receive(after, [ev(8, setHeading(180))]).session)).toBe(180);
  });

  it("stay after a reload that has not reached their event", () => {
    const { session } = takenUnseen(open(), setHeading(90), 7);
    const after = reloadSession(session, reloaded(6, { landingHeading: 270, callSign: "HAWK 6" }));
    expect(after.pending.map((e) => e.state)).toEqual(["acked"]);
    expect(heading(after)).toBe(90);
  });

  it("are confirmed by their result when a reload while the batch was out already holds their event", () => {
    const { session, id } = mine(open(), setHeading(90));
    const sent = nextBatch(session).session;
    const loaded = reloadSession(sent, reloaded(4, { landingHeading: 90, callSign: "HAWK 6" }));
    const { session: after, catchUp } = batchAnswered(loaded, {
      head_seq: 4, has_more: false, results: [{ client_op_id: id, seq: 4, status: "applied", reason: null }],
      events: [ev(4, setHeading(90), { actor: SAM, clientOpId: id })],
    });
    expect(after.pending).toEqual([]);
    expect(catchUp).toBe(false);
  });

  it("are confirmed by a result answered from the log, behind base_seq, when a resend follows a reload", () => {
    const { session, id } = mine(open(), setHeading(90));
    // The first send was taken at 4, but its answer was lost; a reload then moved past it.
    const lost = batchFailed(nextBatch(session).session, { status: 0 });
    const loaded = reloadSession(lost, reloaded(5, { landingHeading: 90, callSign: "HAWK 7" }));
    const again = nextBatch(loaded);
    expect(again.batch.base_seq).toBe(5);
    const { session: after, catchUp } = batchAnswered(again.session, {
      head_seq: 5, has_more: false, results: [{ client_op_id: id, seq: 4, status: "applied", reason: null }], events: [],
    });
    expect(after.pending).toEqual([]);
    expect(catchUp).toBe(false);
  });

  it("that the server skipped are confirmed the same way", () => {
    const { session, id } = mine(open(), { type: "remove", item: "lz-1", path: ["graphics", "helicopters", { id: "h-9" }] });
    const taken = batchAnswered(nextBatch(session).session, {
      head_seq: 6, has_more: true, results: [{ client_op_id: id, seq: 5, status: "skipped", reason: "target_missing" }], events: [],
    }).session;
    expect(taken.pending.map((e) => [e.state, e.seq])).toEqual([["acked", 5]]);
    expect(reloadSession(taken, { ...PACK, head_seq: 6 }).pending).toEqual([]);
  });

  it("whose result names no seq wait for their event, as there is nothing else to go by", () => {
    const { session, id } = mine(open(), setHeading(90));
    const taken = batchAnswered(nextBatch(session).session, {
      head_seq: 7, has_more: true, results: [{ client_op_id: id, status: "applied", reason: null }], events: [],
    }).session;
    expect(taken.pending.map((e) => [e.state, e.seq])).toEqual([["acked", null]]);
    expect(reloadSession(taken, reloaded(9, { landingHeading: 90, callSign: "HAWK 6" })).pending).toHaveLength(1);
  });

  it("named by an answer whose events stop at a gap wait for the catch-up that fills it", () => {
    const { session, id } = mine(open(), setHeading(90));
    const { session: after, catchUp } = batchAnswered(nextBatch(session).session, {
      head_seq: 6, has_more: false, results: [{ client_op_id: id, seq: 6, status: "applied", reason: null }],
      events: [ev(5, setCallSign("HAWK 8")), ev(6, setHeading(90), { actor: SAM, clientOpId: id })],
    });
    expect(catchUp).toBe(true);
    expect(after.seq).toBe(3);
    expect(after.pending.map((e) => [e.state, e.seq])).toEqual([["acked", 6]]);
    const filled = receive(after, [
      ev(4, setCallSign("HAWK 7")), ev(5, setCallSign("HAWK 8")), ev(6, setHeading(90), { actor: SAM, clientOpId: id }),
    ]);
    expect(filled.session.pending).toEqual([]);
  });
});

const FINISH = { type: "pack.finish" };
const FINISHED_423 = { status: 423, code: "pack_finished", finished_by: COLIN, finished_at: T };
const states = (session) => session.pending.map((e) => [e.op.value, e.state]);
const drops = (session) => session.dropped.map((d) => [d.op.value, d.reason]);

describe("a pack that turns read-only while a batch is out", () => {
  const MADE_VIEWER = { type: "member.role", user_id: SAM.id, role: "viewer" };

  /** 90 sent, 91 queued behind it. */
  const outAndQueued = () => {
    const sent = nextBatch(mine(open(), setHeading(90)).session).session;
    return mine(sent, setHeading(91)).session;
  };

  it("drops nothing until the batch is answered, as the server may take it yet, and refuses new edits meanwhile", () => {
    const seen = [
      receive(outAndQueued(), [ev(4, FINISH)]).session,
      receive(outAndQueued(), [ev(4, MADE_VIEWER)]).session,
      reloadSession(outAndQueued(), { ...PACK, head_seq: 4, status: "finished", finished_by: COLIN, finished_at: T }),
      reloadSession(outAndQueued(), { ...PACK, head_seq: 4, role: "viewer" }),
    ];
    seen.forEach((session) => {
      expect(session.readOnly).toBe(true);
      expect(states(session)).toEqual([[90, "sent"], [91, "queued"]]);
      expect(session.dropped).toEqual([]);
      expect(nextBatch(session)).toBeNull();
      expect(edit(session, setHeading(92), newId).refused).toBe("read_only");
    });
  });

  it("then drops it on a refusal with what was queued behind it, in the order they were made, so my version ends on my last edit", () => {
    const finished = batchFailed(receive(outAndQueued(), [ev(4, FINISH)]).session, FINISHED_423);
    expect(drops(finished)).toEqual([[90, "pack_finished"], [91, "pack_finished"]]);
    expect(droppedVersions(finished)[0].data.flightData.landingHeading).toBe(91);
    const viewer = batchFailed(receive(outAndQueued(), [ev(4, MADE_VIEWER)]).session, { status: 403, code: "pack_read_only" });
    expect(drops(viewer)).toEqual([[90, "read_only"], [91, "read_only"]]);
    // Too large: that batch only, and then the rest, as the pack is read-only.
    const tooLarge = batchFailed(receive(outAndQueued(), [ev(4, FINISH)]).session, { status: 413, code: "item_too_large" });
    expect(drops(tooLarge)).toEqual([[90, "item_too_large"], [91, "pack_finished"]]);
    expect(tooLarge.pending).toEqual([]);
  });

  it("or, when the answer says it was taken, keeps it and drops only what was queued behind it", () => {
    // Taken at 4, finished at 5; a reload saw the finish before the answer came.
    const loaded = reloadSession(outAndQueued(), reloaded(5, { landingHeading: 90, callSign: "HAWK 6" },
      { status: "finished", finished_by: COLIN, finished_at: T }));
    const { session: after, catchUp } = batchAnswered(loaded, {
      head_seq: 4, has_more: false, results: [{ client_op_id: "op-1", seq: 4, status: "applied", reason: null }],
      events: [ev(4, setHeading(90), { actor: SAM, clientOpId: "op-1" })],
    });
    expect(after.pending).toEqual([]);
    expect(drops(after)).toEqual([[91, "pack_finished"]]);
    expect(catchUp).toBe(false);
    expect(heading(after)).toBe(90);
  });

  it("sends it again when its answer was lost, with what was queued behind it, and the pack decides", () => {
    const lost = batchFailed(receive(outAndQueued(), [ev(4, FINISH)]).session, { status: 0 });
    expect(states(lost)).toEqual([[90, "queued"], [91, "queued"]]);
    expect(lost.dropped).toEqual([]);
    const again = nextBatch(lost);
    expect(again.batch.ops.map((op) => op.value)).toEqual([90, 91]);
    const reopened = batchAnswered(again.session, {
      head_seq: 7, has_more: false,
      results: [{ client_op_id: "op-1", seq: 6, status: "applied", reason: null }, { client_op_id: "op-2", seq: 7, status: "applied", reason: null }],
      events: [ev(5, { type: "pack.reopen" }), ev(6, setHeading(90), { actor: SAM, clientOpId: "op-1" }),
        ev(7, setHeading(91), { actor: SAM, clientOpId: "op-2" })],
    }).session;
    expect(reopened.readOnly).toBe(false);
    expect(reopened.pending).toEqual([]);
    expect(reopened.dropped).toEqual([]);
  });

  it("but drops it at once when this person is removed, as nothing waits for its answer then", () => {
    const removed = receive(outAndQueued(), [ev(4, { type: "member.remove", user_id: SAM.id, name: "Sam" })]).session;
    expect(removed.gone).toBe("removed");
    expect(drops(removed)).toEqual([[90, "gone"], [91, "gone"]]);
  });
});

// The first send was taken but its answer was lost, so the same batch went again (with what was made meanwhile), and the
// pack refused it: finished, or I was made a viewer, or what was added to it was too large. The refusal says what of the
// batch the pack already has (`taken`, docs/MISSION_PACKS.md §4), and that is the pack's, not mine to save.
describe("a batch sent again after its answer was lost, and refused", () => {
  const took = (id, seq, status = "applied", reason = null) => ({ client_op_id: id, seq, status, reason });
  const held = (session) => session.pending.map((e) => [e.op.client_op_id, e.state, e.seq]);
  const dropIds = (session) => session.dropped.map((d) => [d.op.client_op_id, d.reason]);

  /** 90 sent and its answer lost, 91 made meanwhile: both go again, in one batch. */
  const resent = () => {
    const lost = batchFailed(nextBatch(mine(open(), setHeading(90)).session).session, { status: 0 });
    return nextBatch(mine(lost, setHeading(91)).session);
  };

  it("keeps what a 423 says the pack took the first time and drops only the rest, so my version ends on my last edit", () => {
    const again = resent();
    expect(again.batch.ops.map((op) => op.value)).toEqual([90, 91]);
    const refused = batchFailed(again.session, { ...FINISHED_423, taken: [took("op-1", 4)] });
    expect(held(refused)).toEqual([["op-1", "acked", 4]]);
    expect(drops(refused)).toEqual([[91, "pack_finished"]]);
    expect(refused.readOnly).toBe(true);
    expect(heading(refused)).toBe(90); // the pack's, until its event shows it
    expect(droppedVersions(refused).map((v) => v.data.flightData.landingHeading)).toEqual([91]);
    const seen = receive(refused, [ev(4, setHeading(90), { actor: SAM, clientOpId: "op-1" }), ev(5, FINISH)]).session;
    expect(seen.pending).toEqual([]);
    expect(drops(seen)).toEqual([[91, "pack_finished"]]);
    expect(heading(seen)).toBe(90);
  });

  it("keeps what a 403 pack_read_only says the pack took, one it skipped too, and drops the rest as read_only", () => {
    const remove = { type: "remove", item: "lz-1", path: ["graphics", "helicopters", { id: "h-9" }] };
    const lost = batchFailed(nextBatch(edit(open(), [setHeading(90), remove], newId).session).session, { status: 0 });
    const again = nextBatch(mine(lost, setHeading(91)).session);
    expect(again.batch.ops.map((op) => op.client_op_id)).toEqual(["op-1", "op-2", "op-3"]);
    const refused = batchFailed(again.session, { status: 403, code: "pack_read_only",
      taken: [took("op-1", 4), took("op-2", 5, "skipped", "target_missing")] });
    expect(held(refused)).toEqual([["op-1", "acked", 4], ["op-2", "acked", 5]]);
    expect(dropIds(refused)).toEqual([["op-3", "read_only"]]);
    expect(refused.pack.role).toBe("viewer");
  });

  it("keeps what a 413 says the pack took when the batch grew by an edit too large, drops only that batch's rest, and "
    + "sends what was queued behind it", () => {
    const lost = batchFailed(nextBatch(mine(open(), setHeading(90)).session).session, { status: 0 });
    const again = nextBatch(mine(lost, setCallSign("HAWK 9")).session);
    const queued = mine(again.session, setHeading(92)).session;
    const refused = batchFailed(queued, { status: 413, code: "item_too_large", taken: [took("op-1", 4)] });
    expect(held(refused)).toEqual([["op-1", "acked", 4], ["op-3", "queued", undefined]]);
    expect(dropIds(refused)).toEqual([["op-2", "item_too_large"]]);
    expect(nextBatch(refused).batch.ops.map((op) => op.client_op_id)).toEqual(["op-3"]);
  });

  it("lets go at once of what it says the pack took when a reload has already passed that event, which will not come again", () => {
    const lost = batchFailed(nextBatch(mine(open(), setHeading(90)).session).session, { status: 0 });
    const loaded = reloadSession(lost, reloaded(4, { landingHeading: 90, callSign: "HAWK 6" }));
    const again = nextBatch(loaded);
    expect(again.batch.base_seq).toBe(4);
    const refused = batchFailed(again.session, { ...FINISHED_423, taken: [took("op-1", 4)] });
    expect(refused.pending).toEqual([]);
    expect(refused.dropped).toEqual([]);
    expect(heading(refused)).toBe(90);
  });

  it("from a server that does not say what it took (no taken) drops the whole batch, as before", () => {
    // Passes before and after the change: an older server's refusal reads as nothing taken.
    const again = resent();
    expect(drops(batchFailed(again.session, FINISHED_423))).toEqual([[90, "pack_finished"], [91, "pack_finished"]]);
    expect(drops(batchFailed(again.session, { ...FINISHED_423, taken: "op-1" }))).toEqual([[90, "pack_finished"], [91, "pack_finished"]]);
  });
});

// What the server says it took (a refusal's `taken`, an answer's results) is about the batch out. The server names only
// operations of the batch it was sent, but a client that took any name at its word would ack an edit the server has never
// seen: never sent, never offered back, and drawn over the view until an event that need not come.
describe("what the server says it took is read for the batch out only", () => {
  const took = (id, seq) => ({ client_op_id: id, seq, status: "applied", reason: null });

  it("a 423 whose taken names an edit still queued drops it with the batch, as the pack has never seen it", () => {
    const out = nextBatch(mine(open(), setHeading(90)).session).session;
    const refused = batchFailed(mine(out, setHeading(91)).session, { ...FINISHED_423, taken: [took("op-2", 9)] });
    expect(refused.pending).toEqual([]);
    expect(drops(refused)).toEqual([[90, "pack_finished"], [91, "pack_finished"]]);
  });

  it("a 413 whose taken names an edit still queued and one an earlier answer acked leaves both as they were, and the "
    + "queued one goes next", () => {
    const first = nextBatch(mine(open(), setHeading(90)).session).session;
    const answered = batchAnswered(first, { head_seq: 4, results: [took("op-1", 4)], events: [], has_more: false }).session;
    const out = nextBatch(mine(answered, setHeading(91)).session).session;
    const refused = batchFailed(mine(out, setHeading(92)).session,
      { status: 413, code: "item_too_large", taken: [took("op-1", 7), took("op-3", 8)] });
    expect(refused.pending.map((e) => [e.op.client_op_id, e.state, e.seq])).toEqual([["op-1", "acked", 4], ["op-3", "queued", undefined]]);
    expect(drops(refused)).toEqual([[91, "item_too_large"]]);
    expect(nextBatch(refused).batch.ops.map((op) => op.client_op_id)).toEqual(["op-3"]);
  });

  it("an answer whose results name an edit still queued leaves it queued, and it goes next", () => {
    const out = nextBatch(mine(open(), setHeading(90)).session).session;
    const { session } = batchAnswered(mine(out, setHeading(91)).session, {
      head_seq: 4, has_more: false, results: [took("op-1", 4), took("op-2", 5)],
      events: [ev(4, setHeading(90), { actor: SAM, clientOpId: "op-1" })],
    });
    expect(states(session)).toEqual([[91, "queued"]]);
    expect(nextBatch(session).batch.ops.map((op) => op.client_op_id)).toEqual(["op-2"]);
  });
});

describe("an answer read before its page", () => {
  it("keeps what its results say was taken when the page ends where the pack is finished, and drops what was queued behind it", () => {
    let { session } = mine(open(), setHeading(90));
    session = mine(nextBatch(session).session, setHeading(91)).session;
    const { session: after, catchUp } = batchAnswered(session, {
      head_seq: 7, has_more: true, results: [{ client_op_id: "op-1", seq: 7, status: "applied", reason: null }],
      events: [ev(4, setCallSign("HAWK 7")), ev(5, { type: "pack.finish" })],
    });
    expect(catchUp).toBe(true);
    expect(after.pending.map((e) => [e.op.value, e.state, e.seq])).toEqual([[90, "acked", 7]]);
    expect(after.dropped.map((d) => [d.op.value, d.reason])).toEqual([[91, "pack_finished"]]);
  });

  it("keeps what its results say was taken when the page ends at this person's removal", () => {
    let { session } = mine(open(), setHeading(90));
    session = mine(nextBatch(session).session, setHeading(91)).session;
    const { session: after } = batchAnswered(session, {
      head_seq: 6, has_more: true, results: [{ client_op_id: "op-1", seq: 6, status: "applied", reason: null }],
      events: [ev(4, { type: "member.remove", user_id: SAM.id, name: "Sam" })],
    });
    expect(after.gone).toBe("removed");
    expect(after.pending.map((e) => [e.op.value, e.state])).toEqual([[90, "acked"]]);
    expect(after.dropped.map((d) => [d.op.value, d.reason])).toEqual([[91, "gone"]]);
  });
});

describe("loading the pack again", () => {
  it("into a finished pack drops what was not taken as pack_finished, and into a viewer's as read_only", () => {
    const { session } = mine(open(), setHeading(90));
    const finished = reloadSession(session, { ...PACK, head_seq: 4, status: "finished", finished_by: COLIN, finished_at: T });
    expect(finished.dropped.map((d) => d.reason)).toEqual(["pack_finished"]);
    const viewer = reloadSession(session, { ...PACK, head_seq: 4, role: "viewer" });
    expect(viewer.dropped.map((d) => d.reason)).toEqual(["read_only"]);
  });
});

describe("an update from the original", () => {
  const COPIED = { kind: "lz", uuid: "lib-1", revision: 1 };
  const LAST = { actor: COLIN, summary: 'Colin edited "LZ HAWK".', created_at: T };
  const copier = { ...COPIED, original: "changed", original_updated_at: "2026-10-05T11:00:00", pack_changes: 2, last_pack_change: LAST };
  const anyoneElse = { ...COPIED, original: null, original_updated_at: null, pack_changes: null, last_pack_change: null };
  const packWith = (source) => ({ ...PACK, items: [{ ...PACK.items[0], created_by: SAM, source }] });
  const replace = (seq, actor = SAM) =>
    ev(seq, { type: "item.replace", item: "lz-1", data: { flightData: { landingHeading: 90 } }, source_revision: 3 }, { actor });

  it("by me leaves the source as the server would give it then: the same, nothing an update would replace, "
    + "and when the original last changed not known here (the event does not say)", () => {
    const session = receive(openSession(packWith(copier), SAM.id), [replace(4)]).session;
    expect(session.info["lz-1"].source).toEqual({
      ...COPIED, revision: 3, original: "same", original_updated_at: null, pack_changes: null, last_pack_change: null,
    });
  });

  it("by someone else tells me nothing about their original, as the server never does", () => {
    const session = receive(openSession(packWith(anyoneElse), COLIN.id), [replace(4)]).session;
    expect(session.info["lz-1"].source).toEqual({ ...anyoneElse, revision: 3 });
  });

  it("of an item copied in while the pack is open gives its source the server's shape, the same to me as its copier", () => {
    const copy = ev(4, { type: "item.create", item: "lz-5", kind: "lz", name: "LZ CROW", data: {}, source: COPIED }, { actor: SAM });
    const later = ev(5, { type: "item.replace", item: "lz-5", data: { name: "LZ CROW" }, source_revision: 2 }, { actor: SAM });
    const full = { ...COPIED, revision: 2, original_updated_at: null, pack_changes: null, last_pack_change: null };
    expect(receive(open(), [copy, later]).session.info["lz-5"].source).toEqual({ ...full, original: "same" });
    const theirCopy = { ...copy, actor: COLIN };
    const theirUpdate = { ...later, actor: COLIN };
    expect(receive(open(), [theirCopy, theirUpdate]).session.info["lz-5"].source).toEqual({ ...full, original: null });
  });
});
