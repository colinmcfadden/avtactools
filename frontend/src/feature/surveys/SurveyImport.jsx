import React, { useEffect, useId, useRef, useState } from "react";
import Dialog from "../ui/Dialog";
import { BusyButton } from "../ui/Spinner";
import Icon from "../ui/Icon";

/*
 * The old book imported (docs/LZ_SURVEYS.md §6): the bundle tools/import_survey_book.py writes from its PDF is sent to the book, each
 * page becomes a draft with its photos, and each draft is then measured on the server in the background, one at a time (its boundary
 * found by the model at its grid, then its snapshot). What the import could not read on a page, or found to check (a grid and lat/long
 * that disagree), stays with the survey until an editor marks it checked.
 */

/** Measuring states the server is still working through. */
export const IMPORT_ACTIVE = ["queued", "finding", "measuring"];

const STATE_WORDS = {
  queued: "Waiting to be measured",
  finding: "Finding its boundary…",
  measuring: "Being measured…",
  no_target: "No target: set it on the map",
  no_boundary: "No boundary found: draw it",
  failed: "Could not be measured",
  changed: "Changed while it was measured: measure it",
};

/** How an imported survey's measuring stands, in words; null once it is measured (or for a survey not imported). */
export const importStateWords = (imported) => (imported && imported.state !== "done" ? STATE_WORDS[imported.state] ?? null : null);

const noteCount = (n) => `${n} import ${n === 1 ? "note" : "notes"} to check`;

/** The book's cell for an imported survey: how its measuring stands, and how many of its notes are left to check. */
export const ImportCell = ({ imported }) => {
  if (!imported) return null;
  const words = importStateWords(imported);
  const active = IMPORT_ACTIVE.includes(imported.state);
  return (
    <>
      {words && <div className={active ? "survey-muted" : "survey-state survey-state--warn"}>{words}</div>}
      {imported.to_check > 0 && <div className="survey-state survey-state--warn">{noteCount(imported.to_check)}</div>}
    </>
  );
};

const COMMAND = 'python3 tools/import_survey_book.py "LZ-SURVEYS.pdf" book-bundle.zip';

