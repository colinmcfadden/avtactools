import React, { useCallback, useId, useState } from "react";
import Icon from "../ui/Icon";
import { useToast } from "../ui/Toast";
import { FIELD_SECTIONS, STATUS_WORDS, fullField } from "./surveyFields";
import { boundaryOf, withBoundary, withTarget } from "./surveyDiagram";
import SurveyMap from "./SurveyMap";
import Measurements, { MeasurePreview } from "./Measurements";
import Disagreements from "./Disagreements";
import SurveyPhotos from "./SurveyPhotos";
import PagePreview, { saveBlob } from "./PagePreview";
import { PublishDialog, ReviewPanel, Revisions } from "./Publishing";
import { ImportNotes } from "./SurveyImport";

/*
 * One LZ's survey, for the book's editors (docs/LZ_SURVEYS.md): the field survey's values in the old book's sections, the map in survey
 * mode (target, boundary, best approach and exit, the slope map and contours), what EZ/PZ measured, field against computed, the
 * photos, review and publishing, the printed page and every revision.
 */

const Field = ({ spec, value, onChange, readOnly }) => {
  const id = useId();
  const common = {
    id,
    className: `ui-input${spec.multiline ? " survey-textarea" : " ui-input--36"}`,
    value,
    placeholder: spec.placeholder,
    readOnly,
    onChange: (event) => onChange(spec.key, event.target.value),
    "aria-describedby": spec.hint ? `${id}-hint` : undefined,
  };
  return (
    <div className={`ui-field survey-field${spec.short ? " survey-field--short" : ""}${spec.multiline ? " survey-field--wide" : ""}`}>
      <label htmlFor={id} className="ui-field__label">{spec.label}</label>
      {spec.multiline ? <textarea rows={3} {...common} /> : <input type="text" autoComplete="off" {...common} />}
      {spec.hint && <div id={`${id}-hint`} className="ui-field__hint">{spec.hint}</div>}
    </div>
  );
};

export const FieldForm = ({ field, onChange, readOnly = false }) => (
  <div className="survey-form">
    {FIELD_SECTIONS.map((section) => (
      <fieldset key={section.id} className="survey-section">
        <legend className="survey-section__title">{section.title}</legend>
        <div className="survey-section__fields">
          {section.fields.map((spec) => (
            <Field key={spec.key} spec={spec} value={field[spec.key] ?? ""} onChange={onChange} readOnly={readOnly} />
          ))}
        </div>
      </fieldset>
    ))}
  </div>
);

const whyNotMeasured = (diagram, survey) => {
  if (survey?.archived) return "Restore the survey to measure it.";
  if (!diagram?.target) return "Set the target on the map first.";
  if (!boundaryOf(diagram)) return "Find or draw the boundary to measure its size and slope (the target alone can be measured).";
  return null;
};

const pdfName = (survey, rev) =>
  `${[survey.hlz_id, survey.name, rev ? `REV ${rev}` : (survey.status === "in_review" ? "IN REVIEW" : "DRAFT")].filter(Boolean).join(" ")}.pdf`;

