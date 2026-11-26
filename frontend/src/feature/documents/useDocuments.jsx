import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import DocumentViewer from "./DocumentViewer";
import DocumentVersionDialog from "./DocumentVersionDialog";
import {
  deleteDocument,
  getDocumentPdf,
  getDocumentText,
  listDocuments,
  newDocumentVersion,
  uploadDocument,
  randomId,
  uploadErrorMessage,
} from "./documentsApi";
import { documentDetail } from "./documentWords";
import { savePdf } from "./savePdf";

/*
 * Documents on the web (docs/DOCUMENTS.md §9.1): the Library's list and its calls, the one document being read (DocumentViewer, a
 * window over the map) and the dialog a new version is chosen in. A pack's documents are read and given new versions through the
 * same viewer and dialog (`open`, `askNewVersion`), their calls being the pack's (missionPacks/packApi.js).
 *
 * `enabled`: the account has `documents`, so its Library's are listed and kept; without it nothing of the Library is fetched. `signedIn`:
 * someone is signed in, which is all reading a pack's documents needs (§9.3: a member without the key still reads them); signed out,
 * nothing stays open. `conversion`: the server turns Office files into PDFs (`services.documentConversion` of /api/config).
 *
 * Where the viewer is the whole screen (850 px and below, §8.1), it is a step of the browser's history, so Back closes it.
 */

const NARROW = "(max-width: 850px)";

export { randomId };


export const libraryKey = (id) => `library:${id}`;

