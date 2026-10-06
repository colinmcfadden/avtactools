import { createPackClient } from "./packClient";

const COLIN = { id: 1, name: "Colin" };
const SAM = { id: 2, name: "Sam" };
const T = "2026-10-05T12:00:00";
const LIVE = "ws://live.test/live";

const pack = (overrides = {}) => ({
  uuid: "p-1", name: "OP DK", description: "", status: "active", role: "editor", owner: COLIN, team: null,
  head_seq: 3, member_count: 2, item_count: 1, finished_at: null, finished_by: null, created_at: T, updated_at: T,
  live_url: LIVE,
  members: [{ user_id: 1, name: "Colin", email: "", role: "owner", added_at: T }, { user_id: 2, name: "Sam", email: "", role: "editor", added_at: T }],
  items: [{ uuid: "lz-1", kind: "lz", name: "LZ HAWK", revision: 1, seq: 3, created_by: COLIN, updated_by: COLIN,
    created_at: T, updated_at: T, source: null, data: { flightData: { landingHeading: 270 } } }],
  ...overrides,
});

const setHeading = (value) => ({ type: "set", item: "lz-1", path: ["flightData", "landingHeading"], value });
const ev = (seq, op, extra = {}) => ({ seq, type: op.type, item: op.item ?? null, actor: COLIN, summary: "", status: "applied",
  reason: null, client_op_id: null, op, created_at: T, ...extra });

/** A clock the test moves by hand. */
class Clock {
  constructor() { this.now = 0; this.next = 1; this.pending = new Map(); }
  setTimeout = (fn, ms) => { const id = this.next++; this.pending.set(id, { at: this.now + ms, fn }); return id; };
  clearTimeout = (id) => { this.pending.delete(id); };
  advance(ms) {
    const until = this.now + ms;
    for (;;) {
      const due = [...this.pending.entries()].filter(([, t]) => t.at <= until).sort((a, b) => a[1].at - b[1].at)[0];
      if (!due) break;
      this.pending.delete(due[0]);
      this.now = due[1].at;
      due[1].fn();
    }
    this.now = until;
  }
}

/** A socket the test plays the server's side of. */
class FakeSocket {
  static all = [];
  constructor(url) { this.url = url; this.sent = []; this.closedWith = null; FakeSocket.all.push(this); }
  send(text) { this.sent.push(JSON.parse(text)); }
  close(code) { this.closedWith = code; }
  open() { this.onopen(); }
  say(message) { this.onmessage({ data: JSON.stringify({ pack: "p-1", ...message }) }); }
  drop(code) { this.onclose({ code }); }
}

const settle = async () => { for (let i = 0; i < 25; i += 1) await Promise.resolve(); };

let clock;
let api;
let client;
let hidden;
let ids;

const fail = (status, data = {}) => Object.assign(new Error(`HTTP ${status}`), status ? { response: { status, data } } : {});

const start = async (body = pack()) => {
  api.getPack.mockResolvedValue(body);
  client = createPackClient({
    packUuid: "p-1", me: SAM.id, api, openSocket: (url) => new FakeSocket(url), getToken: () => "sam-token",
    timers: clock, newId: () => `op-${++ids}`, isHidden: () => hidden,
  });
  await client.start();
  await settle();
  return FakeSocket.all[FakeSocket.all.length - 1];
};

const welcome = async (socket, head = 3) => {
  socket.open();
  socket.say({ type: "welcome", head_seq: head, role: "editor", status: "active", you: { session: "s-2", user_id: 2, name: "Sam" } });
  await settle();
};

const heading = () => client.getState().items.find((i) => i.uuid === "lz-1").data.flightData.landingHeading;

beforeEach(() => {
  FakeSocket.all = [];
  clock = new Clock();
  hidden = false;
  ids = 0;
  api = { getPack: jest.fn(), getEvents: jest.fn(), sendOps: jest.fn() };
});

afterEach(() => client?.stop());