const SurveyEditor = ({ surveys }) => {
  const { show } = useToast();
  const { survey, draft, busy, loadPage, loadPdf } = surveys;
  const [publishing, setPublishing] = useState(false);
  const [showingRev, setShowingRev] = useState(null);
  const uuid = survey?.uuid;
  const loadNow = useCallback(() => loadPage(uuid), [loadPage, uuid]);
  const loadRev = useCallback(() => loadPage(uuid, { rev: showingRev }), [loadPage, uuid, showingRev]);
  if (!survey || !draft) return null;
  const archived = Boolean(survey.archived);
  const why = whyNotMeasured(draft.diagram, survey);
  // The comparisons are the server's, for the field as saved.
  const fieldDirty = JSON.stringify(draft.field) !== JSON.stringify(fullField(survey.field));

  const setTarget = (lat, lon) => surveys.setDiagram((diagram) => withTarget(diagram, lat, lon, { id: `survey-${survey.uuid}`, name: draft.name }));
  const setBoundary = (points) => surveys.setDiagram((diagram) => withBoundary(diagram, points));

  const apply = async () => {
    const kept = await surveys.applyMeasure();
    if (kept) show({ message: "Measurements kept with the survey." });
  };

  return (
    <div className="survey-editor">
      <div className="survey-editor__ident">
        <div className="ui-field survey-field survey-field--short">
          <label htmlFor="survey-hlz" className="ui-field__label">HLZ ID</label>
          <input id="survey-hlz" className="ui-input ui-input--36" value={draft.hlz_id} readOnly={archived} onChange={(event) => surveys.setHlzId(event.target.value)} />
        </div>
        <div className="ui-field survey-field">
          <label htmlFor="survey-name" className="ui-field__label">Name</label>
          <input id="survey-name" className="ui-input ui-input--36" value={draft.name} readOnly={archived} onChange={(event) => surveys.setName(event.target.value)} />
        </div>
        <span className="ui-chip">{STATUS_WORDS[survey.status] ?? survey.status}</span>
        <span className="survey-muted">Revision {survey.revision} · {survey.updated_by}</span>
      </div>

      {surveys.conflict && (
        <div className="survey-banner survey-banner--warn" role="alert">
          <Icon name="alertTriangle" size={16} />
          <div>
            <strong>{surveys.conflict.updated_by || "Another admin"} saved this survey while you were editing</strong> (revision {surveys.conflict.revision}
            {surveys.conflictChanges.length ? `: ${surveys.conflictChanges.join(", ")}` : ""}). What you typed is still here.
          </div>
          <button type="button" className="ui-btn ui-btn--30" onClick={surveys.keepMine}>Save mine over theirs</button>
          <button type="button" className="ui-btn ui-btn--30 ui-btn--ghost" onClick={surveys.takeTheirs}>Load theirs</button>
        </div>
      )}
      {surveys.error && (
        <div className="survey-banner survey-banner--error" role="alert">
          <Icon name="alertTriangle" size={16} />
          <div>{surveys.error}</div>
          <button type="button" className="ui-btn ui-btn--30 ui-btn--ghost" onClick={surveys.clearError}>Dismiss</button>
        </div>
      )}
      {archived && (
        <div className="survey-banner" role="status">
          <Icon name="lock" size={16} />
          <div>Archived: kept, but not in the book. Restore it to change it.</div>
        </div>
      )}

      <div className="survey-editor__columns">
        <div className="survey-editor__left">
          <ImportNotes imported={survey.imported} busy={busy} onChecked={surveys.markImportChecked} />
          <FieldForm field={draft.field} onChange={surveys.setField} readOnly={archived} />
        </div>
        <div className="survey-editor__right">
          <SurveyMap
            diagram={draft.diagram}
            field={draft.field}
            terrain={survey.snapshot?.terrain ?? null}
            obstacles={survey.snapshot?.obstacles ?? null}
            onSetTarget={setTarget}
            onSetBoundary={setBoundary}
            readOnly={archived}
          />
          <Measurements
            snapshot={survey.snapshot}
            stale={survey.stale ?? []}
            busy={busy}
            canMeasure={!archived && Boolean(draft.diagram?.target)}
            whyNot={why}
            onMeasure={surveys.measure}
            approach={(survey.comparisons ?? []).find((row) => row.key === "approach") ?? null}
            onDecide={archived ? undefined : surveys.decide}
            onUndecide={archived ? undefined : surveys.undecide}
            deciding={Boolean(busy) || archived}
          />
          <Disagreements
            rows={survey.comparisons ?? []}
            dirtyField={fieldDirty}
            busy={Boolean(busy) || archived}
            onDecide={surveys.decide}
            onUndecide={surveys.undecide}
          />
          <SurveyPhotos
            assets={survey.assets ?? []}
            defaultTakenOn={draft.field.surveyedOn}
            onUpload={surveys.uploadPhoto}
            onUpdate={surveys.updatePhoto}
            onRemove={surveys.removePhoto}
          />
          <ReviewPanel
            survey={survey}
            dirty={surveys.dirty}
            busy={busy}
            onSubmit={() => surveys.setReview("review")}
            onBackToDraft={() => surveys.setReview("draft")}
            onAskPublish={() => setPublishing(true)}
          />
          <PagePreview
            heading="The page as saved"
            title={`${survey.name}, the page as saved`}
            note="It prints the computed value wherever the survey and EZ/PZ both have one, unless a decision above says otherwise."
            load={loadNow}
            loadPdf={() => loadPdf(uuid)}
            fileName={pdfName(survey)}
            stale={surveys.dirty}
          />
          <Revisions
            revisions={survey.revisions}
            showing={showingRev}
            onShow={(number) => setShowingRev((current) => (current === number ? null : number))}
            onPdf={async (number) => {
              try {
                saveBlob(await loadPdf(uuid, { rev: number }), pdfName(survey, number));
              } catch (err) {
                show({ message: err?.message || "The PDF could not be made.", tone: "error" });
              }
            }}
          />
          {showingRev && (
            <PagePreview
              key={showingRev}
              heading={`REV ${showingRev}, as signed off`}
              title={`${survey.name}, REV ${showingRev}`}
              load={loadRev}
              loadPdf={() => loadPdf(uuid, { rev: showingRev })}
              fileName={pdfName(survey, showingRev)}
              autoLoad
            />
          )}
        </div>
      </div>
      {surveys.preview && (
        <MeasurePreview preview={surveys.preview} busy={busy === "applying"} onApply={apply} onCancel={surveys.dismissPreview} />
      )}
      {publishing && (
        <PublishDialog
          survey={survey}
          onCancel={() => setPublishing(false)}
          onPublish={async (values) => {
            const revision = await surveys.publish(values);
            setPublishing(false);
            if (revision) show({ message: `${survey.name} published as REV ${revision.number}.` });
          }}
        />
      )}
    </div>
  );
};

export default SurveyEditor;
