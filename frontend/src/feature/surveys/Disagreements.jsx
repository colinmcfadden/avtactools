import React, { useId, useRef, useState } from "react";
import Dialog from "../ui/Dialog";

/*
 * Field against computed (docs/LZ_SURVEYS.md §2): each measured quantity with the survey's value and EZ/PZ's, how far apart, and
 * which the page prints. The computed value is printed unless an admin overrides it with the field's, with a reason; accepting a
 * disagreement keeps the computed value and takes it off the count. A choice made on other values (the field retyped, the LZ
 * measured again) no longer settles it. The rows are the server's (survey/compare.py), for the survey as saved.
 */

const describe = (row) => {
  if (!row.field && !row.computed) return { tone: "muted", text: "Neither has it" };
  if (!row.computed) return { tone: "muted", text: "Not measured: the survey's is printed" };
  if (!row.field) return { tone: "muted", text: "Measured only" };
  if (row.override && !row.overrideStands) return { tone: "warn", text: "Decided on other values: look again" };
  if (row.overrideStands && row.override.choice === "field") return { tone: "info", text: "Survey's value printed" };
  if (row.overrideStands) return { tone: "ok", text: "Accepted: computed printed" };
  if (row.disagrees) return { tone: "warn", text: "Disagree" };
  return { tone: "ok", text: "Agree" };
};

/** Asks why the survey's value should be printed over what EZ/PZ measured. A reason is required. */
export const ReasonDialog = ({ row, onConfirm, onCancel }) => {
  const id = useId();
  const input = useRef(null);
  const [reason, setReason] = useState("");
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const submit = async () => {
    if (!reason.trim()) {
      setError("Say why: it is kept with the survey for whoever reviews it.");
      input.current?.focus();
      return;
    }
    setBusy(true);
    const ok = await onConfirm(reason.trim());
    if (!ok) setBusy(false);
  };
  return (
    <Dialog
      title={`Print the survey's ${row.label.toLowerCase()}`}
      subtitle={`${row.field} in place of ${row.computed}`}
      icon="pencil"
      onClose={busy ? undefined : onCancel}
      dismissible={!busy}
      initialFocus={input}
      footer={(
        <>
          <button type="button" className="ui-btn ui-btn--ghost ui-btn--38" onClick={onCancel} disabled={busy}>Cancel</button>
          <button type="button" className="ui-btn ui-btn--primary ui-btn--38" onClick={submit} disabled={busy}>Print the survey's</button>
        </>
      )}
    >
      <div className="ui-field">
        <label htmlFor={id} className="ui-field__label">Reason</label>
        <textarea
          ref={input}
          id={id}
          className={`ui-input survey-textarea${error ? " ui-input--error" : ""}`}
          rows={3}
          maxLength={500}
          value={reason}
          aria-invalid={Boolean(error)}
          aria-describedby={`${id}-hint`}
          onChange={(event) => {
            setReason(event.target.value);
            setError(null);
          }}
        />
        <div id={`${id}-hint`} className={`ui-field__hint${error ? " ui-field__hint--error" : ""}`}>
          {error ?? "Kept with the survey: who chose it, when, and the two values it was chosen on."}
        </div>
      </div>
    </Dialog>
  );
};

const Disagreements = ({ rows = [], onDecide, onUndecide, dirtyField = false, busy = false }) => {
  const [asking, setAsking] = useState(null);
  const open = rows.filter((row) => row.open).length;
  return (
    <section className="survey-panel" aria-labelledby="survey-disagreements">
      <div className="survey-panel__head">
        <h3 id="survey-disagreements" className="survey-panel__title">Field against computed</h3>
        <span className={`ui-chip${open ? " survey-chip--warn" : ""}`}>{open ? `${open} to look at` : "Nothing open"}</span>
      </div>
      {dirtyField && <p className="survey-muted">Save to compare what you have typed.</p>}
      <table className="survey-table">
        <thead>
          <tr>
            <th scope="col">Value</th>
            <th scope="col">Survey</th>
            <th scope="col">Computed</th>
            <th scope="col">Apart</th>
            <th scope="col">Printed</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const state = describe(row);
            return (
              <tr key={row.key} className={row.open ? "survey-table__row--open" : undefined}>
                <th scope="row">{row.label}</th>
                <td>{row.field || <span className="survey-muted">—</span>}</td>
                <td>{row.computed || <span className="survey-muted">—</span>}</td>
                <td>{row.apart || <span className="survey-muted">—</span>}</td>
                <td>
                  <div className={`survey-state survey-state--${state.tone}`}>{state.text}</div>
                  {row.override?.reason && <div className="survey-muted">“{row.override.reason}” · {row.override.by}</div>}
                  <div className="survey-actions">
                    {row.open && (
                      <>
                        <button type="button" className="ui-btn ui-btn--28" disabled={busy} onClick={() => onDecide(row.key, "computed", "")}>
                          Accept computed
                        </button>
                        <button type="button" className="ui-btn ui-btn--28" disabled={busy} onClick={() => setAsking(row)}>
                          Print survey's…
                        </button>
                      </>
                    )}
                    {row.override && (
                      <button type="button" className="ui-btn ui-btn--28 ui-btn--ghost" disabled={busy} onClick={() => onUndecide(row.key)}>
                        Undo decision
                      </button>
                    )}
                  </div>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      {asking && (
        <ReasonDialog
          row={asking}
          onCancel={() => setAsking(null)}
          onConfirm={async (reason) => {
            const saved = await onDecide(asking.key, "field", reason);
            if (saved) setAsking(null);
            return Boolean(saved);
          }}
        />
      )}
    </section>
  );
};

export default Disagreements;
