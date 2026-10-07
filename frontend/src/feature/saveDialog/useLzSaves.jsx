import React, { useCallback, useRef, useState } from "react";
import { serializeLzDiagram } from "../lzWorkspace/useLzWorkspace";
import { ConfirmDialog } from "../ui/Dialog";
import { useToast } from "../ui/Toast";
import SaveDialog, { nextFreeName } from "./SaveDialog";

/*
 * Saving LZ/PZs to the Library the redesign's way (docs/MENU_REDESIGN.md §4): the first save asks for
 * a name in the Save dialog, every later save is silent and says so in a toast, and closing one with
 * unsaved changes asks Cancel / Don't save / Save. A save that fails says so in words, with a retry.
 *
 * An edit made while a save is on its way is not lost: the diagram's `updatedAt` is noted when the
 * snapshot is taken, and if it has moved by the time the server answers the diagram stays unsaved.
 */

const kindPhrase = "an LZ/PZ";

const count = (n, one, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;

/** What the Save dialog lists under "What is saved". */
export const lzContents = (diagram) => {
  const g = diagram?.graphics ?? {};
  const parts = [];
  if (g.helicopters?.length) parts.push(count(g.helicopters.length, "helicopter"));
  if (g.sectorsOfFire?.length) parts.push(count(g.sectorsOfFire.length, "sector"));
  if (g.pzMarkers?.length) parts.push(count(g.pzMarkers.length, "PZ marker"));
  if (g.goArounds?.length) parts.push(count(g.goArounds.length, "go-around"));
  if (g.units?.length) parts.push(count(g.units.length, "unit"));
  const graphics = parts.length ? `${parts.join(", ")}, doghouses and flight data` : "Doghouses and flight data";
  const grid = diagram?.target?.mgrs;
  return [
    { text: `Boundary, target${grid ? ` ${grid}` : ""} and slope analysis` },
    { text: graphics },
    { icon: "info", text: "Threats and routes are not included. Routes are saved on their own." },
  ];
};

const failureText = (name, err) =>
  err?.response?.status === 403
    ? `“${name}” was not saved: saving is not turned on for your account.`
    : `“${name}” could not be saved. Check the connection and try again.`;

/**
 * `getDiagram(id)` reads the workspace as it is now. `library`: { history, fetchHistory, saveMap,
 * updateMap } from useSavedMaps. Returns the actions and `dialogs`, the element to render.
 */
export const useLzSaves = ({ getDiagram, diagramTitle, markSaved, markDirty, removeDiagram, library, signedIn, onOpenLibrary }) => {
  const { show } = useToast();
  const [dialog, setDialog] = useState(null);
  const [savingId, setSavingId] = useState(null);
  const [savedAt, setSavedAt] = useState({});
  const lib = useRef(library);
  lib.current = library;

  const title = useCallback((diagram) => diagramTitle?.(diagram) || diagram?.name || "LZ/PZ", [diagramTitle]);

  /** Writes `diagram` as `name` to the record `savedId` (or a new one), and marks it saved. */
  const write = useCallback(
    async (diagram, name, savedId) => {
      const stamp = diagram.updatedAt;
      const snapshot = { ...serializeLzDiagram(diagram), name };
      const result = savedId != null ? await lib.current.updateMap(savedId, snapshot, name) : await lib.current.saveMap(name, snapshot);
      const id = savedId ?? result?.id;
      markSaved({ savedId: id, name }, diagram.id);
      // Edited while the save was on its way: that edit is not in what was saved.
      if (getDiagram(diagram.id)?.updatedAt !== stamp) markDirty(true, diagram.id);
      setSavedAt((prev) => ({ ...prev, [diagram.id]: result?.updated_at ?? new Date().toISOString() }));
      lib.current.fetchHistory?.();
      return id;
    },
    [getDiagram, markDirty, markSaved],
  );

  const savedToast = useCallback(
    (name) => show({ message: `Saved “${name}” to your Library`, action: onOpenLibrary ? { label: "Open Library", onClick: onOpenLibrary } : undefined }),
    [onOpenLibrary, show],
  );

  const quietSave = useCallback(
    async (diagramId) => {
      const diagram = getDiagram(diagramId);
      if (!diagram) return false;
      const name = title(diagram);
      setSavingId(diagramId);
      try {
        await write(diagram, name, diagram.savedId);
        savedToast(name);
        return true;
      } catch (err) {
        show({ tone: "error", message: failureText(name, err), action: { label: "Try again", onClick: () => quietSave(diagramId) } });
        return false;
      } finally {
        setSavingId(null);
      }
    },
    [getDiagram, savedToast, show, title, write],
  );

  /** Save: the dialog the first time, silently after that. `then` runs once it is saved. */
  const save = useCallback(
    (diagramId, { then } = {}) => {
      const diagram = getDiagram(diagramId);
      if (!diagram) return;
      if (!signedIn) {
        show({ tone: "warn", message: "Sign in to save to your Library." });
        return;
      }
      if (diagram.status !== "analyzed") {
        show({ tone: "warn", message: `Analyze ${title(diagram)} before saving it.` });
        return;
      }
      if (diagram.savedId == null) {
        setDialog({ kind: "save", diagramId, then });
        return;
      }
      if (!diagram.dirty) {
        then?.();
        return;
      }
      quietSave(diagramId).then((ok) => ok && then?.());
    },
    [getDiagram, quietSave, show, signedIn, title],
  );

  const saveAs = useCallback(
    (diagramId) => {
      const diagram = getDiagram(diagramId);
      if (!diagram) return;
      if (!signedIn) {
        show({ tone: "warn", message: "Sign in to save to your Library." });
        return;
      }
      setDialog({ kind: "saveAs", diagramId });
    },
    [getDiagram, show, signedIn],
  );

  /** Close: asks first when closing would lose work. */
  const close = useCallback(
    (diagramId) => {
      const diagram = getDiagram(diagramId);
      if (!diagram) return;
      const unsaved = diagram.savedId == null ? Boolean(diagram.target) : diagram.dirty;
      if (!unsaved) {
        removeDiagram(diagramId);
        return;
      }
      setDialog({ kind: "close", diagramId });
    },
    [getDiagram, removeDiagram],
  );

  const diagram = dialog ? getDiagram(dialog.diagramId) : null;
  let dialogs = null;
  if (dialog && !diagram) {
    // The diagram went away (closed elsewhere) while its dialog was open.
    dialogs = null;
  } else if (dialog?.kind === "save" || dialog?.kind === "saveAs") {
    const name = title(diagram);
    const existing = (library.history ?? []).map((entry) => ({ ...entry, updatedAt: entry.updated_at }));
    const copy = dialog.kind === "saveAs";
    dialogs = (
      <SaveDialog
        key={`${dialog.kind}-${dialog.diagramId}`}
        title={copy ? "Save as a new LZ/PZ" : "Save LZ/PZ"}
        subtitle={copy ? "Saves a copy under a new name. What is open becomes the copy." : "Name this LZ/PZ so you can find it in your Library."}
        initialName={copy ? nextFreeName(name, existing) : name}
        contents={lzContents(diagram)}
        existing={existing}
        kindPhrase={kindPhrase}
        onCancel={() => setDialog(null)}
        onSave={async (chosen, { replace }) => {
          await write(diagram, chosen, replace ? replace.id : null);
          setDialog(null);
          savedToast(chosen);
          dialog.then?.();
        }}
      />
    );
  } else if (dialog?.kind === "close") {
    const name = title(diagram);
    const canSave = diagram.status === "analyzed";
    dialogs = canSave ? (
      <ConfirmDialog
        title={`Save changes to ${name}?`}
        text="You have unsaved changes. If you close it now they are lost."
        icon="alertTriangle"
        iconTone="warn"
        confirmLabel="Save"
        confirmIcon="save"
        secondary={{
          label: "Don’t save",
          tone: "danger-soft",
          onClick: () => {
            setDialog(null);
            removeDiagram(diagram.id);
          },
        }}
        onCancel={() => setDialog(null)}
        onConfirm={() => {
          setDialog(null);
          save(diagram.id, { then: () => removeDiagram(diagram.id) });
        }}
      />
    ) : (
      <ConfirmDialog
        title={`Close ${name}?`}
        text="It is not analyzed, so it cannot be saved. Closing takes it out of this session."
        icon="alertTriangle"
        iconTone="warn"
        confirmLabel="Close"
        confirmTone="danger"
        onCancel={() => setDialog(null)}
        onConfirm={() => {
          setDialog(null);
          removeDiagram(diagram.id);
        }}
      />
    );
  }

  return { save, saveAs, close, savingId, savedAt, setSavedAt, dialogs };
};

export default useLzSaves;
