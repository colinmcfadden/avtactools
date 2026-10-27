import React, { useId, useRef, useState } from "react";
import Dialog from "../ui/Dialog";
import Icon from "../ui/Icon";
import { STATUS_WORDS } from "./surveyFields";
import { ImportCell, importStateWords } from "./SurveyImport";
import { saveBlob } from "./PagePreview";

/*
 * One survey book (docs/LZ_SURVEYS.md): for its editors every LZ in page order with its status, how many of its field values disagree
 * with what EZ/PZ measured and nobody has settled, and when it was last surveyed, and New survey; for a reader, the published pages,
 * each with its revision and who signed it off.
 */

const dateOf = (iso) => (iso ? iso.slice(0, 10) : "—");

/** Asks for a new survey's HLZ ID and name. */
export const NewSurveyDialog = ({ onCreate, onCancel }) => {
  const idField = useId();
  const nameField = useId();
  const first = useRef(null);
  const [hlzId, setHlzId] = useState("");
  const [name, setName] = useState("");
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const submit = async (event) => {
    event?.preventDefault();
    if (!name.trim()) {
      setError("Give the LZ its name, as the book has it (LZ DONOVAN).");
      return;
    }
    setBusy(true);
    try {
      await onCreate({ hlzId: hlzId.trim().toUpperCase(), name: name.trim().toUpperCase() });
    } catch (err) {
      setError(err?.message || "The survey could not be made.");
      setBusy(false);
    }
  };
  return (
    <Dialog
      title="New LZ survey"
      icon="plus"
      onClose={busy ? undefined : onCancel}
      dismissible={!busy}
      initialFocus={first}
      footer={(
        <>
          <button type="button" className="ui-btn ui-btn--ghost ui-btn--38" onClick={onCancel} disabled={busy}>Cancel</button>
          <button type="button" className="ui-btn ui-btn--primary ui-btn--38" onClick={submit} disabled={busy}>Make the survey</button>
        </>
      )}
    >
      <form onSubmit={submit} className="survey-new">
        <div className="ui-field">
          <label htmlFor={idField} className="ui-field__label">HLZ ID</label>
          <input ref={first} id={idField} className="ui-input ui-input--36" placeholder="GA 034" value={hlzId} maxLength={40}
            style={{ textTransform: "uppercase" }} onChange={(event) => setHlzId(event.target.value)} />
        </div>
        <div className="ui-field">
          <label htmlFor={nameField} className="ui-field__label">Name</label>
          <input id={nameField} className={`ui-input ui-input--36${error ? " ui-input--error" : ""}`} placeholder="LZ DONOVAN" value={name}
            maxLength={100} style={{ textTransform: "uppercase" }} aria-invalid={Boolean(error)} aria-describedby={`${nameField}-hint`}
            onChange={(event) => { setName(event.target.value); setError(null); }} />
          <div id={`${nameField}-hint`} className={`ui-field__hint${error ? " ui-field__hint--error" : ""}`}>
            {error ?? "A draft: only the book's editors see it until it is published."}
          </div>
        </div>
        <button type="submit" hidden tabIndex={-1} aria-hidden="true" />
      </form>
    </Dialog>
  );
};

const signoffWords = (signoff) => (signoff ? [signoff.rank, signoff.name].filter(Boolean).join(" ") : "—");

const teamWords = (team) => (!team ? "No team · everyone with LZ surveys reads it" : team.gone ? "Its team was deleted" : `For ${team.name}`);

/** A reader's book: the published pages only. */
const ReaderTable = ({ surveys, onOpen }) => (
  <table className="survey-table survey-table--book">
    <thead>
      <tr>
        <th scope="col">Page</th>
        <th scope="col">HLZ ID</th>
        <th scope="col">Name</th>
        <th scope="col">Revision</th>
        <th scope="col">Published</th>
        <th scope="col">Signed off</th>
      </tr>
    </thead>
    <tbody>
      {surveys.map((row, index) => (
        <tr key={row.uuid}>
          <td>{index + 1}</td>
          <td>{row.hlz_id || "—"}</td>
          <th scope="row"><button type="button" className="survey-link" onClick={() => onOpen(row.uuid)}>{row.name}</button></th>
          <td>REV {row.published_revision}</td>
          <td>{dateOf(row.published_at)}</td>
          <td>{signoffWords(row.signoff)}</td>
        </tr>
      ))}
    </tbody>
  </table>
);

const sizeWords = (bytes) => (bytes >= 1048576 ? `${(bytes / 1048576).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`);

const pagesWords = (n) => `${n} ${n === 1 ? "page" : "pages"}`;

/**
 * The whole book's PDF (docs/LZ_SURVEYS.md §5, step L6), for its readers and editors alike: its cover with a map of every LZ, its
 * index, and every published page. It is printed on the server, in the background, and kept until a page in it changes.
 */
