import { act, renderHook, waitFor } from "@testing-library/react";
import api from "../auth/api";
import { makeThreat } from "./threatModel";
import { SAVE_PAUSE_MS, useThreats } from "./useThreats";

vi.mock("../auth/api", () => ({ default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() } }));

// A threat as a saved document holds it, with no radars so that opening it asks for no mask.
const maskFree = (id, name = "SA-11") => {
  const { mask: none, maskLoading, maskError, ...threat } = makeThreat(34.5, -84.1, { name, radars: [] });
  return { ...threat, id };
};

const USER = 7;
const KEY = `ezpz.openThreatSets.${USER}`;
const mask = { bounds: [[34, -85], [35, -84]], radars: [] };
const record = (over = {}) => ({ id: 11, client_uuid: "u-11", revision: 3, name: "NORTH", threat_count: 1, data: { version: 1, threats: [maskFree("t-1")] }, ...over });

let server;
const conflict = (status, data) => Object.assign(new Error("refused"), { response: { status, data } });

beforeEach(() => {
  localStorage.clear();
  vi.useFakeTimers({ shouldAdvanceTime: true });
  server = { list: [], full: {} };
  api.get.mockReset().mockImplementation(async (url) => {
    if (url === "/threatsets") return { data: server.list };
    const id = Number(url.split("/").pop());
    if (!server.full[id]) throw conflict(404, {});
    return { data: server.full[id] };
  });
  api.post.mockReset().mockImplementation(async (url, body) => (url === "/threat-mask" ? { data: mask } : { data: { id: 21, revision: 1, client_uuid: body.client_uuid, name: body.name } }));
  api.put.mockReset().mockImplementation(async (url, body) => ({ data: { id: Number(url.split("/").pop()), revision: 4, client_uuid: "u", name: body.name } }));
  api.delete.mockReset().mockResolvedValue({ data: {} });
});

afterEach(() => vi.useRealTimers());

const start = async (props = {}) => {
  const hook = renderHook((p) => useThreats(p), { initialProps: { userId: USER, canSave: true, ...props } });
  await act(async () => { await vi.advanceTimersByTimeAsync(0); });
  return hook;
};
const pause = () => act(async () => { await vi.advanceTimersByTimeAsync(SAVE_PAUSE_MS + 10); });
const saves = () => api.post.mock.calls.filter(([url]) => url === "/threatsets");
const add = (hook, name = "SA-11") => act(async () => { hook.result.current.saveThreat(makeThreat(34.5, -84.1, { name })); await vi.advanceTimersByTimeAsync(0); });

