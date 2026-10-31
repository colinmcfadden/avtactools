import React, { useCallback, useRef, useState } from "react";
import Icon from "../ui/Icon";
import { useToast } from "../ui/Toast";
import { detectImport } from "./detectImport";
import ImportReviewDialog, { defaultDestination } from "./ImportReviewDialog";

/*
 * Every way a file comes in (the Import menu, the Imports panel, a drop anywhere on the map) goes
 * through here: each file is identified by its content, the person reviews what will happen, and
 * only then is anything imported. Threat files are remembered (name and count) so the Imports panel
 * can list them; their threats live under Threats.
 *
 * `importers`: { msnx(file), lps(file, { name }), ths(file) } that bring a file into the session and
 * return what they made. `destinations`: { library: { msnx(result, item), lps(set) }, pack: { lps(set) } }.
 * `onDone(kinds, imported)` hears which kinds came in and, for each file that did, where it went.
 */

/**
 * Moves points just imported into this session over to the open pack. The session's copy is dropped
 * only once the pack has taken them, so a pack that refuses (finished, only viewed, no usable points)
 * leaves them in the session instead of losing them.
 */
export const movePointsToPack = (set, { createItem, removePointSet }) => {
  if (!createItem(set.name, set.points)) throw new Error("the pack did not take them. They stay in this session: switch to Library to see them.");
  removePointSet(set.id);
};

export const useImports = ({ pack, importers, destinations, onDone }) => {
  const { show } = useToast();
  const [review, setReview] = useState(null);
  const [threatFiles, setThreatFiles] = useState([]);
  const [dragging, setDragging] = useState(false);
  const depth = useRef(0);
  const deps = useRef({ importers, destinations, onDone, pack });
  deps.current = { importers, destinations, onDone, pack };

  const begin = useCallback(
    async (files) => {
      const list = [...files];
      if (list.length === 0) return;
      const detected = await Promise.all(list.map((file) => detectImport(file)));
      setReview(detected.map((item) => ({ ...item, destination: item.kind ? defaultDestination(item.kind, deps.current.pack) : null })));
    },
    [],
  );

  const run = useCallback(
    async (items) => {
      const { importers: imp, destinations: dest, onDone: done } = deps.current;
      const failed = [];
      const imported = [];
      const kinds = new Set();
      for (const item of items) {
        try {
          if (item.kind === "msnx") {
            const result = await imp.msnx(item.file);
            if (item.destination === "library") dest.library.msnx(result, item);
          } else if (item.kind === "lps") {
            const set = await imp.lps(item.file, { name: item.name?.trim() || undefined });
            if (item.destination === "library") await dest.library.lps(set);
            if (item.destination === "pack") await dest.pack.lps(set);
          } else if (item.kind === "ths") {
            const count = await imp.ths(item.file);
            setThreatFiles((prev) => [...prev, { id: `${Date.now()}-${prev.length}`, fileName: item.file.name, count }]);
          }
          kinds.add(item.kind);
          imported.push({ kind: item.kind, destination: item.destination, fileName: item.file.name });
        } catch (err) {
          failed.push(`${item.file.name}: ${err?.message || "it could not be imported"}`);
        }
      }
      setReview(null);
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
    <ImportReviewDialog items={review} pack={pack} onCancel={() => setReview(null)} onImport={run} />
  ) : null;

  return { begin, dialog, overlay, dragProps, threatFiles };
};

export default useImports;
