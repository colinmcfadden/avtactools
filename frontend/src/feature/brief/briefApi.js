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

const PDF_WORDS = {
  pdf_unavailable: "The PDF is not available on this server yet.",
  pdf_failed: "The PDF could not be made. Try again in a moment.",
  invalid_brief: "The brief could not be printed: open it again and retry.",
  too_large: "The brief is too large to print.",
  feature_disabled: "Mission briefs are not turned on for your account.",
};

/**
 * The brief as the server prints it (POST /api/brief/pdf): a Blob of the PDF, or an Error whose message is the app's
 * words for why not (never the server's own text).
 */
export const printBrief = async (model) => {
  try {
    const response = await api.post("/brief/pdf", { model }, { responseType: "blob" });
    return response.data;
  } catch (error) {
    let code = null;
    try {
      code = JSON.parse(await error.response?.data?.text?.())?.code ?? null;
    } catch {
      code = null;
    }
    throw new Error(PDF_WORDS[code] ?? (error.response ? "The PDF could not be made." : "No connection to the server: the PDF needs one."));
  }
};

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
