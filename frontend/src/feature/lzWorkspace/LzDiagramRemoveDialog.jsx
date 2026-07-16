import React from "react";
import "./LzDiagramRemoveDialog.css";

export default function LzDiagramRemoveDialog({
  diagram,
  isSaving = false,
  onCancel,
  onDiscard,
  onSaveFirst,
}) {
  if (!diagram) return null;

  const isSavedDiagram = diagram.savedId != null;
  const title = diagram.name || diagram.target?.mgrs || "Active LZ/PZ";

  return (
    <div className="lz-remove-dialog__backdrop" role="presentation">
      <section
        className="lz-remove-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="lz-remove-dialog-title"
      >
        <header className="lz-remove-dialog__header">
          <span className="lz-remove-dialog__icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" focusable="false">
              <path d="M12 3 2.5 20h19L12 3Z" />
              <path d="M12 9v5M12 17.2v.1" />
            </svg>
          </span>
          <div>
            <p>UNSAVED LZ / PZ</p>
            <h2 id="lz-remove-dialog-title">Save before removing?</h2>
          </div>
        </header>

        <div className="lz-remove-dialog__body">
          <p>
            <strong>{title}</strong> {isSavedDiagram
              ? "has changes that have not been saved."
              : "has not been saved yet."}
          </p>
          <p>
            Removing it only clears this diagram from the current session. It
            does not delete any saved LZ/PZ from the database.
          </p>
        </div>

        <footer className="lz-remove-dialog__actions">
          <button type="button" onClick={onCancel} disabled={isSaving}>
            CANCEL
          </button>
          <button
            type="button"
            className="lz-remove-dialog__discard"
            onClick={onDiscard}
            disabled={isSaving}
          >
            REMOVE WITHOUT SAVING
          </button>
          <button
            type="button"
            className="lz-remove-dialog__save"
            onClick={onSaveFirst}
            disabled={isSaving}
          >
            {isSaving ? "SAVING…" : isSavedDiagram ? "SAVE & REMOVE" : "SAVE FIRST"}
          </button>
        </footer>
      </section>
    </div>
  );
}
