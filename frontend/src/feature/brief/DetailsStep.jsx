import React, { useId, useMemo, useState } from "react";
import DraftField from "./DraftField";
import { partsText, WRITE_IN } from "./compile";

/*
 * Setup, step 2: the brief's details, a paragraph at a time (the design's "Brief setup 2"). Each item shows what the
 * plan gives it, and anything left blank prints as a line to write on. A contingency is Standard (the handbook's
 * procedure, with the person's own values where it has them), Custom (their words) or Blank. Beside it, the script
 * of the step being filled, which the person may rewrite; their words are kept until they reset it.
 */

const setAnswer = (setDoc, key, value) =>
  setDoc((d) => {
    const answers = { ...(d.answers || {}) };
    if (value === null || value === undefined) delete answers[key];
    else answers[key] = value;
    return { ...d, answers };
  });

/** A standard procedure's summary with its values and the person's fields in place, a blank field a line. */
const summaryOf = (procedure, standards, fields = {}) =>
  procedure.summary.replace(/\{([^{}]*)\}/g, (_, name) => {
    const [kind, key] = name.split(/:(.*)/s);
    if (kind === "value") return String(standards.values[key]?.value ?? WRITE_IN);
    if (kind === "field") return String(fields[key] ?? "").trim() || WRITE_IN;
    return "…";
  });

const statusOf = (paragraph, steps) => {
  const ids = new Set(paragraph.sections.map((s) => s.id));
  const items = new Map();
  steps.filter((st) => st.sections.some((id) => ids.has(id))).forEach((st) => st.items.forEach((item) => items.set(item.key, item)));
  const all = [...items.values()];
  if (all.length === 0 || all.every((item) => item.kind === "info")) return { tone: "none", text: "Nothing to fill" };
  const blank = all.filter((item) => item.writeIn).length;
  return blank === 0 ? { tone: "done", text: "All set" } : { tone: "add", text: `${blank} to add` };
};

const ProcedureItem = ({ item, def, answer, standards, onChange }) => {
  const procedure = def.standard ? standards.procedures[def.standard] : null;
  const mode = answer?.mode ?? "blank";
  const fields = answer?.fields || {};
  const set = (patch) => onChange({ ...(answer || {}), ...patch });
  return (
    <div className="brief-procedure">
      <div className="brief-procedure__head">
        <span className="brief-procedure__name">{def.label}</span>
        <div role="radiogroup" aria-label={def.label} className="brief-seg">
          {procedure && (
            <button type="button" role="radio" aria-checked={mode === "standard"} className="brief-seg__opt" onClick={() => set({ mode: "standard" })}>Standard</button>
          )}
          <button type="button" role="radio" aria-checked={mode === "custom"} className="brief-seg__opt" onClick={() => set({ mode: "custom" })}>Custom</button>
          <button type="button" role="radio" aria-checked={mode === "blank"} className="brief-seg__opt" onClick={() => onChange(answer?.fields || answer?.value ? { ...answer, mode: "blank" } : null)}>Blank</button>
        </div>
      </div>
      {mode === "standard" && procedure && (
        <>
          <p className="brief-hint">{summaryOf(procedure, standards, fields)}</p>
          {(procedure.fields || []).length > 0 && (
            <div className="brief-procedure__fields">
              {procedure.fields.map((f) => (
                <label key={f.id} className="brief-field">
                  <span className="brief-field__label">{f.label}</span>
                  <DraftField value={fields[f.id] ?? ""} placeholder="Write-in" onCommit={(value) => set({ fields: { ...fields, [f.id]: value } })} />
                </label>
              ))}
            </div>
          )}
        </>
      )}
      {mode === "custom" && (
        <DraftField multiline rows={3} aria-label={`${def.label}, in your words`} className="ui-input brief-textarea" value={answer?.value ?? ""} placeholder="In your words" onCommit={(value) => set({ value })} />
      )}
      {mode === "blank" && <p className="brief-hint">Prints as a write-in line.</p>}
      {item.writeIn && mode !== "blank" && <p className="brief-hint brief-hint--warn">Not filled yet: prints as a write-in line.</p>}
    </div>
  );
};

