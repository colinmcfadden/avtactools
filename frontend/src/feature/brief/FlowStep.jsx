import React, { useState } from "react";
import Icon from "../ui/Icon";
import DraftField from "./DraftField";
import { ROLES, ROLE_NAMES } from "./templates";

/*
 * Setup, step 1: the mission flow and the constants (the design's "Brief setup 1"). The flow is the order the flight meets
 * each route and LZ/PZ, and each LZ/PZ's role decides where the format briefs it. Until the person moves or re-roles
 * something it follows the routes (compileBrief's default), and "Reset to route order" goes back to that.
 */

const SOURCE_WORDS = {
  typed: "Typed",
  plan: "From the plan",
  standard: "Standard",
  blank: "Prints as a write-in line",
};

const ROLE_LABELS = { PZ: "PZ", LZ: "LZ", FARP: "FARP", HA: "HA", LAAGER: "LAAGER", FALSE_LZ: "FALSE LZ" };

/** The flow as the document keeps it: each entry's reference, and a stop's role. */
const flowOf = (model) => model.flow.map((f) => (f.kind === "stop" ? { ref: f.ref, role: f.role } : { ref: f.ref }));

const constantsOf = (doc, catalog) =>
  (Array.isArray(doc.constants) ? doc.constants : catalog.filter((c) => c.offered).map((c) => ({ id: c.id })));