describe("saving threats", () => {
  it("saves the first threat into a new set once it has been still, with its mask", async () => {
    const hook = await start();
    await add(hook);
    expect(saves()).toHaveLength(0);
    await pause();
    await pause();
    const lastBody = saves().at(-1)?.[1];
    expect(hook.result.current.sets[0].name).toBe("THREATS");
    expect(saves()).toHaveLength(1);
    expect(lastBody.client_uuid).toBeTruthy();
    expect(hook.result.current.saveStates[hook.result.current.sets[0].id]).toBe("saved");
    expect(JSON.parse(localStorage.getItem(KEY))).toEqual([21]);
  });

  it("sends a changed set with the revision it was based on, and nothing when nothing changed", async () => {
    server.full[11] = record();
    localStorage.setItem(KEY, "[11]");
    const hook = await start();
    await pause();
    expect(api.put).not.toHaveBeenCalled();
    act(() => hook.result.current.renameSet(hook.result.current.sets[0].id, "south"));
    await pause();
    expect(api.put).toHaveBeenCalledTimes(1);
    const [url, body, options] = api.put.mock.calls[0];
    expect(url).toBe("/threatsets/11");
    expect(body.name).toBe("SOUTH");
    expect(options.headers["If-Match"]).toBe('"3"');
  });

  it("does not save anything for an account without cloud save", async () => {
    const hook = await start({ canSave: false });
    await add(hook);
    await pause();
    expect(saves()).toHaveLength(0);
    expect(hook.result.current.threats).toHaveLength(1);
  });

  it("brings back the sets open last time, and forgets one that went", async () => {
    server.full[11] = record();
    localStorage.setItem(KEY, "[11,12]");
    const hook = await start();
    expect(hook.result.current.sets.map((s) => s.name)).toEqual(["NORTH"]);
    expect(hook.result.current.threats[0].name).toBe("SA-11");
    expect(JSON.parse(localStorage.getItem(KEY))).toEqual([11]);
  });

  it("keeps the list of open sets when the server could not be reached", async () => {
    api.get.mockImplementation(async () => { throw new Error("Network Error"); });
    localStorage.setItem(KEY, "[11]");
    await start();
    expect(JSON.parse(localStorage.getItem(KEY))).toEqual([11]);
  });

  it("shows another account none of this one's threats", async () => {
    const hook = await start();
    await add(hook);
    hook.rerender({ userId: 8, canSave: true });
    await act(async () => { await vi.advanceTimersByTimeAsync(0); });
    expect(hook.result.current.threats).toEqual([]);
    await pause();
    expect(saves()).toHaveLength(0);
  });

  it("keeps both versions when another device changed the set", async () => {
    server.full[11] = record();
    localStorage.setItem(KEY, "[11]");
    const notify = vi.fn();
    const hook = await start({ notify });
    api.put.mockRejectedValueOnce(conflict(409, { code: "revision_conflict", server: record({ revision: 5, name: "THEIRS", data: { version: 1, threats: [maskFree("t-9", "SA-6")] } }) }));
    act(() => hook.result.current.renameSet(hook.result.current.sets[0].id, "mine"));
    await pause();
    await pause();
    const names = hook.result.current.sets.map((s) => s.name);
    expect(names[0]).toBe("THEIRS");
    expect(names[1]).toMatch(/^MINE \(from this browser, \d\d:\d\d\)$/);
    expect(hook.result.current.threats.map((t) => t.name)).toEqual(["SA-6", "SA-11"]);
    expect(notify).toHaveBeenCalledTimes(1);
    expect(saves().at(-1)[1].name).toBe(names[1]);
  });

  it("does not call it a conflict when only the masks differ", async () => {
    server.full[11] = record();
    localStorage.setItem(KEY, "[11]");
    const hook = await start();
    const t = hook.result.current.threats[0];
    const before = hook.result.current.sets.length;
    api.put.mockRejectedValueOnce(conflict(409, { server: record({ revision: 9 }) }));
    await act(async () => { await hook.result.current.fetchMask(t); });
    await pause();
    await pause();
    expect(hook.result.current.sets).toHaveLength(before);
    expect(api.put).toHaveBeenCalledTimes(2);
    expect(api.put.mock.calls[1][2].headers["If-Match"]).toBe('"9"');
  });

  it("keeps the work when the set was deleted elsewhere", async () => {
    server.full[11] = record();
    localStorage.setItem(KEY, "[11]");
    const hook = await start();
    api.put.mockRejectedValueOnce(conflict(404, {}));
    act(() => hook.result.current.renameSet(hook.result.current.sets[0].id, "kept"));
    await pause();
    await pause();
    expect(saves()).toHaveLength(1);
    expect(saves()[0][1].name).toBe("KEPT");
    expect(saves()[0][1].client_uuid).not.toBe("u-11");
    expect(hook.result.current.sets[0].savedId).toBe(21);
  });

  it("tries again after a failure, and says so", async () => {
    const hook = await start();
    await add(hook);
    api.post.mockImplementationOnce(async () => { throw new Error("Network Error"); });
    await pause();
    const id = hook.result.current.sets[0].id;
    expect(hook.result.current.saveStates[id]).toBe("failed");
    await act(async () => { await vi.advanceTimersByTimeAsync(5100); });
    expect(hook.result.current.saveStates[id]).toBe("saved");
  });

  it("drops a mask that answers for where the threat no longer is", async () => {
    const hook = await start();
    let answer;
    api.post.mockImplementationOnce(() => new Promise((resolve) => { answer = resolve; }));
    const threat = makeThreat(34.5, -84.1);
    await act(async () => { hook.result.current.saveThreat(threat); await vi.advanceTimersByTimeAsync(0); });
    act(() => hook.result.current.moveThreat(threat.id, 35, -84));
    await act(async () => { answer({ data: { bounds: [], radars: [{ type: 0, png: "old" }] } }); await vi.advanceTimersByTimeAsync(0); });
    expect(hook.result.current.threats[0].mask.radars).toEqual([]);
  });

  it("takes a deleted set off the map", async () => {
    server.full[11] = record();
    localStorage.setItem(KEY, "[11]");
    const hook = await start();
    await act(async () => { await hook.result.current.deleteSavedSet({ id: 11 }); });
    expect(hook.result.current.threats).toEqual([]);
    expect(JSON.parse(localStorage.getItem(KEY))).toEqual([]);
  });

  it("closes a set only once it is saved", async () => {
    const notify = vi.fn();
    const hook = await start({ notify });
    await add(hook);
    api.post.mockImplementationOnce(async () => { throw new Error("Network Error"); });
    await act(async () => { await hook.result.current.closeSet(hook.result.current.sets[0].id); });
    expect(hook.result.current.sets).toHaveLength(1);
    expect(notify).toHaveBeenCalledTimes(1);
  });

  it("follows a change made elsewhere when the tab comes back", async () => {
    server.full[11] = record();
    localStorage.setItem(KEY, "[11]");
    const hook = await start();
    server.list = [record({ revision: 6 })];
    server.full[11] = record({ revision: 6, name: "RENAMED" });
    await act(async () => { await hook.result.current.pull(); });
    expect(hook.result.current.sets[0].name).toBe("RENAMED");
    server.list = [];
    await act(async () => { await hook.result.current.pull(); });
    expect(hook.result.current.sets).toEqual([]);
  });
});