/** Sends the old book's bundle to the open book, and says what it made. */
export const ImportDialog = ({ bookName, onImport, onCancel }) => {
  const fileField = useId();
  const first = useRef(null);
  const done = useRef(null);
  const [file, setFile] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState(null);

  const submit = async (event) => {
    event?.preventDefault();
    if (!file) {
      setError("Choose the bundle the importer wrote.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      setResult(await onImport(file));
    } catch (err) {
      setError(err?.message || "The bundle could not be imported.");
    } finally {
      setBusy(false);
    }
  };

  if (result) {
    const made = result.created?.length ?? 0;
    return (
      // A dialog of its own (its key), so the focus goes to Done as it opens: the Import button it replaces had it.
      <Dialog
        key="imported"
        title="The old book is imported"
        icon="download"
        onClose={onCancel}
        initialFocus={done}
        footer={<button ref={done} type="button" className="ui-btn ui-btn--primary ui-btn--38" onClick={onCancel}>Done</button>}
      >
        <div className="survey-import" role="status">
          <p>
            <strong>{made} {made === 1 ? "page" : "pages"}</strong> of {result.source || "the bundle"} {made === 1 ? "is a draft" : "are drafts"} in{" "}
            {bookName}, each with its photos.
            {made > 0 && " They are measured one at a time in the background: the book's list says how each stands."}
          </p>
          {result.skipped?.length > 0 && (
            <>
              <p>Left as they are:</p>
              <ul className="survey-problems">
                {result.skipped.map((page) => (
                  <li key={`${page.page}-${page.hlz_id}`}>Page {page.page}, {[page.hlz_id, page.name].filter(Boolean).join(" ")}: {page.reason}</li>
                ))}
              </ul>
            </>
          )}
        </div>
      </Dialog>
    );
  }

  return (
    <Dialog
      title="Import the old book"
      icon="download"
      onClose={busy ? undefined : onCancel}
      dismissible={!busy}
      initialFocus={first}
      footer={(
        <>
          <button type="button" className="ui-btn ui-btn--ghost ui-btn--38" onClick={onCancel} disabled={busy}>Cancel</button>
          <BusyButton className="ui-btn ui-btn--primary ui-btn--38" busy={busy} onClick={submit}>
            {busy ? "Importing…" : "Import"}
          </BusyButton>
        </>
      )}
    >
      <form onSubmit={submit} className="survey-import">
        <p>
          Read the old book&apos;s PDF into a bundle on a computer with poppler (<code>brew install poppler</code>), from the EZ/PZ
          repository:
        </p>
        <pre className="survey-import__command">{COMMAND}</pre>
        <p className="survey-muted">
          Each page becomes a draft with its two photos. A page whose HLZ ID is in the book already is left as it is. What a page does not
          say, or says in a way to check, stays with its survey until an editor marks it checked.
        </p>
        <div className="ui-field">
          <label htmlFor={fileField} className="ui-field__label">Bundle</label>
          <input ref={first} id={fileField} type="file" accept=".zip,application/zip" className="ui-input ui-input--36"
            aria-invalid={Boolean(error)} aria-describedby={error ? `${fileField}-error` : undefined}
            onChange={(event) => { setFile(event.target.files?.[0] ?? null); setError(null); }} />
        </div>
        {error && (
          <div id={`${fileField}-error`} className="survey-banner survey-banner--error" role="alert">
            <Icon name="alertTriangle" size={16} />
            <div>{error}</div>
          </div>
        )}
        <button type="submit" hidden tabIndex={-1} aria-hidden="true" />
      </form>
    </Dialog>
  );
};

const listWords = (words) => words.join(", ");

/** An imported survey's notes from the old book: where it came from, how its measuring stands, and what to check. */
export const ImportNotes = ({ imported, busy, onChecked }) => {
  const headingId = useId();
  const checkedLine = useRef(null);
  const asked = useRef(false);
  // The button goes once the notes are checked: the focus goes to the line that says so, not to the page's foot.
  useEffect(() => {
    if (imported?.checked && asked.current) {
      asked.current = false;
      checkedLine.current?.focus();
    }
  }, [imported?.checked]);
  if (!imported) return null;
  const notes = imported.notes ?? {};
  const state = importStateWords(imported);
  const any = (notes.unread?.length ?? 0) + (notes.blank?.length ?? 0) + (notes.warnings?.length ?? 0) > 0;
  return (
    <section className="survey-panel" aria-labelledby={headingId}>
      <h3 id={headingId} className="survey-panel__title">From the old book</h3>
      <p className="survey-muted">
        Page {imported.page} of {imported.source || "the old book"}, imported {imported.imported_at?.slice(0, 10)} by {imported.imported_by}.
      </p>
      {state && (
        <p className={IMPORT_ACTIVE.includes(imported.state) ? "survey-muted" : "survey-state survey-state--warn"} role="status">
          {state}{imported.reason ? `. ${imported.reason}` : ""}
        </p>
      )}
      {any ? (
        <ul className="survey-problems">
          {notes.unread?.length > 0 && <li>Not on the page: {listWords(notes.unread)}.</li>}
          {notes.blank?.length > 0 && <li>Blank on the page: {listWords(notes.blank)}.</li>}
          {(notes.warnings ?? []).map((warning, index) => <li key={`${index}-${warning}`}>{warning}</li>)}
        </ul>
      ) : (
        <p className="survey-muted">The import read every value on the page.</p>
      )}
      {imported.checked ? (
        <p ref={checkedLine} tabIndex={-1} className="survey-muted">Checked by {imported.checked_by} on {imported.checked_at?.slice(0, 10)}.</p>
      ) : (
        any && (
          <div className="survey-page-bar">
            <button type="button" className="ui-btn ui-btn--34" onClick={() => { asked.current = true; onChecked(); }} disabled={Boolean(busy)}>
              <Icon name="check" size={14} />
              Mark as checked
            </button>
          </div>
        )
      )}
    </section>
  );
};