const AnswerItem = ({ item, def, answer, setDoc, standards }) => {
  const id = useId();
  const key = item.key;
  const fromPlan = item.source === "plan" ? item.text : null;
  if (def.kind === "info") return <p className="brief-hint">{def.label}: nothing to fill.</p>;
  if (def.kind === "procedure") {
    return <ProcedureItem item={item} def={def} answer={answer} standards={standards} onChange={(value) => setAnswer(setDoc, key, value)} />;
  }
  const commitText = (value) => setAnswer(setDoc, key, value.trim() ? { value } : null);
  let control;
  if (def.kind === "choice") {
    control = (
      <select id={id} aria-describedby={`${id}-hint`} className="ui-input" value={answer?.value ?? ""} onChange={(e) => setAnswer(setDoc, key, e.target.value ? { value: e.target.value } : null)}>
        <option value="">Write-in</option>
        {def.options.map((o) => <option key={o} value={o}>{o}</option>)}
      </select>
    );
  } else if (def.kind === "list") {
    control = (
      <DraftField
        id={id}
        aria-describedby={`${id}-hint`}
        multiline
        rows={4}
        className="ui-input brief-textarea"
        value={Array.isArray(answer?.value) ? answer.value.join("\n") : ""}
        placeholder={fromPlan ? fromPlan.split("; ").join("\n") : "One a line"}
        onCommit={(value) => {
          const lines = value.split("\n").map((l) => l.trim()).filter(Boolean);
          setAnswer(setDoc, key, lines.length ? { value: lines } : null);
        }}
      />
    );
  } else {
    control = (
      <DraftField
        id={id}
        aria-describedby={`${id}-hint`}
        multiline={def.kind === "note"}
        rows={def.kind === "note" ? 3 : undefined}
        className={def.kind === "note" ? "ui-input brief-textarea" : "ui-input"}
        value={answer?.value ?? ""}
        placeholder={fromPlan ?? (def.kind === "time" ? "HH:MM" : "Write-in")}
        onCommit={commitText}
      />
    );
  }
  return (
    <div className="brief-field">
      <label className="brief-field__label" htmlFor={id}>{def.label}</label>
      {control}
      <span id={`${id}-hint`} className={`brief-hint${item.writeIn ? "" : " brief-hint--plan"}`}>
        {answer?.value ? "Typed" : fromPlan ? "From the plan: type over it to change what is briefed" : "Prints as a write-in line"}
      </span>
    </div>
  );
};

const RallyPoints = ({ plan, doc, setDoc }) => {
  const chosen = Array.isArray(doc.rallyPoints) ? doc.rallyPoints : [];
  const routes = plan.routeSets.flatMap((set) =>
    (set.data?.routes || []).filter((r) => r && r.id !== undefined).map((r) => ({ ref: `route:${set.id}/${r.id}`, name: r.name, points: (r.points || []).filter((p) => p.kind !== "shaping" && p.id) })));
  const labelOf = (ref, pointId) => chosen.find((c) => c.ref === ref && c.pointId === pointId)?.label ?? "";
  const set = (ref, pointId, label) =>
    setDoc((d) => {
      const rest = (Array.isArray(d.rallyPoints) ? d.rallyPoints : []).filter((c) => !(c.ref === ref && c.pointId === pointId));
      return { ...d, rallyPoints: label ? [...rest, { ref, pointId, label }] : rest };
    });
  if (routes.length === 0) return null;
  return (
    <section className="brief-card" aria-label="Rally points">
      <h3 className="brief-h3">Rally points</h3>
      <p className="brief-hint">Said with the loss of visual contact, and marked on its figure.</p>
      {routes.map((route) => (
        <div key={route.ref} className="brief-rally">
          <span className="brief-rally__route">{route.name || "ROUTE"}</span>
          {route.points.map((p) => (
            <label key={p.id} className="brief-rally__point">
              <span>{String(p.name || "").replace(/^\./, "") || "Point"}</span>
              <select className="ui-input" value={labelOf(route.ref, p.id)} onChange={(e) => set(route.ref, p.id, e.target.value)}>
                <option value="">Not a rally point</option>
                <option value="inbound">Inbound</option>
                <option value="outbound">Outbound</option>
              </select>
            </label>
          ))}
        </div>
      ))}
    </section>
  );
};