describe("opening a pack", () => {
  it("loads it, opens the stream and says hello with the person's token", async () => {
    const socket = await start();
    expect(api.getPack).toHaveBeenCalledWith("p-1");
    expect(socket.url).toBe(LIVE);
    expect(client.getState().status).toBe("polling"); // until the stream says welcome
    socket.open();
    expect(socket.sent).toEqual([{ type: "hello", pack: "p-1", token: "sam-token" }]);
    await welcome(socket);
    expect(client.getState().status).toBe("live");
    expect(client.getState().items.map((i) => i.name)).toEqual(["LZ HAWK"]);
    clock.advance(10000);
    expect(api.getEvents).not.toHaveBeenCalled(); // no polling while live
  });

  it("catches up when the pack moved on between loading and the welcome", async () => {
    const socket = await start();
    api.getEvents.mockResolvedValueOnce({ events: [ev(4, setHeading(90)), ev(5, setHeading(95))], has_more: false, cursor: 5, head_seq: 5 });
    await welcome(socket, 5);
    expect(api.getEvents).toHaveBeenCalledWith("p-1", 3);
    expect(heading()).toBe(95);
  });

  it("polls every few seconds where there is no live stream, and not while the page is hidden", async () => {
    await start(pack({ live_url: null }));
    expect(FakeSocket.all).toHaveLength(0);
    api.getEvents.mockResolvedValue({ events: [], has_more: false, cursor: 3, head_seq: 3 });
    clock.advance(3000);
    await settle();
    expect(api.getEvents).toHaveBeenCalledTimes(1);
    hidden = true;
    clock.advance(6000);
    await settle();
    expect(api.getEvents).toHaveBeenCalledTimes(1);
  });

  it("says when the pack is not there to open", async () => {
    api.getPack.mockRejectedValue(fail(404, { code: "pack_not_found" }));
    client = createPackClient({ packUuid: "p-1", me: 2, api, openSocket: () => null, getToken: () => "", timers: clock });
    await client.start();
    expect(client.getState().status).toBe("gone");
  });
});

describe("the live stream", () => {
  it("applies the next event as it comes, without asking the server", async () => {
    const socket = await start();
    await welcome(socket);
    socket.say({ type: "event", seq: 4, event: ev(4, setHeading(90)) });
    await settle();
    expect(heading()).toBe(90);
    expect(api.getEvents).not.toHaveBeenCalled();
  });

  it("fetches what it missed when an event skips ahead, or only its number came", async () => {
    const socket = await start();
    await welcome(socket);
    api.getEvents.mockResolvedValue({ events: [ev(4, setHeading(90)), ev(5, setHeading(95))], has_more: false, cursor: 5, head_seq: 5 });
    socket.say({ type: "event", seq: 5, event: ev(5, setHeading(95)) });
    await settle();
    expect(api.getEvents).toHaveBeenCalledWith("p-1", 3);
    expect(heading()).toBe(95);
    api.getEvents.mockResolvedValue({ events: [ev(6, setHeading(100))], has_more: false, cursor: 6, head_seq: 6 });
    socket.say({ type: "head", seq: 6 });
    await settle();
    expect(heading()).toBe(100);
    socket.say({ type: "resync" });
    await settle();
    expect(api.getEvents).toHaveBeenLastCalledWith("p-1", 6);
  });

  it("falls back to polling when the stream drops, and opens it again", async () => {
    const socket = await start();
    await welcome(socket);
    socket.drop(1006);
    expect(client.getState().status).toBe("polling");
    api.getEvents.mockResolvedValue({ events: [], has_more: false, cursor: 3, head_seq: 3 });
    clock.advance(1000);
    expect(FakeSocket.all).toHaveLength(2); // reconnecting
    clock.advance(2000);
    await settle();
    expect(api.getEvents).toHaveBeenCalled();
    await welcome(FakeSocket.all[1]);
    expect(client.getState().status).toBe("live");
  });

  it("stops for good when the pack is deleted or no longer theirs to see", async () => {
    const socket = await start();
    await welcome(socket);
    socket.drop(4410);
    expect(client.getState().status).toBe("gone");
    clock.advance(60000);
    expect(FakeSocket.all).toHaveLength(1);
  });

  it("shows who else is there, and tells them what this person has open, at most every 100 ms", async () => {
    const socket = await start();
    await welcome(socket);
    socket.say({ type: "presence", people: [{ session: "s-1", user_id: 1, name: "Colin", focus: { item: "lz-1" } }] });
    expect(client.getState().people[0].name).toBe("Colin");
    client.setFocus({ item: "lz-1" });
    client.setFocus({ item: "lz-1", graphic: "h-1" });
    client.setFocus({ item: "lz-1", graphic: "h-2" });
    const presence = () => socket.sent.filter((m) => m.type === "presence").map((m) => m.focus);
    expect(presence()).toEqual([{ item: "lz-1" }]);
    clock.advance(100);
    expect(presence()).toEqual([{ item: "lz-1" }, { item: "lz-1", graphic: "h-2" }]);
  });
});

