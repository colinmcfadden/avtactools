import React, { useId, useRef, useState } from "react";
import Dialog from "../ui/Dialog";
import { BusyButton } from "../ui/Spinner";
import Icon from "../ui/Icon";

/*
 * Review and publishing (docs/LZ_SURVEYS.md §3.4): a draft goes for review, and a survey under review is signed off and published as
 * the next revision, REV n, with the approver's name, rank, title and date (the approver need not have an account) and a CHANGES line,
 * suggested from what is printed and edited here. Any change to it afterwards makes it a draft again; readers keep the last REV.
 */

const MONTHS = ["JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"];

/** 27 OCT 2026, as the page prints a date. */
export const todayWords = (now = new Date()) => `${String(now.getDate()).padStart(2, "0")} ${MONTHS[now.getMonth()]} ${now.getFullYear()}`;

const approverWords = (signoff) =>
  signoff ? [[signoff.rank, signoff.name].filter(Boolean).join(" "), signoff.title].filter(Boolean).join(", ") + (signoff.date ? ` · ${signoff.date}` : "") : "—";

export const PublishDialog = ({ survey, onPublish, onCancel }) => {
  const ids = { rank: useId(), name: useId(), title: useId(), date: useId(), changes: useId() };
  const first = useRef(null);
  const [values, setValues] = useState({ rank: "", name: "", title: "", date: todayWords(), changes: survey.suggested_changes ?? "" });
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const set = (key) => (event) => {
    setValues((current) => ({ ...current, [key]: event.target.value }));
    setError(null);
  };
  const submit = async (event) => {
    event?.preventDefault();
    if (!values.name.trim() || !values.date.trim()) {
      setError("Say who signed it off and when: the approver's name and the date.");
      return;
    }
    setBusy(true);
    try {
      await onPublish({
        signoff: { name: values.name.trim(), rank: values.rank.trim().toUpperCase(), title: values.title.trim(), date: values.date.trim().toUpperCase() },
        changes: values.changes.trim(),
      });
    } catch (err) {
      setError(err?.message || "The survey could not be published.");
      setBusy(false);
    }
  };
  return (
    <Dialog
      title={`Publish ${survey.name} as REV ${survey.next_revision}`}
      icon="send"
      onClose={busy ? undefined : onCancel}
      dismissible={!busy}
      initialFocus={first}
      footer={(
        <>
          <button type="button" className="ui-btn ui-btn--ghost ui-btn--38" onClick={onCancel} disabled={busy}>Cancel</button>
          <BusyButton className="ui-btn ui-btn--primary ui-btn--38" busy={busy} onClick={submit}>
            {busy ? "Publishing…" : `Publish REV ${survey.next_revision}`}
          </BusyButton>
        </>
      )}
    >
      <form onSubmit={submit} className="survey-new">
        <p className="survey-muted">
          The sign-off is printed on the page and kept with this revision. Readers see this page until the next is published.
        </p>
        <div className="survey-signoff survey-signoff--who">
          <div className="ui-field">
            <label htmlFor={ids.rank} className="ui-field__label">Rank</label>
            <input id={ids.rank} className="ui-input ui-input--36" placeholder="CW4" maxLength={20} value={values.rank} onChange={set("rank")}
              style={{ textTransform: "uppercase" }} />
          </div>
          <div className="ui-field">
            <label htmlFor={ids.name} className="ui-field__label">Approver's name</label>
            <input ref={first} id={ids.name} className="ui-input ui-input--36" placeholder="J. Doe" maxLength={120} value={values.name} onChange={set("name")} />
          </div>
        </div>
        <div className="survey-signoff survey-signoff--what">
          <div className="ui-field">
            <label htmlFor={ids.title} className="ui-field__label">Title</label>
            <input id={ids.title} className="ui-input ui-input--36" placeholder="Standardization IP" maxLength={120} value={values.title} onChange={set("title")} />
          </div>
          <div className="ui-field">
            <label htmlFor={ids.date} className="ui-field__label">Signed off on</label>
            <input id={ids.date} className="ui-input ui-input--36" maxLength={40} value={values.date} onChange={set("date")}
              style={{ textTransform: "uppercase" }} />
          </div>
        </div>
        <div className="ui-field">
          <label htmlFor={ids.changes} className="ui-field__label">CHANGES line</label>
          <textarea id={ids.changes} className="ui-input survey-textarea" rows={2} maxLength={500} value={values.changes} onChange={set("changes")}
            aria-describedby={`${ids.changes}-hint`} />
          <div id={`${ids.changes}-hint`} className="ui-field__hint">Suggested from what changed on the page; printed at its foot.</div>
        </div>
        {error && <div className="survey-banner survey-banner--error" role="alert"><Icon name="alertTriangle" size={16} /><div>{error}</div></div>}
        <button type="submit" hidden tabIndex={-1} aria-hidden="true" />
      </form>
    </Dialog>
  );
};

