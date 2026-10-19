import React, { useState } from "react";
import { printBrief } from "./briefApi";
import Dialog from "../ui/Dialog";
import Icon from "../ui/Icon";
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

const BriefSetup = ({ brief, title, threatCount, onClose, onPresent }) => {
  const [step, setStep] = useState(0);
  const [printing, setPrinting] = useState(null);
  const { model, doc, setDoc, format } = brief;
  const download = async () => {
    setPrinting({ busy: true });
    try {
      const pdf = await printBrief(model);
      const url = URL.createObjectURL(pdf);
      const link = document.createElement("a");
      link.href = url;
      link.download = `${(brief.inPack ? model.mission.name : "Mission").replace(/[^A-Za-z0-9 _-]+/g, "").trim() || "Mission"} brief.pdf`;
      document.body.appendChild(link);
      link.click();
      link.remove();
      setTimeout(() => URL.revokeObjectURL(url), 30000);
      setPrinting(null);
    } catch (error) {
      setPrinting({ error: error.message });
    }
  };
  const close = () => {
    brief.flush();
    onClose();
  };
  // The sandtable walks the same steps on the map; the setup closes for it.
  const present = () => {
    brief.flush();
    onPresent();
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
  const footNote = printing?.error ?? (printing?.busy ? "Drawing the figures and printing the pages: this takes a few seconds." : note);

  return (
    <Dialog
      title={`Brief setup · ${title}`}
      subtitle={`${format.template.title} · ${format.template.source.replace(/, Part \d+$/, "")}`}
      icon="file"
      onClose={close}
      className="brief-setup"
      note={footNote}
      footer={(
        <>
          {step > 0 && <button type="button" className="ui-btn ui-btn--38" onClick={() => setStep(step - 1)}>Back</button>}
          <span className="brief-setup__count">{filled} filled · {blanks} print as write-in lines</span>
          {step < STEPS.length - 1 ? (
            <button type="button" className="ui-btn ui-btn--primary ui-btn--38" onClick={() => setStep(step + 1)}>
              Next: {STEPS[step + 1].title.toLowerCase()}
            </button>
          ) : (
            <>
              {onPresent && (
                <button type="button" className="ui-btn ui-btn--38" onClick={present}>
                  <Icon name="play" size={14} />
                  Present sandtable
                </button>
              )}
              <button type="button" className="ui-btn ui-btn--primary ui-btn--38" disabled={Boolean(printing?.busy)} onClick={download}>
                <Icon name="download" size={14} />
                {printing?.busy ? "Printing…" : "Download PDF"}
              </button>
            </>
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
