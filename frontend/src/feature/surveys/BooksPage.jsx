import React, { useId, useRef, useState } from "react";
import Dialog from "../ui/Dialog";
import { BusyButton, Spinner } from "../ui/Spinner";
import Icon from "../ui/Icon";

/*
 * The survey books this person reads or edits (docs/LZ_SURVEYS.md §7): a unit's or a team's, each with who it is for and what this
 * person may do in it, and New book for those who may make one (a site admin for any team or none; a team's owner or admin for
 * their team).
 */

const NO_TEAM = "";

/** Asks for a new book's name, unit line and team. */
export const NewBookDialog = ({ teams, canMakeForNoTeam, onCreate, onCancel }) => {
  const nameField = useId();
  const unitField = useId();
  const teamField = useId();
  const first = useRef(null);
  const [name, setName] = useState("");
  const [unitLine, setUnitLine] = useState("");
  const [team, setTeam] = useState(canMakeForNoTeam ? NO_TEAM : String(teams[0]?.id ?? ""));
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const submit = async (event) => {
    event?.preventDefault();
    if (!name.trim()) {
      setError("Give the book a name (AASF 1 HLZ SURVEY BOOK).");
      return;
    }
    setBusy(true);
    try {
      await onCreate({ name: name.trim().toUpperCase(), unitLine: unitLine.trim().toUpperCase(), teamId: team === NO_TEAM ? null : Number(team) });
    } catch (err) {
      setError(err?.message || "The book could not be made.");
      setBusy(false);
    }
  };
  const chosen = teams.find((t) => String(t.id) === team);
  return (
    <Dialog
      title="New survey book"
      icon="plus"
      onClose={busy ? undefined : onCancel}
      dismissible={!busy}
      initialFocus={first}
      footer={(
        <>
          <button type="button" className="ui-btn ui-btn--ghost ui-btn--38" onClick={onCancel} disabled={busy}>Cancel</button>
          <BusyButton className="ui-btn ui-btn--primary ui-btn--38" busy={busy} onClick={submit}>Make the book</BusyButton>
        </>
      )}
    >
      <form onSubmit={submit} className="survey-new">
        <div className="ui-field">
          <label htmlFor={nameField} className="ui-field__label">Name</label>
          <input ref={first} id={nameField} className={`ui-input ui-input--36${error ? " ui-input--error" : ""}`} placeholder="AASF 1 HLZ SURVEY BOOK"
            value={name} maxLength={200} style={{ textTransform: "uppercase" }} aria-invalid={Boolean(error)}
            aria-describedby={error ? `${nameField}-error` : undefined} onChange={(event) => { setName(event.target.value); setError(null); }} />
          {error && <div id={`${nameField}-error`} className="ui-field__hint ui-field__hint--error">{error}</div>}
        </div>
        <div className="ui-field">
          <label htmlFor={unitField} className="ui-field__label">Unit line</label>
          <input id={unitField} className="ui-input ui-input--36" placeholder="AASF 1" value={unitLine} maxLength={200}
            style={{ textTransform: "uppercase" }} onChange={(event) => setUnitLine(event.target.value)} />
        </div>
        <div className="ui-field">
          <label htmlFor={teamField} className="ui-field__label">For</label>
          <select id={teamField} className="ui-input ui-input--36" value={team} onChange={(event) => setTeam(event.target.value)}
            aria-describedby={`${teamField}-hint`}>
            {canMakeForNoTeam && <option value={NO_TEAM}>No team: everyone with LZ surveys</option>}
            {teams.map((t) => <option key={t.id} value={String(t.id)}>{t.name}</option>)}
          </select>
          <div id={`${teamField}-hint`} className="ui-field__hint">
            {chosen
              ? `${chosen.name}'s owner and admins make and publish its surveys; its members with LZ surveys read them.`
              : "Every account with LZ surveys reads its published pages; only site admins edit it."}
          </div>
        </div>
        <button type="submit" hidden tabIndex={-1} aria-hidden="true" />
      </form>
    </Dialog>
  );
};

const teamWords = (team) => {
  if (!team) return "No team · everyone with LZ surveys";
  if (team.gone) return "Its team was deleted";
  return team.name;
};

const BooksPage = ({ surveys, onOpen, onNew, isAdmin }) => {
  const { books } = surveys;
  const canMake = books.canMakeForNoTeam || books.teams.length > 0;
  return (
    <div className="survey-book">
      <div className="survey-book__bar">
        {canMake && (
          <button type="button" className="ui-btn ui-btn--primary ui-btn--34" onClick={onNew}>
            <Icon name="plus" size={14} />
            New book
          </button>
        )}
        {isAdmin && (
          <label className="survey-check">
            <input type="checkbox" checked={surveys.showArchivedBooks} onChange={surveys.toggleArchivedBooks} />
            Show archived
          </label>
        )}
        <button type="button" className="ui-btn ui-btn--ghost ui-btn--34" onClick={() => surveys.refreshBooks()} aria-label="Refresh the books">
          <Icon name="refresh" size={14} />
        </button>
      </div>
      {books.status === "error" && (
        <div className="survey-banner survey-banner--error" role="alert">
          <Icon name="alertTriangle" size={16} />
          <div>{books.error}</div>
          <button type="button" className="ui-btn ui-btn--30" onClick={() => surveys.refreshBooks()}>Try again</button>
        </div>
      )}
      {books.status === "loading" && books.books.length === 0 && <p className="survey-muted ui-loading" role="status"><Spinner size={16} />Loading the books…</p>}
      {books.status === "ready" && books.books.length === 0 && (
        <p className="survey-muted">{canMake ? "No survey books yet. New book makes the first." : "No survey books are open to you yet."}</p>
      )}
      {books.books.length > 0 && (
        <table className="survey-table survey-table--book">
          <thead>
            <tr>
              <th scope="col">Book</th>
              <th scope="col">For</th>
              <th scope="col">Published</th>
              <th scope="col">You</th>
              <th scope="col">Changed</th>
            </tr>
          </thead>
          <tbody>
            {books.books.map((book) => (
              <tr key={book.uuid} className={book.archived ? "survey-table__row--archived" : undefined}>
                <th scope="row">
                  <button type="button" className="survey-link" onClick={() => onOpen(book.uuid)}>{book.name}</button>
                  {book.unit_line && <div className="survey-muted">{book.unit_line}</div>}
                </th>
                <td>{teamWords(book.team)}</td>
                <td>
                  {book.published_count}
                  {book.can_edit && typeof book.survey_count === "number" && <span className="survey-muted"> of {book.survey_count}</span>}
                </td>
                <td><span className="ui-chip">{book.archived ? "Archived" : book.can_edit ? "Edit" : "Read"}</span></td>
                <td>{(book.updated_at ?? book.published_at)?.slice(0, 10) ?? "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
};

export default BooksPage;