export const BookPdf = ({ book, state, onPrint, onDownload }) => {
  const headingId = useId();
  const [busy, setBusy] = useState(null);
  const [error, setError] = useState(null);
  const print = state?.print;
  if (!book || !print) return null;
  const run = async (what, action) => {
    setBusy(what);
    setError(null);
    try {
      await action();
    } catch (err) {
      setError(err?.message || "That did not work. Try again.");
    } finally {
      setBusy(null);
    }
  };
  const download = () => run("download", async () => saveBlob(await onDownload(), `${book.name}.pdf`));
  const askToPrint = () => run("print", onPrint);
  let body;
  if (print.state === "empty") {
    body = <p className="survey-muted">It holds the book's published pages, and nothing is published yet.</p>;
  } else if (print.state === "ready") {
    body = (
      <div className="survey-page-bar">
        <button type="button" className="ui-btn ui-btn--primary ui-btn--34" onClick={download} disabled={Boolean(busy)}>
          <Icon name="download" size={14} />
          {busy === "download" ? "Fetching…" : "Download the book"}
        </button>
        <span className="survey-muted">
          {pagesWords(print.pages)} with its cover and index · {sizeWords(print.size_bytes ?? 0)} · printed {print.printed_at?.slice(0, 10)}
        </span>
      </div>
    );
  } else if (!print.available) {
    body = <p className="survey-muted">The book's PDF is not printed on this server yet. Each page's preview shows it.</p>;
  } else if (print.state === "printing") {
    body = (
      <p className="survey-muted" role="status">
        Printing the book: {print.done_pages ? `page ${print.done_pages} of ${print.pages} drawn` : `${pagesWords(print.pages)} to draw`}…
      </p>
    );
  } else {
    body = (
      <>
        {print.state === "failed" && print.error && <p className="survey-state survey-state--warn" role="status">{print.error}</p>}
        <div className="survey-page-bar">
          <button type="button" className="ui-btn ui-btn--34" onClick={askToPrint} disabled={Boolean(busy)}>
            <Icon name="download" size={14} />
            {busy === "print" ? "Asking…" : print.state === "failed" ? "Try again" : `Print the book (${pagesWords(print.pages)})`}
          </button>
          <span className="survey-muted">Printed on the server, a page at a time: a minute or two for a full book.</span>
        </div>
      </>
    );
  }
  return (
    <section className="survey-panel survey-book__pdf" aria-labelledby={headingId}>
      <h3 id={headingId} className="survey-panel__title">The book's PDF</h3>
      {body}
      {error && <p className="survey-state survey-state--warn" role="status">{error}</p>}
    </section>
  );
};

