/*
 * pdf.js, loaded only when a document opens (docs/DOCUMENTS.md §8.1): a dynamic import, so the viewer's ~1 MB stays out of the main
 * bundle as Cesium does. **Its legacy build**, worker and all: the modern one calls `Promise.try` and `Map.prototype.getOrInsertComputed`
 * with no fallback, which a browser a year or two old (an iPad on iPadOS 17) does not have, and every document would fail to open there;
 * the legacy build carries both. Its worker and the files it reads while drawing (character maps, the standard fonts a PDF names without
 * embedding, the WebAssembly image decoders) come from public/pdfjs/, the site's own origin, copied there at build by
 * scripts/copy-pdfjs.js at the one version package.json pins.
 *
 * Nothing in a PDF runs: pdf.js 6 has no eval path (CVE-2024-4367's font path, and its `isEvalSupported` option, are gone), XFA forms
 * are off, and the viewer uses no scripting sandbox, annotation layer or links.
 */

const BASE = `${import.meta.env?.BASE_URL ?? "/"}pdfjs/`;

let loading = null;

/** The pdf.js module, imported once. A failed import is tried again next time (a deploy can change the chunk under an open tab). */
export const loadPdfJs = () => {
  loading =
    loading ??
    import("pdfjs-dist/legacy/build/pdf.mjs")
      .then((pdfjs) => {
        pdfjs.GlobalWorkerOptions.workerSrc = `${BASE}pdf.worker.min.mjs`;
        return pdfjs;
      })
      .catch((error) => {
        loading = null;
        throw error;
      });
  return loading;
};

/** Opens a PDF's bytes: `{ promise, destroy }`, where `promise` is pdf.js's document. The bytes are copied, since pdf.js takes them. */
export const openPdf = async (bytes) => {
  const pdfjs = await loadPdfJs();
  const task = pdfjs.getDocument({
    data: new Uint8Array(bytes.slice(0)),
    cMapUrl: `${BASE}cmaps/`,
    cMapPacked: true,
    standardFontDataUrl: `${BASE}standard_fonts/`,
    wasmUrl: `${BASE}wasm/`,
    iccUrl: `${BASE}iccs/`,
    enableXfa: false,
    stopAtErrors: false,
  });
  return { promise: task.promise, destroy: () => task.destroy() };
};
