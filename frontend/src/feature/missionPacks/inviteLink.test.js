import { act, renderHook, waitFor } from "@testing-library/react";
import { captureInviteLink, clearInviteLink, INVITE_STORAGE_KEY, pendingInviteLink } from "./inviteLink";
import { joinedMessage, useInviteLink } from "./useInviteLink";

const TOKEN = "Xq3v_8yQm2LZk9-WbT4sPa7Rr1Nd5Cf6Hg0Jj2Kk3Ll";

const at = (search) => window.history.replaceState({ keep: 1 }, "", `/${search}`);

afterEach(() => {
  clearInviteLink();
  sessionStorage.clear();
  at("");
});

describe("captureInviteLink", () => {
  it("takes the token out of the address at once and keeps it for this tab", () => {
    at(`?invite=${TOKEN}&view=sat#map`);
    expect(captureInviteLink()).toBe(TOKEN);
    expect(window.location.search).toBe("?view=sat");
    expect(window.location.hash).toBe("#map");
    expect(window.history.state).toEqual({ keep: 1 });
    expect(sessionStorage.getItem(INVITE_STORAGE_KEY)).toBe(TOKEN);
    expect(pendingInviteLink()).toBe(TOKEN);
  });

  it("drops something that is not a token, and leaves an address without one alone", () => {
    at("?invite=<script>");
    expect(captureInviteLink()).toBeNull();
    expect(window.location.search).toBe("");
    expect(pendingInviteLink()).toBeNull();
    at("?auth=verify&token=abc");
    expect(captureInviteLink()).toBeNull();
    expect(window.location.search).toBe("?auth=verify&token=abc");
  });

  it("still holds the link for this load when the tab's storage is blocked", () => {
    const blocked = {
      location: new URL(`https://ezpztac.app/?invite=${TOKEN}`),
      history: { state: null, replaceState: jest.fn() },
      get sessionStorage() {
        throw new Error("SecurityError");
      },
    };
    expect(captureInviteLink(blocked)).toBe(TOKEN);
    expect(blocked.history.replaceState).toHaveBeenCalledWith(null, "", "/");
    expect(pendingInviteLink(blocked)).toBe(TOKEN);
  });
});

describe("useInviteLink", () => {
  const withLink = () => {
    at(`?invite=${TOKEN}`);
    captureInviteLink();
  };

  it("accepts once the person can open packs, says what they joined and forgets the link", async () => {
    withLink();
    const answer = { pack: { uuid: "p-1", name: "OP DK", role: "editor" }, team: null };
    const accept = jest.fn().mockResolvedValue(answer);
    const onJoined = jest.fn();
    const { result } = renderHook(() => useInviteLink({ enabled: true, accept, onJoined }));
    await waitFor(() => expect(result.current.status).toBe("joined"));
    expect(accept).toHaveBeenCalledWith(TOKEN);
    expect(result.current.message).toBe("You joined OP DK as an editor.");
    expect(onJoined).toHaveBeenCalledWith(answer);
    expect(pendingInviteLink()).toBeNull();
  });

  it("asks once even when React runs the effect twice", async () => {
    withLink();
    let resolve;
    const accept = jest.fn(() => new Promise((r) => { resolve = r; }));
    const { result: first } = renderHook(() => useInviteLink({ enabled: true, accept }));
    const { result: second } = renderHook(() => useInviteLink({ enabled: true, accept }));
    expect(accept).toHaveBeenCalledTimes(1);
    await act(async () => resolve({ team: { id: 2, name: "B Co", role: "member" }, pack: null }));
    expect(first.current.status).toBe("joined");
    expect(second.current.message).toBe("You joined the team B Co.");
  });

  it("keeps the link while packs are not on for the account", () => {
    withLink();
    const accept = jest.fn();
    const { result } = renderHook(() => useInviteLink({ enabled: false, accept }));
    expect(result.current.status).toBe("unavailable");
    expect(accept).not.toHaveBeenCalled();
    expect(pendingInviteLink()).toBe(TOKEN);
  });

  it("forgets a link the server refused, in the app's words", async () => {
    withLink();
    const expired = Object.assign(new Error("Gone"), { response: { status: 410, data: { code: "invite_expired", error: "x" } } });
    const { result } = renderHook(() => useInviteLink({ enabled: true, accept: jest.fn().mockRejectedValue(expired) }));
    await waitFor(() => expect(result.current.status).toBe("failed"));
    expect(result.current.message).toBe("That invitation has expired. Ask whoever sent it for a new one.");
    expect(result.current.retryable).toBe(false);
    expect(pendingInviteLink()).toBeNull();
  });

  it("keeps a link it could not send, and tries again when asked", async () => {
    withLink();
    const accept = jest.fn()
      .mockRejectedValueOnce(Object.assign(new Error("Network Error"), { status: undefined }))
      .mockResolvedValueOnce({ pack: { name: "OP DK", role: "viewer" } });
    const { result } = renderHook(() => useInviteLink({ enabled: true, accept }));
    await waitFor(() => expect(result.current.status).toBe("failed"));
    expect(result.current.retryable).toBe(true);
    expect(pendingInviteLink()).toBe(TOKEN);
    act(() => result.current.retry());
    await waitFor(() => expect(result.current.status).toBe("joined"));
    expect(result.current.message).toBe("You joined OP DK as a viewer.");
  });

  it("does nothing without a link", () => {
    const accept = jest.fn();
    const { result } = renderHook(() => useInviteLink({ enabled: true, accept }));
    expect(result.current.status).toBe("none");
    expect(accept).not.toHaveBeenCalled();
  });

  it("words what was joined", () => {
    expect(joinedMessage({ pack: { name: "OP DK", role: "owner" } })).toBe("You joined OP DK as the owner.");
    expect(joinedMessage({})).toBe("You accepted the invitation.");
  });
});