const FlowStep = ({ model, doc, setDoc, catalog, threatCount, inPack }) => {
  const [custom, setCustom] = useState(null);
  const followsRoutes = !Array.isArray(doc.flow) || doc.flow.length === 0;

  const setFlow = (next) => setDoc((d) => ({ ...d, flow: next }));
  const move = (index, by) => {
    const next = flowOf(model);
    const to = index + by;
    if (to < 0 || to >= next.length) return;
    [next[index], next[to]] = [next[to], next[index]];
    setFlow(next);
  };
  const setRole = (index, role) => setFlow(flowOf(model).map((f, i) => (i === index ? { ...f, role } : f)));
  const removeGone = (ref) => setFlow(flowOf(model).filter((f) => f.ref !== ref));
  const resetFlow = () => setDoc(({ flow, ...rest }) => rest);

  const entries = constantsOf(doc, catalog);
  const setConstants = (next) => setDoc((d) => ({ ...d, constants: next }));
  const present = new Set(entries.map((e) => e.id));
  const suggestions = catalog.filter((c) => !present.has(c.id));
  const threatsOn = doc.include?.threats !== false;

  return (
    <div className="brief-flow">
      <section className="brief-flow__list" aria-labelledby="brief-flow-h">
        <div className="brief-section-head">
          <div>
            <h2 id="brief-flow-h" className="brief-h2">Mission flow</h2>
            <p className="brief-hint">
              The order the flight meets each item. It sets the order the routes and LZ/PZs are briefed in, and the sandtable's steps. Times come from the routes' clocks.
            </p>
          </div>
          {!followsRoutes && (
            <button type="button" className="ui-btn ui-btn--ghost ui-btn--30" onClick={resetFlow}>Reset to route order</button>
          )}
        </div>
        {model.flow.length === 0 ? (
          <p className="brief-empty">
            {inPack
              ? "Nothing to brief yet: the pack has no LZ/PZs or route sets."
              : "Nothing to brief yet: open the mission's LZ/PZs and route sets in this session, or open its Mission Pack."}
          </p>
        ) : (
          <ol className="brief-flow__rows">
            {model.flow.map((f, index) => (
              <li key={f.ref} className={`brief-flow__row${f.gone ? " brief-flow__row--gone" : ""}`}>
                <span className="brief-flow__order">
                  <button type="button" className="brief-icon-btn" aria-label={`Move ${f.name ?? "item"} up`} disabled={index === 0} onClick={() => move(index, -1)}>
                    <Icon name="chevronUp" size={14} />
                  </button>
                  <button type="button" className="brief-icon-btn" aria-label={`Move ${f.name ?? "item"} down`} disabled={index === model.flow.length - 1} onClick={() => move(index, 1)}>
                    <Icon name="chevronDown" size={14} />
                  </button>
                </span>
                <span className="brief-flow__n">{index + 1}</span>
                {f.gone ? (
                  <span className="brief-kind brief-kind--gone">GONE</span>
                ) : f.kind === "route" ? (
                  <span className="brief-kind brief-kind--route">ROUTE</span>
                ) : (
                  <select
                    className={`brief-kind brief-kind--${f.role.toLowerCase()}`}
                    aria-label={`Role of ${f.name}`}
                    value={f.role}
                    onChange={(event) => setRole(index, event.target.value)}
                  >
                    {ROLES.map((role) => <option key={role} value={role}>{ROLE_LABELS[role]}</option>)}
                  </select>
                )}
                <span className="brief-flow__name">
                  <span className="brief-flow__title">{f.gone ? "No longer in the plan" : f.name}</span>
                  <span className="brief-flow__sub">
                    {f.gone ? "Remove it, or open it again." : f.kind === "route" ? `${f.start ?? "no clock"} to ${f.end ?? "no clock"}` : `${ROLE_NAMES[f.role]} · ${f.grid ?? "no grid"}`}
                  </span>
                </span>
                <span className="brief-flow__time">{f.kind === "stop" ? f.arrive ?? "—" : f.start ?? "—"}</span>
                {f.gone && (
                  <button type="button" className="brief-icon-btn" aria-label="Remove from the flow" onClick={() => removeGone(f.ref)}>
                    <Icon name="x" size={14} />
                  </button>
                )}
              </li>
            ))}
          </ol>
        )}
        <p className="brief-note">
          <Icon name="info" size={14} />
          <span>Each LZ/PZ's role decides where it is briefed. Change it from its badge.</span>
        </p>
      </section>

      <div className="brief-flow__side">
        <section className="brief-card" aria-labelledby="brief-constants-h">
          <div className="brief-card__head">
            <h2 id="brief-constants-h" className="brief-h2">Mission constants</h2>
            <span className="brief-hint">Always on screen while briefing</span>
          </div>
          <p className="brief-hint">Values the whole flight holds for the mission. They print on every page and stay in a box on the sandtable.</p>
          <ul className="brief-constants">
            {model.constants.map((c) => (
              <li key={c.id} className="brief-constants__row">
                <span className="brief-constants__label">
                  <span>{c.label}</span>
                  <span className="brief-hint">{SOURCE_WORDS[c.source]}</span>
                </span>
                <DraftField
                  aria-label={c.label}
                  value={entries.find((e) => e.id === c.id)?.value ?? ""}
                  placeholder={c.source === "typed" ? "" : c.value ?? "Write-in"}
                  onCommit={(value) => setConstants(entries.map((e) => (e.id === c.id ? { ...e, value } : e)))}
                />
                <button type="button" className="brief-icon-btn" aria-label={`Remove ${c.label}`} onClick={() => setConstants(entries.filter((e) => e.id !== c.id))}>
                  <Icon name="x" size={14} />
                </button>
              </li>
            ))}
          </ul>
          <div className="brief-constants__add">
            {suggestions.map((c) => (
              <button key={c.id} type="button" className="brief-suggest" onClick={() => setConstants([...entries, { id: c.id }])}>
                + {c.label}
              </button>
            ))}
            {custom === null ? (
              <button type="button" className="brief-suggest" onClick={() => setCustom({ label: "", value: "" })}>+ Your own</button>
            ) : (
              <form
                className="brief-constants__custom"
                onSubmit={(event) => {
                  event.preventDefault();
                  const label = custom.label.trim();
                  if (!label) return;
                  let n = 1;
                  while (present.has(`custom-${n}`)) n += 1;
                  setConstants([...entries, { id: `custom-${n}`, label: label.slice(0, 40), value: custom.value.trim() }]);
                  setCustom(null);
                }}
              >
                <input className="ui-input" aria-label="Name of the constant" placeholder="Name (Formation)" value={custom.label} onChange={(e) => setCustom({ ...custom, label: e.target.value })} />
                <input className="ui-input" aria-label="Its value" placeholder="Value" value={custom.value} onChange={(e) => setCustom({ ...custom, value: e.target.value })} />
                <button type="submit" className="ui-btn ui-btn--30">Add</button>
                <button type="button" className="ui-btn ui-btn--ghost ui-btn--30" onClick={() => setCustom(null)}>Cancel</button>
              </form>
            )}
          </div>
        </section>

        <section className="brief-card" aria-labelledby="brief-also-h">
          <h2 id="brief-also-h" className="brief-h2">Also in the brief</h2>
          <label className="brief-switch">
            <span>
              <span>Threats ({threatCount})</span>
              <span className="brief-hint">Under 2. Situation, each with where it lies from the route</span>
            </span>
            <input
              type="checkbox"
              role="switch"
              checked={threatsOn}
              onChange={(event) => setDoc((d) => ({ ...d, include: { ...(d.include || {}), threats: event.target.checked } }))}
            />
          </label>
        </section>
      </div>
    </div>
  );
};

export default FlowStep;
