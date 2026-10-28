import axios from "axios";

jest.mock("axios");

const encode = (value) => btoa(JSON.stringify(value)).replace(/=+$/, "").replace(/\+/g, "-").replace(/\//g, "_");
const jwt = ({ sid = "fam-1", sub = "7", lifetime = 900, nonce = Math.random() } = {}) => {
  const now = Math.floor(Date.now() / 1000);
  return `${encode({ alg: "HS256" })}.${encode({ sub, sid, iat: now, exp: now + lifetime, nonce })}.sig`;
};
const refused = (status, code) => Object.assign(new Error(`HTTP ${status}`), { response: { status, data: { code } } });
const answered = (token, user = { id: 7 }) => ({ data: { access_token: token, user, expires_in: 900, session: "cookie" } });

const settle = async () => { for (let i = 0; i < 20; i += 1) await Promise.resolve(); };

let session;

beforeEach(() => {
  jest.useFakeTimers();
  localStorage.clear();
  axios.post.mockReset();
  delete navigator.locks;
  jest.isolateModules(() => {
    session = require("./session");
  });
});

afterEach(() => {
  session.forgetToken();
  jest.useRealTimers();
});

describe("after a sign-in", () => {
  test("a browser that sends the cookie back keeps a cookie session, with the token in memory only", async () => {
    const fresh = jwt();
    axios.post.mockResolvedValueOnce(answered(fresh));
    await expect(session.startSession({ access_token: jwt({ lifetime: 86400 }), session: "cookie" })).resolves.toBe("cookie");
    expect(axios.post).toHaveBeenCalledWith(expect.stringMatching(/\/auth\/refresh$/), null, {
      headers: { "X-EZPZ-Session": "cookie" },
      withCredentials: true,
    });
    expect(localStorage.getItem("ezpz.session")).toBe("cookie");
    expect(localStorage.getItem("auth_token")).toBeNull();
    expect(session.getAccessToken()).toBe(fresh);
  });

  test("a browser that will not send it keeps the sign-in's token as the web always has", async () => {
    const signIn = jwt({ lifetime: 86400 });
    axios.post.mockRejectedValueOnce(refused(401, "refresh_cookie_missing"));
    await expect(session.startSession({ access_token: signIn, session: "cookie" })).resolves.toBe("stored");
    expect(localStorage.getItem("auth_token")).toBe(signIn);
    expect(localStorage.getItem("ezpz.session")).toBeNull();
    expect(session.getAccessToken()).toBe(signIn);
  });

  test("so does one that could not be asked, rather than lose the sign-in", async () => {
    const signIn = jwt({ lifetime: 86400 });
    axios.post.mockRejectedValueOnce(new Error("Network Error"));
    await expect(session.startSession({ access_token: signIn, session: "cookie" })).resolves.toBe("stored");
    expect(localStorage.getItem("auth_token")).toBe(signIn);
  });

  test("a server that offers no cookie session is not asked", async () => {
    const signIn = jwt({ lifetime: 86400 });
    await expect(session.startSession({ access_token: signIn })).resolves.toBe("stored");
    expect(axios.post).not.toHaveBeenCalled();
    expect(localStorage.getItem("auth_token")).toBe(signIn);
  });
});

describe("refreshing", () => {
  beforeEach(() => localStorage.setItem("ezpz.session", "cookie"));

  test("everyone who asks at once shares one refresh, so the cookie is spent once", async () => {
    const fresh = jwt();
    axios.post.mockResolvedValueOnce(answered(fresh));
    const all = await Promise.all([session.refreshAccessToken(), session.refreshAccessToken(), session.freshAccessToken()]);
    expect(all).toEqual([fresh, fresh, fresh]);
    expect(axios.post).toHaveBeenCalledTimes(1);
  });

  test("tabs take turns: a refresh waits for the browser-wide lock", async () => {
    const request = jest.fn((name, work) => work());
    navigator.locks = { request };
    axios.post.mockResolvedValueOnce(answered(jwt()));
    await session.refreshAccessToken();
    expect(request).toHaveBeenCalledWith("ezpz-refresh", expect.any(Function));
  });

  test("an ended session answers null; a server that could not be asked rejects and ends nothing", async () => {
    axios.post.mockRejectedValueOnce(refused(401, "refresh_reuse_detected"));
    await expect(session.refreshAccessToken()).resolves.toBeNull();
    axios.post.mockRejectedValueOnce(refused(503));
    await expect(session.refreshAccessToken()).rejects.toThrow("HTTP 503");
    expect(session.hasCookieSession()).toBe(true);
  });

  test("a token is refreshed two minutes before it lapses, without anyone asking", async () => {
    axios.post.mockResolvedValueOnce(answered(jwt()));
    await session.refreshAccessToken();
    const next = jwt();
    axios.post.mockResolvedValueOnce(answered(next));
    jest.advanceTimersByTime(12 * 60 * 1000);
    expect(axios.post).toHaveBeenCalledTimes(1);
    jest.advanceTimersByTime(60 * 1000 + 1);
    expect(axios.post).toHaveBeenCalledTimes(2);
    await settle();
    expect(session.getAccessToken()).toBe(next);
  });

  test("a scheduled refresh that finds the session ended says so", async () => {
    axios.post.mockResolvedValueOnce(answered(jwt()));
    await session.refreshAccessToken();
    const expired = jest.fn();
    window.addEventListener("auth-expired", expired);
    axios.post.mockRejectedValueOnce(refused(401, "invalid_refresh_token"));
    jest.advanceTimersByTime(14 * 60 * 1000);
    await settle();
    expect(expired).toHaveBeenCalledTimes(1);
    expect(localStorage.getItem("ezpz.session")).toBeNull();
    expect(session.getAccessToken()).toBeNull();
    window.removeEventListener("auth-expired", expired);
  });

  test("a token about to lapse is refreshed before it is sent", async () => {
    axios.post.mockResolvedValueOnce(answered(jwt({ lifetime: 20 })));
    await session.refreshAccessToken();
    const next = jwt();
    axios.post.mockResolvedValueOnce(answered(next));
    await expect(session.freshAccessToken()).resolves.toBe(next);
  });

  test("the session's identity holds across refreshes and listeners hear each token", async () => {
    const heard = [];
    session.onAccessToken((token) => heard.push(token));
    const first = jwt();
    const second = jwt();
    axios.post.mockResolvedValueOnce(answered(first)).mockResolvedValueOnce(answered(second));
    await session.refreshAccessToken();
    const key = session.sessionKey();
    await session.refreshAccessToken();
    expect(session.sessionKey()).toBe(key);
    expect(key).toBe("7:fam-1");
    session.forgetToken();
    expect(heard).toEqual([first, second, null]);
  });
});

describe("signing out", () => {
  test("asks the server to end the session and clear its cookie, and forgets it here at once", async () => {
    localStorage.setItem("ezpz.session", "cookie");
    const token = jwt();
    axios.post.mockResolvedValueOnce(answered(token));
    await session.refreshAccessToken();
    axios.post.mockResolvedValueOnce({ data: { status: "success" } });
    const done = session.signOut();
    expect(localStorage.getItem("ezpz.session")).toBeNull();
    expect(session.getAccessToken()).toBeNull();
    await done;
    expect(axios.post).toHaveBeenLastCalledWith(expect.stringMatching(/\/auth\/logout$/), null, {
      headers: { "X-EZPZ-Session": "cookie", Authorization: `Bearer ${token}` },
      withCredentials: true,
    });
  });

  test("a stored token's session is ended on the server too", async () => {
    const token = jwt({ lifetime: 86400 });
    localStorage.setItem("auth_token", token);
    axios.post.mockResolvedValueOnce({ data: { status: "success" } });
    await session.signOut();
    expect(localStorage.getItem("auth_token")).toBeNull();
    expect(axios.post.mock.calls[0][2].headers.Authorization).toBe(`Bearer ${token}`);
  });

  test("with nothing signed in, nothing is sent", async () => {
    await session.signOut();
    expect(axios.post).not.toHaveBeenCalled();
  });
});
