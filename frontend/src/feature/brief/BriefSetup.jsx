import React, { useState } from "react";
import Dialog from "../ui/Dialog";
import FlowStep from "./FlowStep";
import DetailsStep from "./DetailsStep";
import ReviewStep from "./ReviewStep";
import { BRIEF_FORMATS, briefFormat } from "./templates";
import "./brief.css";

/*
 * Brief setup: three steps over the open brief (useBrief), as the design draws them. Everything the person answers is
 * kept with the brief as they go (a pack's item, or their Library), so closing and generating again starts from their
 * answers. The format (air movement or hasty) is chosen here and can be changed: answers are kept by item, so going
 * back finds them again.
 */

const STEPS = [
  { id: "flow", title: "Mission flow and constants" },
  { id: "details", title: "Brief details" },
  { id: "review", title: "Review" },
];

const SAVE_WORDS = { saving: "Saving…", saved: "Saved to your Library", unsaved: "Saving…", error: "Could not save: your answers are kept here and tried again with your next change" };

const BriefSetup = ({ brief, title, threatCount, onClose }) => {
  const [step, setStep] = useState(0);
  const { model, doc, setDoc, format } = brief;
  const close = () => {
    brief.flush();
    onClose();
  };
  const chooseFormat = (id) => {
    const chosen = briefFormat(id);
    if (!chosen || id === format.template.id) return;
    setDoc((d) => ({ ...d, template: { id, version: chosen.template.version } }));
  };
  const blanks = model.steps.reduce((n, st) => n + st.items.filter((i) => i.writeIn).length, 0);
  const filled = model.steps.reduce((n, st) => n + st.items.filter((i) => !i.writeIn && i.kind !== "info").length, 0);

  const note = brief.inPack
    ? `Kept with ${title} for everyone in it.${brief.readOnlyPack ? " The pack is read-only, but its brief stays open to change." : ""}`
    : SAVE_WORDS[brief.saveState] ?? "Kept with this brief in your Library. Generating again starts from these answers.";

  return (
    <Dialog
      title={`Brief setup · ${title}`}
      subtitle={`${format.template.title} · ${format.template.source.replace(/, Part \d+$/, "")}`}
      icon="file"
      onClose={close}
      className="brief-setup"
      note={note}
      footer={(
        <>
          {step > 0 && <button type="button" className="ui-btn ui-btn--38" onClick={() => setStep(step - 1)}>Back</button>}
          <span className="brief-setup__count">{filled} filled · {blanks} print as write-in lines</span>
          {step < STEPS.length - 1 ? (
            <button type="button" className="ui-btn ui-btn--primary ui-btn--38" onClick={() => setStep(step + 1)}>
              Next: {STEPS[step + 1].title.toLowerCase()}
            </button>
          ) : (
            <button type="button" className="ui-btn ui-btn--primary ui-btn--38" disabled title="The PDF is not available yet">Download PDF</button>
          )}
        </>
      )}
    >
      <div className="brief-setup__bar">
        <ol className="brief-steps" aria-label="Steps">
          {STEPS.map((s, i) => (
            <li key={s.id}>
              <button type="button" className="brief-steps__step" aria-current={i === step ? "step" : undefined} onClick={() => setStep(i)}>
                <span className="brief-steps__n">{i + 1}</span>
                {s.title}
              </button>
            </li>
          ))}
        </ol>
        <div role="radiogroup" aria-label="Format" className="brief-seg">
          {BRIEF_FORMATS.map((f) => (
            <button key={f.id} type="button" role="radio" aria-checked={format.template.id === f.id} className="brief-seg__opt" onClick={() => chooseFormat(f.id)}>
              {f.title}
            </button>
          ))}
        </div>
      </div>
      <div className="brief-setup__body">
        {step === 0 && <FlowStep model={model} doc={doc} setDoc={setDoc} catalog={format.constants} threatCount={threatCount} inPack={brief.inPack} />}
        {step === 1 && <DetailsStep model={model} format={format} doc={doc} setDoc={setDoc} plan={brief.plan} />}
        {step === 2 && <ReviewStep model={model} doc={doc} setDoc={setDoc} coverTitle={brief.inPack ? model.mission.name : "MISSION BRIEF"} />}
      </div>
    </Dialog>
  );
};

export default BriefSetup;
