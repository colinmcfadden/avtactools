import React, { useId, useMemo, useRef, useState } from "react";
import { routesMeet } from "../msnxImport/routeSetOps";
import { setSummary } from "../saveDialog/useRouteSaves";
import Dialog from "../ui/Dialog";
import Icon from "../ui/Icon";

/*
 * The two things asked about a route from the Routes panel: which set to move it to (one that is
 * there, or a new one, named here), and which route of its set to join onto its end.
 */

const NEW_SET = "\u0000new";

const plural = (n, one) => `${n} ${one}${n === 1 ? "" : "s"}`;

/**
 * `route` is moving out of the set called `fromName`. `destinations`: the other sets that take routes,
 * `[{ key, name, routes, flots, phaseLines }]`. `suggestedName`: for a new set. `onMove({ key })` or
 * `onMove({ newName })`.
 */
export const MoveRouteDialog = ({ route, fromName, destinations, suggestedName = "", onMove, onCancel }) => {
  const [chosen, setChosen] = useState(destinations[0]?.key ?? NEW_SET);
  const [name, setName] = useState(suggestedName);
  const [error, setError] = useState(null);
  const nameField = useRef(null);
  const fieldId = useId();

  const submit = () => {
    if (chosen !== NEW_SET) {
      onMove({ key: chosen });
      return;
    }
    const newName = name.trim().toUpperCase();
    if (!newName) {
      setError("Give the new set a name.");
      nameField.current?.focus();
      return;
    }
    onMove({ newName });
  };

  return (
    <Dialog
      title={`Move ${route.name}`}
      subtitle={`It leaves ${fromName} and keeps its points, its plan and its colour.`}
      icon="route"
      onClose={onCancel}
      footer={
        <>
          <button type="button" className="ui-btn ui-btn--ghost ui-btn--38" onClick={onCancel}>Cancel</button>
          <button type="button" className="ui-btn ui-btn--primary ui-btn--38" onClick={submit}>
            <Icon name="check" size={15} />
            Move
          </button>
        </>
      }
    >
      <form
        onSubmit={(event) => {
          event.preventDefault();
          submit();
        }}
      >
        <div role="radiogroup" aria-label={`Where ${route.name} goes`} style={{ display: "grid", gap: 8 }}>
          {destinations.map((set) => (
            <label key={set.key} className={`ui-choice${chosen === set.key ? " ui-choice--selected" : ""}`}>
              <input type="radio" name="move-route" checked={chosen === set.key} onChange={() => setChosen(set.key)} />
              <span>
                <span className="ui-choice__title">{set.name}</span>
                <span className="ui-choice__text">{setSummary(set.routes, set.flots, set.phaseLines)}</span>
              </span>
            </label>
          ))}
          <label className={`ui-choice${chosen === NEW_SET ? " ui-choice--selected" : ""}`}>
            <input type="radio" name="move-route" checked={chosen === NEW_SET} onChange={() => setChosen(NEW_SET)} />
            <span style={{ flex: 1, minWidth: 0 }}>
              <span className="ui-choice__title">A new route set</span>
              <span className="ui-choice__text">Made now, with this route in it.</span>
            </span>
          </label>
        </div>
        {chosen === NEW_SET && (
          <div className="ui-field" style={{ marginTop: 12 }}>
            <label htmlFor={fieldId} className="ui-field__label">Name of the new set</label>
            <input
              ref={nameField}
              id={fieldId}
              type="text"
              className={`ui-input${error ? " ui-input--error" : ""}`}
              style={{ textTransform: "uppercase" }}
              value={name}
              maxLength={100}
              autoComplete="off"
              spellCheck={false}
              aria-invalid={Boolean(error)}
              aria-describedby={error ? `${fieldId}-hint` : undefined}
              onChange={(event) => {
                setName(event.target.value);
                setError(null);
              }}
            />
            {error && <div id={`${fieldId}-hint`} className="ui-field__hint ui-field__hint--error">{error}</div>}
          </div>
        )}
        <button type="submit" hidden tabIndex={-1} aria-hidden="true" />
      </form>
    </Dialog>
  );
};

/**
 * `route` takes another route of its set onto its end. `others`: the set's other routes, in the
 * panel's order. `describe(route)`: its line of numbers (distance, time, fuel). `onCombine(otherId)`.
 */
export const CombineRoutesDialog = ({ route, others, describe, onCombine, onCancel }) => {
  // The route that begins where this one ends is the likely one.
  const likely = useMemo(() => others.find((other) => routesMeet(route, other)) ?? others[0], [route, others]);
  const [chosen, setChosen] = useState(likely?.id ?? null);
  const other = others.find((candidate) => candidate.id === chosen) ?? null;
  const total = other ? route.points.length + other.points.length - (routesMeet(route, other) ? 1 : 0) : route.points.length;

  return (
    <Dialog
      title={`Combine with ${route.name}`}
      subtitle={`The route you choose is added after ${route.name}'s last point. The one route keeps ${route.name}'s name and colour.`}
      icon="route"
      onClose={onCancel}
      note={other ? `${plural(total, "point")} in all. Undo takes it back.` : undefined}
      footer={
        <>
          <button type="button" className="ui-btn ui-btn--ghost ui-btn--38" onClick={onCancel}>Cancel</button>
          <button type="button" className="ui-btn ui-btn--primary ui-btn--38" disabled={!other} onClick={() => other && onCombine(other.id)}>
            <Icon name="check" size={15} />
            Combine
          </button>
        </>
      }
    >
      <div role="radiogroup" aria-label={`The route added to ${route.name}`} style={{ display: "grid", gap: 8 }}>
        {others.map((candidate) => (
          <label key={candidate.id} className={`ui-choice${chosen === candidate.id ? " ui-choice--selected" : ""}`}>
            <input type="radio" name="combine-route" checked={chosen === candidate.id} onChange={() => setChosen(candidate.id)} />
            <span className="shell-route__swatch" style={{ background: candidate.color, marginTop: 4 }} />
            <span>
              <span className="ui-choice__title">{candidate.name}</span>
              <span className="ui-choice__text">
                {describe(candidate)}
                {routesMeet(route, candidate) ? ` · begins where ${route.name} ends` : ""}
              </span>
            </span>
          </label>
        ))}
      </div>
    </Dialog>
  );
};