const ScriptPanel = ({ step, doc, setDoc }) => {
  const [editing, setEditing] = useState(false);
  if (!step) return null;
  const edited = typeof doc.scripts?.[step.id] === "string";
  const setScript = (text) =>
    setDoc((d) => {
      const scripts = { ...(d.scripts || {}) };
      if (text === null || !text.trim()) delete scripts[step.id];
      else scripts[step.id] = text;
      return { ...d, scripts };
    });
  return (
    <aside className="brief-script" aria-label={`Script for ${step.number}`}>
      <div className="brief-card__head">
        <h3 className="brief-h3">Script · {step.number}</h3>
        <button type="button" className="ui-btn ui-btn--30" onClick={() => setEditing((e) => !e)}>{editing ? "Done" : "Edit"}</button>
      </div>
      <p className="brief-hint">Written from the fields. It prints under the figure and shows on the sandtable. Your edits are kept until you reset it.</p>
      {editing ? (
        <DraftField
          multiline
          rows={12}
          aria-label="The script"
          className="ui-input brief-textarea"
          value={edited ? doc.scripts[step.id] : step.script.map(partsText).join("\n\n")}
          onCommit={(text) => setScript(text)}
        />
      ) : (
        <div className="brief-script__text">
          <p className="brief-script__who">{step.who}:</p>
          {step.script.map((sentence, i) => (
            // eslint-disable-next-line react/no-array-index-key
            <p key={i}>
              {sentence.map((part, j) => (part.writeIn !== undefined
                // eslint-disable-next-line react/no-array-index-key
                ? <span key={j} className="brief-writein" title={part.label ?? undefined}>{part.label ?? "________"}</span>
                // eslint-disable-next-line react/no-array-index-key
                : <React.Fragment key={j}>{part.text}</React.Fragment>))}
            </p>
          ))}
        </div>
      )}
      {edited && (
        <button type="button" className="brief-link" onClick={() => { setScript(null); setEditing(false); }}>Reset script from fields</button>
      )}
    </aside>
  );
};

const DetailsStep = ({ model, format, doc, setDoc, plan }) => {
  const { template, standards } = format;
  const [current, setCurrent] = useState(template.paragraphs[0]?.number);
  const [stepId, setStepId] = useState(null);
  const paragraph = template.paragraphs.find((p) => p.number === current) ?? template.paragraphs[0];
  const answers = doc.answers || {};
  const statuses = useMemo(() => template.paragraphs.map((p) => statusOf(p, model.steps)), [template, model.steps]);

  const ids = new Set(paragraph.sections.map((s) => s.id));
  const steps = model.steps.filter((st) => st.sections.some((id) => ids.has(id)));
  const shownStep = steps.find((st) => st.id === stepId) ?? steps[0] ?? null;
  const procedures = paragraph.sections.flatMap((s) => s.items.filter((i) => i.kind === "procedure" && i.standard).map((i) => ({ section: s, item: i })));
  const hasRally = paragraph.sections.some((s) => (s.facts || []).includes("rally.points"));

  const useStandardForAll = () =>
    setDoc((d) => {
      const next = { ...(d.answers || {}) };
      steps.forEach((st) => st.items.forEach((item) => {
        const hit = procedures.find(({ section, item: def }) => item.id === `${section.id}.${def.id}`);
        if (hit) next[item.key] = { ...(next[item.key] || {}), mode: "standard" };
      }));
      return { ...d, answers: next };
    });

  return (
    <div className="brief-details">
      <nav className="brief-details__nav" aria-label="Brief paragraphs">
        {template.paragraphs.map((p, i) => (
          <button
            key={p.number}
            type="button"
            aria-current={p.number === paragraph.number ? "true" : undefined}
            className="brief-details__para"
            onClick={() => { setCurrent(p.number); setStepId(null); }}
          >
            <span className="brief-details__n">{p.number}</span>
            <span className="brief-details__name">
              <span>{p.title}</span>
              <span className={`brief-status brief-status--${statuses[i].tone}`}>{statuses[i].text}</span>
            </span>
          </button>
        ))}
      </nav>

      <div className="brief-details__main">
        <div className="brief-section-head">
          <h2 className="brief-h2">{paragraph.number}. {paragraph.title}</h2>
          <span className="brief-hint">Briefed by {paragraph.who ?? template.defaultWho}</span>
          {procedures.length > 0 && <button type="button" className="brief-link" onClick={useStandardForAll}>Use standard for all</button>}
        </div>
        {steps.length === 0 && (
          <p className="brief-empty">Nothing in the mission flow is briefed here. Give an LZ/PZ this role in step 1 to brief it.</p>
        )}
        {steps.map((st) => (
          <section key={st.id} className={`brief-instance${shownStep?.id === st.id ? " brief-instance--shown" : ""}`} onFocus={() => setStepId(st.id)}>
            <h3 className="brief-h3">
              <button type="button" className="brief-instance__title" onClick={() => setStepId(st.id)}>{st.number} {st.title}</button>
            </h3>
            {st.items.map((item) => {
              const section = paragraph.sections.find((sec) => item.id.startsWith(`${sec.id}.`));
              const def = section?.items.find((d) => `${section.id}.${d.id}` === item.id) ?? null;
              if (!def) return null;
              return <AnswerItem key={item.key} item={item} def={def} answer={answers[item.key]} setDoc={setDoc} standards={standards} />;
            })}
          </section>
        ))}
        {hasRally && <RallyPoints plan={plan} doc={doc} setDoc={setDoc} />}
      </div>

      <ScriptPanel step={shownStep} doc={doc} setDoc={setDoc} />
    </div>
  );
};

export default DetailsStep;
