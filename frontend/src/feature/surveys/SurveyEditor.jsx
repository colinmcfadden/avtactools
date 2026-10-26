import React, { useId } from "react";
import Icon from "../ui/Icon";
import { useToast } from "../ui/Toast";
import { FIELD_SECTIONS, STATUS_WORDS, fullField } from "./surveyFields";
import { boundaryOf, withBoundary, withTarget } from "./surveyDiagram";
import SurveyMap from "./SurveyMap";
import Measurements, { MeasurePreview } from "./Measurements";
import Disagreements from "./Disagreements";
import SurveyPhotos from "./SurveyPhotos";

/*
 * One LZ's survey (docs/LZ_SURVEYS.md, step L1): the field survey's values in the old book's sections, the map in survey mode
 * (target, boundary, best approach and exit, the slope map and contours), what EZ/PZ measured, field against computed, and the
 * photos. The page's PDF and its preview, sign-off and publishing come with step L5.
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

const SurveyEditor = ({ surveys }) => {
  const { show } = useToast();
  const { survey, draft, busy } = surveys;
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
          <FieldForm field={draft.field} onChange={surveys.setField} readOnly={archived} />
        </div>
        <div className="survey-editor__right">
          <SurveyMap
            diagram={draft.diagram}
            field={draft.field}
            terrain={survey.snapshot?.terrain ?? null}
            onSetTarget={setTarget}
            onSetBoundary={setBoundary}
            readOnly={archived}
          />
          <Measurements
            snapshot={survey.snapshot}
            field={survey.field}
            busy={busy}
            canMeasure={!archived && Boolean(draft.diagram?.target)}
            whyNot={why}
            onMeasure={surveys.measure}
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
          <section className="survey-panel survey-panel--placeholder" aria-labelledby="survey-page-preview">
            <h3 id="survey-page-preview" className="survey-panel__title">Page preview</h3>
            <p className="survey-muted">
              The one-page PDF, its preview, sign-off and publishing are not built yet. The page will print the computed value
              wherever the survey and EZ/PZ both have one, unless a decision above says otherwise.
            </p>
          </section>
        </div>
      </div>
      {surveys.preview && (
        <MeasurePreview preview={surveys.preview} busy={busy === "applying"} onApply={apply} onCancel={surveys.dismissPreview} />
      )}
    </div>
  );
};

export default SurveyEditor;
