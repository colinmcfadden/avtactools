import api from "../auth/api";
import { beginPriority } from "../auth/requestPriority";

/*
 * The server's side of the LZ survey books (backend/routes/survey_routes.py; docs/LZ_SURVEYS.md §8), web only. Who may read and edit
 * a book is the server's to say (survey/access.py): every book comes with `can_edit`. Every save names the revision it was made on
 * (If-Match), so two editors never overwrite each other: the older save is refused with 409 and the server's copy (`error.server`).
 */

const at = (revision) => ({ headers: { "If-Match": `"${revision}"` } });

/**
 * Heavy work, as an analysis is (the slope over the DEMs, the map's tiles for a page): the 3D view's terrain waits for it. Marked here,
 * not in requestPriority's list, which the native apps are held to (contracts/fixtures/network/priority.json) and which a web-only
 * route has no place in.
 */
const heavy = async (request) => {
  const end = beginPriority();
  try {
    return await request();
  } finally {
    end();
  }
};

/** The books this person reads or edits, the teams they may make a book for, and whether they may make one for no team. */
export const listBooks = async ({ archived = false } = {}) =>
  (await api.get("/survey-books", { params: archived ? { archived: 1 } : undefined })).data;

export const createBook = async ({ name, unitLine = "", teamId = null }) =>
  (await api.post("/survey-books", { name, unit_line: unitLine, team_id: teamId })).data;

/** The book and its surveys in page order: every one for an editor (`archived` lists the archived too), the published for a reader. */
export const getBook = async (uuid, { archived = false } = {}) =>
  (await api.get(`/survey-books/${uuid}`, { params: archived ? { archived: 1 } : undefined })).data;

/** `patch`: any of name, unit_line, cover_text, page_order, team_id (a site admin's). */
export const updateBook = async (uuid, revision, patch) => (await api.patch(`/survey-books/${uuid}`, patch, at(revision))).data;

export const archiveBook = async (uuid) => (await api.post(`/survey-books/${uuid}/archive`)).data;

export const restoreBook = async (uuid) => (await api.post(`/survey-books/${uuid}/restore`)).data;

export const getSurvey = async (uuid) => (await api.get(`/surveys/${uuid}`)).data;

export const createSurvey = async ({ book, name, hlzId, field, diagram }) =>
  (await api.post("/surveys", { book, name, hlz_id: hlzId, field, diagram })).data;

/** `patch`: any of name, hlz_id, field, diagram, overrides, book (moves it). */
export const saveSurvey = async (uuid, revision, patch) => (await api.patch(`/surveys/${uuid}`, patch, at(revision))).data;

export const archiveSurvey = async (uuid) => (await api.post(`/surveys/${uuid}/archive`)).data;

export const restoreSurvey = async (uuid) => (await api.post(`/surveys/${uuid}/restore`)).data;

/** Measures the saved diagram. Without `apply` nothing is kept: { snapshot, changes, comparisons }. With it, `survey` is the survey now. */
export const measureSurvey = async (uuid, { apply = false, revision } = {}) =>
  heavy(async () => (await api.post(`/surveys/${uuid}/measure`, { apply }, apply ? at(revision) : undefined)).data);

/** A draft goes for review. */
export const submitSurvey = async (uuid, revision) => (await api.post(`/surveys/${uuid}/submit`, null, at(revision))).data;

/** A survey under review goes back to being a draft. */
export const backToDraft = async (uuid, revision) => (await api.post(`/surveys/${uuid}/draft`, null, at(revision))).data;

/** Signs off and publishes a survey under review: { survey, revision }. `signoff`: name, rank, title, date. */
export const publishSurvey = async (uuid, revision, { signoff, changes, opacity }) =>
  heavy(async () => (await api.post(`/surveys/${uuid}/publish`, { signoff, changes, opacity }, at(revision))).data);

const pageParams = ({ rev, opacity } = {}) => ({
  ...(rev ? { rev } : {}),
  ...(opacity ? { opacity } : {}),
});

/** The page as one HTML document (its pictures in it): a published revision's (`rev`), or for an editor the survey as it is now. */
export const fetchPage = async (uuid, options = {}) =>
  heavy(async () => (await api.get(`/surveys/${uuid}/page`, { params: pageParams(options), responseType: "text" })).data);

/** The page's PDF as a Blob. 503 `pdf_unavailable` where the server cannot print one: the page preview shows it all the same. */
export const fetchPdf = async (uuid, options = {}) =>
  heavy(async () => (await api.get(`/surveys/${uuid}/pdf`, { params: pageParams(options), responseType: "blob" })).data);

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
  feature_disabled: "Your account cannot open the LZ survey books. An admin can give you access.",
  not_editor: "Only the book's editors can change it.",
  book_required: "Choose the book the survey is for.",
  revision_conflict: "Someone saved this since you opened it.",
  archived: "This survey is archived: restore it to change it.",
  no_target: "Set the LZ's target before measuring.",
  too_large_to_measure: "The boundary is more than 3 km across: too large to be an LZ. Draw it again.",
  not_found: "That is no longer there, or you cannot open it.",
  not_measured: "Measure the LZ first.",
  measure_again: "Measure the LZ again first: the target, the boundary or the best approach changed after it was measured.",
  invalid_status: "The survey has moved on since you opened it. Reload it.",
  pdf_unavailable: "PDFs are not printed on this server yet: the page preview shows the page.",
  pdf_failed: "The PDF could not be made. Try again in a moment.",
  too_large: "A photo can be at most 20 MB.",
  not_an_image: "That file is not a picture that can be read.",
  unsupported_image: "Send a JPEG or a PNG.",
  image_too_large: "That picture is too large to read.",
};

/** The app's words for why a survey call failed (never the server's own text, which is for a developer, but for a 400's). */
export const surveyFailure = (error, fallback = "That did not work. Try again.") => {
  const code = error?.response?.data?.code;
  if (WORDS[code]) return WORDS[code];
  if (error?.response?.status === 400 && error.response.data?.error) return error.response.data.error;
  if (!error?.response) return "No connection to the server. Try again when you have one.";
  return fallback;
};

const blobText = (blob) =>
  typeof blob.text === "function"
    ? blob.text()
    : new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve(String(reader.result));
        reader.onerror = () => reject(reader.error);
        reader.readAsText(blob);
      });

/** The failure words of a call answered with a Blob or text (a PDF's, a page's), whose error body is not JSON yet. */
export const blobFailure = async (error, fallback) => {
  const data = error?.response?.data;
  if (data instanceof Blob) {
    try {
      const parsed = JSON.parse(await blobText(data));
      return surveyFailure({ response: { ...error.response, data: parsed } }, fallback);
    } catch {
      return surveyFailure({ response: { ...error.response, data: {} } }, fallback);
    }
  }
  if (typeof data === "string") {
    try {
      return surveyFailure({ response: { ...error.response, data: JSON.parse(data) } }, fallback);
    } catch {
      return surveyFailure({ response: { ...error.response, data: {} } }, fallback);
    }
  }
  return surveyFailure(error, fallback);
};