describe("edits made here", () => {
  it("show at once, are sent with base_seq, and the answer confirms them", async () => {
    const socket = await start();
    await welcome(socket);
    let answer;
    api.sendOps.mockReturnValue(new Promise((resolve) => { answer = resolve; }));
    expect(client.edit(setHeading(90))).toBeNull();
    expect(heading()).toBe(90);
    expect(api.sendOps).toHaveBeenCalledWith("p-1", { base_seq: 3, ops: [{ ...setHeading(90), client_op_id: "op-1" }] });
    client.edit(setHeading(95)); // waits for the batch in flight
    expect(api.sendOps).toHaveBeenCalledTimes(1);
    api.sendOps.mockResolvedValue({ head_seq: 5, has_more: false, results: [], events: [] });
    answer({ head_seq: 4, has_more: false, results: [{ client_op_id: "op-1", seq: 4, status: "applied", reason: null }],
      events: [ev(4, setHeading(90), { actor: SAM, client_op_id: "op-1" })] });
    await settle();
    expect(api.sendOps).toHaveBeenCalledTimes(2);
    expect(api.sendOps).toHaveBeenLastCalledWith("p-1", { base_seq: 4, ops: [{ ...setHeading(95), client_op_id: "op-2" }] });
    expect(heading()).toBe(95);
  });

  it("are sent again, unchanged, when no answer came", async () => {
    const socket = await start();
    await welcome(socket);
    api.sendOps.mockRejectedValueOnce(fail(0));
    client.edit(setHeading(90));
    await settle();
    expect(heading()).toBe(90); // still shown
    api.sendOps.mockResolvedValue({ head_seq: 4, has_more: false, results: [], events: [ev(4, setHeading(90), { client_op_id: "op-1" })] });
    clock.advance(1000);
    await settle();
    expect(api.sendOps).toHaveBeenCalledTimes(2);
    expect(api.sendOps.mock.calls[1][1]).toEqual(api.sendOps.mock.calls[0][1]);
    expect(client.getState().session.pending).toEqual([]);
  });

  it("refused because the pack was finished are dropped and further edits refused", async () => {
    const socket = await start();
    await welcome(socket);
    api.sendOps.mockRejectedValue(fail(423, { code: "pack_finished", finished_by: COLIN, finished_at: T }));
    client.edit(setHeading(90));
    await settle();
    const { session } = client.getState();
    expect(session.readOnly).toBe(true);
    expect(session.dropped.map((d) => d.reason)).toEqual(["pack_finished"]);
    expect(heading()).toBe(270);
    expect(client.edit(setHeading(95))).toBe("read_only");
  });

  it("whose events did not all come with the answer are fetched", async () => {
    const socket = await start();
    await welcome(socket);
    api.sendOps.mockResolvedValue({ head_seq: 600, has_more: true,
      results: [{ client_op_id: "op-1", seq: 600, status: "applied", reason: null }], events: [] });
    api.getEvents.mockResolvedValue({ events: [ev(4, setHeading(90), { client_op_id: "op-1" })], has_more: false, cursor: 4, head_seq: 4 });
    client.edit(setHeading(90));
    await settle();
    expect(api.getEvents).toHaveBeenCalledWith("p-1", 3);
    expect(client.getState().session.pending).toEqual([]);
  });
});

describe("stopping", () => {
  it("closes the stream and asks nothing more", async () => {
    const socket = await start(pack());
    await welcome(socket);
    client.stop();
    expect(socket.closedWith).toBe(1000);
    socket.drop(1000);
    clock.advance(60000);
    await settle();
    expect(FakeSocket.all).toHaveLength(1);
    expect(api.getEvents).not.toHaveBeenCalled();
  });
});
