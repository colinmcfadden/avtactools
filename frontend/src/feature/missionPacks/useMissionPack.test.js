import { act, renderHook, waitFor } from "@testing-library/react";
import { useMissionPack } from "./useMissionPack";

const T = "2026-10-05T12:00:00";
const PACK = {
  uuid: "p-1", name: "OP DK", description: "", status: "active", role: "editor", owner: { id: 1, name: "Colin" },
  team: null, head_seq: 3, member_count: 1, item_count: 1, finished_at: null, finished_by: null, created_at: T,
  updated_at: T, live_url: "ws://live.test/live", members: [],
  items: [{ uuid: "lz-1", kind: "lz", name: "LZ HAWK", revision: 1, seq: 3, created_by: null, updated_by: null,
    created_at: T, updated_at: T, source: null, data: { flightData: { landingHeading: 270 } } }],
};

const setup = () => {
  const sockets = [];
  const api = {
    getPack: jest.fn().mockResolvedValue(PACK),
    getEvents: jest.fn().mockResolvedValue({ events: [], has_more: false, cursor: 3, head_seq: 3 }),
    sendOps: jest.fn(() => new Promise(() => {})),
  };
  const openSocket = (url) => {
    const socket = { url, sent: [], close: jest.fn(), send(text) { this.sent.push(JSON.parse(text)); } };
    sockets.push(socket);
    return socket;
  };
  return { api, openSocket, sockets };
};

describe("useMissionPack", () => {
  beforeEach(() => localStorage.setItem("auth_token", "the-token"));
  afterEach(() => localStorage.clear());

  it("opens the pack, shows edits at once, and closes the stream when it is closed", async () => {
    const { api, openSocket, sockets } = setup();
    const { result, unmount } = renderHook(() => useMissionPack("p-1", 2, { api, openSocket }));
    await waitFor(() => expect(result.current.items).toHaveLength(1));
    expect(sockets).toHaveLength(1);
    act(() => sockets[0].onopen());
    expect(sockets[0].sent[0]).toEqual({ type: "hello", pack: "p-1", token: "the-token" });

    act(() => {
      expect(result.current.edit({ type: "item.rename", item: "lz-1", name: "LZ EAGLE" })).toBeNull();
    });
    expect(result.current.items[0].name).toBe("LZ EAGLE");
    expect(api.sendOps).toHaveBeenCalledTimes(1);

    unmount();
    expect(sockets[0].close).toHaveBeenCalledWith(1000);
  });

  it("is closed with no pack", () => {
    const { api, openSocket } = setup();
    const { result } = renderHook(() => useMissionPack(null, 2, { api, openSocket }));
    expect(result.current.status).toBe("closed");
    expect(result.current.edit({ type: "item.delete", item: "x" })).toBe("closed");
    expect(api.getPack).not.toHaveBeenCalled();
  });
});
