import api, { isPublicAuthRequest } from "./api";

const unauthorizedAdapter = (config) =>
  Promise.reject({ config, response: { status: 401 } });

describe("auth API unauthorized handling", () => {
  beforeEach(() => {
    localStorage.clear();
    jest.useFakeTimers();
  });

  afterEach(() => {
    jest.runOnlyPendingTimers();
    jest.useRealTimers();
  });

  test("recognizes public authentication requests", () => {
    expect(isPublicAuthRequest("/auth/login")).toBe(true);
    expect(isPublicAuthRequest("https://api.example.com/api/auth/google")).toBe(true);
    expect(isPublicAuthRequest("/terrain-analysis")).toBe(false);
  });

  test("keeps public auth failures inline but expires protected state without a stored token", async () => {
    const onExpired = jest.fn();
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
