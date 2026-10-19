import api from "../auth/api";

/*
 * The server's side of a brief outside a pack (backend/routes/brief_routes.py): the person's answers kept in their
 * Library, synced like a saved LZ. A brief in a pack is a pack item and goes through the pack's own edits instead.
 * And the weather at a planned time (/api/weather's `at`), which a brief asks for its departure, arrival and stops.
 */

export const listBriefs = async () => (await api.get("/briefs")).data;

export const getBrief = async (id) => (await api.get(`/briefs/${id}`)).data;

/** `clientUuid` makes the create repeatable: a second POST with it answers the first record. */
export const createBrief = async ({ name, data, clientUuid }) =>
  (await api.post("/briefs", { name, data, client_uuid: clientUuid })).data;

export const updateBrief = async (id, { name, data }) => (await api.put(`/briefs/${id}`, { name, data })).data;

/** The report or forecast for a place at an instant (ISO); null when the server could not answer. */
export const weatherAt = async ({ lat, lon, at }) => {
  try {
    const params = { lat, lng: lon };
    if (at) params.at = at;
    return (await api.get("/weather", { params })).data;
  } catch {
    return null;
  }
};
