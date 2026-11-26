/*
 * How a document is described in a list (docs/DOCUMENTS.md §9.1): its pages and what it was made from, from a Library summary
 * (`pages`, `source_type`) or a pack item's data (`pdf.pages`, `source.type`) alike.
 */

const SOURCES = { pdf: "PDF", doc: "from Word", docx: "from Word", xls: "from Excel", xlsx: "from Excel", ppt: "from PowerPoint", pptx: "from PowerPoint" };

export const pageWords = (pages) => (Number.isFinite(pages) && pages > 0 ? `${pages.toLocaleString("en-US")} ${pages === 1 ? "page" : "pages"}` : null);

export const sourceWords = (type) => SOURCES[type] ?? null;

/** "14 pages · from Word" for a Library entry or a pack item's data; whatever of it is known. */
export const documentDetail = (document) => {
  const pages = document?.pages ?? document?.pdf?.pages;
  const type = document?.source_type ?? document?.source?.type;
  return [pageWords(pages), sourceWords(type)].filter(Boolean).join(" · ");
};

/** The file types offered for upload: PDF always, and the Office files only where the server converts them. */
export const DOCUMENT_ACCEPT = ".pdf,application/pdf";
export const OFFICE_ACCEPT =
  ".doc,.docx,.xls,.xlsx,.ppt,.pptx,application/msword,application/vnd.ms-excel,application/vnd.ms-powerpoint," +
  "application/vnd.openxmlformats-officedocument.wordprocessingml.document," +
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet," +
  "application/vnd.openxmlformats-officedocument.presentationml.presentation";
export const acceptFor = (conversion) => (conversion ? `${DOCUMENT_ACCEPT},${OFFICE_ACCEPT}` : DOCUMENT_ACCEPT);
