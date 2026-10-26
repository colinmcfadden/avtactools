import api from "../auth/api";
import { beginPriority } from "../auth/requestPriority";

/*
 * The server's side of the LZ survey book (backend/routes/survey_routes.py; docs/LZ_SURVEYS.md §8). Admins only, web only.
 * Every save names the revision it was made on (If-Match), so two admins on one survey never overwrite each other: the
 * older save is refused with 409 and the server's copy (`error.server`).
 */

const at = (revision) => ({ headers: { "If-Match": `"${revision}"` } });

/** The book and its surveys in page order; `archived` lists the archived ones too. */
export const listSurveys = async ({ archived = false } = {}) =>
  (await api.get("/surveys", { params: archived ? { archived: 1 } : undefined })).data;

export const getSurvey = async (uuid) => (await api.get(`/surveys/${uuid}`)).data;

export const createSurvey = async ({ name, hlzId, field, diagram }) =>
  (await api.post("/surveys", { name, hlz_id: hlzId, field, diagram })).data;

/** `patch`: any of name, hlz_id, field, diagram, overrides. */
export const saveSurvey = async (uuid, revision, patch) => (await api.patch(`/surveys/${uuid}`, patch, at(revision))).data;

export const archiveSurvey = async (uuid) => (await api.post(`/surveys/${uuid}/archive`)).data;

export const restoreSurvey = async (uuid) => (await api.post(`/surveys/${uuid}/restore`)).data;

/**
 * Measures the saved diagram. Without `apply` nothing is kept: { snapshot, changes, comparisons }. With it, `survey` is the survey now.
 * Heavy work, as an analysis is (the slope, contours and profiles over the DEMs): the 3D view's terrain waits for it. Marked here,
 * not in requestPriority's list, which the native apps are held to (contracts/fixtures/network/priority.json) and which a web-only
 * route has no place in.
 */
export const measureSurvey = async (uuid, { apply = false, revision } = {}) => {
  const end = beginPriority();
  try {
    return (await api.post(`/surveys/${uuid}/measure`, { apply }, apply ? at(revision) : undefined)).data;
  } finally {
    end();
  }
};

export const updateBook = async (revision, patch) => (await api.patch("/survey-book", patch, at(revision))).data;

/** `meta`: kind, caption, heading_deg, taken_on. */
export const uploadAsset = async (uuid, file, meta = {}) => {
  const form = new FormData();
  form.append("file", file);
  Object.entries(meta).forEach(([key, value]) => {
    if (value !== undefined && value !== null) form.append(key, String(value));
  });
  return (await api.post(`/surveys/${uuid}/assets`, form)).data;
};

export const updateAsset = async (uuid, id, meta) => (await api.patch(`/surveys/${uuid}/assets/${id}`, meta)).data;

export const removeAsset = async (uuid, id) => (await api.delete(`/surveys/${uuid}/assets/${id}`)).data;

/** A picture's bytes, as a Blob: an <img> cannot send the bearer token, so the page fetches it and shows an object URL. */
export const fetchAsset = async (url) => (await api.get(url, { responseType: "blob" })).data;

const WORDS = {
  admin_required: "Only admins can work on LZ surveys.",
  revision_conflict: "Someone saved this survey since you opened it.",
  archived: "This survey is archived: restore it to change it.",
  no_target: "Set the LZ's target before measuring.",
  too_large_to_measure: "The boundary is more than 3 km across: too large to be an LZ. Draw it again.",
  not_found: "That survey is no longer there.",
  too_large: "A photo can be at most 20 MB.",
  not_an_image: "That file is not a picture that can be read.",
  unsupported_image: "Send a JPEG or a PNG.",
  image_too_large: "That picture is too large to read.",
};

/** The app's words for why a survey call failed (never the server's own text, which is for a developer). */
export const surveyFailure = (error, fallback = "That did not work. Try again.") => {
  const code = error?.response?.data?.code;
  if (WORDS[code]) return WORDS[code];
  if (error?.response?.status === 400 && error.response.data?.error) return error.response.data.error;
  if (!error?.response) return "No connection to the server. Try again when you have one.";
  return fallback;
};
