import React, { useId, useRef, useState } from "react";
import Dialog from "../ui/Dialog";
import Icon from "../ui/Icon";
import { STATUS_WORDS } from "./surveyFields";

/*
 * The HLZ survey book (docs/LZ_SURVEYS.md, step L1): every LZ in page order with its status, how many of its field values disagree
 * with what EZ/PZ measured and nobody has settled, and when it was last surveyed; and New survey.
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
            {error ?? "A draft: only admins see it until it is published."}
          </div>
        </div>
        <button type="submit" hidden tabIndex={-1} aria-hidden="true" />
      </form>
    </Dialog>
  );
};

const SurveyBookPage = ({ surveys, onOpen, onNew }) => {
  const { list, showArchived } = surveys;
  return (
    <div className="survey-book">
      <div className="survey-book__bar">
        <button type="button" className="ui-btn ui-btn--primary ui-btn--34" onClick={onNew}>
          <Icon name="plus" size={14} />
          New survey
        </button>
        <label className="survey-check">
          <input type="checkbox" checked={showArchived} onChange={surveys.toggleArchived} />
          Show archived
        </label>
        <button type="button" className="ui-btn ui-btn--ghost ui-btn--34" onClick={() => surveys.refresh()} aria-label="Refresh the list">
          <Icon name="refresh" size={14} />
        </button>
      </div>
      {list.status === "error" && (
        <div className="survey-banner survey-banner--error" role="alert">
          <Icon name="alertTriangle" size={16} />
          <div>{list.error}</div>
          <button type="button" className="ui-btn ui-btn--30" onClick={() => surveys.refresh()}>Try again</button>
        </div>
      )}
      {list.status === "loading" && list.surveys.length === 0 && <p className="survey-muted" role="status">Loading the book…</p>}
      {list.status === "ready" && list.surveys.length === 0 && (
        <p className="survey-muted">No surveys yet. New survey makes the first page of the book.</p>
      )}
      {list.surveys.length > 0 && (
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
                </td>
                <td>
                  {/* Never measured is nothing compared yet, not nothing to look at. */}
                  {!row.measured_at ? (
                    <span className="survey-muted">Not measured</span>
                  ) : row.disagreements ? (
                    <span className="survey-state survey-state--warn">{row.disagreements} {row.disagreements === 1 ? "disagreement" : "disagreements"}</span>
                  ) : (
                    <span className="survey-muted">None</span>
                  )}
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

export default SurveyBookPage;