const SurveyBookPage = ({ surveys, onOpen, onNew, onImport }) => {
  const { list, showArchived } = surveys;
  const editing = Boolean(list.book?.can_edit);
  return (
    <div className="survey-book">
      {list.book && (
        <p className="survey-muted survey-book__about">
          {teamWords(list.book.team)}{list.book.unit_line ? ` · ${list.book.unit_line}` : ""}
          {editing ? " · you edit it" : " · published pages"}
        </p>
      )}
      <div className="survey-book__bar">
        {editing && (
          <button type="button" className="ui-btn ui-btn--primary ui-btn--34" onClick={onNew}>
            <Icon name="plus" size={14} />
            New survey
          </button>
        )}
        {editing && !list.book?.archived && onImport && (
          <button type="button" className="ui-btn ui-btn--34" onClick={onImport}>
            <Icon name="download" size={14} />
            Import the old book
          </button>
        )}
        {editing && (
          <label className="survey-check">
            <input type="checkbox" checked={showArchived} onChange={surveys.toggleArchived} />
            Show archived
          </label>
        )}
        <button type="button" className="ui-btn ui-btn--ghost ui-btn--34" onClick={() => surveys.refresh()} aria-label="Refresh the list">
          <Icon name="refresh" size={14} />
        </button>
      </div>
      <BookPdf book={list.book} state={surveys.bookPrint} onPrint={surveys.printBook} onDownload={surveys.loadBookPdf} />
      {list.status === "error" && (
        <div className="survey-banner survey-banner--error" role="alert">
          <Icon name="alertTriangle" size={16} />
          <div>{list.error}</div>
          <button type="button" className="ui-btn ui-btn--30" onClick={() => surveys.refresh()}>Try again</button>
        </div>
      )}
      {list.status === "loading" && list.surveys.length === 0 && <p className="survey-muted" role="status">Loading the book…</p>}
      {list.status === "ready" && list.surveys.length === 0 && (
        <p className="survey-muted">{editing ? "No surveys yet. New survey makes the first page of the book." : "Nothing is published in this book yet."}</p>
      )}
      {list.surveys.length > 0 && !editing && <ReaderTable surveys={list.surveys} onOpen={onOpen} />}
      {list.surveys.length > 0 && editing && (
        <table className="survey-table survey-table--book">
          <thead>
            <tr>
              <th scope="col">Page</th>
              <th scope="col">HLZ ID</th>
              <th scope="col">Name</th>
              <th scope="col">Status</th>
              <th scope="col">To look at</th>
              <th scope="col">Surveyed</th>
              <th scope="col">Measured</th>
              <th scope="col">Changed</th>
            </tr>
          </thead>
          <tbody>
            {list.surveys.map((row, index) => (
              <tr key={row.uuid} className={row.archived ? "survey-table__row--archived" : undefined}>
                <td>{row.archived ? "—" : index + 1}</td>
                <td>{row.hlz_id || "—"}</td>
                <th scope="row">
                  <button type="button" className="survey-link" onClick={() => onOpen(row.uuid)}>{row.name}</button>
                  {row.grid && <div className="survey-muted">{row.grid}</div>}
                </th>
                <td>
                  <span className="ui-chip">{row.archived ? "Archived" : STATUS_WORDS[row.status] ?? row.status}</span>
                  {row.published_revision && row.status !== "published" && <div className="survey-muted">REV {row.published_revision} published</div>}
                </td>
                <td>
                  {/* Never measured is nothing compared yet, not nothing to look at. An import being measured says how it stands. */}
                  {!row.measured_at ? (
                    importStateWords(row.imported) ? null : <span className="survey-muted">Not measured</span>
                  ) : row.disagreements ? (
                    <span className="survey-state survey-state--warn">{row.disagreements} {row.disagreements === 1 ? "disagreement" : "disagreements"}</span>
                  ) : (
                    !row.imported?.to_check && <span className="survey-muted">None</span>
                  )}
                  <ImportCell imported={row.imported} />
                </td>
                <td>{row.surveyed_on || "—"}</td>
                <td>{dateOf(row.measured_at)}</td>
                <td>
                  {dateOf(row.updated_at)}
                  {row.updated_by && <div className="survey-muted">{row.updated_by}</div>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
};

/** A book's own details: its name, unit line and (for a site admin) the team it is for. */
export const BookSettingsDialog = ({ book, teams, canChooseTeam, onSave, onCancel }) => {
  const nameField = useId();
  const unitField = useId();
  const teamField = useId();
  const first = useRef(null);
  const [name, setName] = useState(book.name);
  const [unitLine, setUnitLine] = useState(book.unit_line ?? "");
  const [team, setTeam] = useState(book.team ? String(book.team.id) : "");
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const submit = async (event) => {
    event?.preventDefault();
    if (!name.trim()) {
      setError("A book needs a name.");
      return;
    }
    const patch = {};
    if (name.trim().toUpperCase() !== book.name) patch.name = name.trim().toUpperCase();
    if (unitLine.trim().toUpperCase() !== (book.unit_line ?? "")) patch.unit_line = unitLine.trim().toUpperCase();
    const teamId = team === "" ? null : Number(team);
    if (canChooseTeam && teamId !== (book.team?.id ?? null)) patch.team_id = teamId;
    if (!Object.keys(patch).length) {
      onCancel();
      return;
    }
    setBusy(true);
    try {
      await onSave(patch);
    } catch (err) {
      setError(err?.message || "The book could not be changed.");
      setBusy(false);
    }
  };
  const teamOptions = book.team && !teams.some((t) => t.id === book.team.id)
    ? [...teams, { id: book.team.id, name: book.team.name ?? "Its deleted team" }] : teams;
  return (
    <Dialog
      title="The book"
      icon="pencil"
      onClose={busy ? undefined : onCancel}
      dismissible={!busy}
      initialFocus={first}
      footer={(
        <>
          <button type="button" className="ui-btn ui-btn--ghost ui-btn--38" onClick={onCancel} disabled={busy}>Cancel</button>
          <button type="button" className="ui-btn ui-btn--primary ui-btn--38" onClick={submit} disabled={busy}>Save</button>
        </>
      )}
    >
      <form onSubmit={submit} className="survey-new">
        <div className="ui-field">
          <label htmlFor={nameField} className="ui-field__label">Name</label>
          <input ref={first} id={nameField} className="ui-input ui-input--36" value={name} maxLength={200} style={{ textTransform: "uppercase" }}
            onChange={(event) => { setName(event.target.value); setError(null); }} />
        </div>
        <div className="ui-field">
          <label htmlFor={unitField} className="ui-field__label">Unit line</label>
          <input id={unitField} className="ui-input ui-input--36" value={unitLine} maxLength={200} style={{ textTransform: "uppercase" }}
            onChange={(event) => setUnitLine(event.target.value)} />
        </div>
        {canChooseTeam && (
          <div className="ui-field">
            <label htmlFor={teamField} className="ui-field__label">For</label>
            <select id={teamField} className="ui-input ui-input--36" value={team} onChange={(event) => setTeam(event.target.value)}
              aria-describedby={`${teamField}-hint`}>
              <option value="">No team: everyone with LZ surveys</option>
              {teamOptions.map((t) => <option key={t.id} value={String(t.id)}>{t.name}</option>)}
            </select>
            <div id={`${teamField}-hint`} className="ui-field__hint">Changes who reads it, and who besides site admins edits it.</div>
          </div>
        )}
        {error && <div className="survey-banner survey-banner--error" role="alert"><Icon name="alertTriangle" size={16} /><div>{error}</div></div>}
        <button type="submit" hidden tabIndex={-1} aria-hidden="true" />
      </form>
    </Dialog>
  );
};

export default SurveyBookPage;
