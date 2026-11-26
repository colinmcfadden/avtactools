import api from "../auth/api";

/*
 * The Library's documents (docs/DOCUMENTS.md §6). The server makes every document from the file sent: a client never sends a
 * document's content, only a file, a name, a rename or a deletion, each made while connected. The pack's own document calls are in
 * missionPacks/packApi.js. Paths are relative to VITE_API_URL, which already ends in /api.
 */

/** A version 4 UUID, which the server takes as an upload's `client_uuid`: crypto's own where the page may use it (a secure context),
 * else made from crypto's random bytes, else Math.random (a page served over plain http on a LAN address has no randomUUID). */
export const randomId = () => {
  if (typeof crypto !== "undefined" && crypto.randomUUID) return crypto.randomUUID();
  const bytes = new Uint8Array(16);
  if (typeof crypto !== "undefined" && crypto.getRandomValues) crypto.getRandomValues(bytes);
  else for (let i = 0; i < 16; i += 1) bytes[i] = Math.floor(Math.random() * 256);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
};

const body = (response) => response.data;
const one = (id) => `/documents/${encodeURIComponent(id)}`;

const form = (file, fields = {}) => {
  const data = new FormData();
  data.append("file", file, file.name);
  Object.entries(fields).forEach(([key, value]) => value !== undefined && value !== null && data.append(key, value));
  return data;
};

/** The caller's documents, newest change first: `{ id, name, pages, bytes, source_type, has_text, created_at, updated_at, ... }`. */
export const listDocuments = () => api.get("/documents").then(body);

/**
 * Uploads a file (a PDF, or a Word, Excel or PowerPoint file, which the server turns into a PDF). `clientUuid` makes a retry after a
 * lost answer the same document; `name` defaults on the server to the file's name less its extension.
 */
export const uploadDocument = (file, { name, clientUuid } = {}) =>
  api.post("/documents", form(file, { name, client_uuid: clientUuid })).then(body);

export const getDocument = (id) => api.get(one(id)).then(body);

export const renameDocument = (id, name) => api.put(one(id), { name }).then(body);

/** A new version of the paper, under the same name and identity. */
export const newDocumentVersion = (id, file) => api.post(`${one(id)}/version`, form(file)).then(body);

export const deleteDocument = (id) => api.delete(one(id)).then(body);

/** The PDF's bytes, as an ArrayBuffer: the route needs the bearer token, so the viewer reads it, never a plain link. */
export const getDocumentPdf = (id) => api.get(`${one(id)}/pdf`, { responseType: "arraybuffer" }).then(body);

/** `{ version, pages, truncated }`: each page's text, for find in the document. A scan has none (404 `no_text`). */
export const getDocumentText = (id) => api.get(`${one(id)}/text`).then(body);

/** What a refused upload says, in words for the person: the server's sentence where it gave one. */
export const uploadErrorMessage = (error) => {
  const data = error?.response?.data;
  if (data?.error && data?.code) return data.error;
  if (!error?.response) return "The upload did not reach the server. Check the connection and try again.";
  return "The document could not be uploaded. Try again.";
};
