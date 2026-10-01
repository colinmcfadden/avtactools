import api from "./api";
import {
  beginPriority, isPriorityRequest, priorityActive, whenIdle,
} from "./requestPriority";

describe("which requests are heavy", () => {
  it("knows the analyses, the viewshed and the exports", () => {
    for (const path of ["/analyze-field", "/terrain-analysis", "/threat-mask",
                        "/export-package", "/generate-excel"]) {
      expect(isPriorityRequest(path)).toBe(true);
    }
  });

  it("leaves everything else alone", () => {
    for (const path of ["/lidar/build/0123456789abcdef", "/terrain/heights", "/weather",
                        "/auth/me", "/elevations"]) {
      expect(isPriorityRequest(path)).toBe(false);
    }
  });
});

describe("waiting for heavy work", () => {
  it("does not wait when nothing is running", async () => {
    expect(priorityActive()).toBe(false);
    await expect(whenIdle()).resolves.toBeUndefined();
  });

  it("waits until the last of overlapping work ends", async () => {
    const endFirst = beginPriority();
    const endSecond = beginPriority();
    let released = false;
    const waiting = whenIdle().then(() => { released = true; });

    endFirst();
    await Promise.resolve();
    expect(released).toBe(false);

    endSecond();
    await waiting;
    expect(released).toBe(true);
    expect(priorityActive()).toBe(false);
  });

  it("counts an end only once", () => {
    const endFirst = beginPriority();
    const endSecond = beginPriority();
    endFirst();
    endFirst();
    expect(priorityActive()).toBe(true);
    endSecond();
    expect(priorityActive()).toBe(false);
  });
});

describe("the API client marks heavy requests while they are in flight", () => {
  const original = api.defaults.adapter;
  afterEach(() => { api.defaults.adapter = original; });

  const respond = (status) => async (config) => {
    const seen = priorityActive();
    const response = { data: { seen }, status, statusText: "", headers: {}, config };
    if (status >= 400) {
      const error = new Error("failed");
      error.config = config;
      error.response = response;
      throw error;
    }
    return response;
  };

  it("during an analysis, and not after", async () => {
    api.defaults.adapter = respond(200);
    const res = await api.post("/analyze-field", {});
    expect(res.data.seen).toBe(true);
    expect(priorityActive()).toBe(false);
  });

  it("not for ordinary requests", async () => {
    api.defaults.adapter = respond(200);
    const res = await api.get("/weather");
    expect(res.data.seen).toBe(false);
  });

  it("and releases even when the analysis fails", async () => {
    api.defaults.adapter = respond(500);
    await expect(api.post("/terrain-analysis", {})).rejects.toThrow();
    expect(priorityActive()).toBe(false);
  });
});