export const useDocuments = ({ enabled, signedIn = enabled, conversion = true, toast }) => {
  const [entries, setEntries] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [viewing, setViewing] = useState(null);
  const [versioning, setVersioning] = useState(null);
  const asked = useRef(0);

  const refresh = useCallback(async () => {
    if (!enabled) return;
    const ask = ++asked.current;
    setLoading(true);
    try {
      const list = await listDocuments();
      if (ask === asked.current) {
        setEntries(Array.isArray(list) ? list : []);
        setError(null);
      }
    } catch (err) {
      if (ask === asked.current) setError(err);
    } finally {
      if (ask === asked.current) setLoading(false);
    }
  }, [enabled]);

  // The list is fetched once the feature is on (a sign-in), and nothing of one account's is left for the next, or for an account the
  // feature was taken from.
  useEffect(() => {
    if (enabled) {
      refresh();
      return;
    }
    asked.current += 1;
    setEntries([]);
    setError(null);
    setLoading(false);
  }, [enabled, refresh]);

  // Signed out, nothing stays open: a pack's document read by a member without the feature included.
  useEffect(() => {
    if (signedIn) return;
    setViewing(null);
    setVersioning(null);
  }, [signedIn]);

  // The whole-screen viewer is a step of the history: Back closes it, and closing it otherwise takes the step back off.
  const isViewing = Boolean(viewing);
  useEffect(() => {
    if (!isViewing || typeof window === "undefined" || !window.matchMedia?.(NARROW).matches) return undefined;
    const mark = `${Date.now()}-${Math.random()}`;
    window.history.pushState({ ...(window.history.state ?? {}), ezpzDocument: mark }, "");
    let popped = false;
    const onPop = () => {
      popped = true;
      setViewing(null);
    };
    window.addEventListener("popstate", onPop);
    return () => {
      window.removeEventListener("popstate", onPop);
      if (!popped && window.history.state?.ezpzDocument === mark) window.history.back();
    };
  }, [isViewing]);

  /** Reads a document: `{ key, title, detail, loadPdf, loadText }`. Opening the one already open shows it again from the start. */
  const open = useCallback((target) => setViewing((before) => ({ ...target, n: (before?.n ?? 0) + 1 })), []);
  const close = useCallback(() => setViewing(null), []);
  /** The document being read renamed, or its pages changed, without reading it again: its window's title and line follow. */
  const retitle = useCallback(
    (key, { title, detail }) => setViewing((now) => (now?.key === key && (now.title !== title || now.detail !== detail) ? { ...now, title, detail } : now)),
    [],
  );

  const openLibrary = useCallback(
    (entry) =>
      open({
        key: libraryKey(entry.id),
        title: entry.name,
        detail: documentDetail(entry),
        loadPdf: () => getDocumentPdf(entry.id),
        loadText: () => getDocumentText(entry.id),
      }),
    [open],
  );

  /** Uploads a file to the Library. `clientUuid` is the upload's own, kept through every try of it, so a retry is the same document. */
  const upload = useCallback(
    async (file, { name, clientUuid = randomId() } = {}) => {
      const made = await uploadDocument(file, { name, clientUuid });
      refresh();
      return made;
    },
    [refresh],
  );

  const remove = useCallback(
    async (entry) => {
      await deleteDocument(entry.id);
      setViewing((now) => (now?.key === libraryKey(entry.id) ? null : now));
      await refresh();
    },
    [refresh],
  );

  /** Downloads a document's PDF: `load()` gives its bytes. */
  const download = useCallback(
    async (load, title) => {
      try {
        savePdf(await load(), title);
      } catch {
        toast?.({ tone: "error", message: `“${title}” could not be downloaded. Check the connection and try again.` });
      }
    },
    [toast],
  );

  /**
   * Asks for a new version: `{ key, name, where, upload(file), errorMessage, reopen }`. Once it is kept the dialog closes, a viewer
   * showing the document opens it again (unless `reopen` is false: a pack's viewer opens again when the pack's new version reaches
   * it, usePackWorkspace), and the toast says so.
   */
  const askNewVersion = useCallback((target) => setVersioning(target), []);

  const newLibraryVersion = useCallback(
    (entry) =>
      askNewVersion({
        key: libraryKey(entry.id),
        name: entry.name,
        where: "you, on every device you use",
        upload: async (file) => {
          await newDocumentVersion(entry.id, file);
          await refresh();
        },
        errorMessage: uploadErrorMessage,
      }),
    [askNewVersion, refresh],
  );

  // A Library document being read follows its record: a rename here or on another device, a new version's pages.
  useEffect(() => {
    if (!viewing?.key?.startsWith("library:")) return;
    const entry = entries.find((e) => libraryKey(e.id) === viewing.key);
    if (entry) retitle(viewing.key, { title: entry.name, detail: documentDetail(entry) });
  }, [entries, viewing?.key, retitle]);

  const openIds = useMemo(() => {
    const ids = new Set();
    entries.forEach((entry) => viewing?.key === libraryKey(entry.id) && ids.add(entry.id));
    return ids;
  }, [entries, viewing]);

  const dialogs = (
    <>
      {viewing && (
        <DocumentViewer
          key={`${viewing.key}#${viewing.n}`}
          title={viewing.title}
          detail={viewing.detail}
          loadPdf={viewing.loadPdf}
          loadText={viewing.loadText}
          onClose={close}
        />
      )}
      {versioning && (
        <DocumentVersionDialog
          name={versioning.name}
          where={versioning.where}
          conversion={conversion}
          errorMessage={versioning.errorMessage ?? uploadErrorMessage}
          onCancel={() => setVersioning(null)}
          onUpload={async (file) => {
            await versioning.upload(file);
            const { key, name, reopen = true } = versioning;
            setVersioning(null);
            if (reopen) setViewing((now) => (now?.key === key ? { ...now, n: now.n + 1 } : now));
            toast?.({ message: `Uploaded a new version of “${name}”` });
          }}
        />
      )}
    </>
  );

  return {
    enabled,
    conversion,
    entries,
    loading,
    error,
    refresh,
    upload,
    remove,
    open,
    openLibrary,
    close,
    retitle,
    viewing,
    openIds,
    download,
    askNewVersion,
    newLibraryVersion,
    dialogs,
  };
};

export default useDocuments;
