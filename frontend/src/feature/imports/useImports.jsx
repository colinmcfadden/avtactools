import React, { useCallback, useRef, useState } from "react";
import Icon from "../ui/Icon";
import { useToast } from "../ui/Toast";
import { randomId, uploadErrorMessage } from "../documents/documentsApi";
import { detectImport } from "./detectImport";
import ImportReviewDialog, { defaultDestination } from "./ImportReviewDialog";

/*
 * Every way a file comes in (the Import menu, the Imports panel, a drop anywhere on the map) goes
 * through here: each file is identified by its content, the person reviews what will happen, and
 * only then is anything imported. Threat files are remembered (name and count) so the Imports panel
 * can list them; their threats live under Threats.
 *
 * `importers`: { msnx(file), lps(file, { name, parsed }), ths(file), routefile(parsed, { name }),
 * overlay(file, { name, parsed }), document(file, { name, clientUuid }) } that bring a file into the session (a document: into the
 * Library) and return what they made.
 * `destinations`: { library: { msnx(result, item), lps(set), routefile(result, item), overlay(made) },
 * pack: { lps(set), ths(file, options), routefile(parsed, options), document(file, { name, item }) } }.
 * `onDone(kinds, imported)` hears which kinds came in and, for each file that did, where it went.
 *
 * `documents`: { on, saved, conversion }: whether the account has documents, whether it can keep them in a Library (cloud save),
 * and whether the server turns Office files into PDFs. A document the account has nowhere to put, or an Office file the server
 * cannot convert, is said in the review and left out. A document's upload has an id of its own (`clientUuid`), kept through every
 * try, so one asked again after a lost answer is the same document.
 */


/** What becomes of a detected document for this account and server: the item as it is, or one left out with the reason. */
export const documentRules = (item, { documents, pack }) => {
  if (item.kind !== "document") return item;
  // An account without the feature is told what anyone is told of a file EZ/PZ does not take.
  if (!documents?.on) return { ...item, kind: null, error: "Not supported. Nothing will be imported from this file." };
  if (item.docType !== "pdf" && !documents.conversion) {
    return { ...item, kind: null, error: "This server cannot turn Office files into PDFs. Save it as a PDF and upload that." };
  }
  // Without cloud save a document can go only into the open pack, and only one this person may change.
  if (!documents.saved && (!pack || pack.finished || pack.role === "viewer")) {
    const why = !pack ? "Open a Mission Pack to upload it there." : pack.finished ? "This pack is finished." : "You can only view this pack.";
    return { ...item, kind: null, error: `Cloud save is off for this account, so a document has nowhere to go. ${why}` };
  }
  return { ...item, clientUuid: item.clientUuid ?? randomId() };
};

/**
 * Moves points just imported into this session over to the open pack. The session's copy is dropped
 * only once the pack has taken them, so a pack that refuses (finished, only viewed, no usable points)
 * leaves them in the session instead of losing them.
 */
export const movePointsToPack = (set, { createItem, removePointSet }) => {
  if (!createItem(set.name, set.points)) throw new Error("the pack did not take them. They stay in this session: switch to Library to see them.");
  removePointSet(set.id);
};

