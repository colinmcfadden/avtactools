import api, { isPublicAuthRequest } from "./api";
import { cookieSessionsOffered, getAccessToken, refreshAccessToken } from "./session";

vi.mock("./session", async (importOriginal) => ({
  ...(await importOriginal()),
  refreshAccessToken: vi.fn(),
  getAccessToken: vi.fn(() => null),
  // CRA resets these before each test (resetMocks): left so, they answer undefined, which sends no token
  // and asks for no cookie session, and nothing reaches a real server.
  cookieSessionsOffered: vi.fn(),
  freshAccessToken: vi.fn(),
}));

const unauthorizedAdapter = (config) =>
  Promise.reject({ config, response: { status: 401 } });

describe("auth API unauthorized handling", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.runOnlyPendingTimers();
    vi.useRealTimers();
  });

  test("recognizes public authentication requests", () => {
    expect(isPublicAuthRequest("/auth/login")).toBe(true);
    expect(isPublicAuthRequest("https://api.example.com/api/auth/google")).toBe(true);
    expect(isPublicAuthRequest("/terrain-analysis")).toBe(false);
  });

  test("keeps public auth failures inline but expires protected state without a stored token", async () => {
    const onExpired = vi.fn();
    window.addEventListener("auth-expired", onExpired);

    await expect(
      api.post("/auth/login", {}, { adapter: unauthorizedAdapter }),
    ).rejects.toBeTruthy();
    expect(onExpired).not.toHaveBeenCalled();

    // Simulates a second tab having removed localStorage before this protected
    // request's 401 response reaches the interceptor.
    expect(localStorage.getItem("auth_token")).toBeNull();
    await expect(
      api.get("/terrain-analysis", { adapter: unauthorizedAdapter }),
    ).rejects.toBeTruthy();
    expect(onExpired).toHaveBeenCalledTimes(1);

    window.removeEventListener("auth-expired", onExpired);
  });
});

// Refused, as if sent with `token` (what the request interceptor records when it adds one).
const sentWith = (token) => (config) => Promise.reject({ config: { ...config, sentToken: token }, response: { status: 401 } });

describe("a cookie session's lapsed token", () => {
  beforeEach(() => {
    localStorage.clear();
    localStorage.setItem("ezpz.session", "cookie");
    vi.clearAllMocks();
    getAccessToken.mockReturnValue("old");
  });

  test("is refreshed once and the request sent again with the new one", async () => {
    refreshAccessToken.mockResolvedValue("new");
    getAccessToken.mockReturnValueOnce("old").mockReturnValue("new");
    const sent = [];
    const adapter = (config) => {
      sent.push(config.headers.Authorization);
      return sent.length === 1
        ? Promise.reject({ config, response: { status: 401 } })
        : Promise.resolve({ data: "ok", status: 200, statusText: "OK", headers: {}, config });
    };
    const response = await api.get("/lz", { adapter: (config) => adapter({ ...config, sentToken: config.sentToken ?? "old" }) });
    expect(response.data).toBe("ok");
    expect(sent[1]).toBe("Bearer new");
    expect(refreshAccessToken).toHaveBeenCalledTimes(1);
  });

  test("a session the refresh finds ended signs the page out", async () => {
    refreshAccessToken.mockResolvedValue(null);
    const onExpired = vi.fn();
    window.addEventListener("auth-expired", onExpired);
    await expect(api.get("/lz", { adapter: sentWith("old") })).rejects.toBeTruthy();
    expect(onExpired).toHaveBeenCalledTimes(1);
    expect(localStorage.getItem("ezpz.session")).toBeNull();
    window.removeEventListener("auth-expired", onExpired);
  });

  test("a refresh that cannot reach the server ends nothing", async () => {
    refreshAccessToken.mockRejectedValue(new Error("Network Error"));
    const onExpired = vi.fn();
    window.addEventListener("auth-expired", onExpired);
    await expect(api.get("/lz", { adapter: sentWith("old") })).rejects.toBeTruthy();
    expect(refreshAccessToken).toHaveBeenCalledTimes(1);
    expect(onExpired).not.toHaveBeenCalled();
    expect(localStorage.getItem("ezpz.session")).toBe("cookie");
    window.removeEventListener("auth-expired", onExpired);
  });
});

describe("credentials", () => {
  beforeEach(() => {
    localStorage.clear();
    cookieSessionsOffered.mockResolvedValue(true);
  });

  const capture = (config) => Promise.resolve({ data: config, status: 200, statusText: "OK", headers: {}, config });

  test("only the /auth routes, where the cookie lives, are sent with credentials and ask for a cookie session", async () => {
    const signIn = (await api.post("/auth/login", {}, { adapter: capture })).data;
    expect(signIn.withCredentials).toBe(true);
    expect(signIn.headers["X-EZPZ-Session"]).toBe("cookie");
    const other = (await api.get("/lz", { adapter: capture })).data;
    expect(other.withCredentials).toBeFalsy();
    expect(other.headers["X-EZPZ-Session"]).toBeUndefined();
  });

  test("a server that does not offer cookie sessions is asked as it always was", async () => {
    cookieSessionsOffered.mockResolvedValueOnce(false);
    const signIn = (await api.post("/auth/login", {}, { adapter: capture })).data;
    expect(signIn.withCredentials).toBeFalsy();
    expect(signIn.headers["X-EZPZ-Session"]).toBeUndefined();
  });
});