/** Where the survey stands, and the step to take from there. */
export const ReviewPanel = ({ survey, dirty, busy, onSubmit, onBackToDraft, onAskPublish }) => {
  const headingId = useId();
  const measured = Boolean(survey.snapshot);
  const archived = Boolean(survey.archived);
  const stale = survey.stale ?? [];
  const remeasure = stale.length ? `Measure the LZ again first. ${stale.join(" ")}` : null;
  const why = archived ? "Restore the survey first." : dirty ? null : !measured ? "Measure the LZ first." : remeasure;
  const published = survey.published_revision;
  return (
    <section className="survey-panel" aria-labelledby={headingId}>
      <h3 id={headingId} className="survey-panel__title">Review and publishing</h3>
      {survey.status === "draft" && (
        <>
          <p>
            A draft: only the book's editors see it.
            {published ? ` Readers see REV ${published} until the next is published.` : " Nothing is published yet."}
          </p>
          <div className="survey-page-bar">
            <BusyButton className="ui-btn ui-btn--primary ui-btn--34" icon="send" onClick={onSubmit} busy={busy === "submitting"}
              disabled={Boolean(why) || Boolean(busy)} aria-describedby={why ? `${headingId}-why` : undefined}>
              {busy === "submitting" ? "Sending…" : dirty ? "Save and send for review" : "Send for review"}
            </BusyButton>
          </div>
          {why && <p id={`${headingId}-why`} className="survey-muted">{why}</p>}
        </>
      )}
      {survey.status === "in_review" && (
        <>
          <p>Under review: this page, as it stands, is the one to be signed off. A change makes it a draft again.</p>
          <div className="survey-page-bar">
            <button type="button" className="ui-btn ui-btn--primary ui-btn--34" onClick={onAskPublish}
              disabled={Boolean(busy) || dirty || archived || Boolean(remeasure)}>
              <Icon name="check" size={14} />
              Publish…
            </button>
            <button type="button" className="ui-btn ui-btn--ghost ui-btn--34" onClick={onBackToDraft} disabled={Boolean(busy) || archived}>
              Back to draft
            </button>
          </div>
          {dirty && <p className="survey-muted">Save or undo your changes first: they would make it a draft again.</p>}
          {!dirty && remeasure && <p className="survey-muted">{remeasure}</p>}
        </>
      )}
      {survey.status === "published" && (
        <p>
          Published as REV {published}: readers see this page. Any change, a photo's included, makes it a draft again, and REV {published}{" "}
          stays theirs until the next.
        </p>
      )}
    </section>
  );
};

/** Every published revision: when and by whom, who signed it off, its CHANGES line, and its page. */
export const Revisions = ({ revisions, onShow, onPdf, showing }) => {
  const headingId = useId();
  if (!revisions?.length) return null;
  return (
    <section className="survey-panel" aria-labelledby={headingId}>
      <h3 id={headingId} className="survey-panel__title">Revisions</h3>
      <table className="survey-table">
        <thead>
          <tr><th scope="col">REV</th><th scope="col">Published</th><th scope="col">Signed off</th><th scope="col">Changes</th><th scope="col"><span className="ui-visually-hidden">Page</span></th></tr>
        </thead>
        <tbody>
          {[...revisions].reverse().map((rev) => (
            <tr key={rev.number}>
              <th scope="row">{rev.number}</th>
              <td>{rev.published_at ? rev.published_at.slice(0, 10) : "—"}<div className="survey-muted">{rev.published_by}</div></td>
              <td>{approverWords(rev.signoff)}</td>
              <td>{rev.changes || "—"}</td>
              <td className="survey-table__actions">
                {onShow && (
                  <button type="button" className="ui-btn ui-btn--ghost ui-btn--30" onClick={() => onShow(rev.number)} aria-pressed={showing === rev.number}>
                    <Icon name="eye" size={13} />
                    Page
                  </button>
                )}
                <button type="button" className="ui-btn ui-btn--ghost ui-btn--30" onClick={() => onPdf(rev.number)} aria-label={`REV ${rev.number} PDF`}>
                  <Icon name="download" size={13} />
                  PDF
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
};