export const useImports = ({ pack, threatsSaved = false, overlaysSaved = false, documents = null, importers, destinations, onDone }) => {
  const { show } = useToast();
  const [review, setReview] = useState(null);
  const [threatFiles, setThreatFiles] = useState([]);
  const [dragging, setDragging] = useState(false);
  const depth = useRef(0);
  // Each review is a dialog of its own (its key), so one opened again for the documents to retry starts from them.
  const reviews = useRef(0);
  const deps = useRef({ importers, destinations, onDone, pack, threatsSaved, overlaysSaved, documents });
  deps.current = { importers, destinations, onDone, pack, threatsSaved, overlaysSaved, documents };

  /** Reviews files. `prefer: "library"` sends a document to the Library where it may go there (the Library's own Upload). */
  const begin = useCallback(
    async (files, { prefer } = {}) => {
      const list = [...files];
      if (list.length === 0) return;
      const detected = (await Promise.all(list.map((file) => detectImport(file)))).map((item) => documentRules(item, deps.current));
      const { pack: open, threatsSaved: threats, overlaysSaved: overlays } = deps.current;
      reviews.current += 1;
      const docsSaved = Boolean(deps.current.documents?.saved);
      const where = (item) =>
        item.kind === "document" && prefer === "library" && docsSaved ? "library" : defaultDestination(item.kind, open, threats, overlays);
      setReview(detected.map((item) => ({ ...item, destination: item.kind ? where(item) : null })));
    },
    [],
  );

  const run = useCallback(
    async (items) => {
      const { importers: imp, destinations: dest, onDone: done } = deps.current;
      const failed = [];
      const imported = [];
      const kinds = new Set();
      // A document that did not go is kept in the review with why, so trying again is the same upload (its clientUuid).
      const retry = [];
      for (const item of items) {
        try {
          if (item.kind === "msnx") {
            const result = await imp.msnx(item.file);
            if (item.destination === "library") dest.library.msnx(result, item);
          } else if (item.kind === "lps") {
            const set = await imp.lps(item.file, { name: item.name?.trim() || undefined, parsed: item.parsed });
            if (item.destination === "library") await dest.library.lps(set);
            if (item.destination === "pack") await dest.pack.lps(set);
          } else if (item.kind === "ths") {
            const options = { name: item.name?.trim() || undefined };
            const count = item.destination === "pack" ? await dest.pack.ths(item.file, options) : await imp.ths(item.file, options);
            setThreatFiles((prev) => [...prev, { id: `${Date.now()}-${prev.length}`, fileName: item.file.name, count }]);
          } else if (item.kind === "routefile") {
            const options = { name: item.name?.trim() || undefined };
            if (item.destination === "pack") {
              await dest.pack.routefile(item.parsed, options);
            } else {
              const result = await imp.routefile(item.parsed, options);
              if (item.destination === "library") dest.library.routefile(result, item);
            }
          } else if (item.kind === "overlay") {
            const made = await imp.overlay(item.file, { name: item.name?.trim() || undefined, parsed: item.parsed });
            if (item.destination === "library") await dest.library.overlay(made);
          } else if (item.kind === "document") {
            const name = item.name?.trim() || undefined;
            if (item.destination === "pack") await dest.pack.document(item.file, { name, item: `doc-${item.clientUuid}` });
            else await imp.document(item.file, { name, clientUuid: item.clientUuid });
          }
          kinds.add(item.kind);
          imported.push({ kind: item.kind, destination: item.destination, fileName: item.file.name });
        } catch (err) {
          // A document's refusal is the server's sentence (the file's size, its password, a converter that is busy), in its words.
          const why = item.kind === "document" ? uploadErrorMessage(err) : err?.message || "it could not be imported";
          failed.push(`${item.file.name}: ${why}`);
          if (item.kind === "document") retry.push({ ...item, failure: why });
        }
      }
      if (retry.length) reviews.current += 1;
      setReview(retry.length ? retry : null);
      const ok = items.length - failed.length;
      if (ok > 0) show({ message: items.length === 1 ? `Imported ${items[0].file.name}` : `Imported ${ok} of ${items.length} files` });
      if (failed.length) show({ tone: "error", message: `Not imported. ${failed.join(" ")}`, duration: 9000 });
      done?.([...kinds], imported);
    },
    [show],
  );

  // Dragging files over the map shows where to drop them; dragging anything else (a marker, text) does not.
  const hasFiles = (event) => [...(event.dataTransfer?.types ?? [])].includes("Files");
  const dragProps = {
    onDragEnter: (event) => {
      if (!hasFiles(event)) return;
      event.preventDefault();
      depth.current += 1;
      setDragging(true);
    },
    onDragOver: (event) => {
      if (!hasFiles(event)) return;
      event.preventDefault();
      event.dataTransfer.dropEffect = "copy";
    },
    onDragLeave: (event) => {
      if (!hasFiles(event)) return;
      depth.current = Math.max(0, depth.current - 1);
      if (depth.current === 0) setDragging(false);
    },
    onDrop: (event) => {
      if (!hasFiles(event)) return;
      event.preventDefault();
      depth.current = 0;
      setDragging(false);
      begin(event.dataTransfer.files);
    },
  };

  const overlay = dragging ? (
    <div className="imports__dropzone" aria-hidden="true">
      <div className="imports__dropzone-card">
        <Icon name="upload" size={20} />
        Drop to import. We work out what each file is.
      </div>
    </div>
  ) : null;

  const dialog = review ? (
    <ImportReviewDialog
      key={reviews.current}
      items={review}
      pack={pack}
      threatsSaved={threatsSaved}
      overlaysSaved={overlaysSaved}
      documentsSaved={Boolean(documents?.saved)}
      onCancel={() => setReview(null)}
      onImport={run}
    />
  ) : null;

  return { begin, dialog, overlay, dragProps, threatFiles };
};

export default useImports;
