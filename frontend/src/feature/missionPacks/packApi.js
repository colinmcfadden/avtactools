import api from "../auth/api";

// The pack routes a sync client needs (contracts/openapi.yaml). Paths are relative to
// REACT_APP_API_URL, which already ends in /api.

const path = (uuid) => `/packs/${encodeURIComponent(uuid)}`;

export const getPack = (uuid) => api.get(path(uuid)).then((r) => r.data);

export const getEvents = (uuid, since, limit = 500) =>
  api.get(`${path(uuid)}/events`, { params: { since, limit } }).then((r) => r.data);

export const sendOps = (uuid, body) => api.post(`${path(uuid)}/ops`, body).then((r) => r.data);

/** What a refused request said, in the shape packSession.batchFailed reads. Status 0: no answer came. */
export const failureOf = (error) => {
  const response = error?.response;
  if (!response) return { status: 0 };
  const data = response.data && typeof response.data === "object" ? response.data : {};
  return { status: response.status, code: data.code, reason: data.reason, finished_by: data.finished_by, finished_at: data.finished_at };
};
